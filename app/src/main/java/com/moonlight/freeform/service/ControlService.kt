package com.moonlight.freeform.service

import android.annotation.SuppressLint
import android.os.SystemClock
import android.util.Log
import android.view.*
import com.moonlight.freeform.IControlService

import com.moonlight.freeform.bean.MotionEventBean
import com.moonlight.freeform.systemapi.InputManager
import com.moonlight.freeform.systemapi.ServiceManager
import com.moonlight.freeform.utils.ShellUtils
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

/**
 * @author sunshine
 * @date 2021/3/17
 */

class ControlService : IControlService.Stub() {
    private var inputManager: InputManager? = null

    /**
     * Initialize the service
     */
    @SuppressLint("PrivateApi")
    override fun init(): Boolean {
        val serviceManager = ServiceManager()
        inputManager = serviceManager.getInputManager()

        return inputManager != null
    }

    override fun execShell(command: String, useRoot: Boolean): Boolean {
        return ShellUtils.execCommand(command, useRoot).result == 0
    }

    /**
     * Return the command's text output (stdout only).
     *
     * Runs in the same Shizuku shell-uid process as execShell; meant for probes like "who asked the window to hide"
     * that need to read the content rather than the exit code. On failure or empty output it returns an empty string,
     * and the caller falls back to default text on an empty string.
     */
    override fun execShellText(command: String, useRoot: Boolean): String {
        return try {
            ShellUtils.execCommand(command, useRoot).successMsg ?: ""
        } catch (e: Exception) {
            // This whole channel is for diagnostics, so if it too can't find anything we must leave a trace even more.
            Log.w(TAG, "exec_shell_text_failed", e)
            ""
        }
    }

    /**
     * Touch event
     */
    override fun touch(motionEventBean: MotionEventBean) {
        val count = motionEventBean.xArray.size
        val pointerProperties: Array<MotionEvent.PointerProperties?> = arrayOfNulls(count)
        val pointerCoords: Array<MotionEvent.PointerCoords?> = arrayOfNulls(count)
        for (i in 0 until count) {
            pointerProperties[i] = MotionEvent.PointerProperties()
            pointerProperties[i]!!.id = i
            pointerProperties[i]!!.toolType = MotionEvent.TOOL_TYPE_FINGER

            pointerCoords[i] = MotionEvent.PointerCoords()
            pointerCoords[i]!!.apply {
                orientation = 0f
                pressure = 1f
                size = 1f
                x = motionEventBean.xArray[i]
                y = motionEventBean.yArray[i]
            }
        }

        val motionEvent = MotionEvent.obtain(
            SystemClock.uptimeMillis(),
            SystemClock.uptimeMillis(),
            motionEventBean.action,
            count,
            pointerProperties,
            pointerCoords,
            0,
            0,
            1.0f,
            1.0f,
            -1,
            0,
            InputDevice.SOURCE_TOUCHSCREEN,
            0
        )

        inputManager!!.injectInputEvent(motionEvent, motionEventBean.displayId)

        motionEvent.recycle()
    }

    /**
     * Move to fullscreen
     */
    override fun moveStack(displayId: Int): Boolean {
        return try {
            val stackId: String = ShellUtils.execCommand(
                "am stack list | grep displayId=$displayId",
                false
            ).successMsg!!.split(" ".toRegex()).toTypedArray()[1].replace("id=", "")

            val result = ShellUtils.execCommand(
                "am display move-stack $stackId 0",
                false
            ).result

            result == 0
        } catch (e: Exception) {
            // am stack / am display move-stack were removed on Android 12+, so failing here is expected,
            // but we must leave a trace, otherwise the upper layer only sees "moving to fullscreen did nothing" with no way to attribute it.
            Log.w(TAG, "move_stack_failed display=$displayId", e)
            false
        }

    }

    /**
     * Press back
     */
    override fun pressBack(displayId: Int) {
        val down = KeyEvent(
            SystemClock.uptimeMillis(),
            SystemClock.uptimeMillis(),
            KeyEvent.ACTION_DOWN,
            KeyEvent.KEYCODE_BACK,
            0
        )
        val up = KeyEvent(
            SystemClock.uptimeMillis(),
            SystemClock.uptimeMillis(),
            KeyEvent.ACTION_UP,
            KeyEvent.KEYCODE_BACK,
            0
        )

        try {
            KeyEvent::class.java.getMethod("setSource", Int::class.javaPrimitiveType)
                .invoke(down, InputDevice.SOURCE_KEYBOARD)
            inputManager!!.injectInputEvent(down, displayId)

            KeyEvent::class.java.getMethod("setSource", Int::class.javaPrimitiveType)
                .invoke(up, InputDevice.SOURCE_KEYBOARD)
            inputManager!!.injectInputEvent(up, displayId)
        } catch (e: Exception) {
            // This service runs in Shizuku's separate process; without logging we could never find why the back key stops working.
            Log.w(TAG, "press_back_failed display=$displayId", e)
        }
    }

    companion object {
        private const val TAG = "ControlService"
    }
}