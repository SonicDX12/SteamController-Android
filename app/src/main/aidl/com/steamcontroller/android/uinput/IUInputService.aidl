package com.steamcontroller.android.uinput;

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
