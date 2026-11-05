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
 * 收起后的悬浮球。
 *
 * 与「收到侧边栏」是两种独立的收起方式：
 * - 侧边栏：完全移除窗口，只保留 FreeformHelper 里的记录，用户从侧边栏列表重新点开。
 * - 悬浮球：在屏幕边缘放一个应用图标的圆球，点击即恢复小窗，可拖动、可调透明度和大小。
 *
 * 悬浮球本身不持有虚拟屏，它只是「这个小窗还活着」的入口，
 * 真正的 FreeformView 仍然在后台，点击球时调 fromBackstage() 把窗口加回来。
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

    // 悬浮球在屏幕上的位置（收起时记住，下次复现）
    private var ballX = 0
    private var ballY = 0

    private var downRawX = 0f
    private var downRawY = 0f
    private var isDragging = false

    // 拖动是高频路径（每秒几十个 MOVE），updateViewLayout 一旦持续失败就会刷日志。
    // 按手势限流：每次按下只记第一次失败。
    private var loggedDragFailure = false

    /** 显示悬浮球。 */
    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (rootView != null) return
        // 先裁决窗口类型：两个资格都没有时直接放弃，一次 addView 都不调
        //（原因见 PermissionUtils.pickOverlayWindowType：被拒的请求会毒掉全进程的悬浮窗）。
        val windowType = overlayWindowType()
        if (windowType == null) {
            Log.w(TAG, "miniball_start_rejected no_overlay_permission")
            PermissionUtils.guideToGrantOverlayPermission(context, context.getString(R.string.scene_miniball))
            return
        }

        val sp = context.getSharedPreferences(MiFreeform.APP_SETTINGS_NAME, Context.MODE_PRIVATE)
        val sizeDp = sp.getInt(KEY_BALL_SIZE_DP, DEFAULT_SIZE_DP).coerceIn(MIN_SIZE_DP, MAX_SIZE_DP)
        // 设置项语义是「透明度」：数值越大越透明。存的是 0~10 的透明度，
        // 这里换算成 View.alpha（0 全透明 ~ 1 不透明）= 1 - 透明度/10。
        val transparency = sp.getInt(KEY_BALL_ALPHA, DEFAULT_ALPHA).coerceIn(0, 10)
        val alpha = 1f - transparency / 10f

        val sizePx = (sizeDp * density).roundToInt()

        // 收起位置：优先用上次记住的位置，否则贴屏幕右侧边缘垂直居中。
        val metrics = context.resources.displayMetrics
        if (ballX == 0 && ballY == 0) {
            ballX = metrics.widthPixels - sizePx
            ballY = (metrics.heightPixels - sizePx) / 2
        }

        val container = FrameLayout(context).apply {
            // 拖动靠根容器接管，ImageView 只负责显示图标。
            setOnTouchListener { _, event -> handleTouch(event, sizePx) }
        }

        val icon = ImageView(context).apply {
            setImageDrawable(loadAppIcon())
            this.alpha = alpha
            // 圆形裁剪：应用图标一般是方的，裁圆后更像悬浮球。
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

    /** 隐藏并释放悬浮球（恢复小窗或退出时调用）。 */
    fun dismiss() {
        val view = rootView ?: return
        rootView = null
        imageView = null
        windowParams = null
        try {
            windowManager.removeViewImmediate(view)
        } catch (e: Exception) {
            // rootView 已经置 null，这句失败就再也没人能把窗口摘下来——悬浮球变僵尸窗口，
            // 并且下次 show() 也认为自己是重复调用而直接 return。
            Log.w(TAG, "miniball_remove_failed", e)
        }
    }

    /** 悬浮球是否正在显示。 */
    fun isShowing(): Boolean = rootView != null

    private fun overlayWindowType(): Int? {
        // 与 Mi-Freeform 既有悬浮窗一致：有overlay权限用 APPLICATION_OVERLAY，
        // 否则只有在无障碍服务真的开着时才能用 ACCESSIBILITY_OVERLAY。
        // 旧写法是无条件退回 ACCESSIBILITY_OVERLAY，可无障碍服务没开时那一次 addView 必定被拒，
        // 正好构成上面说的全进程隐藏，所以这里返回 null 让调用方放弃挂窗。
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
                // 移动超过阈值才算拖动，避免点击被误判。
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
                    // 单击 = 恢复小窗
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

    /** 取应用图标；取不到时退化成一个半透明圆点，保证球不会是空的。 */
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
        const val DEFAULT_ALPHA = 7 // 0~10 的「透明度」，默认 7（alpha = 1 - 7/10 = 0.3）

        const val DRAG_THRESHOLD_PX = 16f

        // TYPE_ACCESSIBILITY_OVERLAY，与 FreeformGestureController 一致。
        const val TYPE_ACCESSIBILITY_OVERLAY = 2032
    }
}
