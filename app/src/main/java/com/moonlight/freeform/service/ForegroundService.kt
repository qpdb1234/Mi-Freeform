package com.moonlight.freeform.service

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.IBinder
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.*
import android.widget.Toast
import com.moonlight.freeform.R
import com.moonlight.freeform.app.MiFreeform
import com.moonlight.freeform.broadcast.StartFreeformReceiver
import com.moonlight.freeform.ui.floating.ChooseAppFloatingView
import com.moonlight.freeform.ui.floating.FloatingActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class ForegroundService : Service(), SharedPreferences.OnSharedPreferenceChangeListener, View.OnTouchListener, GestureDetector.OnGestureListener, ChooseAppFloatingView.OnWindowRemoveCallback {

    //应用内语言切换：切换语言时 SettingFragment 会重启本服务，重新走这里拿到新语言
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.moonlight.freeform.utils.LocaleHelper.wrap(newBase))
    }

    private lateinit var sp: SharedPreferences

    private val scope = MainScope()

    //是否正在展示悬浮按钮
    private var isShowingFloating = false
    //是否正在展示选择应用
    private var isShowingChooseApp = false
    //常驻通知是否已经挂上。挂过一次就不用每次 onStartCommand 都重建（点通知也是 onStartCommand）。
    private var notificationPosted = false

    //窗口管理器
    private lateinit var windowManager: WindowManager
    private lateinit var windowLayoutParams: WindowManager.LayoutParams

    //悬浮按钮配置
    private lateinit var config: FloatingConfig

    //屏幕宽高
    private var screenWidth: Int = 0
    private var screenHeight: Int = 0
    //上一次用来换算 dp 的显示密度。MIUI 的「显示大小」只会改密度不改像素尺寸，
    //单看 screenWidth/screenHeight 察觉不到，必须单独记住。
    private var cachedDensityDpi = 0

    //物理屏幕方向，1竖屏，2横屏
    private var screenRotation: Int = 0
    //通过shizuku服务获得的物理屏幕方向，0 1 2 3分别代表四个方向
    private var displayRotation = Surface.ROTATION_0

    //shizuku获取到的监听屏幕方向的服务
    private lateinit var iWindowManager: IWindowManager
    private lateinit var rotationWatcher: IRotationWatcher
    //旋转监听是否已经注册到 IWindowManager。未注册时销毁流程里不能去反注册。
    private var rotationWatcherRegistered = false

    private lateinit var gestureDetector: GestureDetector

    //悬浮按钮界面
    private lateinit var floatView: View

    //应用选择悬浮界面
    private lateinit var chooseAppFloatingView: ChooseAppFloatingView

    //触摸模式，标记是点击还是长按
    private var touchMode = 0

    private lateinit var displayManager: DisplayManager

    private var startFreeformReceiver = StartFreeformReceiver()

    //获取默认屏幕
    private lateinit var defaultDisplay: Display

    //屏幕监听
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {

        }
        override fun onDisplayRemoved(displayId: Int) {

        }

        /*
            20221208 屏幕旋转可以分为两种
            1.主动旋转，即手动将设备旋转。此时defaultDisplay.rotation是可以正确的反应屏幕方向的
            2.被动旋转，比如在竖屏状态下打开一个视频，或者在竖屏状态下打开横屏游戏，此时defaultDisplay.rotation无法反应正确的屏幕方向
            原因：Display#rotation反应的不是真实的方向，比如平板，就是横着是ROTATION_0
         */
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) {
                // 分辨率/显示大小改变（MIUI「显示大小」「屏幕分辨率」、折叠屏展开）不会走旋转回调，
                // 只有这里能拿到新尺寸。不重算的话 screenWidth 与已建好的窗口像素都停在旧值，
                // 设置页显示的 dp 与实际粗细就对不上了。
                // 注意这个回调可能早于本进程 resources 更新，读到的是旧密度而白跑一次；
                // 真正的兜底是下面的 onConfigurationChanged。
                scope.launch(Dispatchers.Main) { refreshDisplayGeometry() }
            }
        }
    }

    // MIUI「显示大小」只改密度、「屏幕分辨率」只改尺寸，都不伴随旋转，所以 rotationWatcher 那条路不走。
    // 实测（模拟器 wm density 440->320）DisplayListener 的回调到达时进程 resources 还是旧值，把手像素不变；
    // onConfigurationChanged 是系统把新配置刷进本进程之后才回调的，这里才是拿到新密度的可靠时机。
    // Service 不像 Activity 有 configChanges 声明，配置一变必定回调，直接在主线程执行。
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshDisplayGeometry()
    }

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()

        sp = getSharedPreferences(MiFreeform.APP_SETTINGS_NAME, Context.MODE_PRIVATE)
        sp.registerOnSharedPreferenceChangeListener(this)
        if (sp.getInt("service_type", ForegroundService.SERVICE_TYPE) == SERVICE_TYPE) {
            // 与 HookLauncher 的发送方同源（见该文件注释），否则桌面发的广播收不到。
            registerReceiver(
                startFreeformReceiver,
                IntentFilter("${MiFreeform.PACKAGE_NAME}.start_freeform")
            )

            //q221208.1 修复屏幕旋转后侧边栏不贴边的问题
            // rotationWatcher 本身不依赖 Shizuku，先建好；把它注册到 IWindowManager
            // 必须等 Shizuku 的 binder 到位，见下面 initRotationWatcher。
            rotationWatcher = object : IRotationWatcher.Stub() {
                override fun onRotationChanged(rotation: Int) {
                    scope.launch(Dispatchers.Main) {
                        displayRotation = rotation

                        //q220902.3 如果程序崩溃的话，那么resources.configuration.orientation获取到的方向是错误的，所以不应该用该方法
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
                            } catch (e: Exception) {}
                        }
                    }
                }
            }
            if (!initRotationWatcher()) {
                // 服务启动时 Shizuku 的 binder 可能还没到，这时直接调 IWindowManager 会抛
                // IllegalStateException: binder haven't been received，把进程带崩。改成等 binder 到达后再注册。
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
            // 提前把侧边栏面板要的数据在后台取好：不然用户第一次点通知/把手时，
            // Room 首查 + queryIntentActivities 全卡在点击到面板出现之间。
            chooseAppFloatingView.prewarm()
        } else {
            //如果不是前台服务模式，则关闭服务
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // onCreate 里判定「不是前台服务模式」时会直接 stopSelf，那一整分支的 lateinit 成员
        // （chooseAppFloatingView / windowManager / gestureDetector …）都不会被赋值。
        // 此时如果继续往下建通知、处理 ACTION_TOGGLE_FLOATING，就会抛
        // UninitializedPropertyAccessException 把服务进程带崩（用户侧表现为「点常驻通知闪退」），
        // 所以这里以 chooseAppFloatingView 是否就绪为准提前返回，并告诉系统不必重启本服务。
        if (!::chooseAppFloatingView.isInitialized) {
            Log.w(TAG, "onStartCommand skipped: service is not in foreground mode")
            return START_NOT_STICKY
        }

        //通知被点击（携带 ACTION_TOGGLE_FLOATING）时直接打开应用选择浮层，绕过 Activity 中转。
        //这一步必须排在通知重建之前：下面那段要建通知渠道、解码大图标、再走一次 startForeground，
        //都是主线程上的活，排在后面的话面板就得排队等它干完才出现（用户看到的延迟）。
        if (intent?.action == ACTION_TOGGLE_FLOATING) {
            openChooseAppFromEntry()
        }

        postForegroundNotification()

        return START_STICKY
    }

    /**
     * 挂上常驻通知。只在本实例真的挂过一次之后就不再重复：
     * 渠道创建 + 解码 ic_launcher 位图 + startForeground 都有开销，
     * 以前每次 onStartCommand（也就是用户每次点通知）都要重做一遍。
     */
    private fun postForegroundNotification() {
        if (notificationPosted) return

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        //点击常驻通知直接打开侧边栏的应用选择浮层。
        //这里用 getService 而不是 getActivity：以前点通知要先拉起 FloatingActivity，
        //而它会 setContentView + 慢速的 ActivityManager 存活校验后才写 to_show_floating 触发服务，
        //于是打开侧边栏存在可感知的延迟；现在通知直接把 Intent 投递给本服务的 onStartCommand，无延迟。
        val notificationIntent = Intent(this, ForegroundService::class.java).setAction(ACTION_TOGGLE_FLOATING)
        val pendingIntent = PendingIntent.getService(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)
        val builder = Notification.Builder(this.applicationContext,
            CHANNEL_ID
        )
        //创建通知渠道
        val channel = NotificationChannel(
            CHANNEL_ID,
                getString(R.string.foreground_notification_name),
                NotificationManager.IMPORTANCE_LOW
        )
        notificationManager.createNotificationChannel(channel)

        builder.setContentIntent(pendingIntent)
                .setLargeIcon(BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher))
                .setContentTitle(getString(R.string.foreground_notification_title))
                .setContentText(getString(R.string.foreground_notification_text))
                .setSmallIcon(R.drawable.tile_icon)
                .setWhen(System.currentTimeMillis())
        val notification = builder.build()
        notification.flags = Notification.FLAG_ONGOING_EVENT or Notification.FLAG_NO_CLEAR

        startForeground(3, notification)
        notificationPosted = true
    }

    override fun onDestroy() {
        super.onDestroy()
        // onCreate 可能只执行了一部分（service_type 不是前台服务时直接 stopSelf，
        // 或者 Shizuku binder 一直没到），lateinit 成员没初始化就去访问会抛
        // UninitializedPropertyAccessException，销毁时再崩一次会让服务反复重启。
        if (::displayManager.isInitialized) displayManager.unregisterDisplayListener(displayListener)
        if (isShowingFloating) removeFloating()
        if (::sp.isInitialized) sp.unregisterOnSharedPreferenceChangeListener(this)

        try {
            unregisterReceiver(startFreeformReceiver)
        } catch (e: Exception) {
        }

        Shizuku.removeBinderReceivedListener(binderReceivedListener)

        if (rotationWatcherRegistered && ::iWindowManager.isInitialized) {
            try {
                iWindowManager.removeRotationWatcher(rotationWatcher)
            } catch (e: Exception) {
            }
        }

        // 服务销毁：面板的窗口与预搭好的视图树一起丢掉
        if (::chooseAppFloatingView.isInitialized) chooseAppFloatingView.dispose()
    }

    /**
     * 取 IWindowManager 并把旋转监听注册上去。
     *
     * @return true = 已注册（含此前已注册）；false = Shizuku binder 还没到或注册失败，调用方需要稍后重试
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

    /**
     * 重新取一次屏幕尺寸/密度，变了就把把手按新几何摆回去。
     * 调用点有两个（DisplayListener 与 onConfigurationChanged），二者到达顺序与次数都没保证，
     * 所以先跟上次记的值比，真变了才动手，避免每次配置回调都重建窗口。
     */
    private fun refreshDisplayGeometry() {
        // service_type 不是前台服务模式时 onCreate 会直接 stopSelf，windowManager 等成员根本不会被赋值；
        // 无障碍模式下本服务也可能在 onCreate 前后收到配置回调。这两种情况下访问空 lateinit 会把进程带崩。
        if (!::windowManager.isInitialized || !::windowLayoutParams.isInitialized) return

        val dm = resources.displayMetrics
        val portrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        val newWidth = if (portrait) min(dm.widthPixels, dm.heightPixels) else max(dm.widthPixels, dm.heightPixels)
        val newHeight = if (portrait) max(dm.widthPixels, dm.heightPixels) else min(dm.widthPixels, dm.heightPixels)
        val newRotation = if (portrait) Configuration.ORIENTATION_PORTRAIT else Configuration.ORIENTATION_LANDSCAPE
        // 用 densityDpi 而不是 density 比较：整数值相等判断是精确的，浮点会因换算路径不同而不一致
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
            // 方向变了要换左右布局；密度变了要重新 inflate（背景圆角这类 dp 资源是在创建视图时换算的，
            // 只 updateViewLayout 的话窗口尺寸对了、圆角还停在旧密度）。两者都很罕见，重建的闪烁可以接受。
            // 只在确实显示着的时候移除：浮层已收起时 floatView 可能是从未赋值的 lateinit，
            // 不能依赖 removeFloating 里的 try/catch 去吞 UninitializedPropertyAccessException。
            if (isShowingFloating) removeFloating()
            initConfig()
            if (orientationChanged) {
                runCatching { chooseAppFloatingView.onScreenRotationChanged(screenRotation) }
            }
        } else if (isShowingFloating) {
            // 只是屏幕尺寸变了（密度没变）：原地改窗口大小与贴边基准即可，不重建、不闪烁。
            applyFloatingLayout()
        } else {
            initConfig()
        }
    }

    /**
     * 加载配置
     */
    private fun initConfig() {
        config = getFloatingConfig()
        if (getBooleanSp("show_floating", true) && !isShowingFloating && !isShowingChooseApp) {
            showFloating()
        }
    }

    // key 必须声明为可空：实测华为 HarmonyOS 在 sp.clear() 时给监听器回调的 key=null（AOSP 是逐 key 通知，
    // 所以模拟器永远不复现），而 Kotlin 非空参数会在方法入口插入 Intrinsics.checkNotNullParameter，
    // 在进函数体前就抛 NPE，runCatching 拦不住——真机「恢复默认设置」闪退的根因，已在手机上抓栈确认。
    // key=null 时 when 不匹配任何分支，后续 setDefaultValues 的逐 key 回调会完成把手重建。
    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        // 恢复默认设置会一次性 clear+重写大量 key，本回调会被高频触发；
        // 任一分支抛异常都会带崩共享进程，故整体兜底，单个 key 处理失败不影响其余。
        runCatching {
        when(key) {
            "show_floating" -> {
                // show_floating 为 true 时确保把手在显示（没显示才建），为 false 才移除。
                // 旧逻辑把「已显示时又收到 show_floating=true」也归到 else→removeFloating，
                // 而恢复默认设置会重写该 key，导致刚重建好的把手被误删且不再回来。
                if (getBooleanSp(key, false)) {
                    if (!isShowingFloating && !isShowingChooseApp) initConfig()
                } else {
                    removeFloating()
                }
            }
            "floating_position_x" -> {
                //换边：不销毁重建窗口（重建会走窗口进出动画，视觉上就是闪烁），原地更新位置与侧边距方向；
                //把手当前没显示时走 initConfig 正常新建。
                initConfig()
                chooseAppFloatingView.showPositionX = config.positionX
                // 面板树是按原来那一侧搭的，换边后要重建才会出现在新的那一侧
                chooseAppFloatingView.onSideChanged()
                if (isShowingFloating) applyFloatingLayout()
            }
            //Activity/通知传来的打开小窗监听
            "to_show_floating" -> openChooseAppFromEntry()
            //侧边栏长度/宽度/X轴位置/Y轴位置改变时，原地更新到新尺寸与位置。
            //不能 remove+add 销毁重建：那会走窗口进出动画，视觉上就是「拖完手/改完设置把手闪一下」；
            //而且拖动本身已经把窗口移到正确位置，重建纯属多余。
            "sidebar_length_dp", "sidebar_width_dp", "sidebar_pos_x_dp", "sidebar_pos_y_dp" -> {
                if (isShowingFloating) {
                    applyFloatingLayout()
                } else {
                    initConfig()
                }
            }
            "floating_alpha" -> {
                // 设置项语义是「透明度」：数值越大越透明。存的是 0~10 的透明度，
                // 换算成 WindowLayoutParams.alpha（0 全透明 ~ 1 不透明）= 1 - 透明度/10。
                config.alpha = 1f - getIntSp("floating_alpha", 7) / 10f
                if (isShowingFloating) {
                    try {
                        windowManager.updateViewLayout(
                            floatView,
                            windowLayoutParams.apply { alpha = config.alpha }
                        )
                    }catch (e: Exception) {}
                }
            }
        }
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

    /**
     * 从入口（点常驻通知 / to_show_floating）打开应用选择浮层。
     * 复用「点把手打开浮层」的逻辑；isShowingChooseApp 防止重复打开。
     */
    private fun openChooseAppFromEntry() {
        if (!isShowingChooseApp) {
            removeFloating()
            //修复 可以打开多个选择应用界面的情况 q220909.1
            isShowingChooseApp = true
            chooseAppFloatingView.showFloatingView()
        }
    }

    private fun showFloating() {
        floatView =
            if (config.positionX == 1) LayoutInflater.from(this).inflate(R.layout.view_floating_button_right, null, false)
            else LayoutInflater.from(this).inflate(R.layout.view_floating_button_left, null, false)
        val root = floatView.findViewById<View>(R.id.root)

        root.setOnTouchListener(this)

        //侧边栏尺寸可在设置里调整（dp），这里换算成像素；位置存的是像素偏移 offsetX/offsetY
        val density = resources.displayMetrics.density
        //宽度默认 8dp，与 settings.xml 的 android:defaultValue 一致
        val floatingButtonWidth = (getIntSp("sidebar_width_dp", 8) * density).roundToInt()
        val floatingButtonHeight = (getIntSp("sidebar_length_dp", 64) * density).roundToInt()

        if (Settings.canDrawOverlays(this)) {
            // 恢复默认设置时，服务会收到一连串 SP 变更回调，反复 remove/add overlay 窗口；
            // 真机上这会瞬时抛异常（view already added / token 失效等），不接住会把服务进程带崩，
            // 用户看到的就是整个应用“闪退”。这里兜住，最多是把手没显示，不拖垮进程。
            try {
                windowManager.addView(floatView, windowLayoutParams.apply {
                    //X：由「离边距」换算成窗口 x，几何细节见 handleWindowX
                    x = handleWindowX(config.offsetX, floatingButtonWidth)
                    //Y：屏幕中心为 0，可上下拖到 ±屏高/2
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
            try {
                Toast.makeText(this, getString(R.string.request_overlay_permission), Toast.LENGTH_LONG).show()
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${packageName}")
                )
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(
                    intent
                )
            } catch (e: Exception) {
                Toast.makeText(this, getString(R.string.request_overlay_permission_fail), Toast.LENGTH_LONG).show()
            }
        }

    }

    private fun removeFloating() {
        try {
            windowManager.removeViewImmediate(floatView)
        } catch (e: Exception) {

        }
        isShowingFloating = false
    }

    /**
     * 原地更新把手窗口的大小/位置/透明度，不销毁重建窗口（避免闪烁）。
     * 把手胶囊铺满整个窗口（布局里不留外边距），所以窗口贴边 == 可见部分贴边，
     * X 轴位置=0 就是真正的屏幕边缘；左右两侧几何一致，换边只看 positionX 的正负。
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
        }
    }

    /**
     * 「离边距 inset」-> 窗口 x。overlay 窗口没设 gravity，系统按屏幕中心摆放，
     * 实测 frame.left = (screenWidth - width) / 2 + x，所以先算想要的绝对左边缘、再减去这个居中基准。
     * 不能写成 positionX * ((screenWidth - width) / 2 - inset)：整数除法在奇数像素宽度下少 1，
     * 右侧那个基准又会被乘两次，于是 4dp(11px) 这类宽度在屏幕边留下 1px 缝，看着就像宽度不对。
     * positionX 只有 -1 表示左侧，其余（1 右侧、以及旧数据里的异常值）都按右侧处理。
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
            //dp -> 像素：Y 轴偏移（屏幕中心为 0，可为负）、X 轴离边缘的偏移
            (getIntSp("sidebar_pos_y_dp", 0) * density).roundToInt(),
            (getIntSp("sidebar_pos_x_dp", 0) * density).roundToInt(),
            //设置项语义是「透明度」（0~10，越大越透明），换算成 alpha = 1 - 透明度/10
            1f - getIntSp("floating_alpha", 7) / 10f
        )
    }

    private fun handleMove(dx: Float, dy: Float) {
        //锁定后对应轴不参与拖动：X 锁定时不改变离边距 offsetX，Y 锁定时不改变 offsetY
        val lockX = getBooleanSp("sidebar_lock_x", true)
        val lockY = getBooleanSp("sidebar_lock_y", false)
        if (!lockX) {
            //X 只能向外拉：离边距 0 ~ (screenWidth - width)/2。dx 向右为正，右侧(positionX=1)向右即离边更远，
            //左侧(positionX=-1)向右即贴边，故用 positionX 换算符号。
            val maxInset = (screenWidth - windowLayoutParams.width) / 2
            config.offsetX = max(0, min(maxInset, config.offsetX - (dx * config.positionX).roundToInt()))
        }
        if (!lockY) {
            //Y 的可拖范围跟着把手长度收窄：超过 (screenHeight - height)/2 后窗口会被系统卡在应用区
            //上下沿，再拖画面不动却会把偏移写大（设置页的滑条表示不了那个值），所以按可见范围夹住。
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

    //按下时的坐标
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
                    //把手像素偏移换算成 dp 存回，供设置里的位置 SeekBar 反映；锁定的轴不落库，保持锁定值
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
        private const val TAG = "ForegroundService"
        private const val SCROLL = 1
        private const val CHANNEL_ID = "CHANNEL_ID_SUNSHINE_FREEFORM_FOREGROUND"
        const val SERVICE_TYPE = 1
        //常驻通知点击后回投给本服务的 action：直接打开侧边栏，不走 Activity 中转（去延迟）
        const val ACTION_TOGGLE_FLOATING = "com.moonlight.freeform.action.TOGGLE_FLOATING"
    }

    /**
     * 悬浮按钮配置类
     */
    data class FloatingConfig(
        //垂直按钮横坐标，-1左，1右
        var positionX: Int,
        //Y 轴像素偏移，屏幕中心为 0，可上下拖到 ±屏高/2
        var offsetY: Int,
        //X 轴像素偏移，离边缘的距离，只能向外拖 0 ~ (屏宽-宽)/2
        var offsetX: Int,
        //侧边栏透明度
        var alpha: Float
    )
}
