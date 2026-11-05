package com.moonlight.freeform.utils

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Log
import androidx.annotation.StringRes

/**
 * 可回放的失败记录。
 *
 * 米窗大量功能依赖 Shizuku binder 与系统隐藏 API，这些环节失败时过去只有两种表现：
 * 要么弹一个转瞬即逝的 Toast（用户没看见就等于没发生），要么被 catch 吞掉（连 Toast 都没有）。
 * 本记录器把失败写进独立的 SharedPreferences，进程被杀、重启之后仍然读得到，
 * 于是「点了没反应」这件事第一次有了可以事后查看的证据。
 *
 * 写入点应当满足：这次失败是用户能感知的（开不了小窗、看不见悬浮窗、设置没生效），
 * 而不是内部无害的兼容性试探。
 */
object DiagnosticsLog {

    private const val TAG = "DiagnosticsLog"
    private const val SP_NAME = "mifreeform_diagnostics"
    private const val KEY_RECORDS = "records"
    // 记录之间用换行分隔；消息内的换行在编码时就换成空格，所以分割始终是安全的。
    private const val FIELD_SEPARATOR = '\u001E'
    private const val MAX_RECORDS = 20

    /**
     * 同一 (tag, message) 在这个时间窗口内只落一条。
     *
     * 必须有这条：等 binder 的轮询与隐藏窗看门狗都是定时重复的，同一个失败会被成十倍地
     * 重放（实测一次 8 秒的等待写出 41 条）。而每条记录都会重写整个 SharedPreferences、
     * 并刷新一次常驻通知，所以洪水不只是难看：它会让通知在用户眼前反复闪。
     */
    private const val DEDUPE_WINDOW_MS = 5_000L

    // 只用于限流的内存表，不跨进程；上限防泄漏。
    private const val DEDUPE_TABLE_LIMIT = 64
    private val lastWriteUptime = HashMap<String, Long>()

    /** 一条记录：时间戳、来源标签、面向用户的简短描述。 */
    data class Entry(val timeMillis: Long, val tag: String, val message: String)

    fun interface Listener {
        fun onRecorded(entry: Entry)
    }

    private val listeners = mutableListOf<Listener>()

    /**
     * 记下一条失败。可在任意线程调用：落盘走 apply()，不阻塞调用方。
     *
     * 同时输出到 logcat，保证即使用户不去看记录，抓日志的人也能拿到同一份证据。
     *
     * ⚠ 正文是常驻通知给用户看的那一行（F3），所以**不要在这里直接拼中文字面量**——
     * 那样英文用户拿到的通知会标题英文、正文中文。请用下面的 [recordRes]。
     */
    fun record(context: Context, tag: String, message: String) {
        if (!shouldWrite(tag, message)) {
            return
        }
        val entry = Entry(System.currentTimeMillis(), tag, message)
        Log.w("$TAG/$tag", message)
        try {
            val sp = context.applicationContext.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
            val records = read(sp)
            records.add(entry)
            val trimmed = if (records.size > MAX_RECORDS) {
                records.subList(records.size - MAX_RECORDS, records.size)
            } else {
                records
            }
            sp.edit()
                .putString(KEY_RECORDS, trimmed.joinToString("\n") { encode(it) })
                .apply()
        } catch (e: Exception) {
            // 记录器本身坏掉不能把调用方带崩，logcat 那行已经出去了。
            Log.w(TAG, "persist_failed", e)
        }
        notifyListeners(entry)
    }

    /**
     * 面向用户的记录走资源 ID，而不是代码里写死的中文。
     *
     * 记录正文会被常驻通知直接显示（F3），它属于用户能看见的界面文案，
     * 就得和 Toast 一样跟着 locale 走。拿不到资源（极端情况下 context 不对）时退回资源名，
     * 不能因为本地化失败就连记录都不落——记录比措辞重要。
     */
    fun recordRes(context: Context, tag: String, @StringRes resId: Int, vararg args: Any) {
        val message = try {
            context.getString(resId, *args)
        } catch (e: Exception) {
            Log.w(TAG, "record_resolve_failed tag=$tag resId=$resId", e)
            context.resources.getResourceEntryName(resId)
        }
        record(context, tag, message)
    }

    /** 最近一条记录，没有则 null。 */
    fun latest(context: Context): Entry? = read(context).lastOrNull()

    /** 全部记录，按时间从早到晚。 */
    fun all(context: Context): List<Entry> = read(context)

    fun clear(context: Context) {
        try {
            context.applicationContext
                .getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
                .edit().remove(KEY_RECORDS).apply()
        } catch (e: Exception) {
            Log.w(TAG, "clear_failed", e)
        }
    }

    fun addListener(listener: Listener) {
        synchronized(listeners) { listeners.add(listener) }
    }

    fun removeListener(listener: Listener) {
        synchronized(listeners) { listeners.remove(listener) }
    }

    private fun notifyListeners(entry: Entry) {
        val snapshot = synchronized(listeners) { listeners.toList() }
        for (listener in snapshot) {
            try {
                listener.onRecorded(entry)
            } catch (e: Exception) {
                Log.w(TAG, "listener_failed", e)
            }
        }
    }

    /**
     * 限流判定：距上一条同内容记录不足 [DEDUPE_WINDOW_MS] 则丢弃。
     *
     * 丢弃时仍给一行 verbose，便于排查「为什么这条记录没出现」。
     */
    private fun shouldWrite(tag: String, message: String): Boolean {
        val key = "$tag$FIELD_SEPARATOR$message"
        val now = SystemClock.uptimeMillis()
        synchronized(lastWriteUptime) {
            val last = lastWriteUptime[key]
            if (last != null && now - last < DEDUPE_WINDOW_MS) {
                Log.v(TAG, "record_deduped tag=$tag")
                return false
            }
            if (lastWriteUptime.size >= DEDUPE_TABLE_LIMIT) {
                lastWriteUptime.clear()
            }
            lastWriteUptime[key] = now
        }
        return true
    }

    private fun read(context: Context): MutableList<Entry> =
        read(context.applicationContext.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE))

    private fun read(sp: SharedPreferences): MutableList<Entry> {
        return try {
            val raw = sp.getString(KEY_RECORDS, null)
            if (raw.isNullOrEmpty()) {
                mutableListOf()
            } else {
                raw.split("\n")
                    .mapNotNull { decode(it) }
                    .toMutableList()
            }
        } catch (e: Exception) {
            Log.w(TAG, "read_failed", e)
            mutableListOf()
        }
    }

    // 一条记录形如  time\u001Etag\u001Emessage；消息里若又出现分隔符，解析时按剩余部分合并回来。
    private fun encode(entry: Entry): String =
        "${entry.timeMillis}$FIELD_SEPARATOR${entry.tag}$FIELD_SEPARATOR${entry.message.replace('\n', ' ').replace(FIELD_SEPARATOR, ' ')}"

    private fun decode(line: String): Entry? {
        val parts = line.split(FIELD_SEPARATOR)
        if (parts.size < 3) return null
        val time = parts[0].toLongOrNull() ?: return null
        return Entry(time, parts[1], parts.subList(2, parts.size).joinToString(FIELD_SEPARATOR.toString()))
    }
}
