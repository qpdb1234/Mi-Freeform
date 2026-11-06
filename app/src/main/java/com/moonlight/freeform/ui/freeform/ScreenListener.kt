package com.moonlight.freeform.ui.freeform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter

/**
 * A listener that watches Android screen-off and unlock events.
 */
class ScreenListener(private val mContext: Context) {
    private val mScreenReceiver: ScreenBroadcastReceiver
    private var mScreenStateListener: ScreenStateListener? = null

    /**
     * Broadcast receiver for screen state
     */
    private inner class ScreenBroadcastReceiver : BroadcastReceiver() {
        private var action: String? = null
        override fun onReceive(context: Context, intent: Intent) {
            action = intent.action
            if (Intent.ACTION_SCREEN_ON == action) { // screen on
                mScreenStateListener!!.onScreenOn()
            } else if (Intent.ACTION_SCREEN_OFF == action) { // screen off
                mScreenStateListener!!.onScreenOff()
            } else if (Intent.ACTION_USER_PRESENT == action) { // unlocked
                mScreenStateListener!!.onUserPresent()
            }
        }
    }

    /**
     * Start listening to screen state
     *
     * @param listener
     */
    fun begin(listener: ScreenStateListener?) {
        mScreenStateListener = listener
        registerListener()
    }

    /**
     * Stop listening to screen state
     */
    fun unregisterListener() {
        mContext.unregisterReceiver(mScreenReceiver)
    }

    /**
     * Register the screen-state broadcast receiver
     */
    private fun registerListener() {
        val filter = IntentFilter()
        filter.addAction(Intent.ACTION_SCREEN_ON)
        filter.addAction(Intent.ACTION_SCREEN_OFF)
        filter.addAction(Intent.ACTION_USER_PRESENT)
        mContext.registerReceiver(mScreenReceiver, filter)
    }

    interface ScreenStateListener {
        // Report the screen state back to the caller
        fun onScreenOn()
        fun onScreenOff()
        fun onUserPresent()
    }

    init {
        mScreenReceiver = ScreenBroadcastReceiver()
    }
}