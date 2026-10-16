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
 * 设置备份与恢复。
 *
 * 备份范围一共有三块：
 * 1. APP_SETTINGS_NAME（"app_settings"）SharedPreferences —— 设置页里的全部开关与参数；
 * 2. FreeFormAppsEntity —— 侧边栏快捷应用列表（含 sortNum 排序顺序）；
 * 3. NotificationAppsEntity —— 通知栏小窗应用列表。
 *
 * 后两块存在 Room 数据库里，不在 SharedPreferences 里，所以不能光遍历 sp.all，
 * 要单独走 DatabaseRepository 导出/导入。
 *
 * 格式选 JSON 而不是直接复制 SharedPreferences 的 xml 文件：
 * xml 里带有类型前缀（int/boolean/set），跨版本/跨机型恢复时容易出错；
 * JSON 显式记录类型，恢复时按类型写回，可读性也好，出问题时能直接看。
 *
 * 不备份的东西：小窗运行时位置（每次打开都会重算）、版本号字段（version/
 * version_privacy 是用于判断是否首次启动的，恢复过去没有意义）。
 */
object SettingsBackup {

    private const val TAG = "SettingsBackup"

    /** 备份文件的 magic header，用于恢复时校验是不是本应用的备份。 */
    private const val MAGIC = "MiFreeformSettings"

    private const val VERSION = 2

    /**
     * 导出全部设置到指定输出流（由调用方提供，通常是 SAF 选的文件）。
     *
     * @return true = 写入成功
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
            // 侧边栏快捷应用 + 通知栏应用存在数据库里，不在 sp 里，单独导出。
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
     * 从输入流恢复设置（SAF 选的文件）。
     *
     * 恢复成功后调用方需要刷新当前已显示的 Preference 项，
     * 这里只负责把值写进 SharedPreferences 与数据库。
     *
     * @return true = 解析并写入成功
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
            // 先恢复设置项，再恢复应用列表，两者互不依赖。
            root.optJSONArray("settings")?.let { fromJson(sp, it) }
            // 改名之前导出的备份文件里是带旧前缀的键名，刚写进去还是读不到的死档，
            // 这里立即再搬迁一次，不等到下次启动。
            SettingsKeyMigration.migrateFrom(sp)

            val repository = DatabaseRepository(context)
            root.optJSONArray("freeform_apps")?.let { freeformAppsFromJson(repository, it) }
            root.optJSONArray("notification_apps")?.let { notificationAppsFromJson(repository, it) }

            // 通知小窗的应用列表靠这个开关触发界面刷新，不捅一下恢复后界面不会变。
            sp.edit().putBoolean("notify_freeform_changed", !sp.getBoolean("notify_freeform_changed", false)).apply()

            true
        } catch (e: Throwable) {
            Log.w(TAG, "import_failed", e)
            false
        }
    }

    /** 把全部键值序列化成 JSON 数组。 */
    private fun settingsToJson(sp: SharedPreferences): JSONArray {
        val arr = JSONArray()
        for (key in sp.all.keys) {
            // 运行时状态与版本判断字段不备份。
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

    /** 按记录的类型逐条写回。 */
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
     * 侧边栏快捷应用列表。
     *
     * sortNum 是排序主键，必须一起导出，不然恢复回来应用顺序就乱了。
     * 备份文件里数组元素的顺序就是侧边栏里从上到下的顺序。
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
            // 全量替换：先清空再按备份顺序逐条写回，否则会和现有记录混在一起。
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

    /** 不参与备份的键：运行时位置 + 首次启动判断字段。 */
    private val EXCLUDED_KEYS = setOf(
        "version", "version_privacy",
        "freeform_remember_x", "freeform_remember_y",
        "freeform_remember_land_x", "freeform_remember_land_y",
        "freeform_remember_height", "freeform_remember_land_height",
    )
}
