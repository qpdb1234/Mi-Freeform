package com.moonlight.freeform.systemapi

import android.annotation.SuppressLint
import android.os.IInterface
import android.util.Log
import android.view.InputEvent
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

/**
 * @author sunshine
 * @date 2021/2/10
 */
@SuppressLint("DiscouragedPrivateApi", "PrivateApi")
class InputManager(private val manager: IInterface) {

    private var injectInputEventMethod: Method? = null
    private var setDisplayIdMethod: Method? = null

    // 注入与设屏号都在每个输入事件的热路径上，一旦永久失败就会按事件频率刷日志。
    // 因此每类失败只记第一次，既能留下归因线索，又不会把 logcat 冲掉。
    private val loggedInjectFailure = AtomicBoolean(false)
    private val loggedSetDisplayIdFailure = AtomicBoolean(false)

    private fun getInjectInputEventMethod(): Method? {
        try {
            if (injectInputEventMethod == null) {
                injectInputEventMethod = manager.javaClass.getMethod(
                    "injectInputEvent",
                    InputEvent::class.java,
                    Int::class.javaPrimitiveType
                )
            }
        }catch (e: Exception) {
            if (loggedInjectFailure.compareAndSet(false, true)) {
                Log.w(TAG, "get_inject_method_failed", e)
            }
        }
        return injectInputEventMethod
    }

    fun injectInputEvent(inputEvent: InputEvent, displayId: Int): Boolean {
        setDisplayId(inputEvent, displayId)
        return try {
            val method = getInjectInputEventMethod()
            method!!.invoke(manager, inputEvent, 0) as Boolean
        } catch (e: InvocationTargetException) {
            if (loggedInjectFailure.compareAndSet(false, true)) {
                Log.w(TAG, "inject_failed display=$displayId target=${inputEvent.javaClass.simpleName}", e.cause ?: e)
            }
            false
        } catch (e: IllegalAccessException) {
            if (loggedInjectFailure.compareAndSet(false, true)) {
                Log.w(TAG, "inject_failed display=$displayId illegal access", e)
            }
            false
        } catch (e: NoSuchMethodException) {
            if (loggedInjectFailure.compareAndSet(false, true)) {
                Log.w(TAG, "inject_failed display=$displayId no such method", e)
            }
            false
        }
    }

    /**
     * 反射获取setDisplayId(int)方法
     */
    private fun getSetDisplayIdMethod(): Method? {
        if (setDisplayIdMethod == null) {
            setDisplayIdMethod = InputEvent::class.java.getMethod(
                "setDisplayId",
                Int::class.javaPrimitiveType
            )
        }
        return setDisplayIdMethod
    }

    /**
     * 设置displayId
     * 将副屏id传入
     * 用于控制副屏
     */
    private fun setDisplayId(inputEvent: InputEvent, displayId: Int): Boolean {
        return try {
            val method =
                getSetDisplayIdMethod()
            method?.invoke(inputEvent, displayId)
            true
        }catch (e: Exception) {
            // 这一条失败意味着所有事件都只会落到主屏，小窗点不动；必须能查到。
            if (loggedSetDisplayIdFailure.compareAndSet(false, true)) {
                Log.w(TAG, "set_display_id_failed display=$displayId", e)
            }
            false
        }
    }

    companion object {
        private const val TAG = "InputManager"
    }
}