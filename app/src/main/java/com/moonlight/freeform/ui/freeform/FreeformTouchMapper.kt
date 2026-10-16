package com.moonlight.freeform.ui.freeform

/**
 * 小窗触控坐标换算。
 *
 * 纯计算、不依赖 Android 框架，方便在 JVM 上单测。
 *
 * 为什么不能按「名义尺寸」反推缩放：
 * 小窗里承载画面的只有 textureView，画面（虚拟屏 buffer）会被拉伸铺满它，
 * 所以正确的换算就是「视图实测尺寸 → buffer 尺寸」的线性映射。
 * 反过来按 freeformWidth/freeformHeight 这套名义尺寸算 scale，等于假设
 * textureView 恰好等于名义尺寸；可布局里它被夹在顶栏、底栏和阴影之间，
 * 只要窗口高度公式与布局装饰差一项（历史上横屏分支就漏了顶栏），
 * 实际画面就和名义值对不上——缩放、旋转一次，纵向被压缩，
 * 表现为「点小窗里的这个位置，响应的是另一个位置」。
 * 名义尺寸算错一处就全盘错位，而实测尺寸是系统量出来的，不会骗人。
 */
object FreeformTouchMapper {

    /**
     * 水平方向换算。
     *
     * @param srcX 触摸点在 textureView 内的 x（视图坐标）
     * @param viewW textureView 实测宽度，<=0 表示还没测量完
     * @param bufferW 虚拟屏 buffer 宽度
     * @param fallbackScale 旧的名义缩放比例，仅在实测尺寸不可用时兜底
     */
    fun mapX(srcX: Float, viewW: Int, bufferW: Int, fallbackScale: Float): Float =
        if (viewW > 0 && bufferW > 0) srcX * bufferW / viewW else srcX / fallbackScale

    /** 竖直方向换算，语义同 [mapX]。 */
    fun mapY(srcY: Float, viewH: Int, bufferH: Int, fallbackScale: Float): Float =
        if (viewH > 0 && bufferH > 0) srcY * bufferH / viewH else srcY / fallbackScale
}
