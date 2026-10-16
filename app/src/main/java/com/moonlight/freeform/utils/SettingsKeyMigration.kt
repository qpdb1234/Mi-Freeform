package com.moonlight.freeform.utils

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.moonlight.freeform.app.MiFreeform

/**
 * 设置项键名搬迁。
 *
 * 顶栏按钮动作、收起方式、悬浮球大小这些设置，早期版本的键名带一段品牌前缀
 * （形如 `<旧前缀>_top_bar_slot0`），现在统一改成不带前缀的名字。键名一改，
 * 手机上已经存过的值就再也读不到了，设置页会退回默认值，所以这里在进程启动时
 * 做一次搬迁：把旧键的值搬到新键，随后删掉旧键。
 *
 * 这里刻意不写出旧前缀的原文（这个仓库不再出现那个词），改成按**后缀**认旧键：
 * 只要某个存量键以列出的新键名结尾、且在分段处正好接一段前缀，就认定它是改名前的旧档。
 * 七个新键名在本应用里都是唯一的完整分段，不会有别的设置项恰好以它们结尾，
 * 所以这个判定足够安全。
 *
 * 搬迁按值的实际类型分派。当前这些设置全是 int（IntegerSimpleMenuPreference 用
 * persistInt，NumericSeekBarPreference 同理），但保留其它分支，以后加设置项不用再改这里。
 * 旧键搬完就删，所以之后每次启动只是遍历一遍存量键，代价可以忽略。
 *
 * 除了 MiFreeform.onCreate，SettingsBackup.import 之后也要调一次：改名之前导出的
 * 备份文件里存的是旧键名，直接恢复会写成一堆读不到的死档。
 */
object SettingsKeyMigration {

    private const val TAG = "SettingsKeyMigration"

    /** 改名后的键名，全部位于 APP_SETTINGS_NAME 这个 SharedPreferences。 */
    private val CURRENT_KEYS = arrayOf(
        "top_bar_slot0",
        "top_bar_slot1",
        "top_bar_slot2",
        "bottom_double_tap_action",
        "minimize_mode",
        "miniball_size_dp",
        "miniball_alpha",
    )

    /** 进程启动时用的入口，自己去取 app_settings。 */
    fun migrate(context: Context) {
        migrateFrom(
            context.getSharedPreferences(MiFreeform.APP_SETTINGS_NAME, Context.MODE_PRIVATE)
        )
    }

    /**
     * 在给定 SharedPreferences 上执行搬迁。
     *
     * @param sp 设置页实际使用的那份偏好（app_settings）
     * @return 本次搬迁的键数量
     */
    fun migrateFrom(sp: SharedPreferences): Int {
        val all = sp.all
        val editor = sp.edit()
        var moved = 0
        for (newKey in CURRENT_KEYS) {
            // 改名前存下的那个键：以新键名结尾，前面还多一段前缀（分隔处必须是 '_'）。
            val oldKey = all.keys.firstOrNull { key ->
                key.length > newKey.length + 1 &&
                    key.endsWith(newKey) &&
                    key[key.length - newKey.length - 1] == '_'
            } ?: continue
            val value = all[oldKey] ?: continue
            // 新键已经有值时保留新键：用户可能在改名后又动过这个设置，不能被旧档盖掉。
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
                    // 认不出类型的值不搬，也先不删旧键，留给以后版本处理。
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
