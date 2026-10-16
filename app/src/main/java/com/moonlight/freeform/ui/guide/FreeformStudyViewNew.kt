package com.moonlight.freeform.ui.guide

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
import android.provider.Settings
import android.view.*
import android.widget.Toast
import androidx.annotation.RequiresApi
import com.moonlight.freeform.R
import com.moonlight.freeform.app.MiFreeform
import com.moonlight.freeform.bean.MotionEventBean
import com.moonlight.freeform.databinding.ViewFreeformBinding
import com.moonlight.freeform.ui.freeform.*
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
     * 和 [isDestroy] 不是一回事：[isDestroy] 在 moveTaskToDefaultDisplay() 里
     * 会被提前置位（用来让 Sui 钩子别把任务拉回小窗），那时 destroy() 还没执行；
     * 如果幂等也用 [isDestroy]，全屏时 destroy() 会被直接跳过，
     * 小窗就留在屏幕上、虚拟屏也停在全屏尺寸，下次打开应用画面会比小窗小一圈。
     */
    private var isDestroyed = false

    //是否处于后台挂起状态
    private var isBackstage = false

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

    //屏幕监听
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

    //屏幕宽高，不保证大小
    private var realScreenWidth = 0
    private var realScreenHeight = 0

    //小窗的“尺寸”，该尺寸只在小窗内屏幕方向改变时变化
    private var freeformScreenHeight = 0
    private var freeformScreenWidth = 0

    //小窗界面的宽高，该宽高不随着屏幕、小窗方向改变而改变，即h>w恒成立。该尺寸只在物理屏幕方向变化时变化
    private var freeformHeight = 0
    private var freeformWidth = 0

    //max(freeformScreenHeight,freeformScreenWidth)，用于计算scale
    private var maxFreeformScreenSize = 0

    //缩放比例
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

    //教学窗固定：双击底栏中部 = 旋转小窗（不跟随设置，保持教学一致性）
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
                //教学页里任务跳到全屏，同样把旧小窗清掉，
                //否则会留下一个没有画面的透明小窗。
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
            } catch (e: Exception) { }
        }

        override fun onTaskRequestedOrientationChanged(tId: Int, requestedOrientation: Int) {
            //q220902.2 某些竖屏软件也会横屏，经查，会有一个requestedOrientation为2的情况，将其转为1
            var tempRotation = requestedOrientation
            if (tempRotation != VIRTUAL_DISPLAY_ROTATION_PORTRAIT && tempRotation != VIRTUAL_DISPLAY_ROTATION_LANDSCAPE) tempRotation = VIRTUAL_DISPLAY_ROTATION_PORTRAIT
            if (taskId == tId && tempRotation != virtualDisplayRotation) {
                virtualDisplayRotation = tempRotation
                GlobalScope.launch(Dispatchers.Main) {
                    onFreeFormRotationChanged()
                }
            }
        }

        //q220903.2 Android 10系统上需要该回调监听
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

    //练习小窗用
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
        // 与 FreeformView 一致：开屏 loading 已删除，画面从一开始就完全不透明。

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

            //关闭屏幕隐藏小窗
            override fun onScreenOff() {
                //挂起状态无需更新
                //修复 在有正在运行程序的情况下锁屏，米窗崩溃的问题 q220902.1
                if (!isBackstage) {
                    beforeScreenX = windowLayoutParams.x
                    windowManager.updateViewLayout(binding.root, windowLayoutParams.apply {
                        x = -realScreenWidth * 2
                    })
                }
            }

            //解锁恢复小窗
            override fun onUserPresent() {
                //挂起状态无需更新
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
                        FreeformHelper.addFreeformToSet(this@FreeformStudyViewNew)
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
     * 窗口尺寸比画面尺寸多出来的装饰，与正式小窗共用同一套算法，
     * 具体包含哪几项见 [FreeformWindowGeometry]。
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
        }
        else {
            Toast.makeText(context, context.getString(R.string.service_not_running), Toast.LENGTH_SHORT).show()
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
        val tempHeight = max(freeformScreenHeight, freeformScreenWidth)
        val tempWidth = min(freeformScreenHeight, freeformScreenWidth)

        maxFreeformScreenSize = tempHeight

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

    override fun toScreenCenter() {

    }

    override fun moveToFirst() {
        windowManager.removeViewImmediate(binding.root)
        windowManager.addView(binding.root, windowLayoutParams)
        FreeformHelper.addFreeformToSet(this)
    }

    /**
     * 隐藏小窗到后台
     */
    private fun toBackstage() {
        windowManager.removeViewImmediate(binding.root)
        FreeformHelper.removeFreeformFromSet(this)
        FreeformHelper.addMiniFreeformToSet(this)
        isBackstage = true
    }

    /**
     * 从后台恢复
     */
    override fun fromBackstage() {
        windowManager.addView(binding.root, windowLayoutParams)
        FreeformHelper.removeMiniFreeformFromSet(this)
        FreeformHelper.addFreeformToSet(this)
        isBackstage = false
    }

    /**
     * 尝试将小窗内容全屏
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
     * 通知windowManager刷新缩放后的界面
     */
    private fun notifyWindowScale() {
        binding.root.scaleX = 1f
        binding.root.scaleY = 1f
        scale = freeformHeight / maxFreeformScreenSize.toFloat()
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
            R.id.textureView -> {
                if (touchId == R.id.textureView) handleTouch(event)
            }
            //左键监听，单击关闭，长按全屏
            R.id.leftView -> {
                if (touchId == R.id.leftView) leftGestureDetector.onTouchEvent(event)
            }
            R.id.middleView -> {
                middleGestureDetector.onTouchEvent(event)
            }
            //右键监听，点击返回，长按后台挂起
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
            //左键监听，单击关闭，双击全屏
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
            //左键监听，单击关闭，双击全屏
            R.id.leftView -> {
                if (touchId == R.id.leftView) leftGestureDetector.onTouchEvent(event)
            }
            //右按键返回
            R.id.rightView -> {
                if (touchId == R.id.rightView) rightGestureDetector.onTouchEvent(event)
            }
            R.id.leftScale, R.id.rightScale -> {
                notifyWindowScale()
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
            if (tempHeight > MIN_HEIGHT && tempHeight <= maxFreeformScreenSize && tempWidth > MIN_WIDTH && tempWidth < realScreenWidth) {
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
     * 缩放处理
     */
    private fun handleScaleRight(dx: Float, dy: Float) {
        //缩小
        if (dx < 0 && dy < 0) {
            val tempHeight = freeformHeight + dy
            val tempWidth = freeformWidth + dx
            //在尺寸内
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
        //放大
        else if (dx > 0 && dy > 0) {
            val tempHeight = freeformHeight + dy
            val tempWidth = freeformWidth + dx
            //在尺寸内
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
     * 移动位置处理
     */
    private fun handleMove(dx: Float, dy: Float) {
        windowManager.updateViewLayout(binding.root, windowLayoutParams.apply {
            x = (x + dx).roundToInt()
            y = ((y + dy).roundToInt())
        })
    }

    private var setDisplayIdMethod: Method? = null

    /**
     * 把触摸点从 textureView 的视图坐标换算到虚拟屏 buffer 坐标。
     * 教学窗也能双击旋转，旋转后名义尺寸和实际画面差更多，
     * 所以与 FreeformView 一样按画面 View 的实测尺寸映射（理由见 [FreeformTouchMapper]）。
     */
    private fun mapTouchToBuffer(dst: MotionEvent.PointerCoords) {
        val view = binding.textureView
        val bufferX = FreeformTouchMapper.mapX(dst.x, view.width, freeformScreenWidth, scale)
        val bufferY = FreeformTouchMapper.mapY(dst.y, view.height, freeformScreenHeight, scale)
        dst.x = bufferX
        dst.y = bufferY
    }

    /**
     * 触控处理
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

            //Andorid Q及以上系统
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


    /** 供外部调用的销毁入口，幂等。 */
    override fun destroyFreeform() {
        destroy()
    }

    fun destroy() {
        // destroy 有多条入口（用户点关闭、任务被移除、任务跳到全屏），
        // 重复进入会把虚拟屏 release 两次。
        //
        // 注意这里不能用 isDestroy 做幂等：moveTaskToDefaultDisplay() 会先把它置位
        // （让 Sui 钩子别把任务拉回小窗），那时 destroy() 还没执行；
        // 用它做守卫的话全屏时 destroy() 整个被跳过，小窗留在屏幕上、
        // 虚拟屏停在全屏尺寸，下次打开应用画面会比小窗小一圈。
        if (isDestroyed) return

        isDestroy = true
        isDestroyed = true
        try {
            windowManager.removeViewImmediate(binding.root)
        }catch (e: Exception) { }

        try {
            virtualDisplay.surface.release()
            virtualDisplay.release()
        } catch (e: Exception) {}

        try {
            //移除屏幕监听
            displayManager.unregisterDisplayListener(displayListener)
        } catch (e: Exception) {}


        try {
            screenListener.unregisterListener()
        } catch (e: Exception) {}

        //移除小窗管理
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
            if (FreeformHelper.isAppInFreeform(config.packageName, config.userId)) {
                FreeformHelper.getFreeformStackSet().getByPackageName(config.packageName, config.userId)?.moveToFirst()
            } else if (FreeformHelper.isAppInMiniFreeform(config.packageName, config.userId)) {
                FreeformHelper.getMiniFreeformStackSet().getByPackageName(config.packageName, config.userId)?.fromBackstage()
            } else {
                initSystemService()
                initConfig()

                //youtube单独适配
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

        // 与 FreeformView 保持一致：允许等比例缩到很小，贴角挂起时也不要太大。
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
     * 练习结果反馈
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