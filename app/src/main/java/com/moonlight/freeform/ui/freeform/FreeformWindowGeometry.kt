package com.moonlight.freeform.ui.freeform

import android.content.Context
import com.moonlight.freeform.R

/**
 * 小窗「窗口尺寸」与「画面尺寸」之间的固定差值。
 *
 * 小窗窗口 = 画面（textureView）+ 一圈装饰，装饰的每一项都来自 view_freeform.xml：
 * - 水平：CardView 的 layout_marginHorizontal（左右各一条 scale_view_size，
 *   同时也是左右缩放手势区）；
 * - 竖直：CardView 的 marginTop(freeform_shadow) + marginBottom(freeform_shadow_height)
 *   + 顶栏 top_bar_height + 底栏 bottom_bar_height。
 *
 * 之所以抽出来：这个差值原先在 showWindow / updateWindowSize / clampFreeformToScreen
 * 以及教学小窗里各抄了一份，抄漏的几项分别是——横屏分支漏顶栏、所有分支漏
 * marginBottom。于是画面实际高度总比名义的 freeformHeight 矮一截，
 * 旋转后（走 updateWindowSize）再矮 24dp：画面被压扁，按名义尺寸反推的触控
 * 换算也跟着错位。放在一处，四处共用，就不会再各抄各的。
 */
object FreeformWindowGeometry {

    /** 窗口宽度 = 画面宽度 + 本值（横屏时画面长边是水平方向）。 */
    fun chromeWidth(context: Context): Int =
        chromeWidth(context.resources.getDimension(R.dimen.scale_view_size).toInt())

    /** 窗口高度 = 画面高度 + 本值（横屏时画面短边是竖直方向）。 */
    fun chromeHeight(context: Context): Int = chromeHeight(
        context.resources.getDimension(R.dimen.top_bar_height).toInt(),
        context.resources.getDimension(R.dimen.bottom_bar_height).toInt(),
        context.resources.getDimension(R.dimen.freeform_shadow).toInt(),
        context.resources.getDimension(R.dimen.freeform_shadow_height).toInt()
    )

    /** 纯计算重载，不碰 [Context]，方便 JVM 单测。 */
    fun chromeWidth(scaleBarWidth: Int): Int = 2 * scaleBarWidth

    /** 纯计算重载，四个参数按 view_freeform.xml 的层级逐项对应。 */
    fun chromeHeight(
        topBarHeight: Int,
        bottomBarHeight: Int,
        cardMarginTop: Int,
        cardMarginBottom: Int
    ): Int = topBarHeight + bottomBarHeight + cardMarginTop + cardMarginBottom
}
