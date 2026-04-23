package com.antigravity.pptremote

import android.content.Context

object RemotePrefs {
    private const val PREFS_NAME = "ppt_remote_prefs"
    private const val KEY_BRIDGE_URL = "bridge_url"
    private const val KEY_SELECTED_PRESENTATION_ID = "selected_presentation_id"

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
}
