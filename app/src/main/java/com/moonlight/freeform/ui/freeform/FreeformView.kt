package com.moonlight.freeform.ui.freeform

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.hardware.input.IInputManager
import android.net.Uri
import android.os.Build
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
    //服务
    private val windowManager: WindowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val displayManager: DisplayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private var activityTaskManager: IActivityTaskManager? = null
    private var activityManager: IActivityManager? = null
    private var inputManager: IInputManager? = null
    private var iWindowManager: IWindowManager? = null

    //ViewModel
    private val viewModel = FreeformViewModel(context)

    private val scope = MainScope()

    //默认屏幕，用于获取横竖屏状态
    private val defaultDisplay: Display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)

    //界面binding
    private lateinit var binding: ViewFreeformBinding

    //判断是否是初次启动，防止屏幕旋转时再初始化
    private var firstInit = true

    //该小窗是否已经销毁
    private var isDestroy = false

    /**
     * destroy() 是否已经真正执行过，仅用于幂等。
     *
     * 和 [isDestroy] 不是一回事：[isDestroy] 在 [moveTaskToDefaultDisplay] 里
     * 会被提前置位（用来让 Sui 钩子别把任务拉回小窗），那时 destroy() 还没执行；
     * 如果幂等也用 [isDestroy]，全屏时 destroy() 会被直接跳过，
     * 小窗就留在屏幕上、虚拟屏也停在全屏尺寸，下次打开应用画面会比小窗小一圈。
     */
    private var isDestroyed = false

    /** 供外部（再次启动同一应用时）调用的销毁入口，幂等。 */
    override fun destroyFreeform() {
        destroy()
    }

    //是否处于隐藏状态，当打开米窗的正在运行小窗界面时，应当隐藏所有小窗
    private var isHidden = false

    //是否处于后台挂起状态
    private var isBackstage = false

    /** 收起后的悬浮球；仅悬浮球模式下非空。 */
    private var miniBall: MiniBallView? = null

    //小窗中应用的taskId
    private var taskId = -1

    //叠加层Params
    private val windowLayoutParams = WindowManager.LayoutParams()

    //虚拟屏幕
    private lateinit var virtualDisplay: VirtualDisplay

    //物理屏幕方向
    private var screenRotation = defaultDisplay.rotation
    //虚拟屏幕方向，1 竖屏， 0 横屏
    private var virtualDisplayRotation = VIRTUAL_DISPLAY_ROTATION_PORTRAIT

    //用户是否手动旋转过小窗方向。为真时不让应用上报的 requestedOrientation 把窗口复位，
    //且收起/恢复（fromBackstage 不重置该标记）期间保持用户设定的方向。
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

//    //屏幕监听
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

    //触摸监听
    private val touchListener = TouchListener()
    private val touchListenerPreQ = TouchListenerPreQ()

    //屏幕宽高，不保证大小
    private var realScreenWidth = 0
    private var realScreenHeight = 0

    //小窗的“尺寸”，该尺寸只在小窗内屏幕方向改变时变化
    private var freeformScreenHeight = 0
    private var freeformScreenWidth = 0

    //小窗界面的宽高，该宽高不随着屏幕、小窗方向改变而改变，即h>w恒成立。该尺寸只在物理屏幕方向变化时变化
    private var freeformHeight = 0
    private var freeformWidth = 0

    //用户为「竖屏形态」与「横屏形态」分别设定的小窗长边。
    //为什么要分两份：
    //1) 转横屏时如果窗口比屏幕宽，onFreeFormRotationChanged / clampFreeformToScreen 会把
    //   freeformHeight 夹小，那只是为了不溢出屏幕的显示层限制，不是用户改了尺寸；
    //2) 横屏下调出来的尺寸也不该由竖屏的尺寸换算出来，否则「横屏调好 → 转回竖屏 → 再转横屏」
    //   尺寸又被换算了回去，用户看到的就是旋转一次尺寸变一次。
    //竖屏那份开屏就有值（就是开屏尺寸）；横屏那份为 0 表示「用户还没在横屏下调过」，
    //这时横屏沿用竖屏的尺寸，保证「没动过就不变」。
    //这两份记忆属于「当前打开的这个应用的小窗」：它是实例字段，每个应用各一份互不干扰，
    //小窗关闭（destroy，对象销毁）后记忆也随之消失，不会带到下一个应用、也不会留到下次开屏。
    private var userPortraitHeight = 0
    private var userLandscapeHeight = 0

    //max(freeformScreenHeight,freeformScreenWidth)，用于计算scale
    private var maxFreeformScreenSize = 0
    private var minFreeformScreenSize = 0

    //缩放比例
    private var scaleX: Float = 1f
    private var scaleY: Float = 1f

    // 顶栏三段手势：具体执行什么动作由设置决定（TopBarAction），
    // 不再写死。三个 detector 只负责把单击翻译成对应槽位的动作。
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
     * 按设置刷新顶栏三个槽位的图标。
     *
     * 顶栏布局里三个 LinearLayout 的 id 固定（位置固定），
     * 但每个位置显示什么图标、执行什么动作由 TopBarAction 决定。
     * 动作映射在 [handleTopBarSlot]，这里只负责把图标画成设置里的样子。
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
            // 选了「无」就藏掉图标，但触摸区仍在，点了没反应。
            icon.visibility = android.view.View.INVISIBLE
        }
    }

    /**
     * 执行顶栏某个槽位配置的动作。
     *
     * @param slot 0=第1位，1=第2位，2=第3位
     */
    private fun handleTopBarSlot(slot: Int) {
        val sp = context.getSharedPreferences(MiFreeform.APP_SETTINGS_NAME, Context.MODE_PRIVATE)
        runFreeformAction(TopBarAction.getSlotActionFromSp(sp, slot))
    }

    /**
     * 执行一个小窗动作。
     *
     * 顶栏三个槽位与「双击底栏中部按钮」共用这一套分发，
     * 保证同一种动作在入口不同（顶栏点击 / 底栏双击）时行为完全一致。
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
            TopBarAction.NONE -> { /* 无动作 */ }
        }
    }

    /** 当前小窗形态（横屏/竖屏）对应的用户设定长边。 */
    private fun userHeightForCurrentForm(): Int {
        if (virtualDisplayRotation == VIRTUAL_DISPLAY_ROTATION_LANDSCAPE) {
            //横屏没单独调过就沿用竖屏那份，这样「旋转」本身永远不会改变用户设定的尺寸
            return if (userLandscapeHeight > 0) userLandscapeHeight else userPortraitHeight
        }
        return userPortraitHeight
    }

    /** 记下用户为当前小窗形态设定的长边。 */
    private fun setUserHeightForCurrentForm(value: Int) {
        if (virtualDisplayRotation == VIRTUAL_DISPLAY_ROTATION_LANDSCAPE) {
            userLandscapeHeight = value
        } else {
            userPortraitHeight = value
        }
    }

    /** 用户没调过时的默认长边（与开屏尺寸同一套算法，随物理屏幕方向变）。 */
    private fun defaultUserHeight(): Int {
        return if (FreeformHelper.screenIsPortrait(screenRotation)) {
            (config.maxHeight * 0.75).roundToInt()
        } else {
            (config.maxHeight * 0.8).roundToInt()
        }
    }

    /** 小窗内返回。 */
    private fun pressBack() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MiFreeform.me?.getControlService()?.pressBack(virtualDisplay.display.displayId)
        } else {
            pressBackPreQ()
        }
    }

    /** 截屏：保存小窗当前画面到系统截图目录。 */
    private fun screenshot() {
        ScreenshotHelper.capture(context, binding.textureView, config.packageName)
    }

    /** 旋转小窗方向（竖屏/横屏切换）。 */
    private fun rotateFreeform() {
        virtualDisplayRotation = if (virtualDisplayRotation == VIRTUAL_DISPLAY_ROTATION_PORTRAIT) {
            VIRTUAL_DISPLAY_ROTATION_LANDSCAPE
        } else {
            VIRTUAL_DISPLAY_ROTATION_PORTRAIT
        }
        //用户手动旋转：标记为覆盖，后续应用上报的方向不再把窗口复位（收起/恢复也保持该方向）
        manualRotationOverride = true
        onFreeFormRotationChanged()
    }

    //新增 手动调整小窗方向 q220904.7
    /**
     * 底栏左圆点：按住滑动等比例缩放。
     *
     * 行为与按住左边缘缩放（[handleScaleLeft]）完全一致，
     * 保证「按圆点」和「按边缘」是同一种缩放手感。
     * 左圆点在窗口左下角：往下拉 = 放大，往上推 = 缩小；
     * 宽度始终按小窗宽高比从高度算出来（横向分量不单独改宽度），所以旋转往返不会变样。
     */
    private fun handleDotScaleLeft(dx: Float, dy: Float) {
        val tempHeight = freeformHeight + dy
        //宽度一律由高度按固定比例算出来（与按住边缘缩放一致），否则斜着拉会让宽高比
        //悄悄跑偏，旋转时按比例重算宽度就会把用户的小窗变成另一个大小。
        val tempWidth = (tempHeight * config.widthHeightRatio).roundToInt()
        if (tempHeight > MIN_HEIGHT && tempHeight <= config.maxHeight && tempWidth > MIN_WIDTH && tempWidth < realScreenWidth) {
            freeformHeight = tempHeight.roundToInt()
            freeformWidth = tempWidth
            notifyWindowScale()
        }
    }

    /**
     * 底栏右圆点：按住滑动等比例缩放。
     *
     * 行为与按住右边缘缩放（[handleScaleRight]）完全一致。
     * 右圆点在窗口右下角：往下拉 = 放大，往上推 = 缩小；宽度同样按高比例从高度算出来。
     */
    private fun handleDotScaleRight(dx: Float, dy: Float) {
        val tempHeight = freeformHeight + dy
        //同 handleDotScaleLeft：宽度由高度算出来，保持宽高比
        val tempWidth = (tempHeight * config.widthHeightRatio).roundToInt()
        if (tempHeight > MIN_HEIGHT && tempHeight <= config.maxHeight && tempWidth > MIN_WIDTH && tempWidth < realScreenWidth) {
            freeformHeight = tempHeight.roundToInt()
            freeformWidth = tempWidth
            notifyWindowScale()
        }
    }

    private val middleGestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            //双击底栏中部按钮：执行设置里配置的动作（默认旋转），与顶栏槽位共用同一分发
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
            //目前仅支持Android Q及以上版本
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                activityTaskManager?.registerTaskStackListener(taskStackListener)
            }
            activityManager = IActivityManager.Stub.asInterface(ShizukuBinderWrapper(SystemServiceHelper.getSystemService("activity")))
        }catch (e: Exception) {}
        try {
            inputManager = IInputManager.Stub.asInterface(ShizukuBinderWrapper(SystemServiceHelper.getSystemService("input")))
        }catch (e: Exception) {}
        try {
            iWindowManager = IWindowManager.Stub.asInterface(ShizukuBinderWrapper(SystemServiceHelper.getSystemService("window")))
        } catch (e: Exception) {}
    }

    private fun initConfig() {
        config.maxHeight = FreeformHelper.getDefaultHeight(context, defaultDisplay, config.widthHeightRatio)
        //config.widthHeightRatio = ...

        realScreenWidth = context.resources.displayMetrics.widthPixels
        realScreenHeight = context.resources.displayMetrics.heightPixels

        config.freeformDpi = FreeformHelper.getScreenDpi(context)
        //开屏：竖屏那份的基准就是开屏尺寸，横屏那份先留空（=沿用竖屏），等用户亲手缩放才落笔。
        //关闭小窗后对象销毁，两份记忆一起消失。
        freeformHeight = defaultUserHeight()
        freeformWidth = (freeformHeight * config.widthHeightRatio).roundToInt()
        userPortraitHeight = freeformHeight
        userLandscapeHeight = 0

        //优化 QQ和微信也支持缩放了 q220917.1
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
        //---------------客制化配置-----------------

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
            //修复 尝试修复记录的小窗大小异常的问题 q220904.1
            rememberHeight = min(max(MIN_HEIGHT, rememberHeight), config.maxHeight)
            freeformHeight = rememberHeight
            freeformWidth = (freeformHeight * config.widthHeightRatio).roundToInt()
            //记住的位置里存的就是用户设定值（见 destroy() 落库处）。
            //这里存的是物理屏幕方向的一个值，就是开屏该用的那个长边，所以只给竖屏那份起步；
            //横屏那份留空，等用户在横屏下亲手调过再记，没调过就一直沿用竖屏的尺寸。
            userPortraitHeight = rememberHeight
            userLandscapeHeight = 0
            scaleX = freeformHeight / maxFreeformScreenSize.toFloat()
        }

        //修复 开启记录位置选项后触控错位的问题 q220925.3
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

        // 顶栏：与底栏同一套写法（三个分段 + GestureDetector）。
        // 具体执行什么动作由设置决定，图标也跟着设置刷新。
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

        // 小窗画面从一开始就完全不透明：开屏 loading 已删除。
        // 以前这里把 alpha 置 0，再靠首帧计数渐变到 1，应用收起恢复后不重绘时
        // 永远等不到那几帧，小窗就停在透明状态，看起来就是白屏。
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
            return
        }

        screenListener.begin(object : ScreenListener.ScreenStateListener {
            override fun onScreenOn() {}

            //关闭屏幕隐藏小窗
            override fun onScreenOff() {
                //挂起状态无需更新
                //修复 在有正在运行程序的情况下锁屏，米窗崩溃的问题 q220902.1
                //优化 锁屏后小窗的状态 q220917.3
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

            //解锁恢复小窗
            override fun onUserPresent() {
                //挂起状态无需更新
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
        //冷启动监听
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
                    //启动失败
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
                // 画面直接显示，不再做首帧计数 + 淡入，也没有 loading 动画。
            }
        }
    }

    /**
     * 窗口尺寸比画面尺寸多出来的装饰，与教学小窗共用同一套算法，
     * 具体包含哪几项见 [FreeformWindowGeometry]。
     */
    private val windowChromeWidth: Int
        get() = FreeformWindowGeometry.chromeWidth(context)

    private val windowChromeHeight: Int
        get() = FreeformWindowGeometry.chromeHeight(context)

    private fun showWindow() {
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
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
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

        //恢复记录的位置
        if (config.rememberPosition) {
            windowLayoutParams.apply {
                x = config.rememberX
                y = config.rememberY
            }
        } else {
            //横屏移动到屏幕左侧显示小窗
            if (screenRotation == Surface.ROTATION_90 || screenRotation == Surface.ROTATION_270) {
                windowLayoutParams.apply {
                    x = (width - realScreenWidth) / 2
                    //往上移动一些
                    y = 0
                }
            }
        }

        try {
            windowManager.addView(binding.root, windowLayoutParams)
        } catch (e: Exception) {
            try {
                windowManager.removeViewImmediate(binding.root)
            } catch (e: Exception) {}

            if (Settings.canDrawOverlays(context)) {
                windowManager.addView(binding.root, windowLayoutParams.apply {
                    type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                })
            } else {
                destroy()
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
     * 禁用更新过渡动画
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
        } catch (e: Exception) { }
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
        } catch (e: Exception) { }
    }

    private fun onFreeFormRotationChanged() {
        //先把长边恢复到「转过去之后这个形态」的用户设定值，再按新方向摆放：
        //上一次转横屏时为塞进屏幕做的夹紧不该被当成新的尺寸，否则转回去就丢了高度；
        //而横屏下调出来的尺寸也不该被竖屏的尺寸换算掉，所以横竖各自记一份。
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

            //优化 小窗内部旋转时，如果旋转后的尺寸大于屏幕尺寸，则进行调整 q220904.3
            //小窗横屏-物理屏幕竖屏
            if (FreeformHelper.screenIsPortrait(screenRotation)) {
                if (freeformHeight > realScreenWidth) {
                    freeformHeight = realScreenWidth
                    freeformWidth = (freeformHeight * config.widthHeightRatio).roundToInt()
                    scaleX = freeformHeight / maxFreeformScreenSize.toFloat()
                    scaleY = scaleX
                }
            }
        }

        //旋转只改窗口外形，尽量不断面：先把纹理缓冲尺寸切到新方向，再 resize 虚拟屏，
        //避免用旧 buffer 尺寸造成中间黑帧；全程不动 textureView.alpha、不重建 Surface。
        if (binding.textureView.isAvailable) {
            binding.textureView.surfaceTexture?.setDefaultBufferSize(freeformScreenWidth, freeformScreenHeight)
        }
        resizeVirtualDisplay()
        updateWindowSize(false)
        //旋转后按当前物理屏幕夹紧，保证小窗完整可见、不被推出屏外
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

        //该赋值是不需要的，maxFreeformScreenSize只需要与max(freeformScreenHeight, freeformScreenWidth)同步即可 q220908.1
        //maxFreeformScreenSize = config.maxHeight

        //物理转屏会重算 config.maxHeight。用户没亲手调过尺寸时跟着新默认值走；
        //调过就沿用「当前小窗形态」的那一份设定（夹到新的上限内），不再无条件覆盖用户的尺寸。
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

            //修复转屏后屏幕dpi不正确的问题 q220908.2
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
     * 旋转后把小窗夹紧到当前物理屏幕内，保证完整可见、不被推出屏外。
     *
     * 窗口宽/高与 freeformHeight 是线性关系（窗口尺寸 = freeform 尺寸 + 固定装饰），
     * 所以直接解出不溢出时的 freeformHeight 上限并一次到位；
     * 之前按最小缩放比例逐轮缩的写法受迭代上限限制，会残留 1px 溢出。
     * 最后再把窗口位置 x/y 夹到 [0, 屏幕宽-窗口宽]/[0, 屏幕高-窗口高]。
     */
    private fun clampFreeformToScreen() {
        val scaleExtra = windowChromeWidth
        val chromeHeight = windowChromeHeight
        val portraitWindow = virtualDisplayRotation == VIRTUAL_DISPLAY_ROTATION_PORTRAIT
        val ratio = if (config.widthHeightRatio > 0f) config.widthHeightRatio else 1f

        //竖屏窗口：宽 = freeformWidth + scaleExtra，高 = freeformHeight + chromeHeight
        //横屏窗口：宽 = freeformHeight + scaleExtra，高 = freeformWidth + chromeHeight
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

        //用最终的 freeform 尺寸刷新窗口宽高（复用 updateWindowSize 的方向公式）
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
            //横屏移动到屏幕左侧显示小窗
            if (screenRotation == Surface.ROTATION_90 || screenRotation == Surface.ROTATION_270) {
                windowLayoutParams.apply {
                    x = (width - realScreenWidth) / 2
                }
            }
        }

        //非后台挂起状态才进行刷新
        if (!isBackstage) {
            windowManager.updateViewLayout(
                binding.root,
                windowLayoutParams
            )
        }
    }

    /**
     * 如果小窗无法控制了，可以尝试移动到屏幕中心以控制
     */
    override fun toScreenCenter() {
        windowLayoutParams.x = 0
        windowLayoutParams.y = 0
    }

    override fun moveToFirst() {
        windowManager.removeViewImmediate(binding.root)
        windowManager.addView(binding.root, windowLayoutParams)
        FreeformHelper.addFreeformToSet(this)
    }

    /**
     * 隐藏小窗到后台
     *
     * 收起有两种方式，由设置项 minimize_mode 决定：
     * - MODE_SIDEBAR：收进侧边栏列表（原有行为），完全移除窗口。
     * - MODE_BALL：变成悬浮球，在屏幕边缘显示应用图标，点击恢复。
     */
    private fun toBackstage() {
        windowManager.removeViewImmediate(binding.root)
        FreeformHelper.removeFreeformFromSet(this)
        FreeformHelper.addMiniFreeformToSet(this)
        isBackstage = true

        // 悬浮球模式：显示球，侧边栏模式：什么都不显示（原行为）。
        if (getMinimizeMode() == MINIMIZE_MODE_BALL) {
            miniBall?.dismiss()
            miniBall = MiniBallView(context, this)
            miniBall?.show()
        }
    }

    /** 收起方式：0=侧边栏，1=悬浮球。默认悬浮球，与 settings.xml 中 minimize_mode 的 defaultValue 一致。 */
    private fun getMinimizeMode(): Int {
        val sp = context.getSharedPreferences(MiFreeform.APP_SETTINGS_NAME, Context.MODE_PRIVATE)
        return sp.getInt(KEY_MINIMIZE_MODE, MINIMIZE_MODE_BALL)
    }

    /**
     * 从后台恢复
     */
    override fun fromBackstage() {
        // 悬浮球模式下，恢复小窗时要先把球收掉。
        miniBall?.dismiss()
        miniBall = null
        windowManager.addView(binding.root, windowLayoutParams)

        // 修复：收起再还原后小窗变透明 / 白屏。
        // toBackstage 走 removeViewImmediate，TextureView 的 SurfaceTexture 会被销毁；
        // 重新 addView 时系统会新建一个 SurfaceTexture，但旧的虚拟屏 Surface
        // 还指向已销毁的那一个，画面就一直是空的。这里重新把虚拟屏绑到新纹理上。
        resetSurfaceForRestore()

        FreeformHelper.removeMiniFreeformFromSet(this)
        FreeformHelper.addFreeformToSet(this)
        isBackstage = false
    }

    /**
     * 恢复小窗时重新绑定画面。
     *
     * 必须在 addView 之后调用：此时 TextureView 才可能拿到新的 SurfaceTexture。
     * 如果这一刻纹理还不可用，什么都不做就行——系统随后会回调
     * onSurfaceTextureAvailable，那里会用同一个公式重新绑上虚拟屏。
     * 不再像以前那样把 initFinish 置回 false 等首帧渐变 alpha：
     * 应用恢复后不重绘时那些帧根本不会来，小窗会永久停在透明（白屏）状态。
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

            // 锁屏时根容器会被置成透明，而挂起状态下那个分支不会跑，
            // 这里兜底把根容器恢复成不透明。
            binding.root.alpha = 1f
        } catch (e: Throwable) {
            android.util.Log.w("FreeformView", "restore_surface_failed", e)
        }
    }

    /**
     * 尝试将小窗内容全屏
     */
    private fun moveTaskToDefaultDisplay() {
        isDestroy = true
        virtualDisplay.resize(realScreenWidth, realScreenHeight, FreeformHelper.getScreenDpi(context))
        MiFreeform.me?.getControlService()?.execShell("am start -n ${config.packageName}/${config.activityName} --user ${config.userId} --display 0", false)
        destroy()
    }

    /**
     * 通知windowManager刷新缩放后的界面
     */
    private fun notifyWindowScale() {
        binding.root.scaleX = 1f
        binding.root.scaleY = 1f
        //这里是用户拖动缩放的唯一收尾点：他拉出来的就是「当前这个形态」的新设定尺寸，
        //只改写横/竖其中的一份，另一份原样保留。
        setUserHeightForCurrentForm(freeformHeight)
        scaleX = freeformHeight / maxFreeformScreenSize.toFloat()
        scaleY = freeformWidth / minFreeformScreenSize.toFloat()
        updateWindowSize(false)
    }

    //按下时的坐标
    private var lastX = -1f
    private var lastY = -1f
    //当前正在操作的界面id
    private var touchId = -1
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(v: View, event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                if (FreeformHelper.isShowingFirst(this)) {
                    handleDownEvent(v, event)
                } else {
                    //不是处于最上层要移动到最上层
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
            //左/右圆点：按下只记录起点，缩放在 handleMoveEvent 里按逐帧增量处理
            R.id.leftView, R.id.rightView -> {
                beforeScaleHeight = freeformHeight
            }
            R.id.middleView -> {
                middleGestureDetector.onTouchEvent(event)
            }

            R.id.leftScale, R.id.rightScale -> {
                beforeScaleHeight = freeformHeight
            }
            //顶栏：与底栏同一套分发逻辑
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
            //左圆点：逐帧增量缩放，与左边缘缩放同一通道、同一手感
            R.id.leftView -> {
                if (touchId == R.id.leftView) {
                    val dx = event.rawX - lastX
                    val dy = event.rawY - lastY
                    handleDotScaleLeft(dx, dy)
                    lastX = event.rawX
                    lastY = event.rawY
                }
            }
            //右圆点：逐帧增量缩放，与右边缘缩放同一通道、同一手感
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
                    // 已删除：贴屏幕四角时自动缩小挂起的逻辑。
                    // 用户反馈不希望移到屏幕角落时窗口被自动缩小，
                    // 移动就应该是纯粹的移动。
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
            //顶栏：移动阶段只喂给各自的 GestureDetector，位置固定不参与拖动
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
            //左圆点：抬手时刷新虚拟屏尺寸，与边缘缩放收尾一致
            R.id.leftView -> {
                if (touchId == R.id.leftView) notifyWindowScale()
            }
            //右圆点：抬手时刷新虚拟屏尺寸，与边缘缩放收尾一致
            R.id.rightView -> {
                if (touchId == R.id.rightView) notifyWindowScale()
            }
            R.id.leftScale, R.id.rightScale -> {
                notifyWindowScale()
            }
            //顶栏：抬手决定单击/长按生效
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

    //指本次与缩放前的比例
    private var mScale = 1f
    private var beforeScaleHeight = freeformHeight

    /**
     * 缩放处理
     * 优化 尝试更加流畅的缩放动画 q220904.8
     */
    private fun handleScaleLeft(dx: Float, dy: Float) {
        //缩小
        if (dx > 0 && dy < 0) {
            val tempHeight = freeformHeight + dy
            val tempWidth = freeformWidth - dx
            //在尺寸内
            if (tempHeight > MIN_HEIGHT && tempHeight <= config.maxHeight && tempWidth > MIN_WIDTH && tempWidth < realScreenWidth) {
                //优化 尝试更加跟手的缩放操作 q220904.6
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
        //放大
        else if (dx < 0 && dy > 0) {
            val tempHeight = freeformHeight + dy
            val tempWidth = freeformWidth - dx
            //在尺寸内
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
     * 缩放处理
     */
    private fun handleScaleRight(dx: Float, dy: Float) {
        //缩小
        if (dx < 0 && dy < 0) {
            val tempHeight = freeformHeight + dy
            val tempWidth = freeformWidth + dx
            //在尺寸内
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
        //放大
        else if (dx > 0 && dy > 0) {
            val tempHeight = freeformHeight + dy
            val tempWidth = freeformWidth + dx
            //在尺寸内
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
     * 移动位置处理
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

        }
    }

    private fun destroy() {
        // destroy 有多条入口（用户点关闭、任务被移除、任务跳到全屏、
        // 再次进入同一应用时清旧实例），重复进入会把虚拟屏 release 两次。
        //
        // 注意这里不能用 isDestroy 做幂等：moveTaskToDefaultDisplay() 会先把它置位
        // （让 Sui 钩子别把任务拉回小窗），那时 destroy() 还没执行；
        // 用它做守卫的话全屏时 destroy() 整个被跳过，小窗留在屏幕上、
        // 虚拟屏停在全屏尺寸，下次打开应用画面会比小窗小一圈。
        if (isDestroyed) return

        // 退出时一并收掉悬浮球，否则球会留在屏幕上变成孤儿。
        miniBall?.dismiss()
        miniBall = null
        //记录位置
        if (viewModel.getBooleanSp("remember_freeform_position", false)) {
            val sp = context.getSharedPreferences(MiFreeform.APP_SETTINGS_NAME, Context.MODE_PRIVATE)
            //落库用用户设定的长边，不用 freeformHeight：小窗处于横屏（或刚被夹紧过）时
            //freeformHeight 只是不溢出屏幕的临时值，存下来会让下次开屏莫名变小
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
        try {
            windowManager.removeViewImmediate(binding.root)
        }catch (e: Exception) { }
        try {
            virtualDisplay.surface.release()
        } catch (e: Exception) {}
        virtualDisplay.release()

        try {
            iWindowManager?.removeRotationWatcher(iRotationWatcher)
        } catch (e: Exception) {}


        try {
            screenListener.unregisterListener()
        } catch (e: Exception) {}

        //移除小窗管理
        //任务跳到全屏被清掉时，小窗可能正挂在后台（收起到侧边栏/悬浮球），
        //两个集合都要清，否则侧边栏会留下一个点不开的幽灵条目。
        FreeformHelper.removeFreeformFromSet(this)
        FreeformHelper.removeMiniFreeformFromSet(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                activityTaskManager?.unregisterTaskStackListener(taskStackListener)
            } catch (e: Exception) {}
        }
    }

    init {
        if (MiFreeform.me?.getControlService()?.asBinder()?.pingBinder() == true) {
            //尝试恢复小窗状态
            //优化 如果小窗移动到屏幕外导致无法控制，可以尝试从侧边栏再次点击该应用以移动到屏幕中心 q220917.4
            if (FreeformHelper.isAppInFreeform(config.packageName, config.userId)) {
                val freeformView = FreeformHelper.getFreeformStackSet().getByPackageName(config.packageName, config.userId)
                freeformView?.toScreenCenter()
                freeformView?.moveToFirst()
            } else if (FreeformHelper.isAppInMiniFreeform(config.packageName, config.userId)) {
                FreeformHelper.getMiniFreeformStackSet().getByPackageName(config.packageName, config.userId)?.fromBackstage()
            } else {
                initSystemService()
                initConfig()

                //youtube单独适配
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
            MiFreeform.me?.initShizuku()
            Toast.makeText(context, context.getString(R.string.service_not_running), Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 把触摸点从 textureView 的视图坐标换算到虚拟屏 buffer 坐标。
     *
     * 以画面 View 的实测尺寸为准（理由见 [FreeformTouchMapper]）；实测尺寸还没拿到时
     * （极端时序，例如首帧前就有触摸）退回旧的名义比例，保证不出现除零和整屏无响应。
     */
    private fun mapTouchToBuffer(dst: MotionEvent.PointerCoords) {
        val view = binding.textureView
        dst.x = FreeformTouchMapper.mapX(dst.x, view.width, freeformScreenWidth, scaleX)
        dst.y = FreeformTouchMapper.mapY(dst.y, view.height, freeformScreenHeight, scaleY)
    }

    //优化 将触摸设置为一等公民，以支持多点触控，也可以看一下为什么那样，多点触控就不支持了... q220906.1
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
         * 触控处理
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

            //触控是高频路径，Shizuku binder 失效或虚拟屏已释放时 transact 会直接抛异常，
            //一抛就崩掉整个进程（手指一碰就闪退），这里统一兜住并记日志。
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
         * 触控处理
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

            //不是自己的任务、或者任务还在自己的虚拟屏里，都不用处理
            if (tId != taskId || newDisplayId == virtualDisplay.display.displayId || isDestroy) return

            if (newDisplayId != Display.DEFAULT_DISPLAY) return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && config.useSuiRefuseToFullScreen) {
                //用户开了「使用 Shizuku/Sui 阻止应用跳出小窗到全屏」，把任务拉回小窗
                activityTaskManager?.moveRootTaskToDisplay(tId, virtualDisplay.display.displayId)
            } else {
                //任务跑到全屏去了（用户从桌面、通知等入口再次进入了这个应用）。
                //此时旧小窗的虚拟屏已经没有内容，收起来再还原会变成一个
                //点不动也画不出来的透明小窗，所以直接把旧小窗清掉。
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
            } catch (e: Exception) { }
        }

        override fun onTaskRequestedOrientationChanged(tId: Int, requestedOrientation: Int) {
            //用户已手动旋转小窗方向时，忽略应用上报的方向，避免把窗口复位
            if (manualRotationOverride) return
            //q220902.2 某些竖屏软件也会横屏，经查，会有一个requestedOrientation为2的情况，将其转为1
            var tempRotation = requestedOrientation
            if (tempRotation != VIRTUAL_DISPLAY_ROTATION_PORTRAIT && tempRotation != VIRTUAL_DISPLAY_ROTATION_LANDSCAPE) tempRotation = VIRTUAL_DISPLAY_ROTATION_PORTRAIT
            if (taskId == tId && tempRotation != virtualDisplayRotation) {
                virtualDisplayRotation = tempRotation
                scope.launch(Dispatchers.Main) {
                    onFreeFormRotationChanged()
                }
            }
        }

        //q220903.2 Android 10系统上需要该回调监听
        override fun onActivityRequestedOrientationChanged(tId: Int, requestedOrientation: Int) {
            //用户已手动旋转小窗方向时，忽略应用上报的方向，避免把窗口复位
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

        // 等比例缩放的最小尺寸：允许缩到很小。
        // 原值 400/600 在小屏手机上几乎等于「不能缩小」，这里降到真实的最小可用尺寸。
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

        // 收起方式设置项
        const val KEY_MINIMIZE_MODE = "minimize_mode"
        const val MINIMIZE_MODE_SIDEBAR = 0
        const val MINIMIZE_MODE_BALL = 1
    }
}
