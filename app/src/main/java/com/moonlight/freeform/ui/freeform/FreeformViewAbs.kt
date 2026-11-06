package com.moonlight.freeform.ui.freeform

abstract class FreeformViewAbs(open val config: FreeformConfig) {
    abstract fun toScreenCenter()
    abstract fun moveToFirst()
    abstract fun fromBackstage()

    /**
     * Fully destroy the freeform window (release the virtual display, remove the window, clean up the bookkeeping collections).
     *
     * Used to drop the previous instance when the same app opens a freeform again,
     * so that two windows or a leftover floating ball are never left behind.
     */
    abstract fun destroyFreeform()
}
