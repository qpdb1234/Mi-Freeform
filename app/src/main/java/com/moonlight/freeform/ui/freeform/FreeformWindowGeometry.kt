package com.moonlight.freeform.ui.freeform

import android.content.Context
import com.moonlight.freeform.R

/**
 * The fixed delta between the freeform "window size" and the "content size".
 *
 * Freeform window = content (textureView) + a ring of decoration; each decoration item comes from view_freeform.xml:
 * - Horizontal: CardView's layout_marginHorizontal (a scale_view_size bar on each side,
 *   which also serves as the left/right resize gesture area);
 * - Vertical: CardView's marginTop(freeform_shadow) + marginBottom(freeform_shadow_height)
 *   + top bar top_bar_height + bottom bar bottom_bar_height.
 *
 * Why it is extracted: this delta was previously duplicated in showWindow / updateWindowSize / clampFreeformToScreen
 * and the tutorial freeform. The omitted items were—the landscape branch dropped the top bar, and every branch dropped
 * marginBottom. As a result the content height was always shorter than the nominal freeformHeight,
 * and after rotation (via updateWindowSize) shorter by another 24dp: the content got squashed, and the touch
 * mapping derived from the nominal size was misaligned too. Centralizing it lets all four sites share one source.
 */
object FreeformWindowGeometry {

    /** Window width = content width + this value (in landscape the content's long edge is horizontal). */
    fun chromeWidth(context: Context): Int =
        chromeWidth(context.resources.getDimension(R.dimen.scale_view_size).toInt())

    /** Window height = content height + this value (in landscape the content's short edge is vertical). */
    fun chromeHeight(context: Context): Int = chromeHeight(
        context.resources.getDimension(R.dimen.top_bar_height).toInt(),
        context.resources.getDimension(R.dimen.bottom_bar_height).toInt(),
        context.resources.getDimension(R.dimen.freeform_shadow).toInt(),
        context.resources.getDimension(R.dimen.freeform_shadow_height).toInt()
    )

    /** Pure-computation overload, does not touch [Context], convenient for JVM unit tests. */
    fun chromeWidth(scaleBarWidth: Int): Int = 2 * scaleBarWidth

    /** Pure-computation overload; the four parameters map item-by-item to view_freeform.xml's hierarchy. */
    fun chromeHeight(
        topBarHeight: Int,
        bottomBarHeight: Int,
        cardMarginTop: Int,
        cardMarginBottom: Int
    ): Int = topBarHeight + bottomBarHeight + cardMarginTop + cardMarginBottom
}
