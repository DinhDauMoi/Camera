package com.dinh.aicamera.ui

import android.content.Context
import android.content.SharedPreferences

class AppPreferences(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("aicamera_prefs", Context.MODE_PRIVATE)

    var isAiEnabled: Boolean
        get() = prefs.getBoolean(KEY_AI_ENABLED, false) // Mặc định TẮT theo yêu cầu
        set(value) = prefs.edit().putBoolean(KEY_AI_ENABLED, value).apply()

    var isGridEnabled: Boolean
        get() = prefs.getBoolean(KEY_GRID_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_GRID_ENABLED, value).apply()

    var isAutoCaptureEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_CAPTURE, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_CAPTURE, value).apply()

    var isAutoZoomEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_ZOOM, true) // Mặc định BẬT theo yêu cầu
        set(value) = prefs.edit().putBoolean(KEY_AUTO_ZOOM, value).apply()

    var isAutoCheckUpdate: Boolean
        get() = prefs.getBoolean(KEY_AUTO_CHECK_UPDATE, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_CHECK_UPDATE, value).apply()

    var ignoredUpdateVersion: String
        get() = prefs.getString(KEY_IGNORED_UPDATE_VERSION, "") ?: ""
        set(value) = prefs.edit().putString(KEY_IGNORED_UPDATE_VERSION, value).apply()

    companion object {
        private const val KEY_AI_ENABLED = "key_ai_enabled"
        private const val KEY_GRID_ENABLED = "key_grid_enabled"
        private const val KEY_AUTO_CAPTURE = "key_auto_capture"
        private const val KEY_AUTO_ZOOM = "key_auto_zoom"
        private const val KEY_AUTO_CHECK_UPDATE = "key_auto_check_update"
        private const val KEY_IGNORED_UPDATE_VERSION = "key_ignored_update_version"
    }
}
