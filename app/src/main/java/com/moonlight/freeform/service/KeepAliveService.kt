package com.moonlight.freeform.service

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.Service
import android.content.*
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.*
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.moonlight.freeform.R
import com.moonlight.freeform.app.MiFreeform
import com.moonlight.freeform.broadcast.StartFreeformReceiver
import com.moonlight.freeform.ui.floating.ChooseAppFloatingView
import com.moonlight.freeform.ui.freeform.FreeformConfig
import com.moonlight.freeform.utils.PermissionUtils
import kotlinx.coroutines.*
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * @date 2022/8/23
 * @author sunshine0523
 * Keep alive via accessibility; the floating-button UI has also been moved here
 */
class KeepAliveService : AccessibilityService(), SharedPreferences.OnSharedPreferenceChangeListener, View.OnTouchListener, GestureDetector.OnGestureListener, ChooseAppFloatingView.OnWindowRemoveCallback {

    // In-app language switch: this service is bound by the system, so language changes update the text only on the next rebind (e.g. toggling accessibility)
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.moonlight.freeform.utils.LocaleHelper.wrap(newBase))
    }

    private lateinit var sp: SharedPreferences

    private val scope = MainScope()

    // Whether the floating button is currently shown
    private var isShowingFloating = false
    // Whether the app picker is currently shown
    private var isShowingChooseApp = false

    // Window manager
    private lateinit var windowManager: WindowManager
    private lateinit var windowLayoutParams: WindowManager.LayoutParams

    // Floating-button config
    private lateinit var config: FloatingConfig

    // Screen width/height
    private var screenWidth: Int = 0
    private var screenHeight: Int = 0
    // The display density used last time for dp conversion. The system "Display size" setting only changes density, not pixel size,
    // which screenWidth/screenHeight alone can't detect, so we must track it separately.
    private var cachedDensityDpi = 0

    // Physical screen orientation, 1 portrait, 2 landscape
    private var screenRotation: Int = 0
    // Physical screen orientation obtained via the Shizuku service; 0 1 2 3 represent the four directions
    private var displayRotation = Surface.ROTATION_0

    // The service obtained via Shizuku that watches screen orientation
    private lateinit var iWindowManager: IWindowManager
    private lateinit var rotationWatcher: IRotationWatcher
    // Whether the rotation watcher is registered to IWindowManager. When not registered, the destroy flow must not try to unregister it.
    private var rotationWatcherRegistered = false

    private lateinit var gestureDetector: GestureDetector

    // The floating-button UI
    private lateinit var floatView: View

    // The app-picker floating UI
    private lateinit var chooseAppFloatingView: ChooseAppFloatingView

    // Touch mode, marks whether it's a tap or a long press
    private var touchMode = 0

    private lateinit var displayManager: DisplayManager

    // The default display
    private lateinit var defaultDisplay: Display

    private var startFreeformReceiver = StartFreeformReceiver()

    // Screen listener
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {

        }
        override fun onDisplayRemoved(displayId: Int) {

        }

        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) {
                // Resolution/display-size changes (the system "Display size" setting, some ROMs' resolution switch, foldable unfold) don't go through the rotation callback,
                // so only here can we get the new size. If we don't recompute, screenWidth and the already-built window pixels stay at old values,
                // and the dp shown in the settings page would no longer match the actual thickness.
                // Note this callback may arrive before this process's resources update, reading the old density and doing a wasted pass;
                // the real fallback is onConfigurationChanged below.
                scope.launch(Dispatchers.Main) { refreshDisplayGeometry() }
            }
        }
    }

    // The system "Display size" setting only changes density and a resolution switch only changes size, neither accompanied by rotation, so the rotationWatcher path doesn't fire.
    // Measured (emulator wm density 440->320): when the DisplayListener callback arrives, the process resources still hold old values and the handle pixels don't change;
    // onConfigurationChanged is called back only after the system flushes the new config into this process, so this is the reliable point to get the new density.
    // Unlike Activity, a Service has no configChanges declaration, so any config change definitely calls back—run it directly on the main thread.
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshDisplayGeometry()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        sp = getSharedPreferences(MiFreeform.APP_SETTINGS_NAME, Context.MODE_PRIVATE)
        sp.registerOnSharedPreferenceChangeListener(this)

        // Switch to accessibility mode
        if (sp.getInt("service_type", SERVICE_TYPE) != SERVICE_TYPE) {
            sp.edit().putInt("service_type", SERVICE_TYPE).apply()
        }
        stopService(Intent(this, ForegroundService::class.java))

        // Same source as HookLauncher's sender (see that file's comment), otherwise broadcasts sent by the launcher won't be received.
        registerReceiver(
            startFreeformReceiver,
            IntentFilter("${MiFreeform.PACKAGE_NAME}.start_freeform")
        )

        // rotationWatcher itself doesn't depend on Shizuku, so build it first; registering it to IWindowManager
        // must wait for Shizuku's binder to arrive, see initRotationWatcher below.
        rotationWatcher = object : IRotationWatcher.Stub() {
            override fun onRotationChanged(rotation: Int) {
                scope.launch(Dispatchers.Main) {
                    displayRotation = rotation

                    //q220902.3 if the app crashes, the orientation from resources.configuration.orientation is wrong, so we shouldn't use that method
                    val tempScreenRotation = if (displayRotation == Surface.ROTATION_0 || displayRotation == Surface.ROTATION_180) {
                        Configuration.ORIENTATION_PORTRAIT
                    } else {
                        Configuration.ORIENTATION_LANDSCAPE
                    }

                    if (tempScreenRotation != screenRotation) {
                        screenRotation = tempScreenRotation

                        if (screenRotation == Configuration.ORIENTATION_PORTRAIT) {
                            screenHeight = max(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
                            screenWidth = min(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
                        } else {
                            screenWidth = max(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
                            screenHeight = min(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
                        }
                        cachedDensityDpi = resources.displayMetrics.densityDpi

                        removeFloating()
                        initConfig()
                        try {
                            chooseAppFloatingView.onScreenRotationChanged(screenRotation)
                        } catch (e: Exception) {
                            Log.w(TAG, "rotation_changed_notify_failed", e)
                        }
                    }
                }
            }
        }
        if (!initRotationWatcher()) {
            // The accessibility service is often connected by the system before Shizuku's binder (right after process start the binder is still empty),
            // and calling IWindowManager directly then throws IllegalStateException: binder haven't been received,
            // crashing the whole app process—manifesting as a crash right on opening the app. Changed to register after the binder arrives.
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        }

        displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        displayManager.registerDisplayListener(displayListener, null)
        defaultDisplay = displayManager.getDisplay(Display.DEFAULT_DISPLAY)

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowLayoutParams = WindowManager.LayoutParams()

        gestureDetector = GestureDetector(this, this)

        screenWidth = resources.displayMetrics.widthPixels
        screenHeight = resources.displayMetrics.heightPixels
        screenRotation = resources.configuration.orientation
        cachedDensityDpi = resources.displayMetrics.densityDpi

        initConfig()
        chooseAppFloatingView = ChooseAppFloatingView(this, config.positionX, this)
        // Fetch the sidebar panel's data ahead on background: otherwise on the user's first handle tap,
        // the Room first query + queryIntentActivities all get stuck between the tap and the panel appearing.
        chooseAppFloatingView.prewarm()

    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {

    }

    override fun onInterrupt() {

    }

    /**
     * Fetches IWindowManager and registers the rotation watcher on it.
     *
     * @return true = registered (including previously registered); false = Shizuku binder not yet arrived or registration failed; the caller must retry later
     */
    private fun initRotationWatcher(): Boolean {
        if (rotationWatcherRegistered) return true
        if (Shizuku.getBinder() == null) return false
        return try {
            iWindowManager = IWindowManager.Stub.asInterface(
                ShizukuBinderWrapper(
                    SystemServiceHelper.getSystemService("window"))
            )
            iWindowManager.watchRotation(rotationWatcher, Display.DEFAULT_DISPLAY)
            rotationWatcherRegistered = true
            true
        } catch (e: Exception) {
            Log.w(TAG, "initRotationWatcher failed", e)
            false
        }
    }

    private val binderReceivedListener = object : Shizuku.OnBinderReceivedListener {
        override fun onBinderReceived() {
            if (initRotationWatcher()) {
                Shizuku.removeBinderReceivedListener(this)
            }
        }
    }



    override fun onDestroy() {
        super.onDestroy()
        // onServiceConnected doesn't guarantee every step ran (e.g. Shizuku binder never arrived);
        // accessing uninitialized lateinit members throws UninitializedPropertyAccessException,
        // and crashing again during destroy makes the service restart repeatedly, so check each item.
        if (::displayManager.isInitialized) displayManager.unregisterDisplayListener(displayListener)
        if (isShowingFloating) removeFloating()
        if (::sp.isInitialized) sp.unregisterOnSharedPreferenceChangeListener(this)

        try {
            unregisterReceiver(startFreeformReceiver)
        } catch (e: Exception) {
            Log.d(TAG, "unregister startFreeformReceiver skipped", e)
        }

        Shizuku.removeBinderReceivedListener(binderReceivedListener)

        if (rotationWatcherRegistered && ::iWindowManager.isInitialized) {
            try {
                iWindowManager.removeRotationWatcher(rotationWatcher)
            } catch (e: Exception) {
                Log.w(TAG, "removeRotationWatcher failed", e)
            }
        }
        // Service destroyed: discard the panel's window and the pre-built view tree together
        if (::chooseAppFloatingView.isInitialized) chooseAppFloatingView.dispose()
    }

    /**
     * Re-fetches screen size/density once; if changed, repositions the handle by the new geometry.
     * There are two call sites (DisplayListener and onConfigurationChanged), with no guaranteed order or count between them,
     * so we first compare against the last remembered values and only act on a real change, avoiding rebuilding the window on every config callback.
     */
    private fun refreshDisplayGeometry() {
        // When onCreate/onServiceConnected hasn't finished (accessibility bind and config callbacks have no guaranteed order), these members are empty,
        // and accessing them then throws UninitializedPropertyAccessException, crashing the shared process; once init completes it will reposition per the new config anyway.
        if (!::windowManager.isInitialized || !::windowLayoutParams.isInitialized) return

        val dm = resources.displayMetrics
        val portrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        val newWidth = if (portrait) min(dm.widthPixels, dm.heightPixels) else max(dm.widthPixels, dm.heightPixels)
        val newHeight = if (portrait) max(dm.widthPixels, dm.heightPixels) else min(dm.widthPixels, dm.heightPixels)
        val newRotation = if (portrait) Configuration.ORIENTATION_PORTRAIT else Configuration.ORIENTATION_LANDSCAPE
        // Compare by densityDpi rather than density: integer equality is exact, while floats differ depending on the conversion path
        val densityChanged = dm.densityDpi != cachedDensityDpi
        if (newWidth == screenWidth && newHeight == screenHeight && newRotation == screenRotation && !densityChanged) {
            return
        }
        screenWidth = newWidth
        screenHeight = newHeight
        cachedDensityDpi = dm.densityDpi
        val orientationChanged = newRotation != screenRotation
        screenRotation = newRotation
        if (orientationChanged || densityChanged) {
            // Orientation change requires switching the left/right layout; density change requires re-inflating (dp resources like background corners are converted at view-creation time,
            // so only calling updateViewLayout fixes the window size but leaves corners at the old density). Both are rare, and the rebuild flicker is acceptable.
            // Only remove when actually showing: when the overlay is collapsed, floatView may be an unassigned lateinit,
            // so we can't rely on removeFloating's try/catch to swallow UninitializedPropertyAccessException.
            if (isShowingFloating) removeFloating()
            initConfig()
            if (orientationChanged) {
                runCatching { chooseAppFloatingView.onScreenRotationChanged(screenRotation) }
                    .onFailure { Log.w(TAG, "notify_panel_rotation_failed", it) }
            }
        } else if (isShowingFloating) {
            // Only the screen size changed (density unchanged): update window size and edge-alignment in place, no rebuild, no flicker.
            applyFloatingLayout()
        } else {
            initConfig()
        }
    }

    /**
     * Load configuration
     */
    private fun initConfig() {
        config = getFloatingConfig()
        if (getBooleanSp("show_floating", true) && !isShowingFloating && !isShowingChooseApp) {
            showFloating()
        }
    }

    // key must be declared nullable: some vendor ROMs (measured on Huawei HarmonyOS) pass key = null to the listener callback on sp.clear(),
    // while Kotlin's non-null parameter inserts Intrinsics.checkNotNullParameter at the function entry, throwing NPE before the method body runs,
    // which runCatching can't intercept either (the real root cause of the on-device crash on "restore defaults"; AOSP notifies per key so the emulator doesn't reproduce it).
    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        // Restoring defaults clears+rewrites many keys at once, so this callback fires at high frequency;
        // any branch throwing would drag down the shared process, so we guard the whole thing—one key failing shouldn't affect the rest.
        runCatching {
        when(key) {
            "show_floating" -> {
                // When show_floating is true ensure the handle is shown (build only if not already), remove only when false.
                // The old logic grouped "already shown but received show_floating=true" into else→removeFloating,
                // and restoring defaults rewrites this key, so a just-rebuilt handle would be wrongly removed and never come back.
                if (getBooleanSp(key, false)) {
                    if (!isShowingFloating && !isShowingChooseApp) initConfig()
                } else {
                    removeFloating()
                }
            }
            "floating_position_x" -> {
                //Switching sides: don't destroy and rebuild the window (rebuilding runs the enter/exit animation, visually a flicker); update position and side-direction in place;
                //when the handle isn't currently shown, go through initConfig to build it normally.
                initConfig()
                chooseAppFloatingView.showPositionX = config.positionX
                // The panel tree was built for the original side; after switching it must be rebuilt to appear on the new side
                chooseAppFloatingView.onSideChanged()
                if (isShowingFloating) applyFloatingLayout()
            }
            //Listener for opening the small window, sent from the Activity
            "to_show_floating" -> {
                if (!isShowingChooseApp) {
                    removeFloating()
                    //Fix: multiple app-picker windows could be opened q220909.1
                    isShowingChooseApp = true
                    chooseAppFloatingView.showFloatingView()
                }
            }
            //When sidebar length/width/X-axis position/Y-axis position change, update to the new size and position in place.
            //Must not destroy+rebuild via remove+add: that runs the window enter/exit animation, visually "the handle flickers after dragging/changing settings";
            //and dragging itself has already moved the window to the right spot, so rebuilding is purely redundant.
            "sidebar_length_dp", "sidebar_width_dp", "sidebar_pos_x_dp", "sidebar_pos_y_dp" -> {
                if (isShowingFloating) {
                    applyFloatingLayout()
                } else {
                    initConfig()
                }
            }
            "service_type" -> {
                if (getIntSp("service_type", SERVICE_TYPE) == SERVICE_TYPE) {
                    stopService(Intent(this, ForegroundService::class.java))
                }
            }
            "floating_alpha" -> {
                // The setting's semantics are "transparency": larger value = more transparent. Stored as 0~10 transparency,
                // converted to WindowLayoutParams.alpha (0 fully transparent ~ 1 opaque) = 1 - transparency/10.
                config.alpha = 1f - getIntSp("floating_alpha", 7) / 10f
                if (isShowingFloating) {
                    try {
                        windowManager.updateViewLayout(
                            floatView,
                            windowLayoutParams.apply { alpha = config.alpha }
                        )
                    }catch (e: Exception) {
                        Log.w(TAG, "apply_floating_alpha_failed", e)
                    }
                }
            }
        }
        }.onFailure {
            // A single key failing shouldn't drag down the shared process (in accessibility mode this service runs in the main process),
            // but we also can't swallow it silently, otherwise a setting that doesn't take effect could never be traced.
            Log.w(TAG, "onSharedPreferenceChanged key=$key handled failed", it)
        }
    }

    private fun getBooleanSp(key: String, default: Boolean): Boolean {
        return sp.getBoolean(key, default)
    }

    private fun getIntSp(key: String, default: Int): Int {
        return sp.getInt(key, default)
    }

    private fun setIntSp(key: String, value: Int) {
        sp.edit().putInt(key, value).apply()
    }

    private fun showFloating() {
        floatView =
            if (config.positionX == 1) LayoutInflater.from(this).inflate(R.layout.view_floating_button_right, null, false)
            else LayoutInflater.from(this).inflate(R.layout.view_floating_button_left, null, false)
        val root = floatView.findViewById<View>(R.id.root)

        root.setOnTouchListener(this)

        //Sidebar size can be adjusted in settings (dp), converted to pixels here; the position stores pixel offsets offsetX/offsetY
        val density = resources.displayMetrics.density
        //Width defaults to 8dp, matching settings.xml's android:defaultValue
        val floatingButtonWidth = (getIntSp("sidebar_width_dp", 8) * density).roundToInt()
        val floatingButtonHeight = (getIntSp("sidebar_length_dp", 64) * density).roundToInt()

        if (Settings.canDrawOverlays(this)) {
            // When restoring defaults, the service receives a stream of SP-change callbacks, repeatedly remove/adding overlay windows;
            // on real devices this transiently throws (view already added / token invalid etc.), and not catching it would crash the service process,
            // which the user sees as the whole app "force-closing". Guard here; worst case the handle isn't shown, without dragging down the process.
            try {
                windowManager.addView(floatView, windowLayoutParams.apply {
                    //X: convert the "edge distance" to window x, see handleWindowX for geometry details
                    x = handleWindowX(config.offsetX, floatingButtonWidth)
                    //Y: screen center is 0, draggable up/down to ±screenHeight/2
                    y = max(screenHeight / -2, min(screenHeight / 2, config.offsetY))
                    width = floatingButtonWidth
                    height = floatingButtonHeight
                    type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    format = PixelFormat.TRANSLUCENT
                    flags = flags or
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    windowAnimations = android.R.style.Animation_Dialog
                    alpha = config.alpha
                })
                isShowingFloating = true
            } catch (e: Exception) {
                Log.w(TAG, "showFloating addView failed", e)
                isShowingFloating = false
            }
        } else {
            // No permission: don't call addView even once, just report (see PermissionUtils.pickOverlayWindowType for why).
            // The old code would Toast here then jump to the system overlay settings page, and on Android 14 that very jump
            // hides all subsequent overlay windows in this process (same fix as in ForegroundService).
            Log.w(TAG, "handle_start_rejected no_overlay_permission")
            PermissionUtils.guideToGrantOverlayPermission(this, getString(R.string.scene_handle))
        }

    }

    private fun removeFloating() {
        try {
            windowManager.removeViewImmediate(floatView)
        } catch (e: Exception) {
            // If the handle window isn't torn down it becomes a zombie window (invisible but still occupying a surface), so we must log loudly.
            Log.w(TAG, "removeFloating failed (zombie handle window?)", e)
        }
        isShowingFloating = false
    }

    /**
     * Updates the handle window's size/position/alpha in place, without destroying and rebuilding (to avoid flicker).
     * The handle capsule fills the whole window (no outer margin in the layout), so window-edge-aligned == visible-part-aligned,
     * and X-axis position = 0 is the true screen edge; left/right geometry is symmetric, switching sides only depends on positionX's sign.
     */
    private fun applyFloatingLayout() {
        val density = resources.displayMetrics.density
        val width = (getIntSp("sidebar_width_dp", 8) * density).roundToInt()
        val height = (getIntSp("sidebar_length_dp", 64) * density).roundToInt()
        config = getFloatingConfig()
        runCatching {
            windowManager.updateViewLayout(
                floatView,
                windowLayoutParams.apply {
                    this.width = width
                    this.height = height
                    x = handleWindowX(config.offsetX, width)
                    y = max(screenHeight / -2, min(screenHeight / 2, config.offsetY))
                    alpha = config.alpha
                }
            )
        }.onFailure { Log.w(TAG, "applyFloatingLayout failed", it) }
    }

    /**
     * "edge inset" -> window x. The overlay window sets no gravity, so the system places it at the screen center;
     * measured frame.left = (screenWidth - width) / 2 + x, so first compute the desired absolute left edge, then subtract this centering baseline.
     * Must not write positionX * ((screenWidth - width) / 2 - inset): integer division is 1 short at odd pixel widths,
     * and the right-side baseline gets multiplied twice, leaving a 1px gap at the screen edge for widths like 4dp(11px), which looks like a wrong width.
     * positionX only uses -1 for the left side; everything else (1 for right, and anomalous values in old data) is treated as the right side.
     */
    private fun handleWindowX(inset: Int, width: Int): Int {
        val maxInset = (screenWidth - width) / 2
        val d = max(0, min(maxInset, inset))
        return (if (config.positionX < 0) d else screenWidth - width - d) - maxInset
    }

    private fun getFloatingConfig(): FloatingConfig {
        val density = resources.displayMetrics.density
        return FloatingConfig(
            getIntSp("floating_position_x", 1),
            //dp -> pixels: Y-axis offset (screen center is 0, may be negative), X-axis distance from the edge
            (getIntSp("sidebar_pos_y_dp", 0) * density).roundToInt(),
            (getIntSp("sidebar_pos_x_dp", 0) * density).roundToInt(),
            //The setting's semantics are "transparency" (0~10, larger = more transparent), converted to alpha = 1 - transparency/10
            1f - getIntSp("floating_alpha", 7) / 10f
        )
    }

    private fun handleMove(dx: Float, dy: Float) {
        //When locked, the corresponding axis doesn't participate in dragging: while X is locked offsetX is unchanged, while Y is locked offsetY is unchanged
        val lockX = getBooleanSp("sidebar_lock_x", true)
        val lockY = getBooleanSp("sidebar_lock_y", false)
        if (!lockX) {
            //X can only be pulled outward: inset 0 ~ (screenWidth - width)/2. dx is positive rightward; on the right (positionX=1) rightward means farther from the edge,
            //on the left (positionX=-1) rightward means toward the edge, so use positionX to flip the sign.
            val maxInset = (screenWidth - windowLayoutParams.width) / 2
            config.offsetX = max(0, min(maxInset, config.offsetX - (dx * config.positionX).roundToInt()))
        }
        if (!lockY) {
            //Y's draggable range narrows with the handle length: past (screenHeight - height)/2 the window gets clamped by the system at the
            //app area's top/bottom edges, and further dragging leaves the screen still but grows the offset (which the settings slider can't represent), so clamp to the visible range.
            val halfY = (screenHeight - windowLayoutParams.height) / 2
            config.offsetY = max(-halfY, min(halfY, config.offsetY + dy.roundToInt()))
        }
        if (lockX && lockY) return

        windowManager.updateViewLayout(
            floatView,
            windowLayoutParams.apply {
                if (!lockX) x = handleWindowX(config.offsetX, width)
                if (!lockY) y = max(screenHeight / -2, min(screenHeight / 2, config.offsetY))
            }
        )
    }

    //Coordinates at press time
    private var lastX = -1f
    private var lastY = -1f
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(v: View, event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        when(event.action) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.rawX
                lastY = event.rawY
            }
            MotionEvent.ACTION_MOVE -> {
                if (touchMode == SCROLL) {
                    val dx = event.rawX - lastX
                    val dy = event.rawY - lastY
                    handleMove(dx, dy)
                    lastX = event.rawX
                    lastY = event.rawY
                }
            }
            MotionEvent.ACTION_UP -> {
                if (touchMode == SCROLL) {
                    //Convert the handle's pixel offset back to dp for storage, so the position SeekBar in settings reflects it; locked axes aren't persisted, keeping the locked value
                    val density = resources.displayMetrics.density
                    if (!getBooleanSp("sidebar_lock_x", true)) setIntSp("sidebar_pos_x_dp", (config.offsetX / density).roundToInt())
                    if (!getBooleanSp("sidebar_lock_y", false)) setIntSp("sidebar_pos_y_dp", (config.offsetY / density).roundToInt())
                }
                touchMode = 0
            }
        }
        return true
    }

    override fun onDown(e: MotionEvent): Boolean {
        return false
    }

    override fun onShowPress(e: MotionEvent) {

    }

    override fun onSingleTapUp(e: MotionEvent): Boolean {
        chooseAppFloatingView.showFloatingView()
        removeFloating()
        isShowingChooseApp = true
        return false
    }

    override fun onScroll(
        e1: MotionEvent,
        e2: MotionEvent,
        distanceX: Float,
        distanceY: Float
    ): Boolean {
        touchMode = SCROLL
        return false
    }

    override fun onLongPress(e: MotionEvent) {

    }

    override fun onFling(
        e1: MotionEvent,
        e2: MotionEvent,
        velocityX: Float,
        velocityY: Float
    ): Boolean {
        return false
    }

    override fun onChooseAppWindowRemove() {
        isShowingChooseApp = false
        if (getBooleanSp("show_floating", true) && !isShowingFloating) {
            showFloating()
        }
    }

    companion object {
        private const val TAG = "KeepAliveService"
        private const val SCROLL = 1
        const val SERVICE_TYPE = 0
    }

    /**
     * Floating-button configuration class
     */
    data class FloatingConfig(
        //Vertical button's x-coordinate, -1 left, 1 right
        var positionX: Int,
        //Y-axis pixel offset, screen center is 0, draggable up/down to ±screenHeight/2
        var offsetY: Int,
        //X-axis pixel offset, distance from the edge, draggable outward only 0 ~ (screenWidth-width)/2
        var offsetX: Int,
        //Sidebar transparency
        var alpha: Float
    )
}
