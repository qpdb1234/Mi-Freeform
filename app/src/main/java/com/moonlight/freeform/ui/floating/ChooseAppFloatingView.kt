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
import android.net.Uri
import android.os.UserHandle
import android.os.UserManager
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.*
import android.widget.LinearLayout
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.promeg.pinyinhelper.Pinyin
import com.moonlight.freeform.R
import com.moonlight.freeform.room.FreeFormAppsEntity
import com.moonlight.freeform.ui.freeform.FreeformHelper
import com.moonlight.freeform.ui.view.WaveSideBarView
import com.moonlight.freeform.utils.PackageUtils
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
    //服务
    private val windowManager: WindowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val displayManager: DisplayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private val defaultDisplay: Display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)

    //scope
    private val scope = MainScope()

    //处理多用户
    private val launcherApps: LauncherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
    private val userManager: UserManager = context.getSystemService(Context.USER_SERVICE) as UserManager
    private val userHandleMap = HashMap<Int, UserHandle>()

    private val chooseAppFloatingViewModel = ChooseAppFloatingViewModel(context)

    //侧边栏面板的数据缓存。
    //点常驻通知/点把手都要求「点了立刻出面板」，而 Room 首查 + queryIntentActivities +
    //逐个包的安装校验串起来是几百毫秒，全压在 addView 之前，用户看到的就是明显的延迟。
    //所以这些数据一律在后台线程取（服务启动时预热，见 prewarm），点击时只做主线程能秒完成的事。
    private var cachedApps: ArrayList<FreeFormAppsEntity>? = null
    private var cachedLaunchers: List<ResolveInfo>? = null
    // 面板每一行的图标/名称/启动Activity，同样在后台算好（key 见 floatingItemKey）
    private var cachedItemData: Map<String, FloatingItemData> = HashMap()
    //正在后台取数据，避免预热与点击两个入口同时发起同一份加载
    private var isLoadingCache = false
    //加载期间排上的绑定请求（预热还没跑完就被点开）
    private var pendingBind: ((ArrayList<FreeFormAppsEntity>) -> Unit)? = null
    //当前面板里的应用列表，缓存刷新后用它原地换数据
    private var appsRecyclerView: RecyclerView? = null
    //开面板后排队的后台校对任务
    private var verifyJob: Job? = null
    //面板窗口是否已经挂在 WindowManager 上
    private var windowAttached = false
    //面板里现在绑的是哪一份数据，同样的数据不重绑（重绑会闪）
    private var boundApps: List<FreeFormAppsEntity>? = null

    private var floatingView: View? = null
    private var floatingViewLayoutParams = WindowManager.LayoutParams()
    private var allAppsView: View? = null

    //物理屏幕方向，1竖屏，2横屏
    private var screenRotation = context.resources.configuration.orientation

    //所有应用列表
    private var allAppsList = ArrayList<LauncherActivityInfo>()
    //使用拼音排序
    private var appsPinyinMap = HashMap<String, String>()

    /** 面板上「全部应用」那一行与编辑入口的点击回调，抽出来复用 */
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
     * 服务启动后调一下：把面板要用的数据先在后台取好。
     * 侧边栏一般是服务起来之后才被点开，这几百毫秒用户在别的地方，感知不到。
     */
    fun prewarm() {
        loadCache(null, true)
    }

    fun showFloatingView() {
        // 点了就要有反馈：先把面板挂上去。预热成功时整棵树（包含列表与 adapter）已经搭好，
        // 这一步只剩一次 addView，中间没有 IO 等待、没有 Room 首查、没有 PackageManager 调用。
        addFloatingWindow()
        val apps = cachedApps
        if (apps != null && cachedLaunchers != null) {
            // 预热时已经绑过了，这里一般什么都不做；没绑过（冷启动）才补一次绑定
            bindAppList(apps)
            // 缓存可能在应用被卸载/新增后过期，后台校对一次；只有内容真变了才重绑，不会闪。
            scheduleVerify()
        } else {
            // 冷数据（服务刚启动就被点开，预热还没跑完）：数据到了再填列表。
            loadCache({ bindAppList(it) }, false)
        }
    }

    /**
     * 数据到位后在主线程把整棵面板树搭好（含列表容器与 adapter）。
     * 只搭 view，不碰 WindowManager：窗口首帧是系统的开销，提前不了多少，
     * 但 inflate + 建列表这部分是我们自己的，能提前就提前。
     */
    private fun warmPanelTree() {
        if (floatingView != null) return
        val apps = cachedApps ?: return
        // 没悬浮窗权限时不预热，那是用户点开时才该发生的事
        if (!Settings.canDrawOverlays(context)) return
        floatingView = LayoutInflater.from(context).inflate(R.layout.view_choose_app_floating, null, false)
        bindAppList(apps)
    }

    /**
     * 后台校对用的读取很重（Room + 全量 queryIntentActivities + 图标解码），
     * 正开着面板的第一帧要做的全是主线程的活，这时抢CPU会把面板拖慢。
     * 所以等第一帧画完、过了动画再说。
     */
    private fun scheduleVerify() {
        verifyJob?.cancel()
        verifyJob = scope.launch {
            delay(1500)
            loadCache(null, true)
        }
    }

    /**
     * 后台把面板要用的数据取进内存缓存。
     *
     * @param onLoaded 取到数据后在主线程的回调；只在冷启动路径传，null 表示本次是预热/校对
     * @param verifyOnly true = 与现有缓存比对，只有列表内容变了才重绑正开着的面板
     */
    private fun loadCache(onLoaded: ((ArrayList<FreeFormAppsEntity>) -> Unit)?, verifyOnly: Boolean) {
        if (isLoadingCache) {
            // 预热还在跑：冷启动路径必须把回调排上，数据到位时补一次绑定，
            // 不然用户看到的就是一个永远填不满的空面板。
            if (!verifyOnly && onLoaded != null) pendingBind = onLoaded
            return
        }
        if (!verifyOnly && cachedApps != null) {
            // 已经有缓存了还走冷路径，说明另一个入口刚把缓存填上；没什么可等的
            if (onLoaded != null) onLoaded(cachedApps!!)
            return
        }
        isLoadingCache = true
        scope.launch(Dispatchers.IO) {
            val loaded = runCatching { readAppsAndLaunchers() }.getOrNull()
            isLoadingCache = false
            if (loaded == null) {
                // 读取失败：冷启动路径下给用户一个空面板也比什么都不填好
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
                    // 预热成功：顺手把面板的视图树搭好，点开时就不用再 inflate + 建列表
                    warmPanelTree()
                    if (verifyOnly && appsRecyclerView != null && old != null &&
                        !sameAppList(old, loaded.first)
                    ) {
                        // 面板正开着且列表确实变了（卸载/新增应用），原地换一份数据
                        bindAppList(loaded.first)
                    }
                }
            }
        }
    }

    /**
     * 只在后台线程调用：读 Room、剔除已卸载的应用、排序、查可启动的 Activity。
     * 这些全是要花几百毫秒的活，绝不能出现在点击到 addView 之间。
     */
    private suspend fun readAppsAndLaunchers():
            Triple<ArrayList<FreeFormAppsEntity>, List<ResolveInfo>, Map<String, FloatingItemData>> {
        val apps = chooseAppFloatingViewModel.getAllFreeFormApps().first() as ArrayList<FreeFormAppsEntity>?
            ?: ArrayList()

        //删除已经卸载的app
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
        // 全量 queryIntentActivities：本项目的适配器以前在构造器里做这件事，正卡在点击链路上
        val launchers = context.packageManager.queryIntentActivities(launchIntent, 0)

        // 把面板要显示的每一行的图标、应用名、启动Activity都在这里算好。
        // 这些活（LauncherApps binder + 图标解码）以前全在 onBindViewHolder 里，
        // 也就是落在面板首帧的 measure/layout 里：实测7行就花了481毫秒，
        // 用户看到的就是「点了但面板没出来」。
        val itemData = HashMap<String, FloatingItemData>()
        val rows = ArrayList<Pair<String, Int>>()
        apps.forEach { rows.add(Pair(it.packageName, it.userId)) }
        //最小化中的那几个应用也是面板内容
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

    /** 与适配器一致：同一个包有多个可启动 Activity 时取最后一个。 */
    private fun lastLaunchActivity(launchers: List<ResolveInfo>, packageName: String): String {
        var activityName = ""
        launchers.forEach {
            if (it.activityInfo.applicationInfo.packageName == packageName) {
                activityName = it.activityInfo.name
            }
        }
        return activityName
    }

    /** 两份列表是否是同一批应用（包名 + 用户 id + 排序位），用来决定要不要重绑。 */
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
     * 把应用列表绑到面板上。第一次调用时创建列表容器，之后只换 adapter。
     */
    private fun bindAppList(apps: List<FreeFormAppsEntity>) {
        val root = floatingView ?: return
        // 数据没变就不重绑：面板树是提前搭好的，重绑一遍反而会让列表闪一下
        val last = boundApps
        if (appsRecyclerView != null && last != null && sameAppList(last, apps)) return
        val recyclerView = appsRecyclerView ?: createRecyclerContainer(root) ?: return
        recyclerView.adapter = buildAdapter(apps)
        boundApps = apps
    }

    /**
     * 只有第一次真正要显示应用列表时才建列表容器。
     * 放在 bindAppList 而不是 addFloatingWindow 里，是为了点下去能先把窗口挂上去。
     */
    private fun createRecyclerContainer(root: View): RecyclerView? {
        //TODO 这里有一个奇怪的事情：当下面的xml布局为google的cardview时，就会解析失败，但是用androidx的就可以
        val recyclerAppsLayout = LayoutInflater.from(context)
            .inflate(R.layout.view_choose_app_floting_view_recycler_app, null, false)
        val recyclerView: RecyclerView = recyclerAppsLayout.findViewById(R.id.recycler_view) ?: return null
        //添加到界面
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
            // 冷数据路径下可能还没查到，给个空列表；数据到位后 loadCache 会重绑一次
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
     * 把面板挂上窗口。视图树一般已经由 warmPanelTree（或上一次打开）搭好了，
     * 这里只剩算一遍窗口参数 + addView；只有预热没成功时才会在这里现建树。
     */
    private fun addFloatingWindow() {
        if (windowAttached) return
        if (floatingView == null) {
            floatingView = LayoutInflater.from(context).inflate(R.layout.view_choose_app_floating, null, false)
        }
        //贴边方向与屏幕尺寸都可能在上次打开之后变过，窗口参数每次重算
        floatingViewLayoutParams.apply {
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
            //选择应用界面决定还是禁止在系统界面显示了
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
            // 0 = 不要窗口动画。原来是 Animation_Dialog（缩放+淡入约350毫秒），
            // 数据都预热好了以后，用户感觉到的「点了半天才出来」就剩这段动画了。
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
            windowAttached = false
            windowManager.removeViewImmediate(floatingView)
            if (Settings.canDrawOverlays(context)) {
                windowManager.addView(floatingView, floatingViewLayoutParams.apply {
                    type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                })
                windowAttached = true
            } else {
                //没权限：现建的这棵树留着也没用，丢掉，同时引导用户去授权
                floatingView = null
                appsRecyclerView = null
                boundApps = null
                // 窗口没挂上，一定要把「正开着」标记清掉，不然下一次点通知/把手会被挡掉
                removeCallback.onChooseAppWindowRemove()
                try {
                    Toast.makeText(context, context.getString(R.string.request_overlay_permission), Toast.LENGTH_LONG).show()
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    )
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(
                        intent
                    )
                } catch (e: Exception) {
                    Toast.makeText(context, context.getString(R.string.request_overlay_permission_fail), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * 屏幕方向改变监听
     */
    fun onScreenRotationChanged(newRotation: Int) {
        screenRotation = newRotation
        try {
            windowManager.removeViewImmediate(allAppsView)
        }catch (e: Exception) { }
        // 旧的树是按旋转前的尺寸与贴边方向搭的，整个丢掉重来
        discardPanelTree()
        removeCallback.onChooseAppWindowRemove()
        // 等旋转后的屏幕配置稳定下来再把面板树重新搭好，别让下一次点开还要现场 inflate
        scheduleWarmPanelTree()
    }

    /** 等旋转后的屏幕配置稳定下来再重新搭面板树。 */
    private fun scheduleWarmPanelTree() {
        scope.launch {
            delay(600)
            warmPanelTree()
        }
    }

    /**
     * 摘窗口并丢掉面板视图树（旋转/换边/服务销毁：这些情况下旧树的尺寸或方向不对）。
     *
     * @param notifyRemoved 要不要告诉服务「面板收起了」（把把手重新挂回来）；服务自己正在销毁时要传 false
     */
    private fun discardPanelTree(notifyRemoved: Boolean = true) {
        if (windowAttached) {
            try {
                windowManager.removeViewImmediate(floatingView)
            } catch (e: Exception) {}
            windowAttached = false
            // 面板是我们自己摘掉的，不是用户点的：服务那边的「正开着」标记必须跟着清掉，
            // 否则它会以为面板还在，下一次点通知/点把手被防重入挡掉，看起来就是点了没反应。
            if (notifyRemoved) {
                removeCallback.onChooseAppWindowRemove()
            }
        }
        floatingView = null
        appsRecyclerView = null
        boundApps = null
    }

    /** 服务销毁：面板的窗口与视图树都丢掉。 */
    fun dispose() {
        verifyJob?.cancel()
        // 本进程都不在了，别再回调过去把把手窗口重新挂上
        discardPanelTree(notifyRemoved = false)
        try {
            windowManager.removeViewImmediate(allAppsView)
        } catch (e: Exception) {}
        allAppsView = null
    }

    /** 设置里换了左右侧：面板要按新的那一侧重建。 */
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
                                }catch (e: Exception) {}

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

                    //点击外部关闭悬浮窗
                    allAppsView!!.setOnTouchListener { _, _ ->
                        try {
                            windowManager.removeViewImmediate(allAppsView)
                        } catch (e: Exception) {

                        }
                        removeCallback.onChooseAppWindowRemove()
                        true
                    }
                }
            }

            windowManager.addView(allAppsView, layoutParams)
        } else {
            try {
                Toast.makeText(context, context.getString(R.string.request_overlay_permission), Toast.LENGTH_LONG).show()
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                )
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(
                    intent
                )
            } catch (e: Exception) {
                Toast.makeText(context, context.getString(R.string.request_overlay_permission_fail), Toast.LENGTH_LONG).show()
            }
        }

    }

    /**
     * 关闭面板：只摘窗口，视图树与列表数据都留着——下次点开直接 addView 同一棵树。
     */
    private fun removeWindow() {
        if (windowAttached) {
            try {
                windowManager.removeViewImmediate(floatingView)
            } catch (e: Exception) {}
            windowAttached = false
        }
        removeCallback.onChooseAppWindowRemove()
    }

    /**
     * 根据序号排序
     */
    inner class AppsComparable: Comparator<FreeFormAppsEntity> {
        override fun compare(o1: FreeFormAppsEntity?, o2: FreeFormAppsEntity?): Int {
            return o1!!.sortNum.compareTo(o2!!.sortNum)
        }
    }

    /**
     * 根据拼音排序
     */
    inner class PinyinComparable : Comparator<LauncherActivityInfo>{
        override fun compare(o1: LauncherActivityInfo?, o2: LauncherActivityInfo?): Int {
            return appsPinyinMap[o1!!.label]!!.compareTo(appsPinyinMap[o2!!.label]!!)
        }
    }

    /**
     * 当叠加层移除时回调
     */
    interface OnWindowRemoveCallback {
        fun onChooseAppWindowRemove()
    }

    /**
     * 悬浮窗点击监听器
     */
    abstract class ClickListener {
        open fun onClick() {}
    }
}