package com.moonlight.freeform.utils

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import com.moonlight.freeform.room.DatabaseRepository
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream

/**
 * Settings backup and restore.
 *
 * The backup covers three parts:
 * 1. The APP_SETTINGS_NAME ("app_settings") SharedPreferences—all switches and parameters on the settings page;
 * 2. FreeFormAppsEntity—the sidebar shortcut app list (including the sortNum ordering);
 * 3. NotificationAppsEntity—the notification-bar freeform app list.
 *
 * The latter two live in the Room database, not in SharedPreferences, so we can't just iterate sp.all;
 * they must be exported/imported separately through DatabaseRepository.
 *
 * The format is JSON rather than directly copying the SharedPreferences xml file:
 * the xml carries type prefixes (int/boolean/set), which are error-prone across versions/devices on restore;
 * JSON records types explicitly, writes back by type on restore, and is readable—so problems can be inspected directly.
 *
 * Not backed up: freeform runtime position (recomputed every open), and the version fields (version/
 * version_privacy are used to detect first launch; restoring them is meaningless).
 */
object SettingsBackup {

    private const val TAG = "SettingsBackup"

    /** The backup file's magic header, used on restore to verify it's this app's backup. */
    private const val MAGIC = "MiFreeformSettings"

    private const val VERSION = 2

    /**
     * Exports all settings to the given output stream (provided by the caller, usually a file picked via SAF).
     *
     * @return true = write succeeded
     */
    fun export(context: Context, out: OutputStream): Boolean {
        return try {
            val sp = context.getSharedPreferences(
                com.moonlight.freeform.app.MiFreeform.APP_SETTINGS_NAME,
                Context.MODE_PRIVATE
            )
            val root = JSONObject()
            root.put("magic", MAGIC)
            root.put("version", VERSION)
            root.put("settings", settingsToJson(sp))
            // Sidebar shortcut apps + notification-bar apps live in the database, not in sp, so export them separately.
            root.put("freeform_apps", appsToJson(DatabaseRepository(context)))
            root.put("notification_apps", notificationAppsToJson(DatabaseRepository(context)))
            out.bufferedWriter().use { it.write(root.toString(2)) }
            true
        } catch (e: Throwable) {
            Log.w(TAG, "export_failed", e)
            false
        }
    }

    /**
     * Restores settings from an input stream (a file picked via SAF).
     *
     * After a successful restore the caller must refresh the currently displayed Preference items;
     * this only writes the values into SharedPreferences and the database.
     *
     * @return true = parse and write succeeded
     */
    fun import(context: Context, input: java.io.InputStream): Boolean {
        return try {
            val text = input.bufferedReader().use { it.readText() }
            val root = JSONObject(text)
            if (root.optString("magic") != MAGIC) {
                Log.w(TAG, "import_bad_magic")
                return false
            }

            val sp = context.getSharedPreferences(
                com.moonlight.freeform.app.MiFreeform.APP_SETTINGS_NAME,
                Context.MODE_PRIVATE
            )
            // Restore settings first, then the app lists; the two are independent.
            root.optJSONArray("settings")?.let { fromJson(sp, it) }
            // Backup files exported before the rename contain key names with the old prefix; just written, they'd still be dead records that can't be read,
            // so migrate again immediately here rather than waiting for the next launch.
            SettingsKeyMigration.migrateFrom(sp)

            val repository = DatabaseRepository(context)
            root.optJSONArray("freeform_apps")?.let { freeformAppsFromJson(repository, it) }
            root.optJSONArray("notification_apps")?.let { notificationAppsFromJson(repository, it) }

            // The notification freeform app list relies on this switch to trigger a UI refresh; without toggling it the UI won't update after restore.
            sp.edit().putBoolean("notify_freeform_changed", !sp.getBoolean("notify_freeform_changed", false)).apply()

            true
        } catch (e: Throwable) {
            Log.w(TAG, "import_failed", e)
            false
        }
    }

    /** Serializes all key-value pairs into a JSON array. */
    private fun settingsToJson(sp: SharedPreferences): JSONArray {
        val arr = JSONArray()
        for (key in sp.all.keys) {
            // Runtime state and first-launch detection fields are not backed up.
            if (key in EXCLUDED_KEYS) continue
            val entry = JSONObject()
            entry.put("key", key)
            when (val value = sp.all[key]) {
                is Boolean -> {
                    entry.put("type", "boolean")
                    entry.put("value", value)
                }
                is Int -> {
                    entry.put("type", "int")
                    entry.put("value", value)
                }
                is Long -> {
                    entry.put("type", "long")
                    entry.put("value", value)
                }
                is Float -> {
                    entry.put("type", "float")
                    entry.put("value", value.toDouble())
                }
                is String -> {
                    entry.put("type", "string")
                    entry.put("value", value)
                }
                is Set<*> -> {
                    entry.put("type", "set")
                    entry.put("value", JSONArray(value.mapNotNull { it?.toString() }))
                }
                else -> continue
            }
            arr.put(entry)
        }
        return arr
    }

    /** Writes back each entry according to its recorded type. */
    private fun fromJson(sp: SharedPreferences, arr: JSONArray) {
        val editor = sp.edit()
        for (i in 0 until arr.length()) {
            val entry = arr.optJSONObject(i) ?: continue
            val key = entry.optString("key")
            if (key.isEmpty()) continue
            when (entry.optString("type")) {
                "boolean" -> editor.putBoolean(key, entry.optBoolean("value"))
                "int" -> editor.putInt(key, entry.optInt("value"))
                "long" -> editor.putLong(key, entry.optLong("value"))
                "float" -> editor.putFloat(key, entry.optDouble("value").toFloat())
                "string" -> editor.putString(key, entry.optString("value"))
                "set" -> {
                    val set = mutableSetOf<String>()
                    val values = entry.optJSONArray("value") ?: continue
                    for (j in 0 until values.length()) {
                        values.optString(j).takeIf { it.isNotEmpty() }?.let { set.add(it) }
                    }
                    editor.putStringSet(key, set)
                }
            }
        }
        editor.apply()
    }

    /**
     * The sidebar shortcut app list.
     *
     * sortNum is the ordering key and must be exported together, otherwise the app order would be scrambled after restore.
     * The order of array elements in the backup file is exactly the top-to-bottom order in the sidebar.
     */
    private fun appsToJson(repository: DatabaseRepository): JSONArray {
        val arr = JSONArray()
        try {
            for (entity in repository.getAllFreeFormEntitiesSync()) {
                arr.put(JSONObject().apply {
                    put("sortNum", entity.sortNum)
                    put("packageName", entity.packageName)
                    put("userId", entity.userId)
                })
            }
        } catch (e: Throwable) {
            Log.w(TAG, "freeform_apps_export_failed", e)
        }
        return arr
    }

    private fun notificationAppsToJson(repository: DatabaseRepository): JSONArray {
        val arr = JSONArray()
        try {
            for (entity in repository.getAllNotificationEntitiesSync()) {
                arr.put(JSONObject().apply {
                    put("packageName", entity.packageName)
                    put("userId", entity.userId)
                })
            }
        } catch (e: Throwable) {
            Log.w(TAG, "notification_apps_export_failed", e)
        }
        return arr
    }

    private fun freeformAppsFromJson(repository: DatabaseRepository, arr: JSONArray) {
        try {
            // Full replacement: clear first, then write back entry by entry in backup order, otherwise it would mix with existing records.
            repository.deleteAllFreeForm()
            for (i in 0 until arr.length()) {
                val entry = arr.optJSONObject(i) ?: continue
                val packageName = entry.optString("packageName")
                if (packageName.isEmpty()) continue
                repository.insertFreeFormWithSortNum(
                    sortNum = entry.optInt("sortNum", i),
                    packageName = packageName,
                    userId = entry.optInt("userId", 0),
                )
            }
        } catch (e: Throwable) {
            Log.w(TAG, "freeform_apps_import_failed", e)
        }
    }

    private fun notificationAppsFromJson(repository: DatabaseRepository, arr: JSONArray) {
        try {
            repository.deleteAllNotification()
            for (i in 0 until arr.length()) {
                val entry = arr.optJSONObject(i) ?: continue
                val packageName = entry.optString("packageName")
                if (packageName.isEmpty()) continue
                repository.insertNotificationWithUserId(packageName, entry.optInt("userId", 0))
            }
        } catch (e: Throwable) {
            Log.w(TAG, "notification_apps_import_failed", e)
        }
    }

    /** Keys excluded from backup: runtime position + first-launch detection fields. */
    private val EXCLUDED_KEYS = setOf(
        "version", "version_privacy",
        "freeform_remember_x", "freeform_remember_y",
        "freeform_remember_land_x", "freeform_remember_land_y",
        "freeform_remember_height", "freeform_remember_land_height",
    )
}
