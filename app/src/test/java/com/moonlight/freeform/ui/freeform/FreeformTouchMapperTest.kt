package com.moonlight.freeform.ui.freeform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 小窗触控换算 + 窗口装饰尺寸的单元测试（纯计算，JVM 直接跑）。
 *
 * 复现的 bug：旋转或缩放小窗后，点的位置和响应的位置不一样。
 * 两个成因都在这里守着：
 * 1) 旧实现按「名义尺寸 freeformWidth×freeformHeight」反推 scale，而画面
 *    （textureView）被顶栏、底栏、阴影挤掉一截，实际画面比名义值小，
 *    坐标被整体压缩，越靠边偏得越多；
 * 2) 窗口高度公式与布局装饰不一致（横屏分支漏顶栏、两个方向都漏 CardView
 *    的 marginBottom），于是第 1 条的偏差被固定烙进几何里，旋转后更明显。
 */
class FreeformTouchMapperTest {

    private val density = 1.95f // 312dpi
    private val topBar = (24 * density).toInt()      // view_freeform.xml 顶栏
    private val bottomBar = (24 * density).toInt()   // 底栏
    private val shadow = (4 * density).toInt()       // CardView marginTop
    private val shadowHeight = (8 * density).toInt() // CardView marginBottom
    private val scaleBar = (32 * density).toInt()    // 左右两侧缩放条

    // 小窗名义尺寸（长边/短边），与方向无关
    private val longSide = 1000
    private val shortSide = 500

    // 虚拟屏 buffer 尺寸：长边=水平或竖直随方向而变，这里只按长/短边取用
    private val bufferLong = 2000
    private val bufferShort = 1000

    /** 生产代码里的竖直装饰总量。 */
    private val chromeHeight = FreeformWindowGeometry.chromeHeight(topBar, bottomBar, shadow, shadowHeight)

    /** 生产代码里的水平装饰总量。 */
    private val chromeWidth = FreeformWindowGeometry.chromeWidth(scaleBar)

    /**
     * 按 view_freeform.xml 的层级，从窗口高度往下剥装饰得到画面高度：
     * 窗口 -> 去掉 CardView 上下外边距 -> 去掉顶栏底栏。
     */
    private fun textureHeight(windowHeight: Int): Int =
        windowHeight - shadow - shadowHeight - topBar - bottomBar

    /** 旧算法：x/=scaleX、y/=scaleY，scale 由名义尺寸反推。 */
    private fun legacyMap(src: Float, nominal: Int, buffer: Int): Float = src * buffer / nominal

    @Test
    fun `窗口高度公式与布局装饰完全对齐，画面高度等于名义高度`() {
        // 竖屏：窗口高 = 画面(长边) + 装饰；横屏：窗口高 = 画面(短边) + 装饰
        assertEquals(longSide, textureHeight(longSide + chromeHeight))
        assertEquals(shortSide, textureHeight(shortSide + chromeHeight))
        // 水平方向同理：窗口宽 = 画面宽 + 左右缩放条
        assertEquals(shortSide, shortSide + chromeWidth - 2 * scaleBar)
    }

    @Test
    fun `实测尺寸映射在端点与中点都严格精确`() {
        val texH = textureHeight(longSide + chromeHeight)
        assertEquals(0f, FreeformTouchMapper.mapY(0f, texH, bufferLong, 1f), 0.001f)
        // 视图最底部 -> buffer 最底部，不多不少
        assertEquals(bufferLong.toFloat(), FreeformTouchMapper.mapY(texH.toFloat(), texH, bufferLong, 1f), 0.001f)
        assertEquals(bufferLong / 2f, FreeformTouchMapper.mapY(texH / 2f, texH, bufferLong, 1f), 1f)
    }

    @Test
    fun `装饰漏算一项时名义算法立刻偏出可见误差`() {
        // 修复前的两处漏算：所有分支漏 marginBottom(8dp)，横屏分支还漏顶栏(24dp)。
        // 这里按修复前的公式建模窗口高度，量出画面实际高度，再看名义算法偏多少。
        val buggyPortraitH = longSide + topBar + bottomBar + shadow
        val texPortrait = textureHeight(buggyPortraitH)
        val portraitError = bufferLong - legacyMap(texPortrait.toFloat(), longSide, bufferLong)
        assertTrue("竖屏底部点应当偏小", portraitError > 0f)
        assertEquals(shadowHeight.toFloat() * bufferLong / longSide, portraitError, 1f)

        val buggyLandscapeH = shortSide + bottomBar + shadow
        val texLandscape = textureHeight(buggyLandscapeH)
        val landscapeError = bufferShort - legacyMap(texLandscape.toFloat(), shortSide, bufferShort)
        // 横屏少算 顶栏+marginBottom，误差按比例放大到 buffer 坐标，点击明显错位
        assertEquals((topBar + shadowHeight).toFloat() * bufferShort / shortSide, landscapeError, 1f)
        assertTrue("横屏误差应远大于竖屏", landscapeError > portraitError * 3)
    }

    @Test
    fun `几何被改坏时实测映射依然正确`() {
        // 即便窗口高度公式又漏了一项（画面实际高度不等于名义值），
        // 按实测尺寸映射仍然把最底部点对到 buffer 最底部——这就是选实测优先的理由。
        val brokenTexH = textureHeight(shortSide + bottomBar + shadow)
        assertEquals(bufferShort.toFloat(), FreeformTouchMapper.mapY(brokenTexH.toFloat(), brokenTexH, bufferShort, 1f), 0.001f)
    }

    @Test
    fun `旋转后同一个点的映射跟着实测尺寸走`() {
        val pTexH = textureHeight(longSide + chromeHeight)
        val lTexH = textureHeight(shortSide + chromeHeight)
        // 半程点：按实测尺寸映射永远落在 buffer 中线
        assertEquals(bufferLong / 2f, FreeformTouchMapper.mapY(pTexH / 2f, pTexH, bufferLong, 1f), 1f)
        assertEquals(bufferShort / 2f, FreeformTouchMapper.mapY(lTexH / 2f, lTexH, bufferShort, 1f), 1f)
    }

    @Test
    fun `水平方向按实测宽度映射不受左右缩放条影响`() {
        // 窗口宽 = 画面宽 + 2*scale_view_size，textureView 宽 = 画面宽，
        // 名义与实测恰好一致，两种算法都应把最右点映射到 buffer 最右
        val texW = shortSide + chromeWidth - 2 * scaleBar
        assertEquals(bufferShort.toFloat(), FreeformTouchMapper.mapX(texW.toFloat(), texW, bufferShort, 1f), 0.001f)
        assertEquals(legacyMap(texW.toFloat(), shortSide, bufferShort), bufferShort.toFloat(), 0.001f)
    }

    @Test
    fun `实测尺寸不可用时退回名义比例`() {
        // view.width==0（尚未测量）时按 fallback scale 除，保持旧行为而不是崩或全 0
        assertEquals(200f, FreeformTouchMapper.mapX(100f, 0, bufferShort, 0.5f), 0.001f)
        assertEquals(200f, FreeformTouchMapper.mapY(100f, -1, bufferLong, 0.5f), 0.001f)
        // buffer 尺寸非法同样退回
        assertEquals(400f, FreeformTouchMapper.mapX(200f, 100, 0, 0.5f), 0.001f)
    }
}
