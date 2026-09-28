package com.dinh.aicamera.composition

import android.graphics.PointF
import android.graphics.RectF

enum class AiStage {
    SCANNING, // Bước 1: Quét khung hình (hiệu ứng quét nhẹ, chưa hiện gợi ý dồn dập)
    GUIDING,  // Bước 2: Hiện vòng tròn vàng đích + mũi tên hướng lia máy
    ALIGNED   // Bước 3: Đã vào vùng đích -> tự động zoom mượt (1.0x-3.0x), hiện điểm số, đếm 1s auto-capture
}

data class CompositionState(
    val stage: AiStage = AiStage.SCANNING,
    val hasSubject: Boolean = false,
    val isFace: Boolean = false,
    val subjectBounds: RectF = RectF(),
    val subjectCenter: PointF = PointF(),
    val targetPoint: PointF = PointF(),
    val distanceToTarget: Float = 0f,
    val rollAngle: Float = 0f,
    val pitchAngle: Float = 0f,
    val score: Int = 0,
    val guidanceText: String = "",
    val isAutoCaptureReady: Boolean = false,
    val autoCaptureProgress: Float = 0f,
    val targetZoomRatio: Float = 1.0f,
    val shouldZoom: Boolean = false,
    val gridThirdsHorizontal: FloatArray = floatArrayOf(0f, 0f),
    val gridThirdsVertical: FloatArray = floatArrayOf(0f, 0f)
)
