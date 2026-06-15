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

    // Returns [strongMagnitude, weakMagnitude] in 0..65535 if a game triggered rumble,
    // or null if nothing happened since the last poll.
    int[] pollForceFeedback();

    // Run an arbitrary shell command as the Shizuku shell user. Returns the exit code.
    // Used for screenshot (screencap), and as a general escape hatch for future system actions.
    int runShellCommand(in String[] cmd);

    void destroy();
}
