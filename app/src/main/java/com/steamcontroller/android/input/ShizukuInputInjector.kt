package com.steamcontroller.android.input

import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

class ShizukuInputInjector {

    private val TAG = "ShizukuInputInjector"

    private var inputManager: Any? = null
    private var injectMethod: java.lang.reflect.Method? = null

    // Source flags for a virtual gamepad
    private val SOURCE = InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK

    fun init(): Boolean {
        return try {
            val inputManagerClass = Class.forName("android.hardware.input.IInputManager\$Stub")
            val asInterface = inputManagerClass.getMethod("asInterface", android.os.IBinder::class.java)
            val binder = ShizukuBinderWrapper(SystemServiceHelper.getSystemService("input"))
            inputManager = asInterface.invoke(null, binder)

            injectMethod = inputManager!!.javaClass.getMethod(
                "injectInputEvent",
                android.view.InputEvent::class.java,
                Int::class.java
            )
            Log.i(TAG, "ShizukuInputInjector initialized")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init injector: ${e.message}")
            false
        }
    }

    fun injectKey(keyCode: Int, down: Boolean) {
        val action = if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP
        val now = SystemClock.uptimeMillis()
        val event = KeyEvent(
            now, now, action, keyCode, 0,
            0, -1, 0,
            KeyEvent.FLAG_FROM_SYSTEM,
            SOURCE
        )
        inject(event)
    }

    fun injectMotion(axes: Map<Int, Float>) {
        val now = SystemClock.uptimeMillis()
        val builder = MotionEvent.PointerProperties()
        builder.id = 0
        builder.toolType = MotionEvent.TOOL_TYPE_UNKNOWN

        val props = arrayOf(builder)
        val coords = arrayOf(MotionEvent.PointerCoords().also { c ->
            axes.forEach { (axis, value) -> c.setAxisValue(axis, value) }
        })

        val event = MotionEvent.obtain(
            now, now,
            MotionEvent.ACTION_MOVE,
            1, props, coords,
            0, 0,
            1f, 1f,
            -1, 0,
            SOURCE, 0
        )
        inject(event)
        event.recycle()
    }

    private fun inject(event: android.view.InputEvent) {
        try {
            injectMethod?.invoke(inputManager, event, 0)
        } catch (e: Exception) {
            Log.e(TAG, "Inject failed: ${e.message}")
        }
    }

    val isReady get() = inputManager != null && injectMethod != null
}
