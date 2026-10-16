package com.moonlight.freeform.ui.freeform

abstract class FreeformViewAbs(open val config: FreeformConfig) {
    abstract fun toScreenCenter()
    abstract fun moveToFirst()
    abstract fun fromBackstage()

    /**
     * 彻底销毁小窗（释放虚拟屏、移除窗口、清理管理集合）。
     *
     * 用于「同一应用再次启动小窗」时清掉旧的实例，
     * 避免出现两个小窗或残留的悬浮球。
     */
    abstract fun destroyFreeform()
}
