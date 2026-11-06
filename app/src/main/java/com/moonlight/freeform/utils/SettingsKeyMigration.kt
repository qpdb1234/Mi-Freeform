package com.moonlight.freeform.utils

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.moonlight.freeform.app.MiFreeform

/**
 * Settings key-name migration.
 *
 * Settings such as top-bar button actions, collapse mode, and floating-ball size had a brand prefix
 * in their early key names (shaped like `<old-prefix>_top_bar_slot0`); they are now unified to prefix-free names.
 * Once the key name changes, values already stored on the phone become unreadable and the settings page falls back
 * to defaults, so here we migrate once at process start: move the old key's value to the new key, then delete the old key.
 *
 * We deliberately do not spell out the old prefix's original text (that word no longer appears in this repo),
 * and instead recognize old keys by their **suffix**: any stored key that ends with a listed new key name and is
 * preceded by exactly a prefix at the separator is treated as a pre-rename record.
 * All seven new key names are unique complete segments within this app, so no other setting happens to end with them,
 * making this detection safe enough.
 *
 * Migration dispatches on the value's actual type. Currently these settings are all int (IntegerSimpleMenuPreference
 * uses persistInt, and NumericSeekBarPreference likewise), but the other branches are kept so future settings need no change here.
 * Old keys are deleted right after moving, so every later start just iterates the stored keys—an ignorable cost.
 *
 * Besides MiFreeform.onCreate, this must also be called after SettingsBackup.import: backups exported before the rename
 * store old key names, and restoring them directly would write a batch of dead records that can never be read.
 */
object SettingsKeyMigration {

    private const val TAG = "SettingsKeyMigration"

    /** The renamed key names, all located in the APP_SETTINGS_NAME SharedPreferences. */
    private val CURRENT_KEYS = arrayOf(
        "top_bar_slot0",
        "top_bar_slot1",
        "top_bar_slot2",
        "bottom_double_tap_action",
        "minimize_mode",
        "miniball_size_dp",
        "miniball_alpha",
    )

    /** Entry point used at process start; fetches app_settings itself. */
    fun migrate(context: Context) {
        migrateFrom(
            context.getSharedPreferences(MiFreeform.APP_SETTINGS_NAME, Context.MODE_PRIVATE)
        )
    }

    /**
     * Performs the migration on the given SharedPreferences.
     *
     * @param sp the preference actually used by the settings page (app_settings)
     * @return the number of keys migrated in this run
     */
    fun migrateFrom(sp: SharedPreferences): Int {
        val all = sp.all
        val editor = sp.edit()
        var moved = 0
        for (newKey in CURRENT_KEYS) {
            // The key stored before the rename: ends with the new key name and has an extra prefix in front (the separator must be '_').
            val oldKey = all.keys.firstOrNull { key ->
                key.length > newKey.length + 1 &&
                    key.endsWith(newKey) &&
                    key[key.length - newKey.length - 1] == '_'
            } ?: continue
            val value = all[oldKey] ?: continue
            // When the new key already has a value, keep it: the user may have changed this setting after the rename, and it must not be overwritten by the old record.
            if (!all.containsKey(newKey)) {
                when (value) {
                    is Int -> editor.putInt(newKey, value)
                    is Long -> editor.putLong(newKey, value)
                    is Boolean -> editor.putBoolean(newKey, value)
                    is Float -> editor.putFloat(newKey, value)
                    is String -> editor.putString(newKey, value)
                    is Set<*> -> {
                        val strings = value.filterNotNull().map { it.toString() }.toSet()
                        editor.putStringSet(newKey, strings)
                    }
                    // Values of unrecognized type are not moved, and the old key is not deleted either—left for future versions to handle.
                    else -> continue
                }
                moved++
            }
            editor.remove(oldKey)
        }
        if (moved > 0) {
            editor.apply()
            Log.i(TAG, "migrated $moved renamed setting keys")
        }
        return moved
    }
}
