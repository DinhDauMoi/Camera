package com.dinh.aicamera.ui

import android.content.Context
import android.content.SharedPreferences

class AppPreferences(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("aicamera_prefs", Context.MODE_PRIVATE)

    var isAiEnabled: Boolean
        get() = prefs.getBoolean(KEY_AI_ENABLED, false) // Mặc định TẮT theo yêu cầu
        set(value) = prefs.edit().putBoolean(KEY_AI_ENABLED, value).apply()

    // 0 = TẮT, 1 = 1/3, 2 = Golden ratio, 3 = Đường chéo, 4 = Trung tâm
    var gridMode: Int
        get() {
            if (prefs.contains(KEY_GRID_MODE)) {
                return prefs.getInt(KEY_GRID_MODE, 0)
            }
            // Migrate từ pref cũ KEY_GRID_ENABLED
            if (prefs.contains(KEY_GRID_ENABLED)) {
                val legacy = prefs.getBoolean(KEY_GRID_ENABLED, false)
                val migrated = if (legacy) 1 else 0
                prefs.edit().putInt(KEY_GRID_MODE, migrated).remove(KEY_GRID_ENABLED).apply()
                return migrated
            }
            return 0
        }
        set(value) = prefs.edit().putInt(KEY_GRID_MODE, value).apply()

    var isHistogramEnabled: Boolean
        get() = prefs.getBoolean(KEY_HISTOGRAM_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_HISTOGRAM_ENABLED, value).apply()

    var isPoseGuideEnabled: Boolean
        get() = prefs.getBoolean(KEY_POSE_GUIDE_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_POSE_GUIDE_ENABLED, value).apply()

    var poseIndex: Int
        get() = prefs.getInt(KEY_POSE_INDEX, 0)
        set(value) = prefs.edit().putInt(KEY_POSE_INDEX, value).apply()

    var isTasteLearningEnabled: Boolean
        get() = prefs.getBoolean(KEY_TASTE_LEARNING_ENABLED, true) // Mặc định BẬT
        set(value) = prefs.edit().putBoolean(KEY_TASTE_LEARNING_ENABLED, value).apply()

    var isGoldenHourEnabled: Boolean
        get() = prefs.getBoolean(KEY_GOLDEN_HOUR_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_GOLDEN_HOUR_ENABLED, value).apply()

    var isLevelEnabled: Boolean
        get() = prefs.getBoolean(KEY_LEVEL_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_LEVEL_ENABLED, value).apply()

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

    var customFilterTone: Int
        get() = prefs.getInt(KEY_CUSTOM_FILTER_TONE, 50)
        set(value) = prefs.edit().putInt(KEY_CUSTOM_FILTER_TONE, value).apply()

    var customFilterWarmth: Int
        get() = prefs.getInt(KEY_CUSTOM_FILTER_WARMTH, 50)
        set(value) = prefs.edit().putInt(KEY_CUSTOM_FILTER_WARMTH, value).apply()

    var customFilterVivid: Int
        get() = prefs.getInt(KEY_CUSTOM_FILTER_VIVID, 50)
        set(value) = prefs.edit().putInt(KEY_CUSTOM_FILTER_VIVID, value).apply()

    var customFilterGridX: Float
        get() = prefs.getFloat(KEY_CUSTOM_FILTER_GRID_X, 0.5f)
        set(value) = prefs.edit().putFloat(KEY_CUSTOM_FILTER_GRID_X, value).apply()

    var customFilterGridY: Float
        get() = prefs.getFloat(KEY_CUSTOM_FILTER_GRID_Y, 0.5f)
        set(value) = prefs.edit().putFloat(KEY_CUSTOM_FILTER_GRID_Y, value).apply()

    /**
     * Lấy danh sách 9 counter cho vùng 3x3 (index 0..8)
     */
    fun getTasteCounters(): IntArray {
        val raw = prefs.getString(KEY_TASTE_COUNTERS, null) ?: return IntArray(9)
        val parts = raw.split(",")
        if (parts.size != 9) return IntArray(9)
        return IntArray(9) { i -> parts[i].toIntOrNull() ?: 0 }
    }

    /**
     * Ghi lại khi user chạm chọn dot ở tọa độ chuẩn hóa x, y in [0, 1].
     * Tổng số counter tối đa là 50 lượt, khi vượt quá thì scale down.
     */
    fun recordTasteDot(x: Float, y: Float) {
        val col = (x * 3f).toInt().coerceIn(0, 2)
        val row = (y * 3f).toInt().coerceIn(0, 2)
        val idx = row * 3 + col

        val counters = getTasteCounters()
        counters[idx]++

        val total = counters.sum()
        if (total > 50) {
            // Scale down về ~35 lượt để giữ tỉ lệ
            for (i in counters.indices) {
                counters[i] = (counters[i] * 35) / total
            }
        }

        val raw = counters.joinToString(",")
        prefs.edit().putString(KEY_TASTE_COUNTERS, raw).apply()
    }

    /**
     * Trả về tập hợp tối đa 3 index vùng (0..8) có counter cao nhất (counter > 0).
     */
    fun getTop3TasteRegions(): Set<Int> {
        val counters = getTasteCounters()
        return counters.mapIndexed { idx, count -> idx to count }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(3)
            .map { it.first }
            .toSet()
    }

    companion object {
        private const val KEY_AI_ENABLED = "key_ai_enabled"
        private const val KEY_GRID_ENABLED = "key_grid_enabled" // legacy
        private const val KEY_GRID_MODE = "key_grid_mode"
        private const val KEY_HISTOGRAM_ENABLED = "key_histogram_enabled"
        private const val KEY_POSE_GUIDE_ENABLED = "key_pose_guide_enabled"
        private const val KEY_POSE_INDEX = "key_pose_index"
        private const val KEY_TASTE_LEARNING_ENABLED = "key_taste_learning_enabled"
        private const val KEY_GOLDEN_HOUR_ENABLED = "key_golden_hour_enabled"
        private const val KEY_LEVEL_ENABLED = "key_level_enabled"
        private const val KEY_TASTE_COUNTERS = "key_taste_counters"
        private const val KEY_AUTO_CAPTURE = "key_auto_capture"
        private const val KEY_AUTO_ZOOM = "key_auto_zoom"
        private const val KEY_AUTO_CHECK_UPDATE = "key_auto_check_update"
        private const val KEY_IGNORED_UPDATE_VERSION = "key_ignored_update_version"
        private const val KEY_CUSTOM_FILTER_TONE = "key_custom_filter_tone"
        private const val KEY_CUSTOM_FILTER_WARMTH = "key_custom_filter_warmth"
        private const val KEY_CUSTOM_FILTER_VIVID = "key_custom_filter_vivid"
        private const val KEY_CUSTOM_FILTER_GRID_X = "key_custom_filter_grid_x"
        private const val KEY_CUSTOM_FILTER_GRID_Y = "key_custom_filter_grid_y"
    }
}
