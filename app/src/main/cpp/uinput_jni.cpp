// Virtual Xbox 360 gamepad via Linux uinput.
// Must run with a UID that has SELinux permission to open /dev/uinput
// (typically shell via Shizuku, or root). Plain app UID will be denied.

#include <jni.h>
#include <android/log.h>
#include <linux/uinput.h>
#include <linux/input.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/ioctl.h>
#include <string.h>
#include <errno.h>

#define LOG_TAG "uinput_jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Gamepad profiles — VID/PID/name selected at create time
struct gamepad_profile {
    int id;
    uint16_t vid;
    uint16_t pid;
    const char* name;
};

static const gamepad_profile PROFILES[] = {
    { 0, 0x045E, 0x028E, "Microsoft X-Box 360 pad" },           // XBOX_360
    { 1, 0x045E, 0x02EA, "Microsoft Xbox One Controller" },     // XBOX_ONE
    { 2, 0x054C, 0x05C4, "Sony Interactive Entertainment Wireless Controller" }, // DS4
    { 3, 0x054C, 0x0CE6, "Sony Interactive Entertainment DualSense Wireless Controller" }, // DualSense
};

static const gamepad_profile* find_profile(int id) {
    for (const auto& p : PROFILES) if (p.id == id) return &p;
    return &PROFILES[0];  // fallback Xbox 360
}

// Axis ranges matching real Xbox 360 reports
#define STICK_MIN   -32768
#define STICK_MAX    32767
#define TRIG_MIN     0
#define TRIG_MAX     255
#define HAT_MIN     -1
#define HAT_MAX      1

static int g_fd = -1;

static int set_bit_or_log(int fd, unsigned long req, int bit, const char* what) {
    if (ioctl(fd, req, bit) < 0) {
        LOGE("ioctl %s bit=%d failed: %s", what, bit, strerror(errno));
        return -1;
    }
    return 0;
}

static int setup_abs(int fd, int code, int min, int max, int fuzz, int flat) {
    if (ioctl(fd, UI_SET_ABSBIT, code) < 0) return -1;
    struct uinput_abs_setup s;
    memset(&s, 0, sizeof(s));
    s.code = code;
    s.absinfo.minimum = min;
    s.absinfo.maximum = max;
    s.absinfo.fuzz    = fuzz;
    s.absinfo.flat    = flat;
    if (ioctl(fd, UI_ABS_SETUP, &s) < 0) {
        LOGE("UI_ABS_SETUP code=%d failed: %s", code, strerror(errno));
        return -1;
    }
    return 0;
}

static int write_event(int fd, uint16_t type, uint16_t code, int32_t value) {
    struct input_event ev;
    memset(&ev, 0, sizeof(ev));
    ev.type  = type;
    ev.code  = code;
    ev.value = value;
    if (write(fd, &ev, sizeof(ev)) != (ssize_t)sizeof(ev)) {
        LOGE("write event t=%d c=%d v=%d failed: %s", type, code, value, strerror(errno));
        return -1;
    }
    return 0;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_steamcontroller_android_uinput_UInputNative_canOpen(JNIEnv*, jclass) {
    int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (fd < 0) {
        LOGI("canOpen: /dev/uinput open denied: %s", strerror(errno));
        return JNI_FALSE;
    }
    close(fd);
    return JNI_TRUE;
}

// Slot for one stored FF effect — we only track FF_RUMBLE for now.
struct ff_slot {
    int id;
    uint16_t strong;  // left motor magnitude
    uint16_t weak;    // right motor magnitude
};
static constexpr int MAX_FF_EFFECTS = 4;
static ff_slot g_ff_effects[MAX_FF_EFFECTS] = {};

// Latest play command pulled by Kotlin via pollFFEvent().
// strong/weak are 0..65535. -1 magnitudes mean "no pending event".
static int32_t g_pending_strong = -1;
static int32_t g_pending_weak   = -1;

extern "C" JNIEXPORT jboolean JNICALL
Java_com_steamcontroller_android_uinput_UInputNative_createDevice(JNIEnv*, jclass, jint profileId) {
    const gamepad_profile* prof = find_profile(profileId);
    LOGI("createDevice: profile=%d (VID=0x%04X PID=0x%04X name=\"%s\")",
         prof->id, prof->vid, prof->pid, prof->name);
    if (g_fd >= 0) {
        // Already created — destroy and recreate
        ioctl(g_fd, UI_DEV_DESTROY);
        close(g_fd);
        g_fd = -1;
    }

    // Open R/W: we need to read from the fd to receive FF (rumble) commands from games.
    int fd = open("/dev/uinput", O_RDWR | O_NONBLOCK);
    if (fd < 0) {
        LOGE("open /dev/uinput failed: %s", strerror(errno));
        return JNI_FALSE;
    }

    // Enable event types
    if (set_bit_or_log(fd, UI_SET_EVBIT, EV_KEY, "EV_KEY") < 0) goto fail;
    if (set_bit_or_log(fd, UI_SET_EVBIT, EV_ABS, "EV_ABS") < 0) goto fail;
    if (set_bit_or_log(fd, UI_SET_EVBIT, EV_SYN, "EV_SYN") < 0) goto fail;
    if (set_bit_or_log(fd, UI_SET_EVBIT, EV_FF,  "EV_FF")  < 0) goto fail;

    // Supported FF effects — rumble is the universal one
    if (set_bit_or_log(fd, UI_SET_FFBIT, FF_RUMBLE,   "FF_RUMBLE")   < 0) goto fail;
    if (set_bit_or_log(fd, UI_SET_FFBIT, FF_PERIODIC, "FF_PERIODIC") < 0) goto fail;

    // Buttons — match Xbox 360 controller layout exactly
    {
        const int btns[] = {
            BTN_A, BTN_B, BTN_X, BTN_Y,
            BTN_TL, BTN_TR,
            BTN_SELECT, BTN_START, BTN_MODE,
            BTN_THUMBL, BTN_THUMBR
        };
        for (int b : btns) {
            if (set_bit_or_log(fd, UI_SET_KEYBIT, b, "KEY") < 0) goto fail;
        }
    }

    // Axes
    if (setup_abs(fd, ABS_X,        STICK_MIN, STICK_MAX, 16, 128) < 0) goto fail;
    if (setup_abs(fd, ABS_Y,        STICK_MIN, STICK_MAX, 16, 128) < 0) goto fail;
    if (setup_abs(fd, ABS_RX,       STICK_MIN, STICK_MAX, 16, 128) < 0) goto fail;
    if (setup_abs(fd, ABS_RY,       STICK_MIN, STICK_MAX, 16, 128) < 0) goto fail;
    if (setup_abs(fd, ABS_Z,        TRIG_MIN,  TRIG_MAX,   0,   0) < 0) goto fail;
    if (setup_abs(fd, ABS_RZ,       TRIG_MIN,  TRIG_MAX,   0,   0) < 0) goto fail;
    if (setup_abs(fd, ABS_HAT0X,    HAT_MIN,   HAT_MAX,    0,   0) < 0) goto fail;
    if (setup_abs(fd, ABS_HAT0Y,    HAT_MIN,   HAT_MAX,    0,   0) < 0) goto fail;

    // Device identity
    {
        struct uinput_setup us;
        memset(&us, 0, sizeof(us));
        us.id.bustype = BUS_USB;
        us.id.vendor  = prof->vid;
        us.id.product = prof->pid;
        us.id.version = 0x0114;
        us.ff_effects_max = MAX_FF_EFFECTS;
        strncpy(us.name, prof->name, UINPUT_MAX_NAME_SIZE - 1);
        if (ioctl(fd, UI_DEV_SETUP, &us) < 0) {
            LOGE("UI_DEV_SETUP failed: %s", strerror(errno));
            goto fail;
        }
    }

    // Reset FF state
    memset(g_ff_effects, 0, sizeof(g_ff_effects));
    g_pending_strong = -1;
    g_pending_weak   = -1;

    if (ioctl(fd, UI_DEV_CREATE) < 0) {
        LOGE("UI_DEV_CREATE failed: %s", strerror(errno));
        goto fail;
    }

    LOGI("Virtual gamepad created (profile=%d), fd=%d", prof->id, fd);
    g_fd = fd;
    return JNI_TRUE;

fail:
    close(fd);
    return JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_steamcontroller_android_uinput_UInputNative_sendFrame(
        JNIEnv*, jclass,
        jint buttons,
        jint lx, jint ly, jint rx, jint ry,
        jint lt, jint rt,
        jint dpadX, jint dpadY) {
    if (g_fd < 0) return;

    // Bit-to-keycode pairs — order must match XboxDescriptor.kt bit positions.
    // bit 0 = A, 1 = B, 2 = X, 3 = Y, 4 = LB, 5 = RB,
    // 6 = SELECT, 7 = START, 8 = MODE, 9 = THUMBL, 10 = THUMBR
    static const int bit_to_key[] = {
        BTN_A, BTN_B, BTN_X, BTN_Y,
        BTN_TL, BTN_TR,
        BTN_SELECT, BTN_START, BTN_MODE,
        BTN_THUMBL, BTN_THUMBR
    };
    const int n = sizeof(bit_to_key) / sizeof(bit_to_key[0]);
    for (int i = 0; i < n; i++) {
        int pressed = (buttons >> i) & 1;
        write_event(g_fd, EV_KEY, bit_to_key[i], pressed);
    }

    write_event(g_fd, EV_ABS, ABS_X,     lx);
    write_event(g_fd, EV_ABS, ABS_Y,     ly);
    write_event(g_fd, EV_ABS, ABS_RX,    rx);
    write_event(g_fd, EV_ABS, ABS_RY,    ry);
    write_event(g_fd, EV_ABS, ABS_Z,     lt);
    write_event(g_fd, EV_ABS, ABS_RZ,    rt);
    write_event(g_fd, EV_ABS, ABS_HAT0X, dpadX);
    write_event(g_fd, EV_ABS, ABS_HAT0Y, dpadY);

    write_event(g_fd, EV_SYN, SYN_REPORT, 0);
}

// Drain any pending events from the uinput fd. We care about:
//   - UI_FF_UPLOAD: a game uploads an effect. Read the ff_effect, store it by id.
//   - UI_FF_ERASE:  effect removed by the game. Free the slot.
//   - EV_FF code=effect_id value=N: play the effect N times (N=0 → stop).
// Returns: jintArray of size 2 [strongMagnitude, weakMagnitude] (each 0..65535),
//          or null if no rumble event is pending.
extern "C" JNIEXPORT jintArray JNICALL
Java_com_steamcontroller_android_uinput_UInputNative_pollFFEvent(JNIEnv* env, jclass) {
    if (g_fd < 0) return nullptr;

    struct input_event ev;
    // Drain everything available — there may be multiple events in flight
    while (read(g_fd, &ev, sizeof(ev)) == (ssize_t)sizeof(ev)) {
        if (ev.type == EV_UINPUT && ev.code == UI_FF_UPLOAD) {
            struct uinput_ff_upload upload;
            memset(&upload, 0, sizeof(upload));
            upload.request_id = ev.value;
            if (ioctl(g_fd, UI_BEGIN_FF_UPLOAD, &upload) >= 0) {
                if (upload.effect.type == FF_RUMBLE) {
                    // Find/use slot matching effect.id (or first free)
                    int slot = -1;
                    for (int i = 0; i < MAX_FF_EFFECTS; i++) {
                        if (g_ff_effects[i].id == upload.effect.id) { slot = i; break; }
                    }
                    if (slot < 0) {
                        for (int i = 0; i < MAX_FF_EFFECTS; i++) {
                            if (g_ff_effects[i].id == 0) { slot = i; break; }
                        }
                    }
                    if (slot >= 0) {
                        g_ff_effects[slot].id     = upload.effect.id;
                        g_ff_effects[slot].strong = upload.effect.u.rumble.strong_magnitude;
                        g_ff_effects[slot].weak   = upload.effect.u.rumble.weak_magnitude;
                    }
                }
                upload.retval = 0;
                ioctl(g_fd, UI_END_FF_UPLOAD, &upload);
            }
        } else if (ev.type == EV_UINPUT && ev.code == UI_FF_ERASE) {
            struct uinput_ff_erase erase;
            memset(&erase, 0, sizeof(erase));
            erase.request_id = ev.value;
            if (ioctl(g_fd, UI_BEGIN_FF_ERASE, &erase) >= 0) {
                for (int i = 0; i < MAX_FF_EFFECTS; i++) {
                    if (g_ff_effects[i].id == (int)erase.effect_id) {
                        g_ff_effects[i] = {};
                        break;
                    }
                }
                erase.retval = 0;
                ioctl(g_fd, UI_END_FF_ERASE, &erase);
            }
        } else if (ev.type == EV_FF) {
            // Play or stop. ev.code = effect id, ev.value = play count (0 = stop)
            int effect_id = ev.code;
            if (ev.value == 0) {
                g_pending_strong = 0;
                g_pending_weak   = 0;
            } else {
                for (int i = 0; i < MAX_FF_EFFECTS; i++) {
                    if (g_ff_effects[i].id == effect_id) {
                        g_pending_strong = g_ff_effects[i].strong;
                        g_pending_weak   = g_ff_effects[i].weak;
                        break;
                    }
                }
            }
        }
    }

    if (g_pending_strong < 0) return nullptr;

    jintArray result = env->NewIntArray(2);
    jint vals[2] = { g_pending_strong, g_pending_weak };
    env->SetIntArrayRegion(result, 0, 2, vals);
    g_pending_strong = -1;
    g_pending_weak   = -1;
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_steamcontroller_android_uinput_UInputNative_destroy(JNIEnv*, jclass) {
    if (g_fd >= 0) {
        ioctl(g_fd, UI_DEV_DESTROY);
        close(g_fd);
        g_fd = -1;
        LOGI("Virtual gamepad destroyed");
    }
}
