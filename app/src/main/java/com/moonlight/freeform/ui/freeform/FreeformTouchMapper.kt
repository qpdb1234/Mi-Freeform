package com.moonlight.freeform.ui.freeform

/**
 * Freeform touch-coordinate mapping.
 *
 * Pure computation with no dependency on the Android framework, so it is easy to unit-test on the JVM.
 *
 * Why the scale cannot be derived from the "nominal size":
 * Inside the freeform only textureView carries the picture, and the picture (the virtual-display buffer) is
 * stretched to fill it, so the correct mapping is a linear map from "textureView's measured size -> buffer size".
 * Deriving the scale from freeformWidth/freeformHeight instead assumes textureView exactly equals the nominal size;
 * but in the layout it is sandwiched between the top bar, the bottom bar and the shadow, so as soon as the window-height
 * formula and the layout decorations differ by one item (historically the landscape branch omitted the top bar),
 * the actual picture no longer matches the nominal value — after a scale or rotation the vertical axis is compressed,
 * which shows up as "tapping this spot in the freeform triggers a different spot".
 * One wrong nominal computation throws everything off, whereas the measured size is taken by the system and never lies.
 */
object FreeformTouchMapper {

    /**
     * Horizontal mapping.
     *
     * @param srcX the touch point's x within textureView (view coordinates)
     * @param viewW textureView's measured width, <=0 means it hasn't been measured yet
     * @param bufferW the virtual display's buffer width
     * @param fallbackScale the old nominal scale, used only as a fallback when the measured size is unavailable
     */
    fun mapX(srcX: Float, viewW: Int, bufferW: Int, fallbackScale: Float): Float =
        if (viewW > 0 && bufferW > 0) srcX * bufferW / viewW else srcX / fallbackScale

    /** Vertical mapping, same semantics as [mapX]. */
    fun mapY(srcY: Float, viewH: Int, bufferH: Int, fallbackScale: Float): Float =
        if (viewH > 0 && bufferH > 0) srcY * bufferH / viewH else srcY / fallbackScale
}
