package com.moonlight.freeform.ui.freeform

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import com.moonlight.freeform.R
import com.moonlight.freeform.app.MiFreeform
import com.moonlight.freeform.utils.PermissionUtils
import kotlin.math.roundToInt

/**
 * The floating ball shown after collapse.
 *
 * It is an independent collapse mode from "collapse into the sidebar":
 * - Sidebar: fully removes the window, keeping only a record in FreeformHelper; the user reopens it from the sidebar list.
 * - Floating ball: places a circular app-icon ball on the screen edge; tapping restores the freeform; it is draggable with adjustable transparency and size.
 *
 * The ball itself does not own a virtual display; it is only an entry point meaning "this freeform is still alive".
 * The real FreeformView stays in the background; tapping the ball calls fromBackstage() to re-add the window.
 */
internal class MiniBallView(
    context: Context,
    private val freeformView: FreeformView,
) {

    private val context: Context = context.applicationContext
    private val windowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = context.resources.displayMetrics.density

    private var rootView: FrameLayout? = null
    private var imageView: ImageView? = null
    private var windowParams: WindowManager.LayoutParams? = null

    // The ball's position on screen (remembered on collapse, reused on next restore)
    private var ballX = 0
    private var ballY = 0

    private var downRawX = 0f
    private var downRawY = 0f
    private var isDragging = false

    // Dragging is a high-frequency path (dozens of MOVE events per second); updateViewLayout would flood logs if it keeps failing.
    // Throttle per gesture: log only the first failure per press.
    private var loggedDragFailure = false

    /** Show the floating ball. */
    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (rootView != null) return
        // Decide the window type first: if neither qualification is held, give up immediately without calling addView once
        // (see PermissionUtils.pickOverlayWindowType for why: a rejected request poisons overlay windows process-wide).
        val windowType = overlayWindowType()
        if (windowType == null) {
            Log.w(TAG, "miniball_start_rejected no_overlay_permission")
            PermissionUtils.guideToGrantOverlayPermission(context, context.getString(R.string.scene_miniball))
            return
        }

        val sp = context.getSharedPreferences(MiFreeform.APP_SETTINGS_NAME, Context.MODE_PRIVATE)
        val sizeDp = sp.getInt(KEY_BALL_SIZE_DP, DEFAULT_SIZE_DP).coerceIn(MIN_SIZE_DP, MAX_SIZE_DP)
        // The setting's semantics are "transparency": larger value means more transparent. Stored as 0~10 transparency,
        // converted here to View.alpha (0 fully transparent ~ 1 opaque) = 1 - transparency/10.
        val transparency = sp.getInt(KEY_BALL_ALPHA, DEFAULT_ALPHA).coerceIn(0, 10)
        val alpha = 1f - transparency / 10f

        val sizePx = (sizeDp * density).roundToInt()

        // Collapse position: prefer the last remembered position, otherwise snap to the right screen edge, vertically centered.
        val metrics = context.resources.displayMetrics
        if (ballX == 0 && ballY == 0) {
            ballX = metrics.widthPixels - sizePx
            ballY = (metrics.heightPixels - sizePx) / 2
        }

        val container = FrameLayout(context).apply {
            // Dragging is handled by the root container; the ImageView only displays the icon.
            setOnTouchListener { _, event -> handleTouch(event, sizePx) }
        }

        val icon = ImageView(context).apply {
            setImageDrawable(loadAppIcon())
            this.alpha = alpha
            // Circular clipping: app icons are usually square; clipping to a circle makes it look more like a floating ball.
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
        }
        container.addView(
            icon,
            FrameLayout.LayoutParams(sizePx, sizePx, Gravity.CENTER)
        )

        val params = WindowManager.LayoutParams(
            sizePx + (8 * density).roundToInt(),
            sizePx + (8 * density).roundToInt(),
            windowType,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = ballX
            y = ballY
        }

        try {
            windowManager.addView(container, params)
        } catch (e: Exception) {
            Log.w(TAG, "miniball_add_failed", e)
            return
        }

        rootView = container
        imageView = icon
        windowParams = params
    }

    /** Hide and release the floating ball (called when restoring the freeform or exiting). */
    fun dismiss() {
        val view = rootView ?: return
        rootView = null
        imageView = null
        windowParams = null
        try {
            windowManager.removeViewImmediate(view)
        } catch (e: Exception) {
            // rootView is already set to null, so if this fails no one can ever remove the window—the ball becomes a zombie window,
            // and the next show() also thinks it's a duplicate call and returns directly.
            Log.w(TAG, "miniball_remove_failed", e)
        }
    }

    /** Whether the floating ball is currently showing. */
    fun isShowing(): Boolean = rootView != null

    private fun overlayWindowType(): Int? {
        // Consistent with Mi-Freeform's existing overlay windows: use APPLICATION_OVERLAY if the overlay permission is granted,
        // otherwise ACCESSIBILITY_OVERLAY can only be used when the accessibility service is actually on.
        // The old form unconditionally fell back to ACCESSIBILITY_OVERLAY, but when the accessibility service wasn't on, that addView was always rejected,
        // which produced exactly the process-wide hiding described above, so here we return null to let the caller skip attaching the window.
        return when {
            android.provider.Settings.canDrawOverlays(context) ->
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            PermissionUtils.isAccessibilitySettingsOn(context) -> TYPE_ACCESSIBILITY_OVERLAY
            else -> null
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun handleTouch(event: MotionEvent, sizePx: Int): Boolean {
        val params = windowParams ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                isDragging = false
                loggedDragFailure = false
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                // Movement beyond the threshold counts as dragging, to avoid misjudging a tap.
                if (!isDragging && (kotlin.math.abs(dx) > DRAG_THRESHOLD_PX ||
                            kotlin.math.abs(dy) > DRAG_THRESHOLD_PX)
                ) {
                    isDragging = true
                }
                if (isDragging) {
                    params.x = (params.x + dx).roundToInt()
                    params.y = (params.y + dy).roundToInt()
                    safePosition(params)
                    try {
                        windowManager.updateViewLayout(rootView, params)
                    } catch (e: Exception) {
                        if (!loggedDragFailure) {
                            loggedDragFailure = true
                            Log.w(TAG, "miniball_drag_layout_failed", e)
                        }
                    }
                    downRawX = event.rawX
                    downRawY = event.rawY
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!isDragging) {
                    // Single tap = restore the freeform
                    restoreFreeform()
                } else {
                    rememberPosition(params)
                }
                isDragging = false
                return true
            }
        }
        return false
    }

    private fun restoreFreeform() {
        try {
            freeformView.fromBackstage()
        } catch (e: Throwable) {
            Log.w(TAG, "miniball_restore_failed", e)
        }
        dismiss()
    }

    private fun safePosition(params: WindowManager.LayoutParams) {
        val metrics = context.resources.displayMetrics
        params.x = params.x.coerceIn(0, (metrics.widthPixels - params.width).coerceAtLeast(0))
        params.y = params.y.coerceIn(0, (metrics.heightPixels - params.height).coerceAtLeast(0))
        ballX = params.x
        ballY = params.y
    }

    private fun rememberPosition(params: WindowManager.LayoutParams) {
        ballX = params.x
        ballY = params.y
    }

    /** Loads the app icon; if unavailable, degrades to a semi-transparent dot so the ball is never empty. */
    private fun loadAppIcon(): android.graphics.drawable.Drawable {
        return try {
            val config = freeformView.config
            val pm = context.packageManager
            val info = pm.getApplicationInfo(config.packageName, 0)
            pm.getApplicationIcon(info)
        } catch (e: Exception) {
            Log.w(TAG, "miniball_icon_failed", e)
            android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(0xCC607D8B.toInt())
            }
        }
    }

    private companion object {
        const val TAG = "MiniBallView"

        const val KEY_BALL_SIZE_DP = "miniball_size_dp"
        const val KEY_BALL_ALPHA = "miniball_alpha"

        const val DEFAULT_SIZE_DP = 48
        const val MIN_SIZE_DP = 32
        const val MAX_SIZE_DP = 96
        const val DEFAULT_ALPHA = 7 // 0~10 "transparency", default 7 (alpha = 1 - 7/10 = 0.3)

        const val DRAG_THRESHOLD_PX = 16f

        // TYPE_ACCESSIBILITY_OVERLAY, consistent with FreeformGestureController.
        const val TYPE_ACCESSIBILITY_OVERLAY = 2032
    }
}
