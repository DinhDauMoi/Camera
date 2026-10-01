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
            FilterType.CUSTOM -> {
                // Mặc định cho CUSTOM là ma trận gốc (50/50/50 + tâm 0.5f/0.5f)
                return getCustomMatrix()
            }
        }
        return matrix
    }

    /**
     * Tạo ColorMatrix tùy chỉnh phong cách iPhone:
     * @param tone: 0..100 (mặc định 50) - sáng/tối pivot
     * @param warmth: 0..100 (mặc định 50) - lạnh <-> ấm
     * @param vivid: 0..100 (mặc định 50) - bão hòa màu 0.4..1.6
     * @param gridX: 0..1 (mặc định 0.5f) - góc cam (ấm) <-> góc xanh (lạnh)
     * @param gridY: 0..1 (mặc định 0.5f) - cường độ theo trục dọc
     */
    fun getCustomMatrix(
        tone: Int = 50,
        warmth: Int = 50,
        vivid: Int = 50,
        gridX: Float = 0.5f,
        gridY: Float = 0.5f
    ): ColorMatrix {
        // 1. Rực rỡ: setSaturation map 0..100 -> 0.4..1.6
        val sat = 0.4f + (vivid.coerceIn(0, 100) / 100f) * 1.2f
        val satMatrix = ColorMatrix().apply { setSaturation(sat) }

        // 2. Ấm: lệch kênh R/B quanh điểm 50 (warmth > 50 tăng R giảm B và ngược lại)
        val wRatio = (warmth.coerceIn(0, 100) - 50) / 50f // -1.0 .. +1.0
        val warmthMatrix = ColorMatrix(floatArrayOf(
            1f + wRatio * 0.16f, 0f, 0f, 0f, wRatio * 18f,
            0f, 1f + wRatio * 0.04f, 0f, 0f, wRatio * 4f,
            0f, 0f, 1f - wRatio * 0.16f, 0f, -wRatio * 18f,
            0f, 0f, 0f, 1f, 0f
        ))

        // 3. Tông: brightness / contrast pivot quanh 50
        val tRatio = (tone.coerceIn(0, 100) - 50) / 50f // -1.0 .. +1.0
        val contrast = 1f + tRatio * 0.22f
        val brightness = tRatio * 32f
        val toneMatrix = ColorMatrix(floatArrayOf(
            contrast, 0f, 0f, 0f, brightness,
            0f, contrast, 0f, 0f, brightness,
            0f, 0f, contrast, 0f, brightness,
            0f, 0f, 0f, 1f, 0f
        ))

        // 4. Lưới màu 2D:
        // trục ngang = sắc ấm/lạnh (cam <-> xanh dương), góc cam = ấm (trái), góc xanh = lạnh (phải)
        // trục dọc = cường độ
        val gx = (gridX.coerceIn(0f, 1f) - 0.5f) * 2f // -1.0 (cam) .. +1.0 (xanh)
        val intensity = (0.35f + (1f - gridY.coerceIn(0f, 1f)) * 0.65f) // cường độ theo trục dọc
        val ny = (0.5f - gridY.coerceIn(0f, 1f)) * 2f // trục dọc điều chỉnh độ sâu ánh sáng

        val tintR: Float
        val tintG: Float
        val tintB: Float

        if (gx < 0f) {
            // Nghiêng cam: tăng R, tăng G nhẹ, giảm B
            val camWeight = -gx * intensity
            tintR = camWeight * 26f + ny * 6f
            tintG = camWeight * 10f + ny * 6f
            tintB = -camWeight * 20f + ny * 6f
        } else {
            // Nghiêng xanh dương: tăng B, giảm R
            val blueWeight = gx * intensity
            tintR = -blueWeight * 22f + ny * 6f
            tintG = -blueWeight * 4f + ny * 6f
            tintB = blueWeight * 26f + ny * 6f
        }

        val gridMatrix = ColorMatrix(floatArrayOf(
            1f, 0f, 0f, 0f, tintR,
            0f, 1f, 0f, 0f, tintG,
            0f, 0f, 1f, 0f, tintB,
            0f, 0f, 0f, 1f, 0f
        ))

        // Ghép các matrix theo thứ tự hợp lý bằng setConcat: Tone -> Warmth -> Vivid (Sat) -> Grid Tint
        val step1 = ColorMatrix().apply { setConcat(toneMatrix, warmthMatrix) }
        val step2 = ColorMatrix().apply { setConcat(step1, satMatrix) }
        val finalMatrix = ColorMatrix().apply { setConcat(step2, gridMatrix) }

        return finalMatrix
    }

    /**
     * Áp dụng ColorMatrix trực tiếp lên Bitmap gốc khi lưu vào MediaStore
     */
    fun applyFilterToBitmap(
        source: Bitmap,
        filterType: FilterType,
        customMatrix: ColorMatrix? = null
    ): Bitmap {
        if (filterType == FilterType.NONE) return source

        val matrix = if (filterType == FilterType.CUSTOM) {
            customMatrix ?: getCustomMatrix()
        } else {
            getColorMatrix(filterType)
        }

        return applyFilterWithMatrix(source, matrix)
    }

    /**
     * Áp dụng ma trận ColorMatrix bất kỳ lên Bitmap
     */
    fun applyFilterWithMatrix(source: Bitmap, matrix: ColorMatrix): Bitmap {
        val output = Bitmap.createBitmap(source.width, source.height, source.config ?: Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(matrix)
        }
        canvas.drawBitmap(source, 0f, 0f, paint)
        return output
    }
}
