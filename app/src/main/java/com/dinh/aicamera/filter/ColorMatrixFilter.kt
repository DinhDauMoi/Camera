package com.dinh.aicamera.filter

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint

object ColorMatrixFilter {

    fun getColorMatrix(filterType: FilterType): ColorMatrix {
        val matrix = ColorMatrix()
        when (filterType) {
            FilterType.NONE -> {
                matrix.reset()
            }
            FilterType.BRIGHT -> {
                // Sáng: Tăng sáng, tăng độ tươi tắn nhẹ
                matrix.set(floatArrayOf(
                    1.15f, 0f,    0f,    0f, 15f,
                    0f,    1.15f, 0f,    0f, 15f,
                    0f,    0f,    1.15f, 0f, 15f,
                    0f,    0f,    0f,    1f,  0f
                ))
            }
            FilterType.MUTED -> {
                // Trầm: Giảm bão hòa màu, tăng chiều sâu và độ tương phản nhẹ
                val satMatrix = ColorMatrix().apply { setSaturation(0.6f) }
                val contrastMatrix = ColorMatrix(floatArrayOf(
                    1.1f, 0f,   0f,   0f, -10f,
                    0f,   1.1f, 0f,   0f, -10f,
                    0f,   0f,   1.1f, 0f, -10f,
                    0f,   0f,   0f,   1f,  0f
                ))
                matrix.setConcat(contrastMatrix, satMatrix)
            }
            FilterType.BW -> {
                // Đen trắng: Tương phản cao cổ điển
                val bwMatrix = ColorMatrix().apply { setSaturation(0f) }
                val contrast = ColorMatrix(floatArrayOf(
                    1.25f, 0f,    0f,    0f, -20f,
                    0f,    1.25f, 0f,    0f, -20f,
                    0f,    0f,    1.25f, 0f, -20f,
                    0f,    0f,    0f,    1f,  0f
                ))
                matrix.setConcat(contrast, bwMatrix)
            }
            FilterType.WARM -> {
                // Ấm: Ánh nắng hoàng hôn, tone cam vàng nhẹ
                matrix.set(floatArrayOf(
                    1.20f, 0f,    0f,    0f, 20f,
                    0f,    1.08f, 0f,    0f, 10f,
                    0f,    0f,    0.85f, 0f, -15f,
                    0f,    0f,    0f,    1f,  0f
                ))
            }
            FilterType.COOL -> {
                // Lạnh: Tone xanh lạnh điện ảnh, hiện đại
                matrix.set(floatArrayOf(
                    0.88f, 0f,    0f,    0f, -12f,
                    0f,    1.02f, 0f,    0f,  0f,
                    0f,    0f,    1.22f, 0f, 22f,
                    0f,    0f,    0f,    1f,  0f
                ))
            }
            FilterType.FILM -> {
                // Film: Faded blacks, màu hoài niệm retro kiểu analog
                matrix.set(floatArrayOf(
                    1.08f, 0.05f, 0.02f, 0f, 18f,
                    0.02f, 1.05f, 0.05f, 0f, 14f,
                    0.03f, 0.04f, 0.95f, 0f, 22f,
                    0f,    0f,    0f,    1f,  0f
                ))
            }
        }
        return matrix
    }

    /**
     * Áp dụng ColorMatrix trực tiếp lên Bitmap gốc khi lưu vào MediaStore
     */
    fun applyFilterToBitmap(source: Bitmap, filterType: FilterType): Bitmap {
        if (filterType == FilterType.NONE) return source

        val output = Bitmap.createBitmap(source.width, source.height, source.config ?: Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(getColorMatrix(filterType))
        }
        canvas.drawBitmap(source, 0f, 0f, paint)
        return output
    }
}
