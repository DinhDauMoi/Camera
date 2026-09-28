package com.dinh.aicamera.composition

import android.graphics.PointF
import android.graphics.RectF
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

class CompositionEngine {

    var isAutoCaptureEnabled: Boolean = true

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

        if (subjectBox == null || subjectBox.isEmpty) {
            // Không có chủ thể: Chỉ chấm điểm cân bằng máy
            resetAutoCapture()
            val balanceScore = calculateBalanceScore(rollAngle)
            val guide = if (abs(rollAngle) > 2.5f) "Giữ máy thẳng" else "Đang tìm chủ thể..."
            return CompositionState(
                hasSubject = false,
                rollAngle = rollAngle,
                pitchAngle = pitchAngle,
                score = (balanceScore * 0.4f).toInt(),
                guidanceText = guide,
                gridThirdsHorizontal = thirdsH,
                gridThirdsVertical = thirdsV
            )
        }

        val subjectCenter = PointF(subjectBox.centerX(), subjectBox.centerY())

        // 4 giao điểm 1/3 (Power Points)
        val intersectionPoints = if (isFace) {
            // Với chân dung người, ưu tiên đường 1/3 phía trên (tầm mắt)
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

        // Tìm điểm 1/3 gần chủ thể nhất
        var targetPoint = intersectionPoints.first()
        var minDistance = Float.MAX_VALUE
        for (pt in intersectionPoints) {
            val dist = hypot(pt.x - subjectCenter.x, pt.y - subjectCenter.y)
            if (dist < minDistance) {
                minDistance = dist
                targetPoint = pt
            }
        }

        // 1. Điểm khoảng cách tới giao điểm 1/3 (Tối đa 45 điểm)
        val maxDiag = hypot(screenWidth, screenHeight)
        val normalizedDist = minDistance / maxDiag
        val proximityScore = max(0f, 45f * (1f - (normalizedDist / 0.35f)))

        // 2. Điểm cân bằng máy (Tối đa 35 điểm)
        val balanceScore = calculateBalanceScore(rollAngle)

        // 3. Điểm kích thước & viền (Tối đa 20 điểm cơ bản, có trừ điểm vi phạm)
        var framingScore = 20f

        val frameArea = screenWidth * screenHeight
        val subjectArea = subjectBox.width() * subjectBox.height()
        val areaRatio = subjectArea / frameArea

        // Trừ điểm nếu bị cắt viền (chạm sát mép màn hình < 3%)
        val margin = min(screenWidth, screenHeight) * 0.035f
        val touchesEdge = subjectBox.left < margin ||
                subjectBox.top < margin ||
                subjectBox.right > (screenWidth - margin) ||
                subjectBox.bottom > (screenHeight - margin)
        if (touchesEdge) {
            framingScore -= 18f
        }

        // Trừ điểm nếu chủ thể đặt chết ở chính giữa (dead-center, vi phạm 1/3)
        val cxRel = subjectCenter.x / screenWidth
        val cyRel = subjectCenter.y / screenHeight
        val isDeadCenter = cxRel in 0.42f..0.58f && cyRel in 0.42f..0.58f
        if (isDeadCenter) {
            framingScore -= 12f
        }

        // Trừ điểm nếu quá nhỏ hoặc quá to
        val isTooSmall = areaRatio < 0.035f
        val isTooLarge = areaRatio > 0.75f
        if (isTooSmall) framingScore -= 12f
        if (isTooLarge) framingScore -= 12f

        val rawTotal = (proximityScore + balanceScore + framingScore).toInt()
        val totalScore = min(100, max(0, rawTotal))

        // Tạo câu gợi ý tiếng Việt thông minh
        val guidance = generateGuidance(
            rollAngle = rollAngle,
            subjectCenter = subjectCenter,
            targetPoint = targetPoint,
            isTooSmall = isTooSmall,
            isTooLarge = isTooLarge,
            touchesEdge = touchesEdge,
            score = totalScore,
            screenWidth = screenWidth,
            screenHeight = screenHeight
        )

        // Xử lý tự động chụp (khi điểm > 85 duy trì 1s)
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

    private fun generateGuidance(
        rollAngle: Float,
        subjectCenter: PointF,
        targetPoint: PointF,
        isTooSmall: Boolean,
        isTooLarge: Boolean,
        touchesEdge: Boolean,
        score: Int,
        screenWidth: Float,
        screenHeight: Float
    ): String {
        if (score >= 85) {
            return "Bố cục hoàn hảo! Giữ yên để chụp"
        }
        if (abs(rollAngle) > 2.8f) {
            return "Giữ máy thẳng"
        }
        if (touchesEdge) {
            return "Chủ thể sát viền, dịch máy ra giữa"
        }
        if (isTooSmall) {
            return "Tiến lại gần chủ thể hơn"
        }
        if (isTooLarge) {
            return "Lùi ra xa một chút"
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
            else -> "Căn chỉnh thêm một chút..."
        }
    }

    fun resetAutoCapture() {
        highQualityStartTime = 0L
        isTriggeredForCurrentLock = false
    }
}
