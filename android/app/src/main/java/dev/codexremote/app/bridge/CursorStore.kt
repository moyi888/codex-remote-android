package dev.codexremote.app.bridge

import android.content.Context
import android.content.SharedPreferences

/** Runs on the stream event thread; implementations must not wait for another thread calling the stream. */
internal interface CursorStore {
    fun load(): Long

    fun save(cursor: Long)

    fun clear()
}

internal class SharedPreferencesCursorStore(context: Context) : CursorStore {
    private val preferences: SharedPreferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    override fun load(): Long {
        val cursor = preferences.getLong(CURSOR_KEY, DEFAULT_CURSOR)
        check(cursor >= 0) { "Saved event cursor is invalid" }
        return cursor
    }

    override fun save(cursor: Long) {
        require(cursor >= 0) { "Event cursor must be non-negative" }
        check(preferences.edit().putLong(CURSOR_KEY, cursor).commit()) {
            "Unable to persist event cursor"
        }
    }

    override fun clear() {
        check(preferences.edit().remove(CURSOR_KEY).commit()) {
            "Unable to clear event cursor"
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "bridge_event_stream"
        const val CURSOR_KEY = "event_cursor"
        const val DEFAULT_CURSOR = 0L
    }
}
