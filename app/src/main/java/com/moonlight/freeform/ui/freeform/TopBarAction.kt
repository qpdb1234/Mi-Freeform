package com.moonlight.freeform.ui.freeform

import com.moonlight.freeform.R

/**
 * The actions executable by the three top-bar icons.
 *
 * Why an enum persisted by ordinal instead of storing the enum name directly:
 * the settings page uses IntegerSimpleMenuPreference, which stores the ordinal from an integer-array,
 * and resolves it back by ordinal at runtime—consistent with Mi-Freeform's existing preference scheme (floating_position_x, etc.).
 *
 * The ordinals must correspond one-to-one with the order of top_bar_action_value in arrays.xml;
 * new actions can only be appended at the end, never inserted in the middle, otherwise older settings values would shift.
 */
enum class TopBarAction(val id: Int) {
    /** Close the freeform */
    CLOSE(0),
    /** Fullscreen */
    FULL_SCREEN(1),
    /** Collapse (suspend to background) */
    MINIMIZE(2),
    /** Back within the freeform */
    BACK(3),
    /** Screenshot (saved to the system screenshots directory) */
    SCREENSHOT(4),
    /** Rotate the freeform orientation (portrait/landscape toggle) */
    ROTATE(5),
    /** Move to the screen center */
    CENTER(6),
    /** No action (placeholder, allows a slot to be left empty) */
    NONE(7);

    companion object {
        /** Fall back to "no action" when the settings value is invalid, rather than defaulting to some function—to avoid accidental triggers. */
        fun fromId(id: Int): TopBarAction = values().firstOrNull { it.id == id } ?: NONE

        /** Default actions for the three top-bar slots: close / fullscreen / minimize. */
        val DEFAULTS = intArrayOf(CLOSE.id, FULL_SCREEN.id, MINIMIZE.id)

        /**
         * Reads the action from the given SharedPreferences.
         *
         * The settings page's IntegerSimpleMenuPreference writes values into app_settings (SettingFragment
         * specifies sharedPreferencesName); the freeform runtime uses this method to read the same file,
         * requiring no extra synchronization from the settings page.
         */
        fun getSlotActionFromSp(sp: android.content.SharedPreferences, slot: Int): TopBarAction {
            if (slot !in 0..2) return NONE
            return fromId(sp.getInt("top_bar_slot$slot", DEFAULTS.getOrElse(slot) { NONE.id }))
        }

        /**
         * Reads the action configured for "double-tap the bottom-bar center button", defaulting to rotating the freeform.
         *
         * Shares the top_bar_action array with the three top-bar slots; the key in settings.xml is
         * bottom_double_tap_action, storing the integer from entryValues (rotate=5).
         */
        fun getBottomDoubleTapAction(sp: android.content.SharedPreferences): TopBarAction =
            fromId(sp.getInt("bottom_double_tap_action", ROTATE.id))

        /** The icon drawable resource id corresponding to the action. */
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

        /** The action name (used for the accessibility contentDescription). */
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
