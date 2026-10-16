package com.moonlight.freeform.ui.freeform

import com.moonlight.freeform.R

/**
 * 顶栏三个图标可执行的动作。
 *
 * 之所以用枚举 + 序号持久化，而不是直接存枚举名：
 * 设置页用 IntegerSimpleMenuPreference 存的是 integer-array 里的序号，
 * 运行时按序号反解，和 Mi-Freeform 既有偏好体系（floating_position_x 等）保持一致。
 *
 * 序号必须与 arrays.xml 里 top_bar_action_value 的顺序一一对应，
 * 新增动作时只能往后追加，不能插入中间，否则旧版本的设置值会错位。
 */
enum class TopBarAction(val id: Int) {
    /** 退出小窗 */
    CLOSE(0),
    /** 全屏化 */
    FULL_SCREEN(1),
    /** 收起（挂起后台） */
    MINIMIZE(2),
    /** 小窗内返回 */
    BACK(3),
    /** 截屏（保存到系统截图目录） */
    SCREENSHOT(4),
    /** 旋转小窗方向（竖屏/横屏切换） */
    ROTATE(5),
    /** 移动到屏幕中心 */
    CENTER(6),
    /** 无动作（占位，允许某个位置留空） */
    NONE(7);

    companion object {
        /** 设置值非法时退回「无动作」，而不是默认成某个功能——避免误触。 */
        fun fromId(id: Int): TopBarAction = values().firstOrNull { it.id == id } ?: NONE

        /** 顶栏三个位置默认的动作：退出 / 全屏 / 收起。 */
        val DEFAULTS = intArrayOf(CLOSE.id, FULL_SCREEN.id, MINIMIZE.id)

        /**
         * 从指定 SharedPreferences 读取动作。
         *
         * 设置页的 IntegerSimpleMenuPreference 把值写进 app_settings（SettingFragment 里
         * 指定了 sharedPreferencesName），小窗运行时用这个方法读同一个文件，
         * 不需要设置页做额外的同步。
         */
        fun getSlotActionFromSp(sp: android.content.SharedPreferences, slot: Int): TopBarAction {
            if (slot !in 0..2) return NONE
            return fromId(sp.getInt("top_bar_slot$slot", DEFAULTS.getOrElse(slot) { NONE.id }))
        }

        /**
         * 读取「双击底栏中部按钮」配置的动作，默认旋转小窗。
         *
         * 与顶栏三个槽位共用 top_bar_action 数组，settings.xml 里 key 为
         * bottom_double_tap_action，写进去的是 entryValues 的整数（旋转=5）。
         */
        fun getBottomDoubleTapAction(sp: android.content.SharedPreferences): TopBarAction =
            fromId(sp.getInt("bottom_double_tap_action", ROTATE.id))

        /** 动作对应的图标 drawable 资源 id。 */
        fun iconRes(action: TopBarAction): Int = when (action) {
            CLOSE -> R.drawable.ic_top_bar_close
            FULL_SCREEN -> R.drawable.ic_top_bar_full_screen
            MINIMIZE -> R.drawable.ic_top_bar_minimize
            BACK -> R.drawable.ic_top_bar_back
            SCREENSHOT -> R.drawable.ic_top_bar_screenshot
            ROTATE -> R.drawable.ic_top_bar_rotate
            CENTER -> R.drawable.ic_top_bar_center
            NONE -> 0
        }

        /** 动作名称（用于无障碍 contentDescription）。 */
        fun labelRes(action: TopBarAction): Int = when (action) {
            CLOSE -> R.string.top_bar_close
            FULL_SCREEN -> R.string.top_bar_full_screen
            MINIMIZE -> R.string.top_bar_minimize
            BACK -> R.string.top_action_back
            SCREENSHOT -> R.string.top_action_screenshot
            ROTATE -> R.string.top_action_rotate
            CENTER -> R.string.top_action_center
            NONE -> R.string.top_action_none
        }
    }
}
