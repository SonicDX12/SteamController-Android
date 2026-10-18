// NOTE: this interface lives in `ipc`, not `uinput`, on purpose.
//
// AGP 8.11+ writes the full aidl command line into a comment at the top of the
// generated Java. On Windows that path uses backslashes, so a package segment
// starting with "u" yields the literal text \uinput — and javac treats \u as a
// unicode escape everywhere in a source file, comments included, so the build
// fails with "illegal unicode escape". Renaming the segment removes the sequence.
package com.steamcontroller.android.ipc;

interface IUInputService {
    boolean canCreateDevice();

    // Create a virtual gamepad with the given profile id (see GamepadProfile.kt).
    // Returns true on success.
    boolean createGamepad(int profileId);

    void sendFrame(int buttons,
                   int leftStickX, int leftStickY,
                   int rightStickX, int rightStickY,
                   int leftTrigger, int rightTrigger,
                   int dpadX, int dpadY);

    // Desktop / mouse-mode frame. `keys` is a MouseTarget bitmask.
    void sendMouseFrame(int relX, int relY, int scrollY, int keys);

    // Returns [strongMagnitude, weakMagnitude] in 0..65535 if a game triggered rumble,
    // or null if nothing happened since the last poll.
    int[] pollForceFeedback();

    // Run an arbitrary shell command as the Shizuku shell user. Returns the exit code.
    // Used for screenshot (screencap), and as a general escape hatch for future system actions.
    int runShellCommand(in String[] cmd);

    // Same, but returns captured stdout (trimmed) instead of the exit code, or null on failure.
    // Used to read a Settings value before overriding it, so it can be restored later.
    String runShellCommandForOutput(in String[] cmd);

    void destroy();
}
