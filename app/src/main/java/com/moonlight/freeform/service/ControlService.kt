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
     * 初始化服务
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
     * 取命令的文本输出（只用 stdout）。
     *
     * 和 execShell 一样跑在 Shizuku 那个 shell uid 进程里；给「窗口是被谁要求隐藏的」这类
     * 需要读内容而不是读退出码的探针用。失败或没输出时回空串，调用方按空串走兜底文案。
     */
    override fun execShellText(command: String, useRoot: Boolean): String {
        return try {
            ShellUtils.execCommand(command, useRoot).successMsg ?: ""
        } catch (e: Exception) {
            // 这条通道整条都是诊断用的，它自己再查不到就更得留痕。
            Log.w(TAG, "exec_shell_text_failed", e)
            ""
        }
    }

    /**
     * 触摸事件
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
     * 移动到全屏
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
            // am stack / am display move-stack 在 Android 12+ 已被移除，这里失败是预期的，
            // 但必须留痕，否则上层只看到「移动到全屏没反应」，无从归因。
            Log.w(TAG, "move_stack_failed display=$displayId", e)
            false
        }

    }

    /**
     * 点击返回
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
            // 本服务跑在 Shizuku 的独立进程里，不记日志就永远查不到返回键失效的原因。
            Log.w(TAG, "press_back_failed display=$displayId", e)
        }
    }

    companion object {
        private const val TAG = "ControlService"
    }
}