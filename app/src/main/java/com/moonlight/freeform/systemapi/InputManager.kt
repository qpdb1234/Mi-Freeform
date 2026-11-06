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

    // Injecting and setting the display id are both on the hot path of every input event, so a permanent failure would log at event frequency.
    // Therefore each failure kind is logged only once, leaving an attribution clue without flooding logcat.
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
     * Reflectively obtain the setDisplayId(int) method
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
     * Set the displayId.
     * Passes in the secondary display id,
     * used to control the secondary display.
     */
    private fun setDisplayId(inputEvent: InputEvent, displayId: Int): Boolean {
        return try {
            val method =
                getSetDisplayIdMethod()
            method?.invoke(inputEvent, displayId)
            true
        }catch (e: Exception) {
            // This failure means every event lands only on the main display and the freeform becomes untappable; it must be findable.
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