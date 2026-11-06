package com.moonlight.freeform.ui.floating

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.ResolveInfo
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.UserHandle
import android.os.UserManager
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.*
import android.widget.LinearLayout
import androidx.annotation.RequiresApi
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.promeg.pinyinhelper.Pinyin
import com.moonlight.freeform.R
import com.moonlight.freeform.room.FreeFormAppsEntity
import com.moonlight.freeform.ui.freeform.FreeformHelper
import com.moonlight.freeform.ui.view.WaveSideBarView
import com.moonlight.freeform.utils.DiagnosticsLog
import com.moonlight.freeform.utils.PackageUtils
import com.moonlight.freeform.utils.PermissionUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.util.*
import kotlin.collections.ArrayList
import kotlin.collections.HashMap
import kotlin.math.max
import kotlin.math.min

/**
 * @date 2022/8/23
 * @author sunshine0523
 */
class ChooseAppFloatingView(
    private val context: Context,
    var showPositionX: Int,
    private val removeCallback: OnWindowRemoveCallback
){
    // Services
    private val windowManager: WindowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val displayManager: DisplayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private val defaultDisplay: Display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)

    //scope
    private val scope = MainScope()

    // Handle multi-user
    private val launcherApps: LauncherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
    private val userManager: UserManager = context.getSystemService(Context.USER_SERVICE) as UserManager
    private val userHandleMap = HashMap<Int, UserHandle>()

    private val chooseAppFloatingViewModel = ChooseAppFloatingViewModel(context)

    // The sidebar panel's data cache.
    // Tapping the ongoing notification / handle both require "show the panel instantly on tap", but the Room first query + queryIntentActivities +
    // per-package install check chained together take hundreds of ms, all pressed before addView, so the user sees an obvious delay.
    // Hence all this data is fetched on a background thread (prewarmed at service start, see prewarm); tapping only does what the main thread can finish instantly.
    private var cachedApps: ArrayList<FreeFormAppsEntity>? = null
    private var cachedLaunchers: List<ResolveInfo>? = null
    // The icon/name/launch-Activity for each panel row, also computed ahead on background (key see floatingItemKey)
    private var cachedItemData: Map<String, FloatingItemData> = HashMap()
    // Fetching data on background, to avoid the prewarm and tap entry points launching the same load simultaneously
    private var isLoadingCache = false
    // Bind requests queued during loading (panel opened before prewarm finished)
    private var pendingBind: ((ArrayList<FreeFormAppsEntity>) -> Unit)? = null
    // The app list currently bound in the panel; used to swap data in place after a cache refresh
    private var appsRecyclerView: RecyclerView? = null
    // Background verification task enqueued after the panel opens
    private var verifyJob: Job? = null
    // Whether the panel window is already attached to the WindowManager
    private var windowAttached = false
    // Which data snapshot is bound in the panel now; the same data isn't rebound (rebinding flickers)
    private var boundApps: List<FreeFormAppsEntity>? = null

    private var floatingView: View? = null
    private var floatingViewLayoutParams = WindowManager.LayoutParams()
    private var allAppsView: View? = null

    // Physical screen orientation, 1 portrait, 2 landscape
    private var screenRotation = context.resources.configuration.orientation

    // All-apps list
    private var allAppsList = ArrayList<LauncherActivityInfo>()
    // Sort by pinyin
    private var appsPinyinMap = HashMap<String, String>()

    /** Click callback for the panel's "all apps" row and the edit entry, extracted for reuse */
    private val openAllAppsCallback: ClickListener = object : ClickListener() {
        override fun onClick() {
            removeWindow()
            showAllAppsView()
        }
    }

    companion object {
        private const val TAG = "ChooseAppFloatingView"
    }

    init {
        userManager.userProfiles.forEach {
            userHandleMap[com.moonlight.freeform.systemapi.UserHandle.getUserId(it)] = it
        }
    }

    /**
     * Call this after the service starts: fetch the panel's data ahead on background.
     * The sidebar is usually opened only after the service is up, so during those hundreds of ms the user is elsewhere and won't perceive it.
     */
    fun prewarm() {
        loadCache(null, true)
    }

    fun showFloatingView() {
        // Tapping must give feedback: attach the panel first. On a successful prewarm the whole tree (with list and adapter) is already built,
        // so this step is just one addView, with no IO wait, no Room first query, and no PackageManager call in between.
        addFloatingWindow()
        val apps = cachedApps
        if (apps != null && cachedLaunchers != null) {
            // Already bound during prewarm; normally nothing to do here; only bind once if never bound (cold start)
            bindAppList(apps)
            // The cache may be stale after apps are uninstalled/added, so verify once on background; only rebind if content really changed, so it won't flicker.
            scheduleVerify()
        } else {
            // Cold data (opened right after service start, before prewarm finished): fill the list once data arrives.
            loadCache({ bindAppList(it) }, false)
        }
    }

    /**
     * Builds the whole panel tree on the main thread once data is ready (including the list container and adapter).
     * Only builds views, doesn't touch WindowManager: the window's first frame is a system cost that can't be pulled forward much,
     * but the inflate + list-creation part is ours, so we advance it whenever possible.
     */
    private fun warmPanelTree() {
        if (floatingView != null) return
        val apps = cachedApps ?: return
        // Without the overlay permission, don't prewarm—that should only happen when the user opens it
        if (!Settings.canDrawOverlays(context)) return
        floatingView = LayoutInflater.from(context).inflate(R.layout.view_choose_app_floating, null, false)
        bindAppList(apps)
    }

    /**
     * The background read is heavy (Room + full queryIntentActivities + icon decode),
     * while the first frame with the panel open is doing all main-thread work, so contending for CPU then would slow the panel.
     * So wait until the first frame is drawn and the animation is over.
     */
    private fun scheduleVerify() {
        verifyJob?.cancel()
        verifyJob = scope.launch {
            delay(1500)
            loadCache(null, true)
        }
    }

    /**
     * Fetches the panel's data into the in-memory cache on background.
     *
     * @param onLoaded main-thread callback once data is ready; passed only on the cold-start path, null means this run is a prewarm/verify
     * @param verifyOnly true = compare against the existing cache and only rebind the open panel if the list content changed
     */
    private fun loadCache(onLoaded: ((ArrayList<FreeFormAppsEntity>) -> Unit)?, verifyOnly: Boolean) {
        if (isLoadingCache) {
            // Prewarm still running: the cold-start path must enqueue the callback so it binds once data arrives,
            // otherwise the user sees an ever-empty panel.
            if (!verifyOnly && onLoaded != null) pendingBind = onLoaded
            return
        }
        if (!verifyOnly && cachedApps != null) {
            // Cache already exists yet we're on the cold path, meaning another entry just filled it; nothing to wait for
            if (onLoaded != null) onLoaded(cachedApps!!)
            return
        }
        isLoadingCache = true
        scope.launch(Dispatchers.IO) {
            val loaded = runCatching { readAppsAndLaunchers() }.getOrNull()
            isLoadingCache = false
            if (loaded == null) {
                // Read failed: on the cold-start path, giving the user an empty panel is still better than filling nothing
                pendingBind = null
                return@launch
            }
            val old = cachedApps
            cachedApps = loaded.first
            cachedLaunchers = loaded.second
            cachedItemData = loaded.third
            val pending = pendingBind
            pendingBind = null
            withContext(Dispatchers.Main) {
                if (pending != null) {
                    pending(loaded.first)
                } else {
                    // Prewarm succeeded: also build the panel's view tree so opening later won't need inflate + list creation
                    warmPanelTree()
                    if (verifyOnly && appsRecyclerView != null && old != null &&
                        !sameAppList(old, loaded.first)
                    ) {
                        // Panel is open and the list really changed (app uninstall/add): swap the data in place
                        bindAppList(loaded.first)
                    }
                }
            }
        }
    }

    /**
     * Call only on a background thread: read Room, remove uninstalled apps, sort, query launchable Activities.
     * All of these take hundreds of ms and must never appear between the tap and addView.
     */
    private suspend fun readAppsAndLaunchers():
            Triple<ArrayList<FreeFormAppsEntity>, List<ResolveInfo>, Map<String, FloatingItemData>> {
        val apps = chooseAppFloatingViewModel.getAllFreeFormApps().first() as ArrayList<FreeFormAppsEntity>?
            ?: ArrayList()

        // Delete uninstalled apps
        val noInstallAppsList = ArrayList<FreeFormAppsEntity>()
        apps.forEach {
            if (it.userId == 0) {
                if (!PackageUtils.hasInstallThisPackage(it.packageName, context.packageManager)) {
                    noInstallAppsList.add(it)
                }
            } else {
                val userHandle = if (userHandleMap.containsKey(it.userId)) userHandleMap[it.userId]!! else userHandleMap[0]!!
                if (!PackageUtils.hasInstallThisPackageWithUserId(it.packageName, launcherApps, userHandle)) {
                    noInstallAppsList.add(it)
                }
            }
        }
        noInstallAppsList.forEach {
            apps.remove(it)
        }
        if (noInstallAppsList.isNotEmpty()) {
            //Delete NOT install apps from db
            chooseAppFloatingViewModel.deleteNotInstall(noInstallAppsList)
        }

        Collections.sort(apps, AppsComparable())

        val launchIntent = Intent(Intent.ACTION_MAIN, null)
        launchIntent.addCategory(Intent.CATEGORY_LAUNCHER)
        // Full queryIntentActivities: this project's adapter used to do this in its constructor, right on the tap path
        val launchers = context.packageManager.queryIntentActivities(launchIntent, 0)

        // Compute the icon, app name, and launch Activity for each panel row right here.
        // These tasks (LauncherApps binder + icon decode) used to all be in onBindViewHolder,
        // i.e. landing in the panel's first-frame measure/layout: measured, just 7 rows cost 481ms,
        // so what the user saw was "tapped but the panel didn't appear".
        val itemData = HashMap<String, FloatingItemData>()
        val rows = ArrayList<Pair<String, Int>>()
        apps.forEach { rows.add(Pair(it.packageName, it.userId)) }
        // The apps currently minimized are also panel content
        runCatching {
            val stack = FreeformHelper.getMiniFreeformStackSet()
            for (i in 0 until stack.size()) {
                val config = stack.get(i).config
                rows.add(Pair(config.packageName, config.userId))
            }
        }
        rows.forEach { (packageName, userId) ->
            val key = floatingItemKey(packageName, userId)
            if (itemData.containsKey(key)) return@forEach
            runCatching {
                val userHandle = userHandleMap[userId] ?: userHandleMap[0]!!
                val appInfo = launcherApps.getApplicationInfo(packageName, 0, userHandle)
                val label = context.packageManager.getApplicationLabel(appInfo)
                FloatingItemData(
                    appInfo.loadIcon(context.packageManager),
                    if (userId == 0) label else "$label-${context.getString(R.string.fenshen)}$userId",
                    lastLaunchActivity(launchers, packageName)
                )
            }.onSuccess { itemData[key] = it }
        }
        return Triple(apps, launchers, itemData)
    }

    /** Consistent with the adapter: when a package has multiple launchable Activities, take the last one. */
    private fun lastLaunchActivity(launchers: List<ResolveInfo>, packageName: String): String {
        var activityName = ""
        launchers.forEach {
            if (it.activityInfo.applicationInfo.packageName == packageName) {
                activityName = it.activityInfo.name
            }
        }
        return activityName
    }

    /** Whether the two lists contain the same batch of apps (package name + user id + sort position), used to decide whether to rebind. */
    private fun sameAppList(a: List<FreeFormAppsEntity>, b: List<FreeFormAppsEntity>): Boolean {
        if (a.size != b.size) return false
        for (i in a.indices) {
            if (a[i].packageName != b[i].packageName || a[i].userId != b[i].userId || a[i].sortNum != b[i].sortNum) {
                return false
            }
        }
        return true
    }

    /**
     * Binds the app list to the panel. Creates the list container on the first call, then only swaps the adapter.
     */
    private fun bindAppList(apps: List<FreeFormAppsEntity>) {
        val root = floatingView ?: return
        // Don't rebind when data hasn't changed: the panel tree is pre-built, and rebinding would make the list flicker
        val last = boundApps
        if (appsRecyclerView != null && last != null && sameAppList(last, apps)) return
        val recyclerView = appsRecyclerView ?: createRecyclerContainer(root) ?: return
        recyclerView.adapter = buildAdapter(apps)
        boundApps = apps
    }

    /**
     * Creates the list container only on the first time we actually need to show the app list.
     * Placing it in bindAppList rather than addFloatingWindow lets the window be attached first on tap.
     */
    private fun createRecyclerContainer(root: View): RecyclerView? {
        //TODO there's a strange thing here: when the xml layout below uses Google's cardview, parsing fails, but using the androidx one works
        val recyclerAppsLayout = LayoutInflater.from(context)
            .inflate(R.layout.view_choose_app_floting_view_recycler_app, null, false)
        val recyclerView: RecyclerView = recyclerAppsLayout.findViewById(R.id.recycler_view) ?: return null
        //Add to the UI
        val contain = if (showPositionX == -1) root.findViewById<LinearLayout>(R.id.recycler_view_contain_left)
            else root.findViewById<LinearLayout>(R.id.recycler_view_contain_right)
        contain?.addView(recyclerAppsLayout)
        recyclerView.layoutManager = LinearLayoutManager(context)
        appsRecyclerView = recyclerView
        return recyclerView
    }

    private fun buildAdapter(apps: List<FreeFormAppsEntity>): ChooseAppFloatingAdapter {
        return ChooseAppFloatingAdapter(
            context,
            apps,
            // On the cold-data path this may not be queried yet, so pass an empty list; loadCache rebinds once data arrives
            cachedLaunchers ?: ArrayList<ResolveInfo>(),
            userHandleMap,
            cachedItemData,
            object: ClickListener() {
                override fun onClick() {
                    removeWindow()
                }
            },
            openAllAppsCallback
        )
    }

    /**
     * Attaches the panel to a window. The view tree is usually already built by warmPanelTree (or a previous open),
     * so this only recomputes window params + addView; the tree is built here from scratch only when prewarm didn't succeed.
     */
    private fun addFloatingWindow() {
        if (windowAttached) return
        // Decide before attaching: the panel uses the ordinary overlay window type (see the comment above); without permission **don't call addView once**.
        // The old form blindly sent once and only checked permission after an exception, and that failed request on Android 14 would
        // permanently hide all non-system overlay windows this process later creates (see PermissionUtils.pickOverlayWindowType).
        if (!Settings.canDrawOverlays(context)) {
            Log.w(TAG, "panel_start_rejected no_overlay_permission")
            dropPanelAndAskForPermission(context.getString(R.string.scene_open_panel_failed))
            return
        }
        if (floatingView == null) {
            floatingView = LayoutInflater.from(context).inflate(R.layout.view_choose_app_floating, null, false)
        }
        // The edge-alignment side and screen size may both have changed since the last open, so recompute window params every time
        floatingViewLayoutParams.apply {
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
            // After deliberation, the app-picker screen still forbids showing over system UI
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            format = PixelFormat.RGBA_8888
            gravity = Gravity.CENTER_VERTICAL

            val width = if (defaultDisplay.rotation == Surface.ROTATION_0 || defaultDisplay.rotation == Surface.ROTATION_180) {
                min(context.resources.displayMetrics.heightPixels, context.resources.displayMetrics.widthPixels)
            } else {
                max(context.resources.displayMetrics.heightPixels, context.resources.displayMetrics.widthPixels)
            }
            x = width / 2
            // 0 = no window animation. It used to be Animation_Dialog (scale + fade ~350ms);
            // once data is fully prewarmed, the only remaining "tapped but took forever to appear" is this animation.
            windowAnimations = 0
            alpha = 1f
        }

        //click out to remove
        floatingView?.setOnClickListener {
            removeWindow()
        }

        try {
            windowManager.addView(floatingView, floatingViewLayoutParams)
            windowAttached = true
        } catch (e: Exception) {
            Log.w(TAG, "panel_add_view_failed", e)
            windowAttached = false
            // When it wasn't attached this line must throw; it must not propagate the exception back to the caller (that would crash the service process)
            try {
                windowManager.removeViewImmediate(floatingView)
            } catch (removeException: Exception) {
                Log.w(TAG, "panel_remove_half_attached_view_failed", removeException)
            }
            // Don't retry: the panel is fixed to the ordinary overlay window type, so retrying means sending the same request again,
            // and on this device each extra rejected request is another chance to poison overlay windows process-wide.
            DiagnosticsLog.recordRes(
                context, "overlay_rejected",
                R.string.diag_panel_attach_exception, e.javaClass.simpleName
            )
            dropPanelAndAskForPermission(context.getString(R.string.scene_panel_attach_failed))
        }
    }

    /**
     * When the panel can't be attached: drop the freshly built view tree, clear the "currently open" flag, and tell the user which permission is missing.
     *
     * Only informs, doesn't navigate to system settings (see [PermissionUtils.guideToGrantOverlayPermission]).
     */
    private fun dropPanelAndAskForPermission(scene: String) {
        // No permission: keeping this freshly built tree is pointless, drop it
        floatingView = null
        appsRecyclerView = null
        boundApps = null
        // The window isn't attached, so the "currently open" flag must be cleared, otherwise the next tap on the notification/handle would be blocked
        removeCallback.onChooseAppWindowRemove()
        PermissionUtils.guideToGrantOverlayPermission(context, scene)
    }

    /**
     * Screen orientation change listener
     */
    fun onScreenRotationChanged(newRotation: Int) {
        screenRotation = newRotation
        try {
            windowManager.removeViewImmediate(allAppsView)
        }catch (e: Exception) {
            Log.w(TAG, "remove_all_apps_view_failed(rotation)", e)
        }
        // The old tree was built for the pre-rotation size and edge-alignment, so discard it entirely and rebuild
        discardPanelTree()
        removeCallback.onChooseAppWindowRemove()
        // Wait until the post-rotation screen config stabilizes before rebuilding the panel tree, so the next open doesn't have to inflate on the spot
        scheduleWarmPanelTree()
    }

    /** Waits until the post-rotation screen config stabilizes, then rebuilds the panel tree. */
    private fun scheduleWarmPanelTree() {
        scope.launch {
            delay(600)
            warmPanelTree()
        }
    }

    /**
     * Detaches the window and discards the panel view tree (rotation / side change / service destroy: in these cases the old tree's size or orientation is wrong).
     *
     * @param notifyRemoved whether to tell the service "the panel is collapsed" (so it re-attaches the handle); pass false when the service itself is being destroyed
     */
    private fun discardPanelTree(notifyRemoved: Boolean = true) {
        if (windowAttached) {
            try {
                windowManager.removeViewImmediate(floatingView)
            } catch (e: Exception) {
                // A panel window that failed to detach is a zombie window: invisible, untappable, yet still holding a surface.
                Log.w(TAG, "discard_panel_window_failed", e)
            }
            windowAttached = false
            // We collapsed the panel ourselves, not the user: the service's "currently open" flag must be cleared too,
            // otherwise it thinks the panel is still up and the next tap on the notification/handle is blocked by re-entry protection, looking like a dead tap.
            if (notifyRemoved) {
                removeCallback.onChooseAppWindowRemove()
            }
        }
        floatingView = null
        appsRecyclerView = null
        boundApps = null
    }

    /** Service destroyed: discard both the panel's window and view tree. */
    fun dispose() {
        verifyJob?.cancel()
        // This process is gone, so don't call back to re-attach the handle window
        discardPanelTree(notifyRemoved = false)
        try {
            windowManager.removeViewImmediate(allAppsView)
        } catch (e: Exception) {
            Log.w(TAG, "remove_all_apps_view_failed(dispose)", e)
        }
        allAppsView = null
    }

    /** The left/right side was changed in settings: the panel must be rebuilt for the new side. */
    fun onSideChanged() {
        discardPanelTree()
        warmPanelTree()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showAllAppsView() {
        if (Settings.canDrawOverlays(context)) {
            val point = Point()
            val dm = DisplayMetrics()
            windowManager.defaultDisplay.getSize(point)
            windowManager.defaultDisplay.getMetrics(dm)

            var realWidth = 0
            var realHeight = 0
            var overlayWidth = 0
            var overlayHeight = 0

            if (defaultDisplay.rotation == Surface.ROTATION_90 || defaultDisplay.rotation == Surface.ROTATION_270) {
                realWidth = max(point.x, point.y)
                realHeight = min(point.x, point.y)
                overlayWidth = realWidth / 2
                overlayHeight = realHeight
            } else {
                realWidth = min(point.x, point.y)
                realHeight = max(point.x, point.y)
                overlayWidth = realWidth / 4 * 3
                overlayHeight = realHeight / 3 * 2
            }

            allAppsView = LayoutInflater.from(context).inflate(R.layout.view_all_apps, null, false)
            val layoutParams = WindowManager.LayoutParams().apply {
                width = overlayWidth
                height = overlayHeight
                type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                format = PixelFormat.RGBA_8888
                gravity = Gravity.CENTER_VERTICAL
            }
            val recyclerView = allAppsView!!.findViewById<RecyclerView>(R.id.recyclerView)

            scope.launch(Dispatchers.IO) {
                allAppsList.clear()
                userManager.userProfiles.forEach {
                    allAppsList.addAll(launcherApps.getActivityList(null, it))
                }

                allAppsList.forEach {
                    appsPinyinMap[it.label.toString()] = Pinyin.toPinyin(it.label[0])
                }
                Collections.sort(allAppsList, PinyinComparable())

                withContext(Dispatchers.Main) {
                    val adapter = AllAppsAdapter(
                        context,
                        allAppsList,
                        object : ClickListener() {
                            override fun onClick() {
                                try {
                                    windowManager.removeViewImmediate(allAppsView)
                                }catch (e: Exception) {
                                    Log.w(TAG, "remove_all_apps_view_failed(click)", e)
                                }

                                removeCallback.onChooseAppWindowRemove()
                            }
                        }
                    )
                    recyclerView.layoutManager = GridLayoutManager(context, 3)
                    recyclerView.adapter = adapter

                    allAppsView!!.findViewById<WaveSideBarView>(R.id.waveSideBarView).setOnTouchLetterChangeListener {
                        val pos = adapter.getIndex(it)
                        if(pos != -1){
                            recyclerView.scrollToPosition(pos)
                            val layoutManager = recyclerView.layoutManager as GridLayoutManager
                            layoutManager.scrollToPositionWithOffset(pos, 0)
                        }
                    }

                    // Tap outside to dismiss the overlay
                    allAppsView!!.setOnTouchListener { _, _ ->
                        try {
                            windowManager.removeViewImmediate(allAppsView)
                        } catch (e: Exception) {
                            Log.w(TAG, "remove_all_apps_view_failed(touch-outside)", e)
                        }
                        removeCallback.onChooseAppWindowRemove()
                        true
                    }
                }
            }

            windowManager.addView(allAppsView, layoutParams)
        } else {
            Log.w(TAG, "all_apps_start_rejected no_overlay_permission")
            PermissionUtils.guideToGrantOverlayPermission(context, context.getString(R.string.scene_all_apps_failed))
        }

    }

    /**
     * Close the panel: only detach the window, keeping the view tree and list data—next open just addViews the same tree.
     */
    private fun removeWindow() {
        if (windowAttached) {
            try {
                windowManager.removeViewImmediate(floatingView)
            } catch (e: Exception) {
                // If detaching fails the panel becomes permanently invisible, yet windowAttached is set to false,
                // so it will never try to detach again—this is the root of the "panel won't open and won't close" problem.
                Log.w(TAG, "remove_panel_window_failed", e)
            }
            windowAttached = false
        }
        removeCallback.onChooseAppWindowRemove()
    }

    /**
     * Sort by index
     */
    inner class AppsComparable: Comparator<FreeFormAppsEntity> {
        override fun compare(o1: FreeFormAppsEntity?, o2: FreeFormAppsEntity?): Int {
            return o1!!.sortNum.compareTo(o2!!.sortNum)
        }
    }

    /**
     * Sort by pinyin
     */
    inner class PinyinComparable : Comparator<LauncherActivityInfo>{
        override fun compare(o1: LauncherActivityInfo?, o2: LauncherActivityInfo?): Int {
            return appsPinyinMap[o1!!.label]!!.compareTo(appsPinyinMap[o2!!.label]!!)
        }
    }

    /**
     * Callback when the overlay is removed
     */
    interface OnWindowRemoveCallback {
        fun onChooseAppWindowRemove()
    }

    /**
     * Overlay click listener
     */
    abstract class ClickListener {
        open fun onClick() {}
    }
}