package com.antigravity.pptremote

import android.content.Context

object RemotePrefs {
    private const val PREFS_NAME = "ppt_remote_prefs"
    private const val KEY_BRIDGE_URL = "bridge_url"
    private const val KEY_SELECTED_PRESENTATION_ID = "selected_presentation_id"
    private const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"
    private const val KEY_BRIDGE_PORT = "bridge_port"
    private const val KEY_POLLING_INTERVAL = "polling_interval_seconds"
    private const val KEY_IS_DARK_THEME = "is_dark_theme"
    private const val KEY_CONNECTION_HISTORY = "connection_history"
    private const val KEY_NOTIFICATION_TEXT = "notification_text"
    private const val KEY_API_KEY = "api_key"

    fun setBridgeUrl(context: Context, url: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_BRIDGE_URL, url)
            .apply()
    }

    fun getBridgeUrl(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_BRIDGE_URL, "")
            .orEmpty()

    fun setSelectedPresentationId(context: Context, id: String?) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SELECTED_PRESENTATION_ID, id)
            .apply()
    }

    fun getSelectedPresentationId(context: Context): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SELECTED_PRESENTATION_ID, null)

    fun setOnboardingCompleted(context: Context, completed: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ONBOARDING_COMPLETED, completed)
            .apply()
    }

    fun isOnboardingCompleted(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ONBOARDING_COMPLETED, false)

    fun setBridgePort(context: Context, port: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_BRIDGE_PORT, port)
            .apply()
    }

    fun getBridgePort(context: Context): Int =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_BRIDGE_PORT, 8787)

    fun setPollingInterval(context: Context, seconds: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_POLLING_INTERVAL, seconds)
            .apply()
    }

    fun getPollingInterval(context: Context): Int =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_POLLING_INTERVAL, 3)

    fun setDarkTheme(context: Context, isDark: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_IS_DARK_THEME, isDark)
            .apply()
    }

    fun isDarkTheme(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_IS_DARK_THEME, true)

    fun addToConnectionHistory(context: Context, url: String) {
        if (url.isBlank()) return

        val history = getConnectionHistory(context).toMutableList()
        history.remove(url)          // remove duplicate if already present
        history.add(url)             // add to end (most recent)

        // Keep only the last 10 connections
        val limitedHistory = if (history.size > 10) history.takeLast(10) else history

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_CONNECTION_HISTORY, limitedHistory.toSet())
            .apply()
    }

    fun getConnectionHistory(context: Context): List<String> =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getStringSet(KEY_CONNECTION_HISTORY, emptySet())
            ?.toList()
            ?.sorted() ?: emptyList()

    fun removeFromConnectionHistory(context: Context, url: String) {
        val history = getConnectionHistory(context).toMutableSet()
        history.remove(url)
        
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_CONNECTION_HISTORY, history)
            .apply()
    }

    fun setNotificationText(context: Context, text: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_NOTIFICATION_TEXT, text)
            .apply()
    }

    fun getNotificationText(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_NOTIFICATION_TEXT, "Tap ⏮ ⏭ to change slides — works with screen off")
            .orEmpty()

    fun setApiKey(context: Context, key: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_API_KEY, key).apply()
    }

    fun getApiKey(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_API_KEY, "").orEmpty()
}
