package com.dinh.aicamera.composition

import android.graphics.PointF
import android.graphics.RectF
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class CompositionEngine {

    var isAutoCaptureEnabled: Boolean = true
    var isAutoZoomEnabled: Boolean = true

    private var highQualityStartTime: Long = 0L
    private var isTriggeredForCurrentLock: Boolean = false
    private val requiredHoldTimeMs = 1000L // Duy trì 1 giây điểm > 85

    fun evaluate(
        subjectBox: RectF?,
        isFace: Boolean,
        screenWidth: Float,
        screenHeight: Float,
        rollAngle: Float,
        pitchAngle: Float,
        isDeviceSteady: Boolean,
        onAutoCaptureTrigger: () -> Unit
    ): CompositionState {
        if (screenWidth <= 0f || screenHeight <= 0f) {
            return CompositionState()
        }

        val x1 = screenWidth / 3f
        val x2 = screenWidth * 2f / 3f
        val y1 = screenHeight / 3f
        val y2 = screenHeight * 2f / 3f

        val thirdsH = floatArrayOf(y1, y2)
        val thirdsV = floatArrayOf(x1, x2)

        // BƯỚC 1: Đang quét khung hình (chưa có chủ thể cố định)
        if (subjectBox == null || subjectBox.isEmpty) {
            resetAutoCapture()
            return CompositionState(
                stage = AiStage.SCANNING,
                hasSubject = false,
                rollAngle = rollAngle,
                pitchAngle = pitchAngle,
                guidanceText = "Đang quét khung hình...",
                gridThirdsHorizontal = thirdsH,
                gridThirdsVertical = thirdsV
            )
        }

        val subjectCenter = PointF(subjectBox.centerX(), subjectBox.centerY())

        // 4 giao điểm 1/3 (ưu tiên đường 1/3 trên cho mặt người)
        val intersectionPoints = if (isFace) {
            listOf(
                PointF(x1, y1),
                PointF(x2, y1),
                PointF(x1, y2 * 0.9f),
                PointF(x2, y2 * 0.9f)
            )
        } else {
            listOf(
                PointF(x1, y1),
                PointF(x2, y1),
                PointF(x1, y2),
                PointF(x2, y2)
            )
        }

        // BƯỚC 2: Chọn 1 điểm đích 1/3 lý tưởng gần chủ thể nhất
        var targetPoint = intersectionPoints.first()
        var minDistance = Float.MAX_VALUE
        for (pt in intersectionPoints) {
            val dist = hypot(pt.x - subjectCenter.x, pt.y - subjectCenter.y)
            if (dist < minDistance) {
                minDistance = dist
                targetPoint = pt
            }
        }

        val maxDiag = hypot(screenWidth, screenHeight)
        val distanceRatio = minDistance / maxDiag

        // Ngưỡng xác định chủ thể đã vào vùng đích (<= 14% đường chéo)
        val isAligned = distanceRatio <= 0.14f

        val frameArea = screenWidth * screenHeight
        val subjectArea = subjectBox.width() * subjectBox.height()
        val areaRatio = subjectArea / frameArea

        // BƯỚC 2: Đang dẫn hướng (GUIDING) — User lia máy đưa điểm đích về chủ thể
        if (!isAligned) {
            resetAutoCapture()
            val guide = generateDirectionalGuidance(
                rollAngle = rollAngle,
                subjectCenter = subjectCenter,
                targetPoint = targetPoint,
                screenWidth = screenWidth,
                screenHeight = screenHeight
            )

            return CompositionState(
                stage = AiStage.GUIDING,
                hasSubject = true,
                isFace = isFace,
                subjectBounds = subjectBox,
                subjectCenter = subjectCenter,
                targetPoint = targetPoint,
                distanceToTarget = minDistance,
                rollAngle = rollAngle,
                pitchAngle = pitchAngle,
                score = 0, // Chưa hiện điểm số khi đang lia máy
                guidanceText = guide,
                targetZoomRatio = 1.0f,
                shouldZoom = false,
                gridThirdsHorizontal = thirdsH,
                gridThirdsVertical = thirdsV
            )
        }

        // BƯỚC 3: ĐÃ VÀO VÙNG ĐÍCH (ALIGNED)
        // 1. Tính toán Tự động Zoom (1.0x - 3.0x) để vừa khung
        var targetZoom = 1.0f
        var shouldZoom = false
        var isTooLarge = false

        if (areaRatio > 0.60f) {
            // Chủ thể quá to -> không zoom dưới 1.0x mà gợi ý lùi máy ra
            isTooLarge = true
            targetZoom = 1.0f
        } else if (areaRatio < 0.18f && isAutoZoomEnabled && isDeviceSteady) {
            // Chủ thể nhỏ -> tự động zoom tăng dần để vừa vặn khung hình
            val desiredScale = sqrt(0.24f / max(0.03f, areaRatio))
            targetZoom = desiredScale.coerceIn(1.0f, 3.0f)
            shouldZoom = true
        }

        // 2. Chấm điểm bố cục (0 - 100)
        val proximityScore = max(0f, 45f * (1f - (distanceRatio / 0.14f)))
        val balanceScore = calculateBalanceScore(rollAngle)

        var framingScore = 20f
        val margin = min(screenWidth, screenHeight) * 0.035f
        val touchesEdge = subjectBox.left < margin ||
                subjectBox.top < margin ||
                subjectBox.right > (screenWidth - margin) ||
                subjectBox.bottom > (screenHeight - margin)
        if (touchesEdge) framingScore -= 18f
        if (isTooLarge) framingScore -= 12f

        val rawTotal = (proximityScore + balanceScore + framingScore).toInt()
        val totalScore = min(100, max(0, rawTotal))

        // 3. Thông điệp gợi ý bước 3
        val guidance = when {
            totalScore >= 85 -> "Bố cục hoàn hảo! Giữ yên để chụp"
            isTooLarge -> "Lùi máy ra xa một chút"
            abs(rollAngle) > 2.5f -> "Giữ máy thẳng"
            touchesEdge -> "Chủ thể sát viền, dịch máy ra giữa"
            else -> "Căn chỉnh thêm một chút..."
        }

        // 4. Xử lý giữ yên 1 giây để tự động chụp
        var autoCaptureReady = false
        var autoProgress = 0f

        if (totalScore >= 85) {
            val now = SystemClock.elapsedRealtime()
            if (highQualityStartTime == 0L) {
                highQualityStartTime = now
            }
            val elapsed = now - highQualityStartTime
            autoProgress = min(1f, elapsed.toFloat() / requiredHoldTimeMs)

            if (elapsed >= requiredHoldTimeMs && !isTriggeredForCurrentLock) {
                autoCaptureReady = true
                if (isAutoCaptureEnabled) {
                    isTriggeredForCurrentLock = true
                    onAutoCaptureTrigger()
                }
            }
        } else {
            resetAutoCapture()
        }

        return CompositionState(
            stage = AiStage.ALIGNED,
            hasSubject = true,
            isFace = isFace,
            subjectBounds = subjectBox,
            subjectCenter = subjectCenter,
            targetPoint = targetPoint,
            distanceToTarget = minDistance,
            rollAngle = rollAngle,
            pitchAngle = pitchAngle,
            score = totalScore,
            guidanceText = guidance,
            isAutoCaptureReady = autoCaptureReady,
            autoCaptureProgress = autoProgress,
            targetZoomRatio = targetZoom,
            shouldZoom = shouldZoom,
            gridThirdsHorizontal = thirdsH,
            gridThirdsVertical = thirdsV
        )
    }

    private fun calculateBalanceScore(rollAngle: Float): Float {
        val absRoll = abs(rollAngle)
        return when {
            absRoll <= 1.2f -> 35f
            absRoll <= 3.0f -> 28f
            absRoll <= 6.0f -> 18f
            absRoll <= 10.0f -> 8f
            else -> 0f
        }
    }

    private fun generateDirectionalGuidance(
        rollAngle: Float,
        subjectCenter: PointF,
        targetPoint: PointF,
        screenWidth: Float,
        screenHeight: Float
    ): String {
        if (abs(rollAngle) > 3.0f) {
            return "Giữ máy thẳng"
        }

        val dx = targetPoint.x - subjectCenter.x
        val dy = targetPoint.y - subjectCenter.y
        val thresholdX = screenWidth * 0.05f
        val thresholdY = screenHeight * 0.05f

        return when {
            abs(dx) > abs(dy) && abs(dx) > thresholdX -> {
                if (dx > 0) "Dịch máy sang phải" else "Dịch máy sang trái"
            }
            abs(dy) > thresholdY -> {
                if (dy > 0) "Hạ thấp máy xuống" else "Nâng máy lên cao"
            }
            else -> "Đang đưa chủ thể vào vùng đích..."
        }
    }

    fun resetAutoCapture() {
        highQualityStartTime = 0L
        isTriggeredForCurrentLock = false
    }
}
