package com.dinh.aicamera.ui

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.View

object LiquidGlassHelper {

    /**
     * Áp dụng hiệu ứng Blur thời gian thực cho Android 12+ (API 31+).
     * Thiết bị cũ hơn tự động fallback sử dụng nền mờ bán trong suốt (alpha + stroke).
     */
    fun applyBlurEffect(view: View, radiusDp: Float = 20f) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val radiusPx = radiusDp * view.resources.displayMetrics.density
            val blurEffect = RenderEffect.createBlurEffect(radiusPx, radiusPx, Shader.TileMode.CLAMP)
            view.setRenderEffect(blurEffect)
        }
    }

    fun clearBlurEffect(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            view.setRenderEffect(null)
        }
    }
}
