package com.dinh.aicamera.composition

import android.graphics.PointF
import android.graphics.RectF
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class Suggestion(
    val id: Int,
    val x: Float, // Tọa độ chuẩn hóa 0..1 trên preview (X)
    val y: Float, // Tọa độ chuẩn hóa 0..1 trên preview (Y)
    val score: Float,
    val boxW: Float?, // Kích thước bounding box chuẩn hóa (null nếu là gợi ý phong cảnh)
    val boxH: Float?, // Kích thước bounding box chuẩn hóa (null nếu là gợi ý phong cảnh)
    val isScene: Boolean
)

class CompositionEngine {

    private val _suggestions = MutableStateFlow<List<Suggestion>>(emptyList())
    val suggestions: StateFlow<List<Suggestion>> = _suggestions.asStateFlow()

    private var selectedSuggestionId: Int? = null

    fun selectSuggestion(id: Int): Suggestion? {
        if (selectedSuggestionId == id) {
            clearSelection()
            return null
        }
        selectedSuggestionId = id
        return _suggestions.value.find { it.id == id }
    }

    fun clearSelection() {
        selectedSuggestionId = null
    }

    fun getSelectedId(): Int? = selectedSuggestionId

    fun getSelectedSuggestion(): Suggestion? {
        val id = selectedSuggestionId ?: return null
        return _suggestions.value.find { it.id == id }
    }

    fun updateSuggestions(newList: List<Suggestion>) {
        if (selectedSuggestionId == null) {
            _suggestions.value = newList
        }
    }

    fun updateSelectedPosition(newX: Float, newY: Float) {
        val id = selectedSuggestionId ?: return
        val current = _suggestions.value
        val updated = current.map { s ->
            if (s.id == id) s.copy(x = newX, y = newY) else s
        }
        _suggestions.value = updated
    }

    fun clearSuggestions() {
        selectedSuggestionId = null
        _suggestions.value = emptyList()
    }

    companion object {
        fun calculateCandidateScore(
            cx: Float,
            cy: Float,
            boxW: Float,
            boxH: Float,
            confidence: Float
        ): Float {
            val thirds = listOf(
                1f / 3f to 1f / 3f,
                2f / 3f to 1f / 3f,
                1f / 3f to 2f / 3f,
                2f / 3f to 2f / 3f
            )
            var minDist = Float.MAX_VALUE
            for ((tx, ty) in thirds) {
                val d = hypot(cx - tx, cy - ty)
                if (d < minDist) minDist = d
            }
            val proximity = max(0f, 1f - (minDist / 0.45f))

            val area = boxW * boxH
            val sizeScore = when {
                area < 0.02f -> max(0.2f, area / 0.02f)
                area > 0.75f -> max(0.2f, 1f - (area - 0.75f) / 0.25f)
                else -> 1.0f
            }

            val compositionScore = (0.7f * proximity + 0.3f * sizeScore).coerceIn(0.2f, 1.0f)
            return (confidence * compositionScore).coerceIn(0f, 1f)
        }

        fun filterAndNms(
            candidates: List<Suggestion>,
            minScore: Float = 0.45f,
            nmsDistThreshold: Float = 0.12f,
            maxResults: Int = 4
        ): List<Suggestion> {
            val valid = candidates.filter { it.score >= minScore }
                .sortedByDescending { it.score }

            val result = mutableListOf<Suggestion>()
            for (c in valid) {
                val tooClose = result.any { accepted ->
                    hypot(c.x - accepted.x, c.y - accepted.y) < nmsDistThreshold
                }
                if (!tooClose) {
                    result.add(c)
                    if (result.size >= maxResults) break
                }
            }
            return result
        }
    }

    var isAutoCaptureEnabled: Boolean = true
    var isAutoZoomEnabled: Boolean = true

    private var highQualityStartTime: Long = 0L
    private var isTriggeredForCurrentLock: Boolean = false
    private val requiredHoldTimeMs = 1000L // Duy trì 1 giây điểm > 85
    private var lastLoggedStage: AiStage? = null
    private var lockedTargetPoint: PointF? = null
    private var anchorSubjectCenter: PointF? = null

    private fun logStageChange(newStage: AiStage, details: String) {
        if (newStage != lastLoggedStage) {
            android.util.Log.d("CompositionEngine", "AI Stage: $lastLoggedStage -> $newStage | $details")
            lastLoggedStage = newStage
        }
    }

    fun evaluate(
        subjectBox: RectF?,
        isFace: Boolean,
        screenWidth: Float,
        screenHeight: Float,
        rollAngle: Float,
        pitchAngle: Float,
        isDeviceSteady: Boolean,
        suppressAutoCapture: Boolean = false,
        aiMode: AiMode = AiMode.OFF,
        onAutoCaptureTrigger: () -> Unit
    ): CompositionState {
        if (screenWidth <= 0f || screenHeight <= 0f || aiMode == AiMode.OFF) {
            return CompositionState()
        }

        val x1 = screenWidth / 3f
        val x2 = screenWidth * 2f / 3f
        val y1 = screenHeight / 3f
        val y2 = screenHeight * 2f / 3f

        val thirdsH = floatArrayOf(y1, y2)
        val thirdsV = floatArrayOf(x1, x2)

        // Mode DOTS: chỉ tính và publish suggestions, trả về CompositionState trung tính
        // KHÔNG vòng vàng đích, KHÔNG mũi tên, KHÔNG tự động chụp, KHÔNG auto-zoom legacy
        if (aiMode == AiMode.DOTS) {
            resetAutoCapture()
            lockedTargetPoint = null
            anchorSubjectCenter = null
            return CompositionState(
                stage = AiStage.SCANNING,
                hasSubject = (subjectBox != null && !subjectBox.isEmpty),
                isFace = isFace,
                subjectBounds = subjectBox ?: RectF(),
                subjectCenter = subjectBox?.let { PointF(it.centerX(), it.centerY()) } ?: PointF(),
                targetPoint = PointF(),
                distanceToTarget = 0f,
                rollAngle = rollAngle,
                pitchAngle = pitchAngle,
                score = 0,
                guidanceText = "Gợi ý bố cục đẹp",
                targetZoomRatio = 1.0f,
                shouldZoom = false,
                isAutoCaptureReady = false,
                autoCaptureProgress = 0f,
                gridThirdsHorizontal = thirdsH,
                gridThirdsVertical = thirdsV
            )
        }

        // Mode ZOOM: Đảm bảo _suggestions luôn rỗng
        if (_suggestions.value.isNotEmpty()) {
            clearSuggestions()
        }

        // BƯỚC 1: Đang quét khung hình (chưa có chủ thể cố định)
        if (subjectBox == null || subjectBox.isEmpty) {
            resetAutoCapture()
            lockedTargetPoint = null
            anchorSubjectCenter = null
            logStageChange(AiStage.SCANNING, "Đang quét tìm chủ thể...")
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

        val maxDiag = hypot(screenWidth, screenHeight)

        // Hysteresis cho targetPoint — đã chọn thì GIỮ, chỉ tính lại khi subjectCenter dịch > 8% đường chéo màn hình
        val targetPoint: PointF
        val anchor = anchorSubjectCenter
        val currentLocked = lockedTargetPoint

        if (currentLocked != null && anchor != null && hypot(subjectCenter.x - anchor.x, subjectCenter.y - anchor.y) <= 0.08f * maxDiag) {
            targetPoint = currentLocked
        } else {
            var bestPt = intersectionPoints.first()
            var minD = Float.MAX_VALUE
            for (pt in intersectionPoints) {
                val dist = hypot(pt.x - subjectCenter.x, pt.y - subjectCenter.y)
                if (dist < minD) {
                    minD = dist
                    bestPt = pt
                }
            }
            targetPoint = bestPt
            lockedTargetPoint = bestPt
            anchorSubjectCenter = PointF(subjectCenter.x, subjectCenter.y)
        }

        val minDistance = hypot(targetPoint.x - subjectCenter.x, targetPoint.y - subjectCenter.y)
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

            logStageChange(AiStage.GUIDING, "Đang dẫn hướng, khoảng cách tới đích: ${minDistance.toInt()}px")
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
        } else if (isAutoZoomEnabled && isDeviceSteady) {
            // MỚI: chủ thể cỡ trung bình (0.18–0.60) -> zoom in dần về ~28% khung hình
            val desiredScale = sqrt(0.28f / max(0.03f, areaRatio))
            targetZoom = desiredScale.coerceIn(1.0f, 3.0f)
            shouldZoom = targetZoom > 1.05f
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
        val a = abs(rollAngle) % 90f
        val levelErr = min(a, 90f - a)
        val guidance = when {
            totalScore >= 85 -> "Bố cục hoàn hảo! Giữ yên để chụp"
            isTooLarge -> "Lùi máy ra xa một chút"
            levelErr > 2.5f -> "Giữ máy thẳng"
            touchesEdge -> "Chủ thể sát viền, dịch máy ra giữa"
            else -> "Căn chỉnh thêm một chút..."
        }

        // 4. Xử lý giữ yên 1 giây để tự động chụp
        var autoCaptureReady = false
        var autoProgress = 0f

        if (totalScore >= 85 && !suppressAutoCapture && selectedSuggestionId == null) {
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

        logStageChange(AiStage.ALIGNED, "Đã vào vùng đích! Điểm: $totalScore, zoom: $targetZoom")
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
        val a = abs(rollAngle) % 90f
        val levelErr = min(a, 90f - a)
        return when {
            levelErr <= 1.2f -> 35f
            levelErr <= 3.0f -> 28f
            levelErr <= 6.0f -> 18f
            levelErr <= 10.0f -> 8f
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
        val a = abs(rollAngle) % 90f
        val levelErr = min(a, 90f - a)
        if (levelErr > 3.0f) {
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
