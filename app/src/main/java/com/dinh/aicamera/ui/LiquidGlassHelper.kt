package com.dinh.aicamera.ui

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.View
import android.view.ViewOutlineProvider

object LiquidGlassHelper {

    /**
     * Chuẩn hóa Liquid Glass:
     * - Luôn bật clipToOutline = true theo đường viền bo tròn hoàn toàn (chống vệt nhòe tràn ra ngoài).
     * - Giữ icon và chữ bên trong sắc nét 100%.
     */
    fun setupGlass(view: View) {
        view.outlineProvider = ViewOutlineProvider.BACKGROUND
        view.clipToOutline = true
    }

    fun setupGlassPill(view: View) {
        setupGlass(view)
    }

    fun applyBlurEffect(backdropView: View, radiusDp: Float = 16f) {
        setupGlass(backdropView)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val radiusPx = radiusDp * backdropView.resources.displayMetrics.density
            val blurEffect = RenderEffect.createBlurEffect(radiusPx, radiusPx, Shader.TileMode.CLAMP)
            backdropView.setRenderEffect(blurEffect)
        }
    }

    fun clearBlurEffect(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            view.setRenderEffect(null)
        }
    }
}
