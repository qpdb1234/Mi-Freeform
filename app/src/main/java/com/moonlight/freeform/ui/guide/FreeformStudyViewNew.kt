package com.moonlight.freeform.ui.guide

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.hardware.input.IInputManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.*
import android.widget.Toast
import androidx.annotation.RequiresApi
import com.moonlight.freeform.R
import com.moonlight.freeform.app.MiFreeform
import com.moonlight.freeform.bean.MotionEventBean
import com.moonlight.freeform.databinding.ViewFreeformBinding
import com.moonlight.freeform.ui.freeform.*
import com.moonlight.freeform.utils.DiagnosticsLog
import com.moonlight.freeform.utils.PermissionUtils
import kotlinx.coroutines.*
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.lang.reflect.Field
import java.lang.reflect.Method
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class FreeformStudyViewNew(
    override val config: FreeformConfig,
    private val context: Context,
    private val callback: Callback
) : FreeformViewAbs(config), View.OnTouchListener {
    //Services
    private val windowManager: WindowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val displayManager: DisplayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private var activityTaskManager: IActivityTaskManager? = null
    private var activityManager: IActivityManager? = null
    private var inputManager: IInputManager? = null
    private var iWindowManager: IWindowManager? = null

    //ViewModel
    private val viewModel = FreeformViewModel(context)

    private val scope = MainScope()

    //The default display, used to get the portrait/landscape state
    private val defaultDisplay: Display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)

    //UI binding
    private lateinit var binding: ViewFreeformBinding

    //Whether this is the first launch, to avoid re-initializing on screen rotation
    private var firstInit = true

    //Whether this freeform window is already destroyed
    private var isDestroy = false

    /**
     * Whether destroy() has truly run; used only for idempotence.
     *
     * This is not the same as [isDestroy]: [isDestroy] gets set early inside moveTaskToDefaultDisplay()
     * (to stop the Sui hook from pulling the task back into the freeform window), before destroy() has run;
     * if idempotence also used [isDestroy], then in fullscreen destroy() would be skipped outright,
     * leaving the freeform window on screen and the virtual display stuck at fullscreen size, so next time the app would render a size smaller than the window.
     */
    private var isDestroyed = false

    // Whether the window is really attached to the WindowManager. When not attached, removeViewImmediate always throws,
    // and with the added logging that exception would read as a real fault, so we must check this flag before detaching.
    private var windowAttached = false

    //Whether in the background-suspended state
    private var isBackstage = false

    //The taskId of the app inside the freeform window
    private var taskId = -1

    //Overlay params
    private val windowLayoutParams = WindowManager.LayoutParams()

    //Virtual display
    private lateinit var virtualDisplay: VirtualDisplay

    //Physical screen orientation
    private var screenRotation = defaultDisplay.rotation
    //Virtual display orientation, 1 portrait, 0 landscape
    private var virtualDisplayRotation = VIRTUAL_DISPLAY_ROTATION_PORTRAIT

    //Screen listener
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}

        override fun onDisplayRemoved(displayId: Int) {}

        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) {
                val tempScreenRotation = defaultDisplay.rotation
                if (tempScreenRotation != screenRotation) {
                    screenRotation = tempScreenRotation
                    onScreenOrientationChanged()
                }
            }
        }
    }

    private val screenListener = ScreenListener(context)

    //Screen width/height, size not guaranteed
    private var realScreenWidth = 0
    private var realScreenHeight = 0

    //The freeform window's "size"; this size only changes when the orientation inside the freeform window changes
    private var freeformScreenHeight = 0
    private var freeformScreenWidth = 0

    //The freeform window UI's width/height; this size doesn't change with screen or window orientation, i.e. h>w always holds. It only changes when the physical screen orientation changes
    private var freeformHeight = 0
    private var freeformWidth = 0

    //max(freeformScreenHeight,freeformScreenWidth), used to compute scale
    private var maxFreeformScreenSize = 0

    //Scale ratio
    private var scale: Float = 0f

    private val leftGestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (canClose) {
                callback.onCloseClick()
            }
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (canToFullScreen) {callback.onToFullClick()}
        }
    })

    private val rightGestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (canBack) {callback.onBackClick()}
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (canToBackStage) {callback.onToBackStageClick()}
        }
    })

    //Fixed for the teaching window: double-tap the middle of the bottom bar = rotate the freeform window (doesn't follow settings, to keep the teaching consistent)
    private val middleGestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            virtualDisplayRotation = if (virtualDisplayRotation == VIRTUAL_DISPLAY_ROTATION_PORTRAIT) {
                VIRTUAL_DISPLAY_ROTATION_LANDSCAPE
            } else {
                VIRTUAL_DISPLAY_ROTATION_PORTRAIT
            }
            onFreeFormRotationChanged()
            return false
        }
    })

    @RequiresApi(Build.VERSION_CODES.Q)
    private val taskStackListener = object : TaskStackListener() {
        override fun onTaskRemovalStarted(taskInfo: ActivityManager.RunningTaskInfo) {
            if (taskInfo.taskId == taskId) {
                GlobalScope.launch(Dispatchers.Main) {
                    destroy()
                }
            }
        }

        override fun onTaskDisplayChanged(tId: Int, newDisplayId: Int) {
            if (taskId == -1 && newDisplayId == virtualDisplay.display.displayId) taskId = tId

            if (tId != taskId || newDisplayId == virtualDisplay.display.displayId || isDestroy) return

            if (newDisplayId != Display.DEFAULT_DISPLAY) return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && config.useSuiRefuseToFullScreen) {
                activityTaskManager?.moveRootTaskToDisplay(tId, virtualDisplay.display.displayId)
            } else {
                //In the teaching page, when a task jumps to fullscreen, also clear the old freeform window,
                //otherwise a transparent freeform window with no content would be left behind.
                GlobalScope.launch(Dispatchers.Main) {
                    destroy()
                }
            }
        }

        override fun onTaskMovedToFront(taskInfo: ActivityManager.RunningTaskInfo) {
            try {
                val userId = taskInfo::class.java.getField("userId").get(taskInfo)
                if (taskInfo.baseActivity!!.packageName == config.packageName && userId == config.userId) {
                    taskId = taskInfo.taskId
                }
            } catch (e: Exception) {
                //On some ROMs RunningTaskInfo has no userId field; if it can't be read we just don't recognize this task, not a fault
                Log.w(TAG, "guide_read_task_userId_failed pkg=${config.packageName}", e)
            }
        }

        override fun onTaskRequestedOrientationChanged(tId: Int, requestedOrientation: Int) {
            //q220902.2 some portrait apps also go landscape; investigation found a case with requestedOrientation=2, so convert it to 1
            var tempRotation = requestedOrientation
            if (tempRotation != VIRTUAL_DISPLAY_ROTATION_PORTRAIT && tempRotation != VIRTUAL_DISPLAY_ROTATION_LANDSCAPE) tempRotation = VIRTUAL_DISPLAY_ROTATION_PORTRAIT
            if (taskId == tId && tempRotation != virtualDisplayRotation) {
                virtualDisplayRotation = tempRotation
                GlobalScope.launch(Dispatchers.Main) {
                    onFreeFormRotationChanged()
                }
            }
        }

        //q220903.2 on Android 10 this callback is needed for listening
        override fun onActivityRequestedOrientationChanged(tId: Int, requestedOrientation: Int) {
            var tempRotation = requestedOrientation
            if (tempRotation != VIRTUAL_DISPLAY_ROTATION_PORTRAIT && tempRotation != VIRTUAL_DISPLAY_ROTATION_LANDSCAPE) tempRotation = VIRTUAL_DISPLAY_ROTATION_PORTRAIT
            if (taskId == tId && tempRotation != virtualDisplayRotation) {
                virtualDisplayRotation = tempRotation
                GlobalScope.launch(Dispatchers.Main) {
                    onFreeFormRotationChanged()
                }
            }
        }
    }

    //Used by the practice freeform window
    private var canMove = false
    private var canClose = false
    private var canBack = false
    private var canToBackStage = false
    private var canToFullScreen = false
    private var canScale = false

    private var initSuccess = false

    fun setCanMove() {
        canMove = true
        canClose = false
        canBack = false
        canToBackStage = false
        canToFullScreen = false
        canScale = false
    }

    fun setCanClose() {
        canMove = false
        canClose = true
        canBack = false
        canToBackStage = false
        canToFullScreen = false
        canScale = false
    }

    fun setCanBack() {
        canMove = false
        canClose = false
        canBack = true
        canToBackStage = false
        canToFullScreen = false
        canScale = false
    }

    fun setCanToBackStage() {
        canMove = false
        canClose = false
        canBack = false
        canToBackStage = true
        canToFullScreen = false
        canScale = false
    }

    fun setCanToFullScreen() {
        canMove = false
        canClose = false
        canBack = false
        canToBackStage = false
        canToFullScreen = true
        canScale = false
    }

    fun setCanScale() {
        canMove = false
        canClose = false
        canBack = false
        canToBackStage = false
        canToFullScreen = false
        canScale = true

        binding.leftScale.setBackgroundColor(context.resources.getColor(R.color.warn_color))
        binding.rightScale.setBackgroundColor(context.resources.getColor(R.color.warn_color))
    }

    @SuppressLint("ClickableViewAccessibility")
    fun setGuideSuccess() {
        binding.bottomBar.leftView.setOnTouchListener(null)
        binding.bottomBar.leftView.setOnClickListener {
            destroy()
            callback.onSuccess()
        }
    }

    private fun initSystemService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            setDisplayIdMethod = MotionEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
        }
        try {
            activityTaskManager = IActivityTaskManager.Stub.asInterface(ShizukuBinderWrapper(SystemServiceHelper.getSystemService("activity_task")))
            //Currently only supports Android Q and above
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                activityTaskManager?.registerTaskStackListener(taskStackListener)
            }
            activityManager = IActivityManager.Stub.asInterface(ShizukuBinderWrapper(SystemServiceHelper.getSystemService("activity")))
        }catch (e: Exception) {
            Log.w(TAG, "guide_init_activity_task_service_failed", e)
        }
        try {
            inputManager = IInputManager.Stub.asInterface(ShizukuBinderWrapper(SystemServiceHelper.getSystemService("input")))
        }catch (e: Exception) {
            Log.w(TAG, "guide_init_input_service_failed", e)
        }
        try {
            iWindowManager = IWindowManager.Stub.asInterface(ShizukuBinderWrapper(SystemServiceHelper.getSystemService("window")))
        } catch (e: Exception) {
            Log.w(TAG, "guide_init_window_service_failed", e)
        }
    }

    private fun initConfig() {
        config.maxHeight = FreeformHelper.getDefaultHeight(context, defaultDisplay, config.widthHeightRatio)
        //config.widthHeightRatio = ...

        realScreenWidth = context.resources.displayMetrics.widthPixels
        realScreenHeight = context.resources.displayMetrics.heightPixels

        freeformScreenHeight = FreeformHelper.getDefaultHeight(context, defaultDisplay, config.widthHeightRatio)
        freeformScreenWidth = (freeformScreenHeight * config.widthHeightRatio).roundToInt()

        freeformHeight = if (FreeformHelper.screenIsPortrait(screenRotation)) (config.maxHeight * 0.75).roundToInt() else (config.maxHeight * 0.8).roundToInt()
        freeformWidth = (freeformHeight * config.widthHeightRatio).roundToInt()

        maxFreeformScreenSize = freeformScreenHeight

        scale = freeformHeight / maxFreeformScreenSize.toFloat()

        config.freeformDpi = FreeformHelper.getScreenDpi(context)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initView() {
        binding = ViewFreeformBinding.bind(LayoutInflater.from(context).inflate(R.layout.view_freeform, null, false))

        binding.bottomBar.leftView.setOnTouchListener(this)
        binding.bottomBar.middleView.setOnTouchListener(this)
        binding.bottomBar.rightView.setOnTouchListener(this)
        binding.leftScale.setOnTouchListener(this)
        binding.rightScale.setOnTouchListener(this)
        binding.textureView.setOnTouchListener(this)
        // Same as FreeformView: the startup loading is removed, the content is fully opaque from the start.

        initDisplay()
    }

    private fun initDisplay() {
        try {
            virtualDisplay = displayManager.createVirtualDisplay(
                "MiFreeform@${config.packageName}@${config.userId}",
                freeformScreenWidth,
                freeformScreenHeight,
                config.freeformDpi,
                null,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
            )
            initSuccess = true
        } catch (e: Exception) {
            Toast.makeText(context, context.getString(R.string.create_display_fail), Toast.LENGTH_SHORT).show()
            return
        }

        var beforeScreenX = windowLayoutParams.x

        screenListener.begin(object : ScreenListener.ScreenStateListener {
            override fun onScreenOn() {}

            //Screen off hides the freeform window
            override fun onScreenOff() {
                //Suspended state needs no update
                //Fix: MiFreeform crashed when locking the screen with a running program q220902.1
                if (!isBackstage) {
                    beforeScreenX = windowLayoutParams.x
                    windowManager.updateViewLayout(binding.root, windowLayoutParams.apply {
                        x = -realScreenWidth * 2
                    })
                }
            }

            //Unlock restores the freeform window
            override fun onUserPresent() {
                //Suspended state needs no update
                if (!isBackstage) {
                    windowManager.updateViewLayout(binding.root, windowLayoutParams.apply {
                        x = beforeScreenX
                    })
                }
            }
        })

        initOrientationChangedListener()
        initTextureViewListener()
    }

    private fun initOrientationChangedListener() {
        displayManager.registerDisplayListener(displayListener, null)
    }

    private fun initTextureViewListener() {
        //Cold-start listener
        binding.textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(
                surface: SurfaceTexture,
                width: Int,
                height: Int
            ) {
                surface.setDefaultBufferSize(freeformScreenWidth, freeformScreenHeight)
                virtualDisplay.surface = Surface(surface)

                if (firstInit) {
                    if (MiFreeform.me?.getControlService()?.execShell("am start -n ${config.packageName}/${config.activityName} --user ${config.userId} --display ${virtualDisplay.display.displayId}", false) == true) {
                        FreeformHelper.addFreeformToSet(this@FreeformStudyViewNew)
                        firstInit = false
                    }
                    //Launch failed
                    else {
                        destroy()
                    }
                }
            }

            override fun onSurfaceTextureSizeChanged(
                surface: SurfaceTexture,
                width: Int,
                height: Int
            ) {
                surface.setDefaultBufferSize(freeformScreenWidth, freeformScreenHeight)
            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                // Content is shown directly, no more first-frame counting + fade-in, and no loading animation.
            }
        }
    }

    /**
     * The decoration by which the window size exceeds the content size; shares the same algorithm as the real freeform window,
     * see [FreeformWindowGeometry] for exactly which items it includes.
     */
    private val windowChromeWidth: Int
        get() = FreeformWindowGeometry.chromeWidth(context)

    private val windowChromeHeight: Int
        get() = FreeformWindowGeometry.chromeHeight(context)

    fun showWindow() {
        if (MiFreeform.me?.getControlService() != null) {
            if (initSuccess) {
                if (virtualDisplayRotation == VIRTUAL_DISPLAY_ROTATION_PORTRAIT) {
                    windowLayoutParams.apply {
                        width = freeformWidth + windowChromeWidth
                        height = freeformHeight + windowChromeHeight
                    }
                } else {
                    windowLayoutParams.apply {
                        width = freeformHeight + windowChromeWidth
                        height = freeformWidth + windowChromeHeight
                    }
                }
                //Decide first, then open the window, for the same reason as FreeformView.showWindow: when unauthorized / accessibility service off,
                //that blind addView is guaranteed rejected, and a single rejected request can permanently hide all subsequent non-system overlay windows
                //in this process (measured on Android 14, see PermissionUtils.pickOverlayWindowType).
                val overlayAllowed = Settings.canDrawOverlays(context)
                val accessibilityOverlayUsable = PermissionUtils.isAccessibilitySettingsOn(context)
                if (!overlayAllowed && !accessibilityOverlayUsable) {
                    Log.w(TAG, "guide_start_rejected no_overlay_permission pkg=${config.packageName}")
                    destroy()
                    askForOverlayPermission(context.getString(R.string.scene_study, config.packageName))
                    return
                }
                windowLayoutParams.apply {
                    type = if (accessibilityOverlayUsable) {
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                    } else {
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    }
                    flags =
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
                    format = PixelFormat.RGBA_8888
                    windowAnimations = android.R.style.Animation_Dialog
                }

                setWindowNoUpdateAnimation()

                //Restore the recorded position
                if (config.rememberPosition) {
                    windowLayoutParams.apply {
                        x = config.rememberX
                        y = config.rememberY
                    }
                } else {
                    //In landscape, move to the left of the screen to show the freeform window
                    if (screenRotation == Surface.ROTATION_90 || screenRotation == Surface.ROTATION_270) {
                        windowLayoutParams.apply {
                            x = (width - realScreenWidth) / 2
                            //Move up a bit
                            y = 0
                        }
                    }
                }

                try {
                    windowManager.addView(binding.root, windowLayoutParams)
                    windowAttached = true
                } catch (e: Exception) {
                    Log.w(TAG, "guide_add_view_failed pkg=${config.packageName} type=${windowLayoutParams.type}", e)
                    windowAttached = false
                    try {
                        windowManager.removeViewImmediate(binding.root)
                    } catch (removeException: Exception) {
                        Log.w(TAG, "guide_remove_half_attached_view_failed pkg=${config.packageName}", removeException)
                    }

                    if (overlayAllowed) {
                        //When the accessibility type can't be attached, fall back to a normal overlay window; this step is only a legitimate request when the permission is held
                        try {
                            windowManager.addView(binding.root, windowLayoutParams.apply {
                                type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                            })
                            windowAttached = true
                        } catch (fallbackException: Exception) {
                            Log.e(TAG, "guide_add_view_fallback_failed pkg=${config.packageName}", fallbackException)
                            DiagnosticsLog.recordRes(
                                context, "overlay_rejected",
                                R.string.diag_study_both_types, config.packageName, fallbackException.javaClass.simpleName
                            )
                            destroy()
                        }
                    } else {
                        //When there's no permission, retrying is a second "rejected request", so we mustn't try—just tear it down cleanly.
                        Log.e(TAG, "guide_add_view_rejected_no_fallback pkg=${config.packageName}", e)
                        DiagnosticsLog.recordRes(
                            context, "overlay_rejected",
                            R.string.diag_study_no_fallback, config.packageName
                        )
                        destroy()
                    }
                }
            }
        }
        else {
            Log.w(TAG, "guide_start_rejected shizuku not bound pkg=${config.packageName}")
            DiagnosticsLog.recordRes(
                context, "shizuku_unbound", R.string.service_not_running_pkg, config.packageName
            )
            Toast.makeText(context, context.getString(R.string.service_not_running), Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Tells the user the overlay permission is missing, report only without jumping to system settings.
     *
     * Previously this did `startActivity(ACTION_MANAGE_OVERLAY_PERMISSION)`, and on Android 14
     * that jump inside the rejection branch is exactly what makes all subsequent overlay windows in this process "invisible"
     *(see [PermissionUtils.guideToGrantOverlayPermission] for the measurement and rationale).
     */
    private fun askForOverlayPermission(scene: String) {
        PermissionUtils.guideToGrantOverlayPermission(context, scene)
    }

    /**
     * Disable the update transition animation
     */
    private fun setWindowNoUpdateAnimation() {
        val classname = "android.view.WindowManager\$LayoutParams"
        try {
            val layoutParamsClass: Class<*> = Class.forName(classname)
            val privateFlags: Field = layoutParamsClass.getField("privateFlags")
            val noAnim: Field = layoutParamsClass.getField("PRIVATE_FLAG_NO_MOVE_ANIMATION")
            var privateFlagsValue: Int = privateFlags.getInt(windowLayoutParams)
            val noAnimFlag: Int = noAnim.getInt(windowLayoutParams)
            privateFlagsValue = privateFlagsValue or noAnimFlag
            privateFlags.setInt(windowLayoutParams, privateFlagsValue)
        } catch (e: Exception) {
            Log.w(TAG, "disable_window_animation_failed", e)
        }
    }

    private fun setWindowEnableUpdateAnimation() {
        val classname = "android.view.WindowManager\$LayoutParams"
        try {
            val layoutParamsClass: Class<*> = Class.forName(classname)
            val privateFlags: Field = layoutParamsClass.getField("privateFlags")
            val noAnim: Field = layoutParamsClass.getField("PRIVATE_FLAG_NO_MOVE_ANIMATION")
            var privateFlagsValue: Int = privateFlags.getInt(windowLayoutParams)
            val noAnimFlag: Int = noAnim.getInt(windowLayoutParams)
            privateFlagsValue = privateFlagsValue and noAnimFlag.inv()
            privateFlags.setInt(windowLayoutParams, privateFlagsValue)
        } catch (e: Exception) {
            Log.w(TAG, "enable_window_animation_failed", e)
        }
    }

    private fun onFreeFormRotationChanged() {
        val tempHeight = max(freeformScreenHeight, freeformScreenWidth)
        val tempWidth = min(freeformScreenHeight, freeformScreenWidth)

        maxFreeformScreenSize = tempHeight

        if (virtualDisplayRotation == VIRTUAL_DISPLAY_ROTATION_PORTRAIT) {
            freeformScreenHeight = tempHeight
            freeformScreenWidth = tempWidth
        } else {
            freeformScreenHeight = tempWidth
            freeformScreenWidth = tempHeight

            //Optimization: when rotating inside the freeform window, adjust if the rotated size exceeds the screen q220904.3
            //freeform landscape - physical screen portrait
            if (FreeformHelper.screenIsPortrait(screenRotation)) {
                if (freeformHeight > realScreenWidth) {
                    freeformHeight = realScreenWidth
                    freeformWidth = (freeformHeight * config.widthHeightRatio).roundToInt()
                    scale = freeformHeight / maxFreeformScreenSize.toFloat()
                }
            }
        }

        resizeVirtualDisplay()
        updateWindowSize(false)
    }

    private fun onScreenOrientationChanged() {
        if (screenRotation == Surface.ROTATION_0 || screenRotation == Surface.ROTATION_180) {
            realScreenHeight = max(context.resources.displayMetrics.widthPixels, context.resources.displayMetrics.heightPixels)
            realScreenWidth = min(context.resources.displayMetrics.widthPixels, context.resources.displayMetrics.heightPixels)
        } else {
            realScreenWidth = max(context.resources.displayMetrics.widthPixels, context.resources.displayMetrics.heightPixels)
            realScreenHeight = min(context.resources.displayMetrics.widthPixels, context.resources.displayMetrics.heightPixels)
        }

        config.maxHeight = FreeformHelper.getDefaultHeight(context, defaultDisplay, config.widthHeightRatio)
        maxFreeformScreenSize = config.maxHeight
        freeformHeight = if (FreeformHelper.screenIsPortrait(defaultDisplay.rotation)) (config.maxHeight * 0.75).roundToInt() else (config.maxHeight * 0.8).roundToInt()
        freeformWidth = (freeformHeight * config.widthHeightRatio).roundToInt()

        if (config.changeDpi && config.packageName != QQ) {
            config.freeformDpi = FreeformHelper.getScreenDpi(context) +
                    if (screenRotation == Surface.ROTATION_0 || screenRotation == Surface.ROTATION_180) viewModel.getIntSp("freeform_dpi_portrait", 0)
                    else viewModel.getIntSp("freeform_dpi_landscape", 0)
            resizeVirtualDisplay()
        }

        windowLayoutParams.apply {
            x = 0
            y = 0
        }

        updateWindowSize(true)
    }

    private fun resizeVirtualDisplay() {
        virtualDisplay.resize(
            freeformScreenWidth,
            freeformScreenHeight,
            config.freeformDpi
        )
    }

    private fun updateWindowSize(orientationChanged: Boolean) {
        scale = freeformHeight / maxFreeformScreenSize.toFloat()
        if (virtualDisplayRotation == VIRTUAL_DISPLAY_ROTATION_PORTRAIT) {
            windowLayoutParams.apply {
                width = freeformWidth + windowChromeWidth
                height = freeformHeight + windowChromeHeight
            }
        } else {
            windowLayoutParams.apply {
                width = freeformHeight + windowChromeWidth
                height = freeformWidth + windowChromeHeight
            }
        }

        if (orientationChanged) {
            //In landscape, move to the left of the screen to show the freeform window
            if (screenRotation == Surface.ROTATION_90 || screenRotation == Surface.ROTATION_270) {
                windowLayoutParams.apply {
                    x = (width - realScreenWidth) / 2
                }
            }
        }

        //Only refresh when not in the background-suspended state
        if (!isBackstage) {
            windowManager.updateViewLayout(
                binding.root,
                windowLayoutParams
            )
        }
    }

    override fun toScreenCenter() {

    }

    override fun moveToFirst() {
        if (windowAttached) {
            try {
                windowManager.removeViewImmediate(binding.root)
            } catch (e: Exception) {
                Log.w(TAG, "guide_remove_view_failed(toFront) pkg=${config.packageName}", e)
            }
            windowAttached = false
        }
        try {
            windowManager.addView(binding.root, windowLayoutParams)
            windowAttached = true
        } catch (e: Exception) {
            Log.w(TAG, "guide_add_view_failed(toFront) pkg=${config.packageName}", e)
        }
        FreeformHelper.addFreeformToSet(this)
    }

    /**
     * Hide the freeform window into the background
     */
    private fun toBackstage() {
        if (windowAttached) {
            try {
                windowManager.removeViewImmediate(binding.root)
            } catch (e: Exception) {
                Log.w(TAG, "guide_remove_view_failed(toBackstage) pkg=${config.packageName}", e)
            }
            windowAttached = false
        }
        FreeformHelper.removeFreeformFromSet(this)
        FreeformHelper.addMiniFreeformToSet(this)
        isBackstage = true
    }

    /**
     * Restore from the background
     */
    override fun fromBackstage() {
        try {
            windowManager.addView(binding.root, windowLayoutParams)
            windowAttached = true
        } catch (e: Exception) {
            Log.w(TAG, "guide_add_view_failed(fromBackstage) pkg=${config.packageName}", e)
        }
        FreeformHelper.removeMiniFreeformFromSet(this)
        FreeformHelper.addFreeformToSet(this)
        isBackstage = false
    }

    /**
     * Try to make the freeform window's content fullscreen
     */
    private fun moveTaskToDefaultDisplay() {
        isDestroy = true
//        if (activityTaskManager != null) {
//            activityTaskManager!!.moveRootTaskToDisplay(taskId, 0)
//        } else {
//            MiFreeform.miFreeformViewModel.getControlService()?.moveStack(virtualDisplay.display.displayId)
//        }
        if (MiFreeform.me?.getControlService()?.moveStack(virtualDisplay.display.displayId) != true) {
            MiFreeform.me?.getControlService()?.execShell("am start -n ${config.packageName}/${config.activityName} --user ${config.userId} --display 0", false)
        }
        destroy()
    }

    /**
     * Notify windowManager to refresh the scaled UI
     */
    private fun notifyWindowScale() {
        binding.root.scaleX = 1f
        binding.root.scaleY = 1f
        scale = freeformHeight / maxFreeformScreenSize.toFloat()
        updateWindowSize(false)
    }

    //Coordinates at press time
    private var lastX = -1f
    private var lastY = -1f
    //The UI element id currently being operated
    private var touchId = -1
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(v: View, event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                if (FreeformHelper.isShowingFirst(this)) {
                    handleDownEvent(v, event)
                } else {
                    //Not on top, so move it to the top
                    moveToFirst()
                }
            }
            MotionEvent.ACTION_MOVE -> {
                handleMoveEvent(v, event)
            }
            MotionEvent.ACTION_UP -> {
                handleUpEvent(v, event)
            }
        }
        return true
    }

    private fun handleDownEvent(v: View, event: MotionEvent) {
        if (touchId == -1) touchId = v.id

        lastX = event.rawX
        lastY = event.rawY
        when(v.id) {
            R.id.textureView -> {
                if (touchId == R.id.textureView) handleTouch(event)
            }
            //Left button listener: single tap closes, long press goes fullscreen
            R.id.leftView -> {
                if (touchId == R.id.leftView) leftGestureDetector.onTouchEvent(event)
            }
            R.id.middleView -> {
                middleGestureDetector.onTouchEvent(event)
            }
            //Right button listener: tap goes back, long press suspends to background
            R.id.rightView -> {
                if (touchId == R.id.rightView) rightGestureDetector.onTouchEvent(event)
            }

            R.id.leftScale, R.id.rightScale -> {
                beforeScaleHeight = freeformHeight
            }
        }
    }

    private fun handleMoveEvent(v: View, event: MotionEvent) {
        when(v.id) {
            R.id.textureView -> {
                if (touchId == R.id.textureView) handleTouch(event)
            }
            //Left button listener: single tap closes, double tap goes fullscreen
            R.id.leftView -> {
                if (touchId == R.id.leftView) leftGestureDetector.onTouchEvent(event)
            }
            R.id.rightView -> {
                if (touchId == R.id.rightView) rightGestureDetector.onTouchEvent(event)
            }
            R.id.middleView -> {
                if (touchId == R.id.middleView) {
                    val dx = event.rawX - lastX
                    val dy = event.rawY - lastY

                    handleMove(dx, dy)
                    lastX = event.rawX
                    lastY = event.rawY

                    middleGestureDetector.onTouchEvent(event)
                }
            }
            R.id.leftScale -> {
                if (touchId == R.id.leftScale && canScale) {
                    val dx = event.rawX - lastX
                    val dy = event.rawY - lastY
                    handleScaleLeft(dx, dy)
                    lastX = event.rawX
                    lastY = event.rawY
                }
            }
            R.id.rightScale -> {
                if (touchId == R.id.rightScale && canScale) {
                    val dx = event.rawX - lastX
                    val dy = event.rawY - lastY
                    handleScaleRight(dx, dy)
                    lastX = event.rawX
                    lastY = event.rawY
                }
            }
        }
    }

    private fun handleUpEvent(v: View, event: MotionEvent) {
        when (v.id) {
            R.id.textureView -> {
                if (touchId == R.id.textureView) handleTouch(event)
            }
            R.id.middleView -> {
                middleGestureDetector.onTouchEvent(event)
            }
            //Left button listener: single tap closes, double tap goes fullscreen
            R.id.leftView -> {
                if (touchId == R.id.leftView) leftGestureDetector.onTouchEvent(event)
            }
            //Right button goes back
            R.id.rightView -> {
                if (touchId == R.id.rightView) rightGestureDetector.onTouchEvent(event)
            }
            R.id.leftScale, R.id.rightScale -> {
                notifyWindowScale()
            }
        }
        touchId = -1
    }

    //The ratio of this pass relative to before scaling
    private var mScale = 1f
    private var beforeScaleHeight = freeformHeight

    /**
     * Scale handling
     * Optimization: try a smoother scaling animation q220904.8
     */
    private fun handleScaleLeft(dx: Float, dy: Float) {
        //Shrink
        if (dx > 0 && dy < 0) {
            val tempHeight = freeformHeight + dy
            val tempWidth = freeformWidth - dx
            //Within size bounds
            if (tempHeight > MIN_HEIGHT && tempHeight <= maxFreeformScreenSize && tempWidth > MIN_WIDTH && tempWidth < realScreenWidth) {
                //Optimization: try a more finger-following scale operation q220904.6
                val maxMove = max(abs(dx), abs(dy)).roundToInt()
                freeformHeight -= maxMove
                freeformWidth = (freeformHeight * config.widthHeightRatio).roundToInt()

                if (freeformHeight > beforeScaleHeight) {
                    beforeScaleHeight = freeformHeight
                    notifyWindowScale()
                } else {
                    mScale = freeformHeight / beforeScaleHeight.toFloat()
                    binding.root.scaleX = mScale
                    binding.root.scaleY = mScale
                }
            }
        }
        //Enlarge
        else if (dx < 0 && dy > 0) {
            val tempHeight = freeformHeight + dy
            val tempWidth = freeformWidth - dx
            //Within size bounds
            if (tempHeight > MIN_HEIGHT && tempHeight <= maxFreeformScreenSize && tempWidth > MIN_WIDTH && tempWidth < realScreenWidth) {
                val maxMove = max(abs(dx), abs(dy)).roundToInt()
                freeformHeight += maxMove
                freeformWidth = (freeformHeight * config.widthHeightRatio).roundToInt()

                if (freeformHeight > beforeScaleHeight) {
                    beforeScaleHeight = freeformHeight
                    notifyWindowScale()
                } else {
                    mScale = freeformHeight / beforeScaleHeight.toFloat()
                    binding.root.scaleX = mScale
                    binding.root.scaleY = mScale
                }
            }
        }
        callback.onScaleClick()
    }

    /**
     * Scale handling
     */
    private fun handleScaleRight(dx: Float, dy: Float) {
        //Shrink
        if (dx < 0 && dy < 0) {
            val tempHeight = freeformHeight + dy
            val tempWidth = freeformWidth + dx
            //Within size bounds
            if (tempHeight > MIN_HEIGHT && tempHeight <= maxFreeformScreenSize && tempWidth > MIN_WIDTH && tempWidth < realScreenWidth) {
                val maxMove = max(abs(dx), abs(dy)).roundToInt()
                freeformHeight -= maxMove
                freeformWidth = (freeformHeight * config.widthHeightRatio).roundToInt()

                if (freeformHeight > beforeScaleHeight) {
                    beforeScaleHeight = freeformHeight
                    notifyWindowScale()
                } else {
                    mScale = freeformHeight / beforeScaleHeight.toFloat()
                    binding.root.scaleX = mScale
                    binding.root.scaleY = mScale
                }
            }
        }
        //Enlarge
        else if (dx > 0 && dy > 0) {
            val tempHeight = freeformHeight + dy
            val tempWidth = freeformWidth + dx
            //Within size bounds
            if (tempHeight > MIN_HEIGHT && tempHeight <= maxFreeformScreenSize && tempWidth > MIN_WIDTH && tempWidth < realScreenWidth) {
                val maxMove = max(abs(dx), abs(dy)).roundToInt()
                freeformHeight += maxMove
                freeformWidth = (freeformHeight * config.widthHeightRatio).roundToInt()

                if (freeformHeight > beforeScaleHeight) {
                    beforeScaleHeight = freeformHeight
                    notifyWindowScale()
                } else {
                    mScale = freeformHeight / beforeScaleHeight.toFloat()
                    binding.root.scaleX = mScale
                    binding.root.scaleY = mScale
                }
            }
        }
        callback.onScaleClick()
    }

    /**
     * Move-position handling
     */
    private fun handleMove(dx: Float, dy: Float) {
        windowManager.updateViewLayout(binding.root, windowLayoutParams.apply {
            x = (x + dx).roundToInt()
            y = ((y + dy).roundToInt())
        })
    }

    private var setDisplayIdMethod: Method? = null

    /**
     * Maps the touch point from textureView's view coordinates to the virtual display's buffer coordinates.
     * The teaching window can also double-tap to rotate, after which nominal size and actual content differ even more,
     * so like FreeformView we map by the content View's measured size (see [FreeformTouchMapper] for why).
     */
    private fun mapTouchToBuffer(dst: MotionEvent.PointerCoords) {
        val view = binding.textureView
        val bufferX = FreeformTouchMapper.mapX(dst.x, view.width, freeformScreenWidth, scale)
        val bufferY = FreeformTouchMapper.mapY(dst.y, view.height, freeformScreenHeight, scale)
        dst.x = bufferX
        dst.y = bufferY
    }

    /**
     * Touch handling
     */
    private fun handleTouch(event: MotionEvent) {
        if (inputManager != null) {
            val pointerCoords: Array<MotionEvent.PointerCoords?> = arrayOfNulls(event.pointerCount)
            val pointerProperties: Array<MotionEvent.PointerProperties?> = arrayOfNulls(event.pointerCount)
            for (i in 0 until event.pointerCount) {
                val oldCoords = MotionEvent.PointerCoords()
                val pointerProperty = MotionEvent.PointerProperties()
                event.getPointerCoords(i, oldCoords)
                event.getPointerProperties(i, pointerProperty)
                pointerCoords[i] = oldCoords
                mapTouchToBuffer(oldCoords)
                pointerProperties[i] = pointerProperty
            }

            val newEvent = MotionEvent.obtain(
                event.downTime,
                event.eventTime,
                event.action,
                event.pointerCount,
                pointerProperties,
                pointerCoords,
                event.metaState,
                event.buttonState,
                event.xPrecision,
                event.yPrecision,
                event.deviceId,
                event.edgeFlags,
                event.source,
                event.flags
            )

            //Android Q and above systems
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setDisplayIdMethod?.invoke(newEvent, virtualDisplay.display.displayId)

                inputManager!!.injectInputEvent(newEvent, 0)
            } else {
                inputManager!!.injectInputEvent(newEvent, virtualDisplay.display.displayId)
            }
            newEvent.recycle()
        } else {
            val count = event.pointerCount
            val xArray = FloatArray(count)
            val yArray = FloatArray(count)

            for (i in 0 until count) {
                val coords = MotionEvent.PointerCoords()
                event.getPointerCoords(i, coords)
                val view = binding.textureView
                xArray[i] = FreeformTouchMapper.mapX(coords.x, view.width, freeformScreenWidth, scale)
                yArray[i] = FreeformTouchMapper.mapY(coords.y, view.height, freeformScreenHeight, scale)
            }
            MiFreeform.me?.getControlService()?.touch(MotionEventBean(event.action, xArray, yArray, virtualDisplay.display.displayId))
        }
    }


    /** The externally callable destroy entry point, idempotent. */
    override fun destroyFreeform() {
        destroy()
    }

    fun destroy() {
        // destroy has multiple entry points (user taps close, task removed, task jumps to fullscreen),
        // and re-entering would release the virtual display twice.
        //
        // Note we can't use isDestroy for idempotence here: moveTaskToDefaultDisplay() sets it first
        // (to stop the Sui hook from pulling the task back into the freeform window), before destroy() has run;
        // guarding with it would skip destroy() entirely in fullscreen, leaving the freeform window on screen and
        // the virtual display stuck at fullscreen size, so next time the app would render a size smaller than the window.
        if (isDestroyed) return

        isDestroy = true
        isDestroyed = true
        // Only detach a genuinely attached window: when not attached this line always throws, and a throw mustn't be treated as a real fault
        if (windowAttached) {
            windowAttached = false
            try {
                windowManager.removeViewImmediate(binding.root)
            }catch (e: Exception) {
                Log.e(TAG, "guide_remove_view_failed - zombie window left pkg=${config.packageName}", e)
                DiagnosticsLog.recordRes(
                    context, "zombie_window",
                    R.string.diag_study_zombie, config.packageName, e.javaClass.simpleName
                )
            }
        }

        try {
            virtualDisplay.surface.release()
            virtualDisplay.release()
        } catch (e: Exception) {
            //An unreleased virtual display is a leak source that keeps holding memory and GPU
            Log.e(TAG, "guide_release_virtual_display_failed pkg=${config.packageName}", e)
            DiagnosticsLog.recordRes(
                context, "display_leak",
                R.string.diag_study_display_leak, config.packageName, e.javaClass.simpleName
            )
        }

        try {
            //Remove the screen listener
            displayManager.unregisterDisplayListener(displayListener)
        } catch (e: Exception) {
            Log.w(TAG, "guide_unregister_display_listener_failed pkg=${config.packageName}", e)
        }


        try {
            screenListener.unregisterListener()
        } catch (e: Exception) {
            Log.w(TAG, "guide_unregister_screen_listener_failed pkg=${config.packageName}", e)
        }

        //Remove freeform-window management
        //Both sets must be cleared, otherwise the sidebar keeps an untappable ghost entry.
        FreeformHelper.removeFreeformFromSet(this)
        FreeformHelper.removeMiniFreeformFromSet(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                activityTaskManager?.unregisterTaskStackListener(taskStackListener)
            } catch (e: Exception) {
                Log.w(TAG, "guide_unregister_task_stack_listener_failed pkg=${config.packageName}", e)
            }
        }
    }

    init {
        if (MiFreeform.me?.getControlService()?.asBinder()?.pingBinder() == true) {
            //Try to restore the freeform-window state
            if (FreeformHelper.isAppInFreeform(config.packageName, config.userId)) {
                FreeformHelper.getFreeformStackSet().getByPackageName(config.packageName, config.userId)?.moveToFirst()
            } else if (FreeformHelper.isAppInMiniFreeform(config.packageName, config.userId)) {
                FreeformHelper.getMiniFreeformStackSet().getByPackageName(config.packageName, config.userId)?.fromBackstage()
            } else {
                initSystemService()
                initConfig()

                //YouTube-specific adaptation
                if (config.packageName == YOUTUBE) {
                    config.activityName = YOUTUBE_ACTIVITY
                }
                if (config.packageName == QQ) {
                    config.freeformDpi = FreeformHelper.getScreenDpi(context)
                }
                initView()
            }
        }
        else {
            MiFreeform.me?.initShizuku()
            Toast.makeText(context, context.getString(R.string.service_not_running), Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val TAG = "FreeformView"

        // Keep consistent with FreeformView: allow proportional scaling down to a very small size; don't make it too large when corner-suspended.
        private const val MIN_WIDTH = 200
        private const val MIN_HEIGHT = 300

        private const val QQ = "com.tencent.mobileqq"
        private const val YOUTUBE = "com.google.android.youtube"
        private const val YOUTUBE_ACTIVITY = "com.google.android.youtube.HomeActivity"

        const val REMEMBER_X = "freeform_remember_x"
        const val REMEMBER_Y = "freeform_remember_y"
        const val REMEMBER_LAND_X = "freeform_remember_land_x"
        const val REMEMBER_LAND_Y = "freeform_remember_land_y"
        const val REMEMBER_HEIGHT = "freeform_remember_height"
        const val REMEMBER_LAND_HEIGHT = "freeform_remember_land_height"

        private const val VIRTUAL_DISPLAY_ROTATION_PORTRAIT = 1
        private const val VIRTUAL_DISPLAY_ROTATION_LANDSCAPE = 0
    }

    /**
     * Practice result feedback
     */
    interface Callback {
        fun onMoveClick()
        fun onCloseClick()
        fun onBackClick()
        fun onToBackStageClick()
        fun onToFullClick()
        fun onScaleClick()
        fun onSuccess()
    }
}