package com.moonlight.freeform.ui.freeform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for freeform touch mapping + window decoration sizing (pure computation, runs directly on the JVM).
 *
 * Reproduced bug: after rotating or resizing the freeform, the tapped position and the responding position differ.
 * Both causes are guarded here:
 * 1) The old implementation derived scale from the "nominal size freeformWidth×freeformHeight", but the content
 *    (textureView) gets squeezed by the top bar, bottom bar, and shadows, so the actual content is smaller than the nominal value,
 *    compressing coordinates overall—more error toward the edges;
 * 2) The window-height formula was inconsistent with the layout decorations (the landscape branch dropped the top bar, both directions dropped CardView's
 *    marginBottom), so the deviation from cause 1 was baked into the geometry, becoming more obvious after rotation.
 */
class FreeformTouchMapperTest {

    private val density = 1.95f // 312dpi
    private val topBar = (24 * density).toInt()      // view_freeform.xml top bar
    private val bottomBar = (24 * density).toInt()   // bottom bar
    private val shadow = (4 * density).toInt()       // CardView marginTop
    private val shadowHeight = (8 * density).toInt() // CardView marginBottom
    private val scaleBar = (32 * density).toInt()    // left/right scale bars

    // Nominal freeform size (long/short side), independent of orientation
    private val longSide = 1000
    private val shortSide = 500

    // Virtual-display buffer size: the long edge is horizontal or vertical depending on orientation; here only long/short edges are used
    private val bufferLong = 2000
    private val bufferShort = 1000

    /** Total vertical decoration in production code. */
    private val chromeHeight = FreeformWindowGeometry.chromeHeight(topBar, bottomBar, shadow, shadowHeight)

    /** Total horizontal decoration in production code. */
    private val chromeWidth = FreeformWindowGeometry.chromeWidth(scaleBar)

    /**
     * Following view_freeform.xml's hierarchy, peel decorations off the window height down to the content height:
     * window -> remove CardView top/bottom margins -> remove top/bottom bars.
     */
    private fun textureHeight(windowHeight: Int): Int =
        windowHeight - shadow - shadowHeight - topBar - bottomBar

    /** Old algorithm: x/=scaleX, y/=scaleY, with scale derived from the nominal size. */
    private fun legacyMap(src: Float, nominal: Int, buffer: Int): Float = src * buffer / nominal

    @Test
    fun `window height formula fully matches layout decorations, content height equals nominal height`() {
        // Portrait: window height = content (long side) + decoration; landscape: window height = content (short side) + decoration
        assertEquals(longSide, textureHeight(longSide + chromeHeight))
        assertEquals(shortSide, textureHeight(shortSide + chromeHeight))
        // Horizontal likewise: window width = content width + left/right scale bars
        assertEquals(shortSide, shortSide + chromeWidth - 2 * scaleBar)
    }

    @Test
    fun `measured-size mapping is exact at both endpoints and midpoint`() {
        val texH = textureHeight(longSide + chromeHeight)
        assertEquals(0f, FreeformTouchMapper.mapY(0f, texH, bufferLong, 1f), 0.001f)
        // View bottom -> buffer bottom, no more, no less
        assertEquals(bufferLong.toFloat(), FreeformTouchMapper.mapY(texH.toFloat(), texH, bufferLong, 1f), 0.001f)
        assertEquals(bufferLong / 2f, FreeformTouchMapper.mapY(texH / 2f, texH, bufferLong, 1f), 1f)
    }

    @Test
    fun `omitting one decoration makes the nominal algorithm drift by a visible error`() {
        // The two omissions before the fix: all branches dropped marginBottom (8dp), and the landscape branch also dropped the top bar (24dp).
        // Here we model window height with the pre-fix formula, measure the actual content height, then see how far the nominal algorithm drifts.
        val buggyPortraitH = longSide + topBar + bottomBar + shadow
        val texPortrait = textureHeight(buggyPortraitH)
        val portraitError = bufferLong - legacyMap(texPortrait.toFloat(), longSide, bufferLong)
        assertTrue("portrait bottom point should map too small", portraitError > 0f)
        assertEquals(shadowHeight.toFloat() * bufferLong / longSide, portraitError, 1f)

        val buggyLandscapeH = shortSide + bottomBar + shadow
        val texLandscape = textureHeight(buggyLandscapeH)
        val landscapeError = bufferShort - legacyMap(texLandscape.toFloat(), shortSide, bufferShort)
        // Landscape undercounts top bar + marginBottom; the error scales up to buffer coordinates, causing obvious tap misalignment
        assertEquals((topBar + shadowHeight).toFloat() * bufferShort / shortSide, landscapeError, 1f)
        assertTrue("landscape error should far exceed portrait", landscapeError > portraitError * 3)
    }

    @Test
    fun `measured mapping stays correct even when geometry is broken`() {
        // Even if the window-height formula drops another item (actual content height no longer equals the nominal value),
        // mapping by measured size still puts the bottom point at the buffer bottom—that's why measured size is preferred.
        val brokenTexH = textureHeight(shortSide + bottomBar + shadow)
        assertEquals(bufferShort.toFloat(), FreeformTouchMapper.mapY(brokenTexH.toFloat(), brokenTexH, bufferShort, 1f), 0.001f)
    }

    @Test
    fun `after rotation the same point's mapping follows measured size`() {
        val pTexH = textureHeight(longSide + chromeHeight)
        val lTexH = textureHeight(shortSide + chromeHeight)
        // Midpoint: mapping by measured size always lands on the buffer centerline
        assertEquals(bufferLong / 2f, FreeformTouchMapper.mapY(pTexH / 2f, pTexH, bufferLong, 1f), 1f)
        assertEquals(bufferShort / 2f, FreeformTouchMapper.mapY(lTexH / 2f, lTexH, bufferShort, 1f), 1f)
    }

    @Test
    fun `horizontal mapping by measured width is unaffected by left/right scale bars`() {
        // Window width = content width + 2*scale_view_size, textureView width = content width,
        // so nominal and measured coincide exactly, and both algorithms should map the rightmost point to the buffer's rightmost
        val texW = shortSide + chromeWidth - 2 * scaleBar
        assertEquals(bufferShort.toFloat(), FreeformTouchMapper.mapX(texW.toFloat(), texW, bufferShort, 1f), 0.001f)
        assertEquals(legacyMap(texW.toFloat(), shortSide, bufferShort), bufferShort.toFloat(), 0.001f)
    }

    @Test
    fun `falls back to the nominal ratio when measured size is unavailable`() {
        // When view.width==0 (not yet measured), divide by the fallback scale, preserving old behavior rather than crashing or returning all 0
        assertEquals(200f, FreeformTouchMapper.mapX(100f, 0, bufferShort, 0.5f), 0.001f)
        assertEquals(200f, FreeformTouchMapper.mapY(100f, -1, bufferLong, 0.5f), 0.001f)
        // An invalid buffer size also falls back
        assertEquals(400f, FreeformTouchMapper.mapX(200f, 100, 0, 0.5f), 0.001f)
    }
}
