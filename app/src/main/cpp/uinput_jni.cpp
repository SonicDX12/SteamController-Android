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
#include <time.h>

#define LOG_TAG "uinput_jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Gamepad profiles — VID/PID/name selected at create time
struct gamepad_profile {
    int id;
    uint16_t vid;
    uint16_t pid;
    const char* name;
    bool mouse_mode;
};

static const gamepad_profile PROFILES[] = {
    { 0, 0x045E, 0x028E, "Microsoft X-Box 360 pad",                                           false }, // XBOX_360
    { 1, 0x045E, 0x02EA, "Microsoft Xbox One Controller",                                     false }, // XBOX_ONE
    { 2, 0x054C, 0x05C4, "Sony Interactive Entertainment Wireless Controller",                false }, // DS4
    { 3, 0x054C, 0x0CE6, "Sony Interactive Entertainment DualSense Wireless Controller",      false }, // DualSense
    { 4, 0x046D, 0xC077, "Steam Controller Desktop",                                          true  }, // MOUSE
};

// Keyboard keys exposed in mouse mode. Order MUST match MouseKeyBit in MouseTarget.kt.
static const int MOUSE_KEYS[] = {
    KEY_UP, KEY_DOWN, KEY_LEFT, KEY_RIGHT,        // 0..3
    KEY_ENTER, KEY_BACK, KEY_TAB, KEY_SPACE,      // 4..7
    KEY_HOME, KEY_ESC,                            // 8..9
    KEY_VOLUMEUP, KEY_VOLUMEDOWN,                 // 10..11
    KEY_PLAYPAUSE, KEY_MENU,                      // 12..13
    KEY_BACKSPACE,                                // 14
    KEY_SELECT,                                   // 15 → AKEYCODE_DPAD_CENTER, required to "click" on Leanback IME keys
};
static constexpr int MOUSE_KEY_COUNT = sizeof(MOUSE_KEYS) / sizeof(MOUSE_KEYS[0]);
// Bits 16,17,18 = BTN_LEFT, BTN_RIGHT, BTN_MIDDLE (handled separately).

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

// Up to three uinput devices live in parallel:
//   - g_fd_gamepad: emulated controller (Xbox/PS profiles), opened R/W for FF rumble.
//   - g_fd_mouse:   pure mouse (EV_REL + 3 mouse buttons). Always present alongside
//                   the gamepad as a "sidecar" so the right trackpad can drive a
//                   real cursor while games still see a gamepad. In Desktop mode
//                   this IS the primary device.
//   - g_fd_kbd:     pure keyboard (EV_KEY). Sidecar in gamepad mode (enables
//                   keyboard-key mappings on back paddles etc.); primary in Desktop.
// A single device declaring EV_REL+EV_KEY together gets classified SOURCE_MOUSE
// by Android, which makes IMEs ignore its key events — splitting into separate
// fds mirrors how a real composite USB keyboard+mouse combo looks.
static int g_fd_gamepad = -1;
static int g_fd_mouse   = -1;
static int g_fd_kbd     = -1;

// Cached "last frame" state for delta encoding in sendMouseFrame.
// Reset on every destroy_devices() so a new device starts from a clean slate.
static int g_last_mouse_buttons = 0;
static int g_last_kbd_keys      = 0;

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

// Helper: finalise a uinput device with the given identity strings and create it.
static int finalize_device(int fd, uint16_t vid, uint16_t pid, const char* name, uint32_t ff_effects_max) {
    struct uinput_setup us;
    memset(&us, 0, sizeof(us));
    us.id.bustype = BUS_USB;
    us.id.vendor  = vid;
    us.id.product = pid;
    us.id.version = 0x0114;
    us.ff_effects_max = ff_effects_max;
    strncpy(us.name, name, UINPUT_MAX_NAME_SIZE - 1);
    if (ioctl(fd, UI_DEV_SETUP, &us) < 0) {
        LOGE("UI_DEV_SETUP failed: %s", strerror(errno));
        return -1;
    }
    if (ioctl(fd, UI_DEV_CREATE) < 0) {
        LOGE("UI_DEV_CREATE failed: %s", strerror(errno));
        return -1;
    }
    return 0;
}

// Set up a pure mouse device (EV_REL + 3 mouse buttons). Returns fd or -1.
static int create_mouse_fd(uint16_t vid, uint16_t pid) {
    int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (fd < 0) { LOGE("open /dev/uinput (mouse) failed: %s", strerror(errno)); return -1; }
    if (set_bit_or_log(fd, UI_SET_EVBIT,  EV_KEY,    "mouse EV_KEY")    < 0) goto fail;
    if (set_bit_or_log(fd, UI_SET_EVBIT,  EV_REL,    "mouse EV_REL")    < 0) goto fail;
    if (set_bit_or_log(fd, UI_SET_EVBIT,  EV_SYN,    "mouse EV_SYN")    < 0) goto fail;
    if (set_bit_or_log(fd, UI_SET_RELBIT, REL_X,     "REL_X")           < 0) goto fail;
    if (set_bit_or_log(fd, UI_SET_RELBIT, REL_Y,     "REL_Y")           < 0) goto fail;
    if (set_bit_or_log(fd, UI_SET_RELBIT, REL_WHEEL, "REL_WHEEL")       < 0) goto fail;
    if (set_bit_or_log(fd, UI_SET_KEYBIT, BTN_LEFT,   "BTN_LEFT")       < 0) goto fail;
    if (set_bit_or_log(fd, UI_SET_KEYBIT, BTN_RIGHT,  "BTN_RIGHT")      < 0) goto fail;
    if (set_bit_or_log(fd, UI_SET_KEYBIT, BTN_MIDDLE, "BTN_MIDDLE")     < 0) goto fail;
    if (finalize_device(fd, vid, pid, "Steam Controller Mouse", 0) < 0) goto fail;
    return fd;
fail:
    close(fd);
    return -1;
}

// Set up a pure keyboard device (EV_KEY only). Returns fd or -1.
//
// `full_alpha` declares the full A-Z/0-9 alphabet so Android's EventHub classifies
// this device as INPUT_DEVICE_CLASS_ALPHAKEY + DPAD (needed on Android TV so the
// Leanback IME routes DPAD navigation correctly — see comment below). ONLY pass
// true for the Desktop-mode keyboard. Passing true for the gamepad-mode sidecar
// makes Android believe a real hardware QWERTY keyboard is attached at all times,
// which suppresses the on-screen keyboard for every text field system-wide while
// the controller is connected — the gamepad already exposes its own ABS_HAT dpad,
// so the sidecar doesn't need this trick, only the small MOUSE_KEYS set used by
// back-paddle key mappings.
static int create_keyboard_fd(uint16_t vid, uint16_t pid, bool full_alpha) {
    int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (fd < 0) { LOGE("open /dev/uinput (kbd) failed: %s", strerror(errno)); return -1; }
    if (set_bit_or_log(fd, UI_SET_EVBIT, EV_KEY, "kbd EV_KEY") < 0) goto fail;
    if (set_bit_or_log(fd, UI_SET_EVBIT, EV_SYN, "kbd EV_SYN") < 0) goto fail;
    for (int i = 0; i < MOUSE_KEY_COUNT; i++) {
        if (set_bit_or_log(fd, UI_SET_KEYBIT, MOUSE_KEYS[i], "MOUSE_KEY") < 0) goto fail;
    }
    if (full_alpha) {
        // Declare the full alphabet + digits so Android's EventHub classifies this
        // as INPUT_DEVICE_CLASS_ALPHAKEY (cheap test: KEY_Q present).
        //
        // Also declare KEY_SELECT (Linux 353) — Generic.kl maps it to DPAD_CENTER,
        // which is the missing 5th key needed for INPUT_DEVICE_CLASS_DPAD. Without
        // DPAD class the source is SOURCE_KEYBOARD only, and the Leanback IME on
        // Android TV source-filters DPAD navigation to SOURCE_DPAD — that's why
        // arrow presses worked outside the IME but not on the soft keyboard.
        const int alpha_keys[] = {
            KEY_A, KEY_B, KEY_C, KEY_D, KEY_E, KEY_F, KEY_G, KEY_H, KEY_I, KEY_J,
            KEY_K, KEY_L, KEY_M, KEY_N, KEY_O, KEY_P, KEY_Q, KEY_R, KEY_S, KEY_T,
            KEY_U, KEY_V, KEY_W, KEY_X, KEY_Y, KEY_Z,
            KEY_0, KEY_1, KEY_2, KEY_3, KEY_4, KEY_5, KEY_6, KEY_7, KEY_8, KEY_9,
            KEY_LEFTSHIFT, KEY_RIGHTSHIFT, KEY_LEFTCTRL, KEY_LEFTALT, KEY_CAPSLOCK,
            KEY_COMMA, KEY_DOT, KEY_SLASH, KEY_SEMICOLON, KEY_APOSTROPHE,
            KEY_MINUS, KEY_EQUAL,
            KEY_SELECT,  // → AKEYCODE_DPAD_CENTER, completes DPAD classification
        };
        for (int k : alpha_keys) {
            if (set_bit_or_log(fd, UI_SET_KEYBIT, k, "alpha KEY") < 0) goto fail;
        }
    }
    // PID +1 keeps a stable, distinct identity vs the mouse half
    if (finalize_device(fd, vid, (uint16_t)(pid + 1), "Steam Controller Keyboard", 0) < 0) goto fail;
    return fd;
fail:
    close(fd);
    return -1;
}

static void destroy_devices() {
    bool destroyed = false;
    if (g_fd_gamepad >= 0) {
        ioctl(g_fd_gamepad, UI_DEV_DESTROY);
        close(g_fd_gamepad);
        g_fd_gamepad = -1;
        destroyed = true;
    }
    if (g_fd_mouse >= 0) {
        ioctl(g_fd_mouse, UI_DEV_DESTROY);
        close(g_fd_mouse);
        g_fd_mouse = -1;
        destroyed = true;
    }
    if (g_fd_kbd >= 0) {
        ioctl(g_fd_kbd, UI_DEV_DESTROY);
        close(g_fd_kbd);
        g_fd_kbd = -1;
        destroyed = true;
    }
    // Any cached delta-state belonged to the destroyed device — reset.
    g_last_mouse_buttons = 0;
    g_last_kbd_keys = 0;
    if (destroyed) {
        // Give the kernel + InputReader a moment to fully release the input
        // device records. Without this, rapid profile switching can hit transient
        // failures (UI_DEV_CREATE returns success but Android never sees the new
        // device, leaving the UI thinking it's healthy while no events flow).
        struct timespec ts = { 0, 80 * 1000 * 1000 };  // 80ms
        nanosleep(&ts, nullptr);
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_steamcontroller_android_uinput_UInputNative_createDevice(JNIEnv*, jclass, jint profileId) {
    const gamepad_profile* prof = find_profile(profileId);
    LOGI("createDevice: profile=%d (VID=0x%04X PID=0x%04X name=\"%s\")",
         prof->id, prof->vid, prof->pid, prof->name);

    destroy_devices();

    if (prof->mouse_mode) {
        // Desktop: just mouse + keyboard, no gamepad.
        int mouse_fd = create_mouse_fd(prof->vid, prof->pid);
        if (mouse_fd < 0) return JNI_FALSE;
        int kbd_fd = create_keyboard_fd(prof->vid, prof->pid, /*full_alpha=*/true);
        if (kbd_fd < 0) {
            ioctl(mouse_fd, UI_DEV_DESTROY);
            close(mouse_fd);
            return JNI_FALSE;
        }
        g_fd_mouse = mouse_fd;
        g_fd_kbd   = kbd_fd;
        LOGI("Desktop devices created — mouse fd=%d, kbd fd=%d", mouse_fd, kbd_fd);
        return JNI_TRUE;
    }

    // Gamepad path — gamepad device + sidecar mouse + sidecar keyboard.
    // The sidecar pair lets the right trackpad drive a cursor and lets back-paddle
    // mappings hit keyboard keys, while games still see a proper gamepad.
    int fd = open("/dev/uinput", O_RDWR | O_NONBLOCK);
    if (fd < 0) {
        LOGE("open /dev/uinput failed: %s", strerror(errno));
        return JNI_FALSE;
    }

    if (set_bit_or_log(fd, UI_SET_EVBIT, EV_KEY, "EV_KEY") < 0) goto fail;
    if (set_bit_or_log(fd, UI_SET_EVBIT, EV_ABS, "EV_ABS") < 0) goto fail;
    if (set_bit_or_log(fd, UI_SET_EVBIT, EV_SYN, "EV_SYN") < 0) goto fail;
    if (set_bit_or_log(fd, UI_SET_EVBIT, EV_FF,  "EV_FF")  < 0) goto fail;

    if (set_bit_or_log(fd, UI_SET_FFBIT, FF_RUMBLE,   "FF_RUMBLE")   < 0) goto fail;
    if (set_bit_or_log(fd, UI_SET_FFBIT, FF_PERIODIC, "FF_PERIODIC") < 0) goto fail;

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

    if (setup_abs(fd, ABS_X,        STICK_MIN, STICK_MAX, 16, 128) < 0) goto fail;
    if (setup_abs(fd, ABS_Y,        STICK_MIN, STICK_MAX, 16, 128) < 0) goto fail;
    if (setup_abs(fd, ABS_RX,       STICK_MIN, STICK_MAX, 16, 128) < 0) goto fail;
    if (setup_abs(fd, ABS_RY,       STICK_MIN, STICK_MAX, 16, 128) < 0) goto fail;
    if (setup_abs(fd, ABS_Z,        TRIG_MIN,  TRIG_MAX,   0,   0) < 0) goto fail;
    if (setup_abs(fd, ABS_RZ,       TRIG_MIN,  TRIG_MAX,   0,   0) < 0) goto fail;
    if (setup_abs(fd, ABS_HAT0X,    HAT_MIN,   HAT_MAX,    0,   0) < 0) goto fail;
    if (setup_abs(fd, ABS_HAT0Y,    HAT_MIN,   HAT_MAX,    0,   0) < 0) goto fail;

    if (finalize_device(fd, prof->vid, prof->pid, prof->name, MAX_FF_EFFECTS) < 0) goto fail;

    memset(g_ff_effects, 0, sizeof(g_ff_effects));
    g_pending_strong = -1;
    g_pending_weak   = -1;

    LOGI("Virtual gamepad created (profile=%d), fd=%d", prof->id, fd);
    g_fd_gamepad = fd;

    // Sidecar mouse + keyboard — non-fatal if either fails (gamepad still works).
    g_fd_mouse = create_mouse_fd(prof->vid, (uint16_t)(prof->pid + 0x100));
    if (g_fd_mouse < 0) {
        LOGE("Sidecar mouse creation failed — trackpad-as-cursor will be unavailable");
    }
    g_fd_kbd = create_keyboard_fd(prof->vid, (uint16_t)(prof->pid + 0x200), /*full_alpha=*/false);
    if (g_fd_kbd < 0) {
        LOGE("Sidecar keyboard creation failed — key mappings on back paddles will be unavailable");
    }
    LOGI("Gamepad devices ready — gamepad=%d, mouse=%d, kbd=%d", g_fd_gamepad, g_fd_mouse, g_fd_kbd);
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
    if (g_fd_gamepad < 0) return;

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
        write_event(g_fd_gamepad, EV_KEY, bit_to_key[i], pressed);
    }

    write_event(g_fd_gamepad, EV_ABS, ABS_X,     lx);
    write_event(g_fd_gamepad, EV_ABS, ABS_Y,     ly);
    write_event(g_fd_gamepad, EV_ABS, ABS_RX,    rx);
    write_event(g_fd_gamepad, EV_ABS, ABS_RY,    ry);
    write_event(g_fd_gamepad, EV_ABS, ABS_Z,     lt);
    write_event(g_fd_gamepad, EV_ABS, ABS_RZ,    rt);
    write_event(g_fd_gamepad, EV_ABS, ABS_HAT0X, dpadX);
    write_event(g_fd_gamepad, EV_ABS, ABS_HAT0Y, dpadY);

    write_event(g_fd_gamepad, EV_SYN, SYN_REPORT, 0);
}

// Drain any pending events from the uinput fd. We care about:
//   - UI_FF_UPLOAD: a game uploads an effect. Read the ff_effect, store it by id.
//   - UI_FF_ERASE:  effect removed by the game. Free the slot.
//   - EV_FF code=effect_id value=N: play the effect N times (N=0 → stop).
// Returns: jintArray of size 2 [strongMagnitude, weakMagnitude] (each 0..65535),
//          or null if no rumble event is pending.
extern "C" JNIEXPORT jintArray JNICALL
Java_com_steamcontroller_android_uinput_UInputNative_pollFFEvent(JNIEnv* env, jclass) {
    if (g_fd_gamepad < 0) return nullptr;

    struct input_event ev;
    // Drain everything available — there may be multiple events in flight
    while (read(g_fd_gamepad, &ev, sizeof(ev)) == (ssize_t)sizeof(ev)) {
        if (ev.type == EV_UINPUT && ev.code == UI_FF_UPLOAD) {
            struct uinput_ff_upload upload;
            memset(&upload, 0, sizeof(upload));
            upload.request_id = ev.value;
            if (ioctl(g_fd_gamepad, UI_BEGIN_FF_UPLOAD, &upload) >= 0) {
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
                ioctl(g_fd_gamepad, UI_END_FF_UPLOAD, &upload);
            }
        } else if (ev.type == EV_UINPUT && ev.code == UI_FF_ERASE) {
            struct uinput_ff_erase erase;
            memset(&erase, 0, sizeof(erase));
            erase.request_id = ev.value;
            if (ioctl(g_fd_gamepad, UI_BEGIN_FF_ERASE, &erase) >= 0) {
                for (int i = 0; i < MAX_FF_EFFECTS; i++) {
                    if (g_ff_effects[i].id == (int)erase.effect_id) {
                        g_ff_effects[i] = {};
                        break;
                    }
                }
                erase.retval = 0;
                ioctl(g_fd_gamepad, UI_END_FF_ERASE, &erase);
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

// Mouse + keyboard frame. `relX`/`relY` are mouse motion deltas, `scrollY` is wheel ticks,
// `keys` is a bitmask: bits 0..MOUSE_KEY_COUNT-1 = MOUSE_KEYS entries (routed to the
// keyboard fd), bits 15/16/17 = BTN_LEFT/RIGHT/MIDDLE (routed to the mouse fd).
//
// Only writes EV_KEY events when the bit actually changes, and only writes SYN_REPORT
// on a fd that emitted at least one event this frame. This is required for IME focus:
// if the mouse fd reports every frame (300Hz), Android keeps the cursor "active" and
// routes DPAD events to it instead of the focused IME view.
extern "C" JNIEXPORT void JNICALL
Java_com_steamcontroller_android_uinput_UInputNative_sendMouseFrame(
        JNIEnv*, jclass, jint relX, jint relY, jint scrollY, jint keys) {
    // Bit 16/17/18 of `keys` = BTN_LEFT/RIGHT/MIDDLE; bits 0..MOUSE_KEY_COUNT-1 = keyboard keys.
    const int mouse_bits = keys & ((1 << 16) | (1 << 17) | (1 << 18));
    const int kbd_bits   = keys & ((1 << MOUSE_KEY_COUNT) - 1);

    // Mouse fd: motion + button edges
    if (g_fd_mouse >= 0) {
        bool any_event = false;
        const int btn_changed = mouse_bits ^ g_last_mouse_buttons;
        if (btn_changed & (1 << 16)) { write_event(g_fd_mouse, EV_KEY, BTN_LEFT,   (mouse_bits >> 16) & 1); any_event = true; }
        if (btn_changed & (1 << 17)) { write_event(g_fd_mouse, EV_KEY, BTN_RIGHT,  (mouse_bits >> 17) & 1); any_event = true; }
        if (btn_changed & (1 << 18)) { write_event(g_fd_mouse, EV_KEY, BTN_MIDDLE, (mouse_bits >> 18) & 1); any_event = true; }
        if (relX != 0)    { write_event(g_fd_mouse, EV_REL, REL_X,     relX);    any_event = true; }
        if (relY != 0)    { write_event(g_fd_mouse, EV_REL, REL_Y,     relY);    any_event = true; }
        if (scrollY != 0) { write_event(g_fd_mouse, EV_REL, REL_WHEEL, scrollY); any_event = true; }
        if (any_event) {
            write_event(g_fd_mouse, EV_SYN, SYN_REPORT, 0);
            g_last_mouse_buttons = mouse_bits;
        }
    }

    // Keyboard fd: only emit on bit change
    if (g_fd_kbd >= 0) {
        const int kbd_changed = kbd_bits ^ g_last_kbd_keys;
        if (kbd_changed != 0) {
            for (int i = 0; i < MOUSE_KEY_COUNT; i++) {
                if (kbd_changed & (1 << i)) {
                    write_event(g_fd_kbd, EV_KEY, MOUSE_KEYS[i], (kbd_bits >> i) & 1);
                }
            }
            write_event(g_fd_kbd, EV_SYN, SYN_REPORT, 0);
            g_last_kbd_keys = kbd_bits;
        }
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_steamcontroller_android_uinput_UInputNative_destroy(JNIEnv*, jclass) {
    destroy_devices();
    LOGI("Virtual devices destroyed");
}
