package com.moonlight.freeform.ui.freeform

import android.annotation.SuppressLint
import android.app.*
import android.content.ComponentName
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.hardware.input.IInputManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.*
import android.widget.FrameLayout
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.cardview.widget.CardView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.marginBottom
import androidx.core.view.marginTop
import com.moonlight.freeform.R
import com.moonlight.freeform.app.MiFreeform
import com.moonlight.freeform.databinding.ViewFreeformBinding
import com.moonlight.freeform.service.KeepAliveService
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

class FreeformView(
    override val config: FreeformConfig,
    private val context: Context,
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
     * This is not the same as [isDestroy]: [isDestroy] gets set early inside [moveTaskToDefaultDisplay]
     * (to stop the Sui hook from pulling the task back into the freeform window), before destroy() has run;
     * if idempotence also used [isDestroy], then in fullscreen destroy() would be skipped outright,
     * leaving the freeform window on screen and the virtual display stuck at fullscreen size, so next time the app would render a size smaller than the window.
     */
    private var isDestroyed = false

    /**
     * Whether the window is really attached to the WindowManager right now.
     *
     * destroy() uses it to decide whether to removeView. Without this flag we'd raise a false alarm:
     * after a failed window open (both window-type addViews failed) we still run destroy(),
     * and there removeViewImmediate always throws "View not attached", so we'd log a
     * "zombie window holding memory" when there's nothing on screen at all. Measured: it misfired once already.
     */
    private var windowAttached = false

    /**
     * The first check after opening the window that confirms "the system really drew this window".
     *
     * Why it's needed: when the window is hidden by the system, everything looks normal locally—addView doesn't error,
     * TextureView still gets its texture, the virtual display still runs—only there's nothing on screen.
     * Measured: such a window has mForceHideNonSystemOverlayWindow=true in WMS.
     */
    private val surfaceWatchdog = Runnable { probeWindowDisplayed(first = true) }

    /**
     * The second confirmation check.
     *
     * We can't tear down the window the moment we see "hidden": measured, there's a kind of hiding that lasts only a few seconds
     * and fixes itself the next second, and tearing down then would remove the freeform window the user is using. What we really must handle is the kind that never recovers until the process restarts.
     */
    private val surfaceWatchdogConfirm = Runnable { probeWindowDisplayed(first = false) }

    /** The destroy entry point callable externally (when re-launching the same app), idempotent. */
    override fun destroyFreeform() {
        destroy()
    }

    //Whether in the hidden state; when MiFreeform's "running freeform windows" screen is opened, all freeform windows should be hidden
    private var isHidden = false

    //Whether in the background-suspended state
    private var isBackstage = false

    /** The floating ball after collapse; non-null only in floating-ball mode. */
    private var miniBall: MiniBallView? = null

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

    //Whether the user manually rotated the freeform window's orientation. When true, don't let the app-reported requestedOrientation reset the window,
    //and keep the user-set orientation during collapse/restore (fromBackstage doesn't reset this flag).
    private var manualRotationOverride = false

    private val iRotationWatcher = object : IRotationWatcher.Stub() {
        override fun onRotationChanged(rotation: Int) {
            if (rotation != screenRotation) {
                screenRotation = rotation
                scope.launch(Dispatchers.Main) {
                    onScreenOrientationChanged()
                }
            }
        }
    }

//    //Screen listener
//    private val displayListener = object : DisplayManager.DisplayListener {
//        override fun onDisplayAdded(displayId: Int) {}
//
//        override fun onDisplayRemoved(displayId: Int) {}
//
//        override fun onDisplayChanged(displayId: Int) {
//            if (displayId == Display.DEFAULT_DISPLAY) {
//                val tempScreenRotation = defaultDisplay.rotation
//                if (tempScreenRotation != screenRotation) {
//                    screenRotation = tempScreenRotation
//                    onScreenOrientationChanged()
//                }
//            }
//        }
//    }

    private val screenListener = ScreenListener(context)

    //Touch listener
    private val touchListener = TouchListener()
    private val touchListenerPreQ = TouchListenerPreQ()

    //Screen width/height, size not guaranteed
    private var realScreenWidth = 0
    private var realScreenHeight = 0

    //The freeform window's "size"; this size only changes when the orientation inside the freeform window changes
    private var freeformScreenHeight = 0
    private var freeformScreenWidth = 0

    //The freeform window UI's width/height; this size doesn't change with screen or window orientation, i.e. h>w always holds. It only changes when the physical screen orientation changes
    private var freeformHeight = 0
    private var freeformWidth = 0

    //The user-set long edge of the freeform window for the "portrait form" and the "landscape form" separately.
    //Why two copies:
    //1) when turning landscape, if the window is wider than the screen, onFreeFormRotationChanged / clampFreeformToScreen
    //   clamp freeformHeight down—that's only a display-layer limit to avoid overflow, not the user changing the size;
    //2) the size tuned in landscape shouldn't be derived from the portrait one, otherwise "tune in landscape → rotate back to portrait → rotate to landscape"
    //   recomputes the size again, and the user sees the size change on every rotation.
    //The portrait copy has a value from launch (the launch size); the landscape copy being 0 means "the user hasn't tuned it in landscape yet",
    //in which case landscape reuses the portrait size, ensuring "if untouched, it stays".
    //These two memories belong to "the freeform window of the currently open app": they're instance fields, one per app without interference,
    //and when the freeform window closes (destroy, object reclaimed) the memory vanishes too—it won't carry to the next app nor persist to the next launch.
    private var userPortraitHeight = 0
    private var userLandscapeHeight = 0

    //max(freeformScreenHeight,freeformScreenWidth), used to compute scale
    private var maxFreeformScreenSize = 0
    private var minFreeformScreenSize = 0

    //Scale ratio
    private var scaleX: Float = 1f
    private var scaleY: Float = 1f

    // Top-bar three-segment gestures: what each does is decided by settings (TopBarAction),
    // no longer hardcoded. The three detectors only translate a single tap into the corresponding slot's action.
    private val topMinimizeGestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            handleTopBarSlot(2)
            return true
        }
    })

    private val topFullScreenGestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            handleTopBarSlot(1)
            return true
        }
    })

    private val topCloseGestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            handleTopBarSlot(0)
            return true
        }
    })

    /**
     * Refreshes the top bar's three slot icons per settings.
     *
     * The three LinearLayout ids in the top-bar layout are fixed (positions fixed),
     * but what icon each position shows and what action it runs is decided by TopBarAction.
     * The action mapping is in [handleTopBarSlot]; here we only draw the icons to match settings.
     */
    private fun refreshTopBarIcons() {
        applyTopBarIcon(binding.topBar.topBarClose, TopBarAction.getSlotActionFromSp(context.getSharedPreferences(com.moonlight.freeform.app.MiFreeform.APP_SETTINGS_NAME, android.content.Context.MODE_PRIVATE), 0))
        applyTopBarIcon(binding.topBar.topBarFullScreen, TopBarAction.getSlotActionFromSp(context.getSharedPreferences(com.moonlight.freeform.app.MiFreeform.APP_SETTINGS_NAME, android.content.Context.MODE_PRIVATE), 1))
        applyTopBarIcon(binding.topBar.topBarMinimize, TopBarAction.getSlotActionFromSp(context.getSharedPreferences(com.moonlight.freeform.app.MiFreeform.APP_SETTINGS_NAME, android.content.Context.MODE_PRIVATE), 2))
    }

    private fun applyTopBarIcon(container: android.view.ViewGroup, action: TopBarAction) {
        val icon = container.getChildAt(0) as? android.widget.ImageView ?: return
        val res = TopBarAction.iconRes(action)
        if (res != 0) {
            icon.setImageResource(res)
            icon.visibility = android.view.View.VISIBLE
            icon.contentDescription = context.getString(TopBarAction.labelRes(action))
        } else {
            // Choosing "none" hides the icon, but the touch area remains—tapping does nothing.
            icon.visibility = android.view.View.INVISIBLE
        }
    }

    /**
     * Runs the action configured for a top-bar slot.
     *
     * @param slot 0=first, 1=second, 2=third
     */
    private fun handleTopBarSlot(slot: Int) {
        val sp = context.getSharedPreferences(MiFreeform.APP_SETTINGS_NAME, Context.MODE_PRIVATE)
        runFreeformAction(TopBarAction.getSlotActionFromSp(sp, slot))
    }

    /**
     * Runs a freeform-window action.
     *
     * The three top-bar slots and "double-tap the middle bottom-bar button" share this dispatch,
     * ensuring the same action behaves identically across different entries (top-bar tap / bottom-bar double tap).
     */
    private fun runFreeformAction(action: TopBarAction) {
        when (action) {
            TopBarAction.CLOSE -> destroy()
            TopBarAction.FULL_SCREEN -> moveTaskToDefaultDisplay()
            TopBarAction.MINIMIZE -> toBackstage()
            TopBarAction.BACK -> pressBack()
            TopBarAction.SCREENSHOT -> screenshot()
            TopBarAction.ROTATE -> rotateFreeform()
            TopBarAction.CENTER -> toScreenCenter()
            TopBarAction.NONE -> { /* no action */ }
        }
    }

    /** The user-set long edge for the current freeform-window form (landscape/portrait). */
    private fun userHeightForCurrentForm(): Int {
        if (virtualDisplayRotation == VIRTUAL_DISPLAY_ROTATION_LANDSCAPE) {
            //If not tuned separately in landscape, reuse the portrait copy, so "rotate" itself never changes the user-set size
            return if (userLandscapeHeight > 0) userLandscapeHeight else userPortraitHeight
        }
        return userPortraitHeight
    }

    /** Records the long edge the user set for the current freeform-window form. */
    private fun setUserHeightForCurrentForm(value: Int) {
        if (virtualDisplayRotation == VIRTUAL_DISPLAY_ROTATION_LANDSCAPE) {
            userLandscapeHeight = value
        } else {
            userPortraitHeight = value
        }
    }

    /** The default long edge when the user hasn't tuned it (same algorithm as the launch size, varies with physical screen orientation). */
    private fun defaultUserHeight(): Int {
        return if (FreeformHelper.screenIsPortrait(screenRotation)) {
            (config.maxHeight * 0.75).roundToInt()
        } else {
            (config.maxHeight * 0.8).roundToInt()
        }
    }

    /** Go back inside the freeform window. */
    private fun pressBack() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MiFreeform.me?.getControlService()?.pressBack(virtualDisplay.display.displayId)
        } else {
            pressBackPreQ()
        }
    }

    /** Screenshot: save the freeform window's current frame to the system screenshots directory. */
    private fun screenshot() {
        ScreenshotHelper.capture(context, binding.textureView, config.packageName)
    }

    /** Rotates the freeform window's orientation (toggle portrait/landscape). */
    private fun rotateFreeform() {
        virtualDisplayRotation = if (virtualDisplayRotation == VIRTUAL_DISPLAY_ROTATION_PORTRAIT) {
            VIRTUAL_DISPLAY_ROTATION_LANDSCAPE
        } else {
            VIRTUAL_DISPLAY_ROTATION_PORTRAIT
        }
        //Manual user rotation: mark as override, so the app-reported orientation no longer resets the window (also keeps this orientation across collapse/restore)
        manualRotationOverride = true
        onFreeFormRotationChanged()
    }

    //Added: manually adjust the freeform window orientation q220904.7
    /**
     * Bottom-bar left dot: hold and slide to scale proportionally.
     *
     * Behaves exactly like holding the left edge to scale ([handleScaleLeft]),
     * ensuring "press the dot" and "press the edge" have the same scaling feel.
     * The left dot is at the window's bottom-left corner: pull down = enlarge, push up = shrink;
     * the width is always computed from the height by the freeform aspect ratio (the horizontal component doesn't change width separately), so rotation round-trips won't distort.
     */
    private fun handleDotScaleLeft(dx: Float, dy: Float) {
        val tempHeight = freeformHeight + dy
        //Width is always computed from the height by a fixed ratio (same as edge-held scaling), otherwise dragging diagonally
        //silently skews the aspect ratio, and recomputing width by that ratio during rotation would turn the user's freeform window into a different size.
        val tempWidth = (tempHeight * config.widthHeightRatio).roundToInt()
        if (tempHeight > MIN_HEIGHT && tempHeight <= config.maxHeight && tempWidth > MIN_WIDTH && tempWidth < realScreenWidth) {
            freeformHeight = tempHeight.roundToInt()
            freeformWidth = tempWidth
            notifyWindowScale()
        }
    }

    /**
     * Bottom-bar right dot: hold and slide to scale proportionally.
     *
     * Behaves exactly like holding the right edge to scale ([handleScaleRight]).
     * The right dot is at the window's bottom-right corner: pull down = enlarge, push up = shrink; width is likewise computed from the height by ratio.
     */
    private fun handleDotScaleRight(dx: Float, dy: Float) {
        val tempHeight = freeformHeight + dy
        //Same as handleDotScaleLeft: width is computed from the height, keeping the aspect ratio
        val tempWidth = (tempHeight * config.widthHeightRatio).roundToInt()
        if (tempHeight > MIN_HEIGHT && tempHeight <= config.maxHeight && tempWidth > MIN_WIDTH && tempWidth < realScreenWidth) {
            freeformHeight = tempHeight.roundToInt()
            freeformWidth = tempWidth
            notifyWindowScale()
        }
    }

    private val middleGestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            //Double-tap the middle bottom-bar button: run the action configured in settings (default rotate), sharing the same dispatch as top-bar slots
            runFreeformAction(config.bottomDoubleTapAction)
            return false
        }
    })

    @RequiresApi(Build.VERSION_CODES.Q)
    private val taskStackListener = MTaskStackListener()

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
            // Without activity_task there's no taskStackListener, so the freeform window won't follow task moves/rotations,
            // but the UI just looks "a bit off" and it can't be traced without a log.
            Log.w(TAG, "init_activity_task_service_failed pkg=${config.packageName}", e)
        }
        try {
            inputManager = IInputManager.Stub.asInterface(ShizukuBinderWrapper(SystemServiceHelper.getSystemService("input")))
        }catch (e: Exception) {
            // When the input service is unavailable, touch injection fails silently (safe calls here, so no crash); we must record it.
            Log.w(TAG, "init_input_service_failed pkg=${config.packageName}", e)
        }
        try {
            iWindowManager = IWindowManager.Stub.asInterface(ShizukuBinderWrapper(SystemServiceHelper.getSystemService("window")))
        } catch (e: Exception) {
            // Without the window service we can't register the rotation watcher, so the freeform window won't follow screen rotation.
            Log.w(TAG, "init_window_service_failed pkg=${config.packageName}", e)
        }
    }

    private fun initConfig() {
        config.maxHeight = FreeformHelper.getDefaultHeight(context, defaultDisplay, config.widthHeightRatio)
        //config.widthHeightRatio = ...

        realScreenWidth = context.resources.displayMetrics.widthPixels
        realScreenHeight = context.resources.displayMetrics.heightPixels

        config.freeformDpi = FreeformHelper.getScreenDpi(context)
        //On launch: the portrait copy's baseline is the launch size, the landscape copy stays empty (= reuse portrait) until the user scales by hand.
        //After closing the freeform window the object is destroyed and both memories vanish together.
        freeformHeight = defaultUserHeight()
        freeformWidth = (freeformHeight * config.widthHeightRatio).roundToInt()
        userPortraitHeight = freeformHeight
        userLandscapeHeight = 0

        //Optimization: QQ and WeChat now support scaling too q220917.1
        freeformScreenHeight = FreeformHelper.getDefaultHeight(context, defaultDisplay, config.widthHeightRatio)
        if (!config.useCustomConfig) {
            freeformScreenHeight -=
                if (FreeformHelper.screenIsPortrait(screenRotation)) viewModel.getIntSp("freeform_scale_portrait", 0)
                else viewModel.getIntSp("freeform_scale_landscape", 0)

            freeformScreenHeight = max(1, freeformScreenHeight)
        }
        freeformScreenWidth = (freeformScreenHeight * config.widthHeightRatio).roundToInt()
        maxFreeformScreenSize = freeformScreenHeight
        minFreeformScreenSize = freeformScreenWidth

        if (config.useCustomConfig) return
        //---------------custom configuration-----------------

        config.rememberPosition = viewModel.getBooleanSp("remember_freeform_position", false)
        if (config.rememberPosition) {
            config.rememberX = if (FreeformHelper.screenIsPortrait(screenRotation)) {
                viewModel.getIntSp(REMEMBER_X, 0)
            } else {
                viewModel.getIntSp(REMEMBER_LAND_X, 0)
            }
            config.rememberY = if (FreeformHelper.screenIsPortrait(screenRotation)) {
                viewModel.getIntSp(REMEMBER_Y, 0)
            } else {
                viewModel.getIntSp(REMEMBER_LAND_Y, 0)
            }
            var rememberHeight = if (FreeformHelper.screenIsPortrait(screenRotation)) {
                viewModel.getIntSp(REMEMBER_HEIGHT, freeformHeight)
            } else {
                viewModel.getIntSp(REMEMBER_LAND_HEIGHT, freeformHeight)
            }
            //Fix: try to fix the abnormal recorded freeform-window size q220904.1
            rememberHeight = min(max(MIN_HEIGHT, rememberHeight), config.maxHeight)
            freeformHeight = rememberHeight
            freeformWidth = (freeformHeight * config.widthHeightRatio).roundToInt()
            //The remembered position stores the user-set value (see the persist point in destroy()).
            //What's stored here is one value for the physical screen orientation, i.e. the long edge launch should use, so we only seed the portrait copy;
            //the landscape copy stays empty, recorded only after the user tunes it by hand in landscape, and reuses the portrait size until then.
            userPortraitHeight = rememberHeight
            userLandscapeHeight = 0
            scaleX = freeformHeight / maxFreeformScreenSize.toFloat()
        }

        //Fix: touch misalignment after enabling the remember-position option q220925.3
        scaleX = freeformHeight / maxFreeformScreenSize.toFloat()
        scaleY = scaleX

        config.useSuiRefuseToFullScreen = viewModel.getBooleanSp("use_sui_refuse_to_fullscreen", false)
        config.changeDpi = viewModel.getBooleanSp("change_dpi", false)
        config.bottomDoubleTapAction = TopBarAction.getBottomDoubleTapAction(
            context.getSharedPreferences(MiFreeform.APP_SETTINGS_NAME, Context.MODE_PRIVATE)
        )
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initView() {
        binding = ViewFreeformBinding.bind(LayoutInflater.from(context).inflate(R.layout.view_freeform, null, false))

        binding.bottomBar.leftView.setOnTouchListener(this)
        binding.bottomBar.middleView.setOnTouchListener(this)
        binding.bottomBar.rightView.setOnTouchListener(this)

        // Top bar: same pattern as the bottom bar (three segments + GestureDetector).
        // What each action does is decided by settings, and the icons refresh to match.
        binding.topBar.topBarMinimize.setOnTouchListener(this)
        binding.topBar.topBarFullScreen.setOnTouchListener(this)
        binding.topBar.topBarClose.setOnTouchListener(this)
        refreshTopBarIcons()
        binding.leftScale.setOnTouchListener(this)
        binding.rightScale.setOnTouchListener(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            binding.textureView.setOnTouchListener(touchListener)
        } else {
            binding.textureView.setOnTouchListener(touchListenerPreQ)
        }

        // The freeform content is fully opaque from the start: the launch loading is removed.
        // Previously this set alpha to 0 and relied on first-frame counting to fade to 1; after an app collapses and restores without redrawing,
        // those frames never arrive and the freeform window stays transparent, which looks like a white screen.
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
        } catch (e: Exception) {
            Toast.makeText(context, context.getString(R.string.create_display_fail), Toast.LENGTH_SHORT).show()
            DiagnosticsLog.recordRes(
                context, "create_display",
                R.string.diag_create_display_failed,
                config.packageName, freeformScreenWidth, freeformScreenHeight, config.freeformDpi, e.javaClass.simpleName
            )
            return
        }

        screenListener.begin(object : ScreenListener.ScreenStateListener {
            override fun onScreenOn() {}

            //Screen off hides the freeform window
            override fun onScreenOff() {
                //Suspended state needs no update
                //Fix: MiFreeform crashed when locking the screen with a running program q220902.1
                //Optimization: freeform-window state after locking the screen q220917.3
                if (!isBackstage) {
                    binding.root.alpha = 0f
                    windowLayoutParams.flags =
                            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                            WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
                    windowManager.updateViewLayout(binding.root, windowLayoutParams)
                }
            }

            //Unlock restores the freeform window
            override fun onUserPresent() {
                //Suspended state needs no update
                if (!isBackstage) {
                    binding.root.alpha = 1f
                    windowLayoutParams.flags =
                            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                            WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM

                    windowManager.updateViewLayout(binding.root, windowLayoutParams)
                }
            }
        })

        initOrientationChangedListener()
        initTextureViewListener()

        showWindow()
    }

    private fun initOrientationChangedListener() {
        iWindowManager?.watchRotation(iRotationWatcher, Display.DEFAULT_DISPLAY)
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
                        FreeformHelper.addFreeformToSet(this@FreeformView)
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
     * The decoration by which the window size exceeds the content size; shares the same algorithm as the teaching freeform window,
     * see [FreeformWindowGeometry] for exactly which items it includes.
     */
    private val windowChromeWidth: Int
        get() = FreeformWindowGeometry.chromeWidth(context)

    private val windowChromeHeight: Int
        get() = FreeformWindowGeometry.chromeHeight(context)

    private fun showWindow() {
        // First work out which window type this attempt is eligible for, then decide whether to call addView.
        // Measured (HyperOS 14): calling addView without the overlay permission is recorded by the system as
        // a "rejected non-system overlay request", and then **all** non-system overlay windows created by this process afterward
        // get permanently set to mForceHideNonSystemOverlayWindow=true—re-granting doesn't clear it, only a process restart recovers.
        // The old code blindly tried TYPE_ACCESSIBILITY_OVERLAY first regardless, and without the accessibility service enabled here,
        // every open first threw a needless BadTokenException; when unauthorized, that very attempt caused the poisoning above.
        val overlayAllowed = Settings.canDrawOverlays(context)
        val accessibilityOverlayUsable = isAccessibilityServiceEnabled()

        if (!overlayAllowed && !accessibilityOverlayUsable) {
            // Neither type is eligible: don't try even once, just tell the user what's missing.
            Log.w(TAG, "start_rejected no_overlay_permission pkg=${config.packageName}")
            requestOverlayPermissionUi(destroyFirst = true)
            return
        }

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
            // Reaching here means this window type was rejected by the system (accessibility service not actually active, window-count cap, etc.).
            Log.w(TAG, "add_view_failed pkg=${config.packageName} type=${windowLayoutParams.type}", e)
            windowAttached = false
            try {
                windowManager.removeViewImmediate(binding.root)
            } catch (removeException: Exception) {
                // A half-attached window left uncleared becomes a zombie window (has a surface, invisible, unreclaimed).
                Log.w(TAG, "remove_half_attached_view_failed pkg=${config.packageName}", removeException)
            }

            if (overlayAllowed) {
                // Only fall back and retry once when we're sure the overlay permission is held: without permission a second addView
                // is another "rejected request" and would poison all overlays in the process.
                try {
                    windowManager.addView(binding.root, windowLayoutParams.apply {
                        type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    })
                    windowAttached = true
                } catch (fallbackException: Exception) {
                    // Both addViews failed. Previously the exception here wasn't caught, so it propagated
                    // out of FreeformView.<init> to the caller, while the already-built virtual display was never released
                    // —that's the measured "not a single freeform window on screen, yet one virtual display is occupied".
                    Log.e(TAG, "add_view_fallback_failed pkg=${config.packageName}", fallbackException)
                    DiagnosticsLog.recordRes(
                        context, "overlay_rejected",
                        R.string.diag_overlay_rejected_both, config.packageName
                    )
                    destroy()
                }
            } else {
                // Eligible to try the accessibility window type but has no overlay permission, so there's no fallback to try.
                Log.e(TAG, "add_view_rejected_no_fallback pkg=${config.packageName}", e)
                DiagnosticsLog.recordRes(
                    context, "overlay_rejected",
                    R.string.diag_overlay_rejected_no_fallback, config.packageName
                )
                destroy()
            }
        }

        // addView succeeding doesn't mean the freeform window really displayed: the window's visibility is decided by the system
        // at the instant of addView and never changes afterward. So we attach a watchdog to confirm later that the system is actually drawing this window.
        if (windowAttached) {
            binding.root.postDelayed(surfaceWatchdog, SURFACE_WATCHDOG_MS)
        }
    }

    /**
     * Tells the user the overlay permission is missing and tears down everything already built.
     *
     * Previously this also did `startActivity(ACTION_MANAGE_OVERLAY_PERMISSION)` to send the user to grant it,
     * and real-device experiments pinned that very jump as the cause: the real rejection branch poisoned all overlays in the process 4/4,
     * while the jump-removed control was 0/4 (HyperOS 14, see [PermissionUtils.guideToGrantOverlayPermission] for rationale and data).
     * So the notice has no automatic jump anymore; the grant entry became a "grant" button on the ongoing notification (user-initiated).
     */
    private fun requestOverlayPermissionUi(destroyFirst: Boolean) {
        if (destroyFirst) {
            destroy()
        }
        PermissionUtils.guideToGrantOverlayPermission(context, context.getString(R.string.scene_freeform_failed, config.packageName))
    }

    /**
     * Whether this app is enabled by the system as an accessibility service. Only then is TYPE_ACCESSIBILITY_OVERLAY a legitimate window type.
     */
    private fun isAccessibilityServiceEnabled(): Boolean {
        return try {
            val enabled = Settings.Secure.getString(
                context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val mine = ComponentName(context, KeepAliveService::class.java)
            val full = mine.flattenToString()
            val short = mine.flattenToShortString()
            enabled.split(':').any { it.equals(full, true) || it.equals(short, true) }
        } catch (e: Exception) {
            // If we can't read it, treat it as off and fall back to the normal overlay type; but we must leave a trace, otherwise all we see is "switched to another window type". 
            Log.w(TAG, "read_accessibility_state_failed pkg=${config.packageName}", e)
            false
        }
    }

    /**
     * Asks the system once whether any of our own overlay windows is force-hidden.
     *
     * The criterion is process-level (any window of our app matching counts): measured, hiding affects the whole process at once,
     * not a single window; and while hidden a window's local state is entirely normal, so we can't distinguish which one and don't force it.
     *
     * @param first the first probe of hidden doesn't tear down immediately; wait an interval and confirm once more, to avoid mis-tearing a window that was only transiently hidden
     */
    private fun probeWindowDisplayed(first: Boolean) {
        if (isDestroyed || isBackstage) return

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (powerManager?.isInteractive != true) {
            // With the screen off the system isn't drawing anyway; wait for it to wake and check again, don't misreport inside the lock screen
            binding.root.postDelayed(surfaceWatchdog, SURFACE_WATCHDOG_MS)
            return
        }

        val service = MiFreeform.me?.getControlService() ?: return

        // Locally we can't tell "is the system drawing my window": addView doesn't error, TextureView still gets its texture,
        // and windowVisibility is the value we set—measured, all these are normal even for a hidden window.
        // The only reliable criterion is on the WMS side, and we have a system-uid shell channel;
        // execShell only returns an exit code, so we compress the criterion into a boolean: match means exit 0.
        scope.launch(Dispatchers.IO) {
            val hiddenBySystem = try {
                service.execShell(HIDDEN_WINDOW_CHECK_COMMAND, false)
            } catch (e: Exception) {
                Log.w(TAG, "window_visibility_probe_failed pkg=${config.packageName}", e)
                false
            }
            if (!hiddenBySystem) return@launch

            withContext(Dispatchers.Main) {
                if (isDestroyed || isBackstage) return@withContext
                if (first) {
                    Log.w(TAG, "window_not_displayed_suspect pkg=${config.packageName} type=${windowLayoutParams.type}")
                    binding.root.postDelayed(surfaceWatchdogConfirm, SURFACE_CONFIRM_MS)
                    return@withContext
                }

                Log.e(TAG, "window_not_displayed pkg=${config.packageName} type=${windowLayoutParams.type}")

                // It's the same "window exists but the system isn't drawing it", yet there are really two causes with opposite messages to the user:
                // one is the app inside the freeform window itself carrying HIDE_NON_SYSTEM_OVERLAY_WINDOWS (Android 12+ anti-hijack,
                // measured on stock A14: exiting it recovers, restarting doesn't); the other type can't be named, so it uses the old text.
                // So before tearing down we name it once, then decide which entry to report.
                val culprit = probeHideRequester()
                if (culprit.isNotEmpty()) {
                    Log.e(TAG, "window_hidden_by_app_request pkg=${config.packageName} culprit=$culprit")
                    DiagnosticsLog.recordRes(
                        context, "window_hidden",
                        R.string.diag_window_hidden_by_app, culprit
                    )
                    Toast.makeText(
                        context,
                        context.getString(R.string.window_hidden_by_app, friendlyCulprit(culprit)),
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    DiagnosticsLog.recordRes(
                        context, "window_hidden",
                        R.string.diag_window_hidden, config.packageName
                    )
                    Toast.makeText(context, context.getString(R.string.window_hidden_by_system), Toast.LENGTH_LONG).show()
                }
                destroy()
            }
        }
    }

    /**
     * After a force-hide is detected, ask once more "which app requested it".
     *
     * The criterion is another window carrying HIDE_NON_SYSTEM_OVERLAY_WINDOWS: measured on stock A14 the system settings page carries this flag,
     * and even when it runs on a virtual display, it's our main-screen overlays that get hidden—cross-display, globally effective.
     * If nothing is found return an empty string and the caller uses the fallback text; a failure here doesn't affect tearing down the window.
     */
    private fun probeHideRequester(): String {
        val service = MiFreeform.me?.getControlService() ?: return ""
        return try {
            service.execShellText(HIDDEN_WINDOW_CULPRIT_COMMAND, false)
                .lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("、")
        } catch (e: Exception) {
            Log.w(TAG, "window_hidden_culprit_probe_failed pkg=${config.packageName}", e)
            ""
        }
    }

    /**
     * Converts the named package names to app display labels, only for the part shown to the user.
     *
     * Measured on A14 the Toast only has two lines and truncates with ellipsis beyond that, and a package name like com.android.settings
     * alone eats 20 characters—using budget the user can't read; switching to the app's display label ("Settings") saves more than half.
     * Logs and diagnostic records still keep the original package name—those two are for locating issues and mustn't be replaced by display labels.
     * If no display label is available (app uninstalled, no info) fall back to the package name; naming must never be lost due to a translation failure.
     */
    private fun friendlyCulprit(culprit: String): String =
        culprit.split("、").filter { it.isNotBlank() }.joinToString("、") { name ->
            try {
                context.packageManager.getApplicationLabel(
                    context.packageManager.getApplicationInfo(name, 0)
                ).toString()
            } catch (e: Exception) {
                Log.w(TAG, "window_hidden_culprit_label_failed culprit=$name", e)
                name
            }
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
            // PRIVATE_FLAG_NO_MOVE_ANIMATION is a hidden field; reflection failing on higher versions is common,
            // and the only consequence is the drag carrying a transition animation, but we must be able to tell whether this path broke.
            Log.w(TAG, "disable_move_animation_flag_failed", e)
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
            Log.w(TAG, "enable_move_animation_flag_failed", e)
        }
    }

    private fun onFreeFormRotationChanged() {
        //First restore the long edge to the user-set value for "the form after rotating over", then lay out by the new orientation:
        //the clamp made last time to fit landscape into the screen shouldn't be taken as a new size, otherwise turning back loses the height;
        //and the size tuned in landscape shouldn't be recomputed away by the portrait size, so portrait and landscape each remember their own.
        val remembered = userHeightForCurrentForm()
        freeformHeight = if (remembered > 0) remembered else defaultUserHeight()
        freeformWidth = (freeformHeight * config.widthHeightRatio).roundToInt()

        val tempHeight = max(freeformScreenHeight, freeformScreenWidth)
        val tempWidth = min(freeformScreenHeight, freeformScreenWidth)

        maxFreeformScreenSize = tempHeight
        minFreeformScreenSize = tempWidth

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
                    scaleX = freeformHeight / maxFreeformScreenSize.toFloat()
                    scaleY = scaleX
                }
            }
        }

        //Rotation only changes the window's shape; try not to break the image: first switch the texture buffer size to the new orientation, then resize the virtual display,
        //avoiding a black intermediate frame caused by the old buffer size; throughout we don't touch textureView.alpha nor rebuild the Surface.
        if (binding.textureView.isAvailable) {
            binding.textureView.surfaceTexture?.setDefaultBufferSize(freeformScreenWidth, freeformScreenHeight)
        }
        resizeVirtualDisplay()
        updateWindowSize(false)
        //After rotation clamp to the current physical screen, ensuring the freeform window stays fully visible and isn't pushed off-screen
        clampFreeformToScreen()
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

        //This assignment is unnecessary; maxFreeformScreenSize only needs to stay in sync with max(freeformScreenHeight, freeformScreenWidth) q220908.1
        //maxFreeformScreenSize = config.maxHeight

        //A physical screen rotation recomputes config.maxHeight. When the user hasn't tuned the size by hand, follow the new default;
        //once tuned, reuse the "current freeform-window form"'s setting (clamped to the new cap), no longer unconditionally overwriting the user's size.
        val remembered = userHeightForCurrentForm()
        freeformHeight = if (remembered > 0) min(max(MIN_HEIGHT, remembered), config.maxHeight) else defaultUserHeight()
        freeformWidth = (freeformHeight * config.widthHeightRatio).roundToInt()

        if (config.changeDpi) {
            freeformScreenHeight = FreeformHelper.getDefaultHeight(context, defaultDisplay, config.widthHeightRatio)
            freeformScreenHeight -=
                if (FreeformHelper.screenIsPortrait(screenRotation)) viewModel.getIntSp("freeform_scale_portrait", 0)
                else viewModel.getIntSp("freeform_scale_landscape", 0)
            freeformScreenHeight = max(1, freeformScreenHeight)
            freeformScreenWidth = (freeformScreenHeight * config.widthHeightRatio).roundToInt()
            maxFreeformScreenSize = freeformScreenHeight
            minFreeformScreenSize = freeformScreenWidth
            scaleX = freeformHeight / maxFreeformScreenSize.toFloat()
            scaleY = scaleX

            //Fix: incorrect screen dpi after rotation q220908.2
            val tempFreeformScreenHeight = freeformScreenHeight
            val tempFreeformScreenWidth = freeformScreenWidth

            maxFreeformScreenSize = tempFreeformScreenHeight
            minFreeformScreenSize = tempFreeformScreenWidth

            if (virtualDisplayRotation == VIRTUAL_DISPLAY_ROTATION_PORTRAIT) {
                freeformScreenHeight = max(tempFreeformScreenHeight, tempFreeformScreenWidth)
                freeformScreenWidth = min(tempFreeformScreenHeight, tempFreeformScreenWidth)
            } else {
                freeformScreenHeight = min(tempFreeformScreenHeight, tempFreeformScreenWidth)
                freeformScreenWidth = max(tempFreeformScreenHeight, tempFreeformScreenWidth)
            }

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

    /**
     * After rotation, clamp the freeform window into the current physical screen, ensuring it's fully visible and not pushed off-screen.
     *
     * Window width/height is linear in freeformHeight (window size = freeform size + fixed decoration),
     * so we directly solve the freeformHeight cap that doesn't overflow and set it in one pass;
     * the previous per-round shrink by minimum scale ratio was limited by an iteration cap and could leave a 1px overflow.
     * Finally clamp the window position x/y into [0, screen width - window width]/[0, screen height - window height].
     */
    private fun clampFreeformToScreen() {
        val scaleExtra = windowChromeWidth
        val chromeHeight = windowChromeHeight
        val portraitWindow = virtualDisplayRotation == VIRTUAL_DISPLAY_ROTATION_PORTRAIT
        val ratio = if (config.widthHeightRatio > 0f) config.widthHeightRatio else 1f

        //Portrait window: width = freeformWidth + scaleExtra, height = freeformHeight + chromeHeight
        //Landscape window: width = freeformHeight + scaleExtra, height = freeformWidth + chromeHeight
        val capByWindowWidth = if (portraitWindow) {
            ((realScreenWidth - scaleExtra) / ratio).toInt()
        } else {
            realScreenWidth - scaleExtra
        }
        val capByWindowHeight = if (portraitWindow) {
            realScreenHeight - chromeHeight
        } else {
            ((realScreenHeight - chromeHeight) / ratio).toInt()
        }
        val capHeight = max(MIN_HEIGHT, min(capByWindowWidth, capByWindowHeight))
        if (freeformHeight > capHeight) {
            freeformHeight = capHeight
            freeformWidth = (freeformHeight * ratio).roundToInt()
            scaleX = freeformHeight / maxFreeformScreenSize.toFloat()
            scaleY = scaleX
        }

        //Refresh the window width/height with the final freeform size (reusing updateWindowSize's orientation formula)
        updateWindowSize(false)
        windowLayoutParams.x = max(0, min(windowLayoutParams.x, realScreenWidth - windowLayoutParams.width))
        windowLayoutParams.y = max(0, min(windowLayoutParams.y, realScreenHeight - windowLayoutParams.height))
        if (!isBackstage) {
            windowManager.updateViewLayout(binding.root, windowLayoutParams)
        }
    }

    private fun updateWindowSize(orientationChanged: Boolean) {
        scaleX = freeformHeight / maxFreeformScreenSize.toFloat()
        scaleY = freeformWidth / minFreeformScreenSize.toFloat()
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

    /**
     * If the freeform window becomes uncontrollable, move it to the screen center so it can be controlled.
     */
    override fun toScreenCenter() {
        windowLayoutParams.x = 0
        windowLayoutParams.y = 0
    }

    override fun moveToFirst() {
        if (windowAttached) {
            try {
                windowManager.removeViewImmediate(binding.root)
            } catch (e: Exception) {
                Log.w(TAG, "remove_view_failed(toFront) pkg=${config.packageName}", e)
            }
            windowAttached = false
        }
        // Bringing to front is a high-frequency path triggered by user taps: it can throw when the permission is revoked midway, and not catching it means crashing whenever the freeform window is touched.
        try {
            windowManager.addView(binding.root, windowLayoutParams)
            windowAttached = true
        } catch (e: Exception) {
            Log.e(TAG, "add_view_failed(toFront) pkg=${config.packageName} type=${windowLayoutParams.type}", e)
            DiagnosticsLog.recordRes(
                context, "overlay_rejected",
                R.string.diag_bring_to_front_failed, config.packageName, e.javaClass.simpleName
            )
        }
        FreeformHelper.addFreeformToSet(this)
    }

    /**
     * Hide the freeform window into the background
     *
     * There are two collapse ways, decided by the setting minimize_mode:
     * - MODE_SIDEBAR: collapse into the sidebar list (original behavior), fully removing the window.
     * - MODE_BALL: turn into a floating ball, showing the app icon at the screen edge, tap to restore.
     */
    private fun toBackstage() {
        if (windowAttached) {
            try {
                windowManager.removeViewImmediate(binding.root)
            } catch (e: Exception) {
                Log.w(TAG, "remove_view_failed(toBackstage) pkg=${config.packageName}", e)
            }
            windowAttached = false
        }
        FreeformHelper.removeFreeformFromSet(this)
        FreeformHelper.addMiniFreeformToSet(this)
        isBackstage = true

        // Floating-ball mode: show the ball; sidebar mode: show nothing (original behavior).
        if (getMinimizeMode() == MINIMIZE_MODE_BALL) {
            miniBall?.dismiss()
            miniBall = MiniBallView(context, this)
            miniBall?.show()
        }
    }

    /** Collapse mode: 0=sidebar, 1=floating ball. Defaults to floating ball, matching minimize_mode's defaultValue in settings.xml. */
    private fun getMinimizeMode(): Int {
        val sp = context.getSharedPreferences(MiFreeform.APP_SETTINGS_NAME, Context.MODE_PRIVATE)
        return sp.getInt(KEY_MINIMIZE_MODE, MINIMIZE_MODE_BALL)
    }

    /**
     * Restore from the background
     */
    override fun fromBackstage() {
        // In floating-ball mode, restore the freeform window only after dismissing the ball.
        miniBall?.dismiss()
        miniBall = null
        try {
            windowManager.addView(binding.root, windowLayoutParams)
            windowAttached = true
        } catch (e: Exception) {
            Log.e(TAG, "add_view_failed(fromBackstage) pkg=${config.packageName} type=${windowLayoutParams.type}", e)
            DiagnosticsLog.recordRes(
                context, "overlay_rejected",
                R.string.diag_resume_attach_failed, config.packageName, e.javaClass.simpleName
            )
        }

        // On collapse-then-restore the window is recreated and may likewise get hidden by the system, so the watchdog goes back on duty
        binding.root.removeCallbacks(surfaceWatchdog)
        binding.root.removeCallbacks(surfaceWatchdogConfirm)
        binding.root.postDelayed(surfaceWatchdog, SURFACE_WATCHDOG_MS)

        // Fix: the freeform window turns transparent / white after collapse-then-restore.
        // toBackstage goes through removeViewImmediate, which destroys the TextureView's SurfaceTexture;
        // on re-addView the system creates a new SurfaceTexture, but the old virtual-display Surface
        // still points at the destroyed one, so the content stays empty. Here we rebind the virtual display to the new texture.
        resetSurfaceForRestore()

        FreeformHelper.removeMiniFreeformFromSet(this)
        FreeformHelper.addFreeformToSet(this)
        isBackstage = false
    }

    /**
     * Rebinds the content when restoring the freeform window.
     *
     * Must be called after addView: only then can the TextureView possibly have a new SurfaceTexture.
     * If the texture isn't available at this moment, do nothing—the system will soon call back
     * onSurfaceTextureAvailable, which rebinds the virtual display with the same formula.
     * We no longer reset initFinish to false and wait for first frames to fade alpha as before:
     * when a restored app doesn't redraw, those frames never arrive and the freeform window stays permanently transparent (white screen).
     */
    private fun resetSurfaceForRestore() {
        try {
            firstInit = false

            val texture = binding.textureView
            if (texture.isAvailable) {
                texture.surfaceTexture?.let {
                    it.setDefaultBufferSize(freeformScreenWidth, freeformScreenHeight)
                    virtualDisplay.surface = Surface(it)
                }
            }

            // When locked, the root container gets set transparent, and that branch doesn't run in the suspended state,
            // so here we restore the root container to opaque as a fallback.
            binding.root.alpha = 1f
        } catch (e: Throwable) {
            android.util.Log.w("FreeformView", "restore_surface_failed", e)
        }
    }

    /**
     * Try to make the freeform window's content fullscreen
     */
    private fun moveTaskToDefaultDisplay() {
        isDestroy = true
        virtualDisplay.resize(realScreenWidth, realScreenHeight, FreeformHelper.getScreenDpi(context))
        MiFreeform.me?.getControlService()?.execShell("am start -n ${config.packageName}/${config.activityName} --user ${config.userId} --display 0", false)
        destroy()
    }

    /**
     * Notify windowManager to refresh the scaled UI
     */
    private fun notifyWindowScale() {
        binding.root.scaleX = 1f
        binding.root.scaleY = 1f
        //This is the single wrap-up point for user drag-scaling: what they dragged out is the new set size for "this current form",
        //rewriting only the landscape or portrait copy, leaving the other as-is.
        setUserHeightForCurrentForm(freeformHeight)
        scaleX = freeformHeight / maxFreeformScreenSize.toFloat()
        scaleY = freeformWidth / minFreeformScreenSize.toFloat()
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
            //Left/right dot: press only records the starting point; scaling is handled in handleMoveEvent per-frame incrementally
            R.id.leftView, R.id.rightView -> {
                beforeScaleHeight = freeformHeight
            }
            R.id.middleView -> {
                middleGestureDetector.onTouchEvent(event)
            }

            R.id.leftScale, R.id.rightScale -> {
                beforeScaleHeight = freeformHeight
            }
            //Top bar: same dispatch logic as the bottom bar
            R.id.topBarMinimize -> {
                if (touchId == R.id.topBarMinimize) topMinimizeGestureDetector.onTouchEvent(event)
            }
            R.id.topBarFullScreen -> {
                if (touchId == R.id.topBarFullScreen) topFullScreenGestureDetector.onTouchEvent(event)
            }
            R.id.topBarClose -> {
                if (touchId == R.id.topBarClose) topCloseGestureDetector.onTouchEvent(event)
            }
        }
    }

    private fun handleMoveEvent(v: View, event: MotionEvent) {
        when(v.id) {
            //Left dot: per-frame incremental scaling, same channel and same feel as left-edge scaling
            R.id.leftView -> {
                if (touchId == R.id.leftView) {
                    val dx = event.rawX - lastX
                    val dy = event.rawY - lastY
                    handleDotScaleLeft(dx, dy)
                    lastX = event.rawX
                    lastY = event.rawY
                }
            }
            //Right dot: per-frame incremental scaling, same channel and same feel as right-edge scaling
            R.id.rightView -> {
                if (touchId == R.id.rightView) {
                    val dx = event.rawX - lastX
                    val dy = event.rawY - lastY
                    handleDotScaleRight(dx, dy)
                    lastX = event.rawX
                    lastY = event.rawY
                }
            }
            R.id.middleView -> {
                if (touchId == R.id.middleView) {
                    val dx = event.rawX - lastX
                    val dy = event.rawY - lastY

                    handleMove(dx, dy)
                    // Removed: the logic that auto-shrank and suspended when hugging a screen corner.
                    // Users didn't want the window auto-shrunk when moved to a screen corner;
                    // moving should be purely moving.
                    lastX = event.rawX
                    lastY = event.rawY

                    middleGestureDetector.onTouchEvent(event)
                }
            }
            R.id.leftScale -> {
                if (touchId == R.id.leftScale) {
                    val dx = event.rawX - lastX
                    val dy = event.rawY - lastY
                    handleScaleLeft(dx, dy)
                    lastX = event.rawX
                    lastY = event.rawY
                }
            }
            R.id.rightScale -> {
                if (touchId == R.id.rightScale) {
                    val dx = event.rawX - lastX
                    val dy = event.rawY - lastY
                    handleScaleRight(dx, dy)
                    lastX = event.rawX
                    lastY = event.rawY
                }
            }
            //Top bar: the move phase only feeds each GestureDetector; the position is fixed and doesn't participate in dragging
            R.id.topBarMinimize -> {
                if (touchId == R.id.topBarMinimize) topMinimizeGestureDetector.onTouchEvent(event)
            }
            R.id.topBarFullScreen -> {
                if (touchId == R.id.topBarFullScreen) topFullScreenGestureDetector.onTouchEvent(event)
            }
            R.id.topBarClose -> {
                if (touchId == R.id.topBarClose) topCloseGestureDetector.onTouchEvent(event)
            }
        }
    }

    private fun handleUpEvent(v: View, event: MotionEvent) {
        when (v.id) {
            R.id.middleView -> {
                middleGestureDetector.onTouchEvent(event)
            }
            //Left dot: on release refresh the virtual-display size, same wrap-up as edge scaling
            R.id.leftView -> {
                if (touchId == R.id.leftView) notifyWindowScale()
            }
            //Right dot: on release refresh the virtual-display size, same wrap-up as edge scaling
            R.id.rightView -> {
                if (touchId == R.id.rightView) notifyWindowScale()
            }
            R.id.leftScale, R.id.rightScale -> {
                notifyWindowScale()
            }
            //Top bar: on release decide whether single tap / long press takes effect
            R.id.topBarMinimize -> {
                if (touchId == R.id.topBarMinimize) topMinimizeGestureDetector.onTouchEvent(event)
            }
            R.id.topBarFullScreen -> {
                if (touchId == R.id.topBarFullScreen) topFullScreenGestureDetector.onTouchEvent(event)
            }
            R.id.topBarClose -> {
                if (touchId == R.id.topBarClose) topCloseGestureDetector.onTouchEvent(event)
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
            if (tempHeight > MIN_HEIGHT && tempHeight <= config.maxHeight && tempWidth > MIN_WIDTH && tempWidth < realScreenWidth) {
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
            if (tempHeight > MIN_HEIGHT && tempHeight <= config.maxHeight && tempWidth > MIN_WIDTH && tempWidth < realScreenWidth) {
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
            if (tempHeight > MIN_HEIGHT && tempHeight <= config.maxHeight && tempWidth > MIN_WIDTH && tempWidth < realScreenWidth) {
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
            if (tempHeight > MIN_HEIGHT && tempHeight <= config.maxHeight && tempWidth > MIN_WIDTH && tempWidth < realScreenWidth) {
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

    private fun pressBackPreQ() {
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
            inputManager?.injectInputEvent(down, virtualDisplay.display.displayId)

            KeyEvent::class.java.getMethod("setSource", Int::class.javaPrimitiveType)
                .invoke(up, InputDevice.SOURCE_KEYBOARD)
            inputManager?.injectInputEvent(up, virtualDisplay.display.displayId)
        } catch (e: Exception) {
            Log.w(TAG, "press_back_failed pkg=${config.packageName}", e)
        }
    }

    private fun destroy() {
        // destroy has multiple entry points (user taps close, task removed, task jumps to fullscreen,
        // re-entering the same app clearing the old instance), and re-entering would release the virtual display twice.
        //
        // Note we can't use isDestroy for idempotence here: moveTaskToDefaultDisplay() sets it first
        // (to stop the Sui hook from pulling the task back into the freeform window), before destroy() has run;
        // guarding with it would skip destroy() entirely in fullscreen, leaving the freeform window on screen and
        // the virtual display stuck at fullscreen size, so next time the app would render a size smaller than the window.
        if (isDestroyed) return

        // On exit also dismiss the floating ball, otherwise it stays on screen as an orphan.
        miniBall?.dismiss()
        miniBall = null
        //Record position
        if (viewModel.getBooleanSp("remember_freeform_position", false)) {
            val sp = context.getSharedPreferences(MiFreeform.APP_SETTINGS_NAME, Context.MODE_PRIVATE)
            //Persist the user-set long edge, not freeformHeight: when the freeform window is landscape (or just clamped),
            //freeformHeight is only a temporary value to avoid overflow, and storing it would make the next launch mysteriously smaller
            val userHeight = userHeightForCurrentForm().let { if (it > 0) it else freeformHeight }
            if (screenRotation == Surface.ROTATION_90 || screenRotation == Surface.ROTATION_270) {
                sp.edit()
                    .putInt(REMEMBER_LAND_X, windowLayoutParams.x)
                    .putInt(REMEMBER_LAND_Y, windowLayoutParams.y)
                    .putInt(REMEMBER_LAND_HEIGHT, userHeight)
                    .apply()
            } else {
                sp.edit()
                    .putInt(REMEMBER_X, windowLayoutParams.x)
                    .putInt(REMEMBER_Y, windowLayoutParams.y)
                    .putInt(REMEMBER_HEIGHT, userHeight)
                    .apply()
            }
        }

        isDestroy = true
        isDestroyed = true
        binding.root.removeCallbacks(surfaceWatchdog)
        binding.root.removeCallbacks(surfaceWatchdogConfirm)
        // Only detach a genuinely attached window. When not attached removeViewImmediate always throws,
        // and that exception gets logged as zombie_window, a false alarm we created ourselves.
        if (windowAttached) {
            windowAttached = false
            try {
                windowManager.removeViewImmediate(binding.root)
            } catch (e: Exception) {
                // If this line fails the window stays permanently in the WindowManager (with a surface, invisible, holding 30+MB),
                // and virtualDisplay still gets released afterward, becoming a zombie with "a window but no content".
                // Measured that such a zombie window really exists, so we must log loudly here.
                Log.e(TAG, "remove_view_failed - zombie window left pkg=${config.packageName}", e)
                DiagnosticsLog.recordRes(
                    context, "zombie_window",
                    R.string.diag_zombie_window, config.packageName, e.javaClass.simpleName
                )
            }
        }
        try {
            virtualDisplay.surface.release()
        } catch (e: Exception) {
            Log.w(TAG, "release_surface_failed pkg=${config.packageName}", e)
        }
        virtualDisplay.release()

        try {
            iWindowManager?.removeRotationWatcher(iRotationWatcher)
        } catch (e: Exception) {
            Log.w(TAG, "remove_rotation_watcher_failed pkg=${config.packageName}", e)
        }


        try {
            screenListener.unregisterListener()
        } catch (e: Exception) {
            Log.w(TAG, "unregister_screen_listener_failed pkg=${config.packageName}", e)
        }

        //Remove freeform-window management
        //When a task jumps to fullscreen and gets cleared, the freeform window may be hanging in the background (collapsed to sidebar/floating ball),
        //so both sets must be cleared, otherwise the sidebar keeps an untappable ghost entry.
        FreeformHelper.removeFreeformFromSet(this)
        FreeformHelper.removeMiniFreeformFromSet(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                activityTaskManager?.unregisterTaskStackListener(taskStackListener)
            } catch (e: Exception) {
                Log.w(TAG, "unregister_task_stack_listener_failed pkg=${config.packageName}", e)
            }
        }
    }

    init {
        if (MiFreeform.me?.getControlService()?.asBinder()?.pingBinder() == true) {
            //Try to restore the freeform-window state
            //Optimization: if the freeform window moved off-screen and can't be controlled, tap the app again in the sidebar to move it to the screen center q220917.4
            if (FreeformHelper.isAppInFreeform(config.packageName, config.userId)) {
                val freeformView = FreeformHelper.getFreeformStackSet().getByPackageName(config.packageName, config.userId)
                freeformView?.toScreenCenter()
                freeformView?.moveToFirst()
            } else if (FreeformHelper.isAppInMiniFreeform(config.packageName, config.userId)) {
                FreeformHelper.getMiniFreeformStackSet().getByPackageName(config.packageName, config.userId)?.fromBackstage()
            } else if (!Settings.canDrawOverlays(context) && !isAccessibilityServiceEnabled()) {
                // Move the decision ahead of building the virtual display (promoted after the `_rz268` rz_gate_first arm passed measurement): when
                // neither window type is eligible, there's no need to build the virtual display just to tear it down—report the missing permission directly.
                Log.w(TAG, "start_rejected no_overlay_permission gate_first pkg=${config.packageName}")
                requestOverlayPermissionUi(destroyFirst = false)
            } else {
                initSystemService()
                initConfig()

                //YouTube-specific adaptation
                if (config.packageName == YOUTUBE) {
                    config.activityName = YOUTUBE_ACTIVITY
                }
//                if (config.packageName == QQ) {
//                    config.freeformDpi = FreeformHelper.getScreenDpi(context)
//                }
                initView()
            }
        }
        else {
            // Measured: the first freeform-window open after force-stop lands here—no window, no virtual display, no log,
            // the user only sees "tapping does nothing". A Toast passes in a flash, so we must also leave a replayable record.
            Log.w(TAG, "start_rejected shizuku not bound pkg=${config.packageName}")
            DiagnosticsLog.recordRes(
                context, "shizuku_unbound", R.string.service_not_running_pkg, config.packageName
            )
            MiFreeform.me?.initShizuku()
            Toast.makeText(context, context.getString(R.string.service_not_running), Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Maps the touch point from textureView's view coordinates to the virtual display's buffer coordinates.
     *
     * Based on the content View's measured size (see [FreeformTouchMapper] for why); when the measured size isn't available yet
     * (extreme timing, e.g. a touch before the first frame), fall back to the old nominal ratio to guarantee no divide-by-zero and no dead screen.
     */
    private fun mapTouchToBuffer(dst: MotionEvent.PointerCoords) {
        val view = binding.textureView
        dst.x = FreeformTouchMapper.mapX(dst.x, view.width, freeformScreenWidth, scaleX)
        dst.y = FreeformTouchMapper.mapY(dst.y, view.height, freeformScreenHeight, scaleY)
    }

    //Optimization: treat touch as a first-class citizen to support multi-touch; also worth looking into why, that way, multi-touch wasn't supported... q220906.1
    private inner class TouchListener : View.OnTouchListener{
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            handleTouch(event)
            when(event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (FreeformHelper.isShowingFirst(this@FreeformView)) {
                        touchId = R.id.textureView
                    } else {
                        moveToFirst()
                    }
                }
                MotionEvent.ACTION_UP -> {
                    touchId = -1
                }
            }
            return true
        }

        /**
         * Touch handling
         */
        private fun handleTouch(event: MotionEvent) {
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

            //Touch is a high-frequency path: when the Shizuku binder dies or the virtual display is already released, transact throws directly,
            //and a single throw crashes the whole process (a finger tap closes the app), so we catch it uniformly here and log it.
            runCatching {
                setDisplayIdMethod?.invoke(newEvent, virtualDisplay.display.displayId)
                inputManager?.injectInputEvent(newEvent, 0)
            }.onFailure { Log.w(TAG, "inject touch failed", it) }
            newEvent.recycle()
        }
    }

    private inner class TouchListenerPreQ : View.OnTouchListener {
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            handleTouch(event)
            when(event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (FreeformHelper.isShowingFirst(this@FreeformView)) {
                        touchId = R.id.textureView
                    } else {
                        moveToFirst()
                    }
                }
                MotionEvent.ACTION_UP -> {
                    touchId = -1
                    //handleTouch2(event)
                }
            }
            return true
        }

        /**
         * Touch handling
         */
        private fun handleTouch(event: MotionEvent) {
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
            runCatching {
                inputManager?.injectInputEvent(newEvent, virtualDisplay.display.displayId)
            }.onFailure { Log.w(TAG, "inject touch failed", it) }
            newEvent.recycle()
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private inner class MTaskStackListener : TaskStackListener() {
        override fun onTaskRemovalStarted(taskInfo: ActivityManager.RunningTaskInfo) {
            if (taskInfo.taskId == taskId) {
                scope.launch(Dispatchers.Main) {
                    destroy()
                }
            }
        }

        override fun onTaskDisplayChanged(tId: Int, newDisplayId: Int) {
            if (taskId == -1 && newDisplayId == virtualDisplay.display.displayId) taskId = tId

            //Not our task, or the task is still inside our own virtual display—nothing to handle either way
            if (tId != taskId || newDisplayId == virtualDisplay.display.displayId || isDestroy) return

            if (newDisplayId != Display.DEFAULT_DISPLAY) return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && config.useSuiRefuseToFullScreen) {
                //The user enabled "use Shizuku/Sui to stop apps from leaving the freeform window into fullscreen", so pull the task back into the freeform window
                activityTaskManager?.moveRootTaskToDisplay(tId, virtualDisplay.display.displayId)
            } else {
                //The task went to fullscreen (the user re-entered this app from the launcher, a notification, etc.).
                //At this point the old freeform window's virtual display has no content; collapsing and restoring it would turn it into
                //a transparent freeform window that can neither be tapped nor drawn, so we just clear the old window directly.
                scope.launch(Dispatchers.Main) {
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
                // If the userId field name ever changes (later versions may rename it or promote it to a public property), taskId stays stuck at the old value forever,
                // and the consequence is that the window doesn't reset when the app rotates itself. The failure must be visible.
                Log.w(TAG, "read_task_userId_failed pkg=${config.packageName}", e)
            }
        }

        override fun onTaskRequestedOrientationChanged(tId: Int, requestedOrientation: Int) {
            //When the user has manually rotated the freeform window's orientation, ignore the app-reported orientation to avoid resetting the window
            if (manualRotationOverride) return
            //q220902.2 some portrait apps also go landscape; investigation found a case with requestedOrientation=2, so convert it to 1
            var tempRotation = requestedOrientation
            if (tempRotation != VIRTUAL_DISPLAY_ROTATION_PORTRAIT && tempRotation != VIRTUAL_DISPLAY_ROTATION_LANDSCAPE) tempRotation = VIRTUAL_DISPLAY_ROTATION_PORTRAIT
            if (taskId == tId && tempRotation != virtualDisplayRotation) {
                virtualDisplayRotation = tempRotation
                scope.launch(Dispatchers.Main) {
                    onFreeFormRotationChanged()
                }
            }
        }

        //q220903.2 on Android 10 this callback is needed for listening
        override fun onActivityRequestedOrientationChanged(tId: Int, requestedOrientation: Int) {
            //When the user has manually rotated the freeform window's orientation, ignore the app-reported orientation to avoid resetting the window
            if (manualRotationOverride) return
            var tempRotation = requestedOrientation
            if (tempRotation != VIRTUAL_DISPLAY_ROTATION_PORTRAIT && tempRotation != VIRTUAL_DISPLAY_ROTATION_LANDSCAPE) tempRotation = VIRTUAL_DISPLAY_ROTATION_PORTRAIT
            if (taskId == tId && tempRotation != virtualDisplayRotation) {
                virtualDisplayRotation = tempRotation
                scope.launch(Dispatchers.Main) {
                    onFreeFormRotationChanged()
                }
            }
        }
    }

    companion object {
        private const val TAG = "FreeformView"

        // How long after opening the window to decide "the system gave no surface". On MIX 2S the normal path completes far earlier than this;
        // the margin is to avoid false alarms on slow devices. Better to report four seconds late than to miss an invisible window occupying the virtual display.
        private const val SURFACE_WATCHDOG_MS = 4000L

        // After first detecting the hiding, how long to wait before confirming. Measured, a momentary hiding recovers on its own within seconds,
        // while the kind we really must handle never recovers until the process restarts, so relaxing this to ten seconds doesn't affect the decision.
        private const val SURFACE_CONFIRM_MS = 10000L

        // Only count the force-hide marker within "our own window block"; on a hit, exit 0.
        // In dumpsys a window starts with "Window #N Window{... pkg}", and subsequent lines belong to the same block,
        // so use f to remember "whether we're currently reading our own block".
        private const val HIDDEN_WINDOW_CHECK_COMMAND =
            "dumpsys window windows | awk '/Window #/{f=(\$0 ~ /mifreeform\\.freeform}/)} " +
                    "f&&/mForceHideNonSystemOverlayWindow=true/{n++} END{exit(n>0?0:1)}'"

        // The previous command only gives a boolean; this one gives text: extract the package name of "the window carrying the anti-hijack flag".
        // In dumpsys a window starts with "Window #N Window{token u0 pkg/Activity}",
        // and the flag line appears a bit later within the same record, so use h to remember the current block's header line and then cut the package name from it.
        private const val HIDDEN_WINDOW_CULPRIT_COMMAND =
            "dumpsys window windows | awk '/Window #/{h=\$0} /HIDE_NON_SYSTEM_OVERLAY_WINDOWS/" +
                    "{if (match(h, /u0 [a-zA-Z0-9._]+\\//)) {print substr(h, RSTART+3, RLENGTH-4)}}' | sort -u"

        // Minimum size for proportional scaling: allow scaling down quite small.
        // The original 400/600 was effectively "can't shrink" on small-screen phones, so lower it to the real minimum usable size.
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

        // Collapse-mode setting key
        const val KEY_MINIMIZE_MODE = "minimize_mode"
        const val MINIMIZE_MODE_SIDEBAR = 0
        const val MINIMIZE_MODE_BALL = 1
    }
}
