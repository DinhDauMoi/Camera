package com.dinh.aicamera.filter

import androidx.annotation.StringRes
import com.dinh.aicamera.R

enum class FilterType(val id: String, @StringRes val titleRes: Int) {
    NONE("none", R.string.filter_none),
    BRIGHT("bright", R.string.filter_bright),
    MUTED("muted", R.string.filter_muted),
    BW("bw", R.string.filter_bw),
    WARM("warm", R.string.filter_warm),
    COOL("cool", R.string.filter_cool),
    FILM("film", R.string.filter_film),
    CUSTOM("custom", R.string.filter_custom)
}
