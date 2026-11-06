package com.moonlight.freeform.utils

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Log
import androidx.annotation.StringRes

/**
 * A replayable record of failures.
 *
 * Many MiFreeform features depend on the Shizuku binder and system hidden APIs; when these steps failed, previously there were only two outcomes:
 * either a fleeting Toast (if the user didn't see it, it's as if it never happened), or swallowed by a catch (not even a Toast).
 * This logger writes failures into a dedicated SharedPreferences, still readable after the process is killed and restarted,
 * so the "tapped but nothing happened" issue now has after-the-fact evidence for the first time.
 *
 * Write sites should satisfy: the failure is user-perceivable (freeform won't open, floating window isn't visible, a setting didn't take effect),
 * not an internally harmless compatibility probe.
 */
object DiagnosticsLog {

    private const val TAG = "DiagnosticsLog"
    private const val SP_NAME = "mifreeform_diagnostics"
    private const val KEY_RECORDS = "records"
    // Records are separated by newlines; newlines inside a message are replaced with spaces at encode time, so splitting is always safe.
    private const val FIELD_SEPARATOR = '\u001E'
    private const val MAX_RECORDS = 20

    /**
     * The same (tag, message) is persisted only once within this time window.
     *
     * This rule is mandatory: the binder-wait polling and the hidden-window watchdog are both periodic repeats, so one failure
     * gets replayed tenfold (measured: a single 8-second wait wrote 41 records). And each record rewrites the whole SharedPreferences
     * and refreshes the ongoing notification once, so the flood isn't just unsightly—it makes the notification flicker repeatedly in front of the user.
     */
    private const val DEDUPE_WINDOW_MS = 5_000L

    // In-memory table used only for throttling; not cross-process; the cap prevents leaks.
    private const val DEDUPE_TABLE_LIMIT = 64
    private val lastWriteUptime = HashMap<String, Long>()

    /** One record: timestamp, source tag, and a short user-facing description. */
    data class Entry(val timeMillis: Long, val tag: String, val message: String)

    fun interface Listener {
        fun onRecorded(entry: Entry)
    }

    private val listeners = mutableListOf<Listener>()

    /**
     * Records a failure. Can be called from any thread: persistence uses apply(), not blocking the caller.
     *
     * Also outputs to logcat, ensuring that even if the user doesn't look at the records, whoever captures logs gets the same evidence.
     *
     * ⚠ The message is the line shown to the user in the ongoing notification (F3), so **do not concatenate Chinese literals here**—
     * that would give English users a notification with an English title but a Chinese body. Use [recordRes] below instead.
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
            // A broken logger must not crash the caller; the logcat line has already been emitted.
            Log.w(TAG, "persist_failed", e)
        }
        notifyListeners(entry)
    }

    /**
     * User-facing records go through resource IDs rather than hardcoded Chinese in code.
     *
     * The record body is shown directly by the ongoing notification (F3); it is UI text the user can see,
     * so it must follow the locale just like a Toast. When the resource can't be resolved (in edge cases the context is wrong), fall back to the resource name;
     * a localization failure must not cause us to skip persisting the record—the record matters more than the wording.
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

    /** The most recent record, or null if none. */
    fun latest(context: Context): Entry? = read(context).lastOrNull()

    /** All records, ordered from earliest to latest. */
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
     * Throttling decision: drops a record if it's within [DEDUPE_WINDOW_MS] of the previous identical one.
     *
     * On drop, still emit one verbose line, to help investigate "why this record didn't appear".
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

    // A record is shaped like time\u001Etag\u001Emessage; if the separator appears again in the message, it is merged back as the remainder when parsing.
    private fun encode(entry: Entry): String =
        "${entry.timeMillis}$FIELD_SEPARATOR${entry.tag}$FIELD_SEPARATOR${entry.message.replace('\n', ' ').replace(FIELD_SEPARATOR, ' ')}"

    private fun decode(line: String): Entry? {
        val parts = line.split(FIELD_SEPARATOR)
        if (parts.size < 3) return null
        val time = parts[0].toLongOrNull() ?: return null
        return Entry(time, parts[1], parts.subList(2, parts.size).joinToString(FIELD_SEPARATOR.toString()))
    }
}
