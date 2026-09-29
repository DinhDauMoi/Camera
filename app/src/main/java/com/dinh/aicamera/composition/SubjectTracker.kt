package com.dinh.aicamera.composition

import android.graphics.RectF

data class TrackedSubject(
    val box: RectF, // Đã được làm mượt qua EMA
    val isFace: Boolean,
    val isLocked: Boolean
)

/**
 * SubjectTracker: Chốt chủ thể và làm mượt tọa độ, chống nhảy box/vòng tròn
 */
class SubjectTracker(
    val screenWidth: Float,
    val screenHeight: Float
) {
    private var isLocked: Boolean = false
    private var candidateConsecutiveFrames: Int = 0
    private var previousCandidateBox: RectF? = null
    private var lockedBox: RectF? = null
    private var lostFramesCount: Int = 0

    // Chống flip-flop giữa Face và Object (phải xuất hiện liên tục 8 frame mới đổi)
    private var currentIsFace: Boolean = false
    private var pendingIsFace: Boolean = false
    private var faceTypeConsecutiveCount: Int = 0

    private val frameArea: Float = screenWidth * screenHeight

    fun update(detectedBox: RectF?, isFace: Boolean): TrackedSubject? {
        // 5. Bỏ box có diện tích < 2% frame
        val candidate = if (detectedBox != null && !detectedBox.isEmpty) {
            val boxArea = detectedBox.width() * detectedBox.height()
            if (boxArea >= 0.02f * frameArea) detectedBox else null
        } else {
            null
        }

        // Cập nhật loại isFace (4. Chỉ đổi isFace khi loại mới xuất hiện LIÊN TỤC 8 frame)
        if (candidate != null) {
            if (isFace != currentIsFace) {
                if (isFace == pendingIsFace) {
                    faceTypeConsecutiveCount++
                    if (faceTypeConsecutiveCount >= 8) {
                        currentIsFace = isFace
                    }
                } else {
                    pendingIsFace = isFace
                    faceTypeConsecutiveCount = 1
                }
            } else {
                pendingIsFace = currentIsFace
                faceTypeConsecutiveCount = 0
            }
        }

        if (!isLocked) {
            // 1. CHƯA LOCK: Box chỉ thành ứng viên khi IoU > 0.5 với box frame trước, LIÊN TỤC 10 frame -> isLocked = true
            if (candidate != null) {
                val prev = previousCandidateBox
                if (prev != null) {
                    val iou = calculateIoU(prev, candidate)
                    if (iou > 0.5f) {
                        candidateConsecutiveFrames++
                        previousCandidateBox = RectF(candidate)
                        lockedBox = smoothBox(lockedBox ?: RectF(candidate), candidate)

                        if (candidateConsecutiveFrames >= 10) {
                            isLocked = true
                            lostFramesCount = 0
                            return TrackedSubject(
                                box = RectF(lockedBox!!),
                                isFace = currentIsFace,
                                isLocked = true
                            )
                        }
                    } else {
                        // IoU <= 0.5f, reset đếm
                        candidateConsecutiveFrames = 1
                        previousCandidateBox = RectF(candidate)
                        lockedBox = RectF(candidate)
                    }
                } else {
                    candidateConsecutiveFrames = 1
                    previousCandidateBox = RectF(candidate)
                    lockedBox = RectF(candidate)
                }
            } else {
                previousCandidateBox = null
                candidateConsecutiveFrames = 0
            }
            // Chưa lock -> trả null để hiển thị SCANNING
            return null
        } else {
            // 2. ĐÃ LOCK: Mỗi frame chọn detected box có IoU cao nhất với box đang lock (nhận nếu IoU > 0.4)
            // Mất liên tục 30 frame (~1s) -> unlock, trả null
            val currentLock = lockedBox ?: run {
                unlock()
                return null
            }

            if (candidate != null) {
                val iou = calculateIoU(currentLock, candidate)
                if (iou > 0.4f) {
                    lostFramesCount = 0
                    // 3. EMA: smoothed = lerp(smoothed, newBox, 0.35) cho tâm và kích thước
                    lockedBox = smoothBox(currentLock, candidate)
                    return TrackedSubject(
                        box = RectF(lockedBox!!),
                        isFace = currentIsFace,
                        isLocked = true
                    )
                } else {
                    // Box detect lệch quá nhiều -> tính là frame mất dấu
                    lostFramesCount++
                }
            } else {
                lostFramesCount++
            }

            if (lostFramesCount >= 30) {
                unlock()
                return null
            }

            // Vẫn giữ box cũ trong thời gian ngắn (<1s) để chống chớp giật
            return TrackedSubject(
                box = RectF(lockedBox!!),
                isFace = currentIsFace,
                isLocked = true
            )
        }
    }

    private fun unlock() {
        isLocked = false
        lockedBox = null
        previousCandidateBox = null
        candidateConsecutiveFrames = 0
        lostFramesCount = 0
    }

    /**
     * 3. EMA: smoothed = lerp(smoothed, newBox, 0.35) cho tâm và kích thước
     */
    private fun smoothBox(current: RectF, target: RectF): RectF {
        val alpha = 0.35f
        return RectF(
            current.left + alpha * (target.left - current.left),
            current.top + alpha * (target.top - current.top),
            current.right + alpha * (target.right - current.right),
            current.bottom + alpha * (target.bottom - current.bottom)
        )
    }

    private fun calculateIoU(a: RectF, b: RectF): Float {
        val left = maxOf(a.left, b.left)
        val top = maxOf(a.top, b.top)
        val right = minOf(a.right, b.right)
        val bottom = minOf(a.bottom, b.bottom)

        if (right <= left || bottom <= top) return 0f

        val intersection = (right - left) * (bottom - top)
        val areaA = a.width() * a.height()
        val areaB = b.width() * b.height()
        val union = areaA + areaB - intersection
        if (union <= 0f) return 0f
        return intersection / union
    }

    fun reset() {
        unlock()
        currentIsFace = false
        pendingIsFace = false
        faceTypeConsecutiveCount = 0
    }
}
