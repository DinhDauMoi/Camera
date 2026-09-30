package com.dinh.aicamera.camera

import android.content.Context
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class FrameAnalyzer(
    context: Context,
    private val onFrameAnalyzed: (
        subjectBox: RectF?,
        isFace: Boolean,
        averageLuminance: Float
    ) -> Unit
) : ImageAnalysis.Analyzer {

    // Fast Face Detector (On-device, offline, free)
    private val faceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .build()
    )

    // YOLO11n On-Device Detector (TFLite)
    private val yoloDetector = YoloDetector(context)

    private val isProcessing = AtomicBoolean(false)

    // AI mặc định TẮT: khi tắt thì KHÔNG quét để tiết kiệm pin tối đa
    var isAiEnabled: Boolean = false

    // View dimensions for coordinate scaling
    var previewViewWidth: Int = 1080
    var previewViewHeight: Int = 1920

    // 1 frame chạy AI, 1 frame bỏ qua (trả lại kết quả từ frame trước) -> giảm 50% tải CPU/GPU
    private var frameCount: Long = 0L
    private var lastCandidateBox: RectF? = null
    private var lastIsFace: Boolean = false

    var compositionEngine: com.dinh.aicamera.composition.CompositionEngine? = null

    private var aiEnabledTimestamp: Long = 0L
    private var lastSuggestionTime: Long = 0L

    fun onAiToggled(enabled: Boolean) {
        isAiEnabled = enabled
        if (enabled) {
            aiEnabledTimestamp = android.os.SystemClock.elapsedRealtime()
            lastSuggestionTime = 0L
        } else {
            aiEnabledTimestamp = 0L
            lastSuggestionTime = 0L
        }
    }

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        // Bỏ qua frame nếu frame trước chưa xử lý xong để tránh lag UI
        if (!isProcessing.compareAndSet(false, true)) {
            imageProxy.close()
            return
        }

        // Tính độ sáng nhanh từ Y-plane (siêu nhẹ, không tốn tài nguyên)
        val avgLuminance = calculateAverageLuminance(imageProxy.planes[0].buffer)

        // Nếu AI TẮT: Dừng ngay mọi xử lý, trả về khung trống, tiết kiệm pin tuyệt đối
        if (!isAiEnabled) {
            lastCandidateBox = null
            lastIsFace = false
            onFrameAnalyzed(null, false, avgLuminance)
            isProcessing.set(false)
            imageProxy.close()
            return
        }

        val now = android.os.SystemClock.elapsedRealtime()
        if (aiEnabledTimestamp == 0L) {
            aiEnabledTimestamp = now
        }

        // Trạng thái SCANNING (~1.5s khi vừa bật AI)
        val isScanning = (now - aiEnabledTimestamp) < 1500L
        if (isScanning) {
            lastCandidateBox = null
            lastIsFace = false
            onFrameAnalyzed(null, false, avgLuminance)
            isProcessing.set(false)
            imageProxy.close()
            return
        }

        // Nhịp chạy: 1 frame chạy AI, 1 frame bỏ qua (trả lại kết quả frame trước)
        val currentFrame = frameCount++
        if (currentFrame % 2L != 0L) {
            onFrameAnalyzed(lastCandidateBox, lastIsFace, avgLuminance)
            isProcessing.set(false)
            imageProxy.close()
            return
        }

        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        val inputImage = InputImage.fromMediaImage(mediaImage, rotationDegrees)

        // Bước 1: Ưu tiên tìm mặt người trước (nhẹ, nhanh)
        faceDetector.process(inputImage)
            .addOnSuccessListener { faces ->
                if (faces.isNotEmpty()) {
                    val allFaceBoxes = faces.map { face ->
                        mapFaceBoxToPreviewCoordinates(face.boundingBox, imageProxy.width, imageProxy.height, rotationDegrees) to 0.92f
                    }
                    val primaryFace = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                    val mappedFaceBox = primaryFace?.boundingBox?.let { box ->
                        mapFaceBoxToPreviewCoordinates(box, imageProxy.width, imageProxy.height, rotationDegrees)
                    }

                    lastCandidateBox = mappedFaceBox
                    lastIsFace = true

                    handleSuggestionsAndTracking(allFaceBoxes, imageProxy, now)

                    onFrameAnalyzed(mappedFaceBox, true, avgLuminance)
                    isProcessing.set(false)
                    imageProxy.close()
                } else {
                    detectObjectsWithYolo(imageProxy, rotationDegrees, avgLuminance, now)
                }
            }
            .addOnFailureListener {
                detectObjectsWithYolo(imageProxy, rotationDegrees, avgLuminance, now)
            }
    }

    private fun detectObjectsWithYolo(
        imageProxy: ImageProxy,
        rotationDegrees: Int,
        avgLuminance: Float,
        now: Long
    ) {
        try {
            val detections = yoloDetector.detect(imageProxy)
            val isRotated = rotationDegrees == 90 || rotationDegrees == 270
            val uprightW = if (isRotated) imageProxy.height else imageProxy.width
            val uprightH = if (isRotated) imageProxy.width else imageProxy.height

            if (detections.isNotEmpty()) {
                val allObjBoxes = detections.map { det ->
                    mapUprightBoxToPreviewCoordinates(det.box, uprightW, uprightH) to det.confidence
                }
                val primaryObj = detections.maxByOrNull { it.box.width() * it.box.height() }
                val mappedObjBox = primaryObj?.let {
                    mapUprightBoxToPreviewCoordinates(it.box, uprightW, uprightH)
                }

                lastCandidateBox = mappedObjBox
                lastIsFace = false

                handleSuggestionsAndTracking(allObjBoxes, imageProxy, now)
                onFrameAnalyzed(mappedObjBox, false, avgLuminance)
            } else {
                lastCandidateBox = null
                lastIsFace = false

                handleSuggestionsAndTracking(emptyList(), imageProxy, now)
                onFrameAnalyzed(null, false, avgLuminance)
            }
        } catch (e: Throwable) {
            lastCandidateBox = null
            lastIsFace = false
            onFrameAnalyzed(null, false, avgLuminance)
        } finally {
            isProcessing.set(false)
            imageProxy.close()
        }
    }

    private fun handleSuggestionsAndTracking(
        allBoxes: List<Pair<RectF, Float>>,
        imageProxy: ImageProxy,
        now: Long
    ) {
        val engine = compositionEngine ?: return
        val selectedId = engine.getSelectedId()

        val viewW = previewViewWidth.toFloat()
        val viewH = previewViewHeight.toFloat()
        if (viewW <= 0f || viewH <= 0f) return

        if (selectedId == null) {
            // Chưa chọn chấm nào: refresh gợi ý mỗi 2s
            if (now - lastSuggestionTime >= 2000L) {
                lastSuggestionTime = now
                val candidates = mutableListOf<com.dinh.aicamera.composition.Suggestion>()
                var idCounter = 1

                if (allBoxes.isNotEmpty()) {
                    for ((box, conf) in allBoxes) {
                        val cx = (box.centerX() / viewW).coerceIn(0f, 1f)
                        val cy = (box.centerY() / viewH).coerceIn(0f, 1f)
                        val bw = (box.width() / viewW).coerceIn(0.01f, 1f)
                        val bh = (box.height() / viewH).coerceIn(0.01f, 1f)

                        val score = com.dinh.aicamera.composition.CompositionEngine.calculateCandidateScore(
                            cx, cy, bw, bh, conf
                        )
                        if (score >= 0.45f) {
                            candidates.add(
                                com.dinh.aicamera.composition.Suggestion(
                                    id = idCounter++,
                                    x = cx,
                                    y = cy,
                                    score = score,
                                    boxW = bw,
                                    boxH = bh,
                                    isScene = false
                                )
                            )
                        }
                    }
                }

                // Nếu không có detection (phong cảnh): chia preview thành lưới 3x3
                if (candidates.isEmpty()) {
                    val sceneDots = evaluateLandscapeGrid(imageProxy)
                    candidates.addAll(sceneDots)
                }

                val filtered = com.dinh.aicamera.composition.CompositionEngine.filterAndNms(
                    candidates,
                    minScore = 0.45f,
                    nmsDistThreshold = 0.12f,
                    maxResults = 4
                )
                engine.updateSuggestions(filtered)
            }
        } else {
            // Đã chọn 1 chấm: bám theo vật thể nếu có detection
            val selected = engine.getSelectedSuggestion()
            if (selected != null && !selected.isScene && allBoxes.isNotEmpty()) {
                val currentX = selected.x * viewW
                val currentY = selected.y * viewH
                val closest = allBoxes.minByOrNull { (box, _) ->
                    kotlin.math.hypot(box.centerX() - currentX, box.centerY() - currentY)
                }
                if (closest != null) {
                    val (box, _) = closest
                    val dist = kotlin.math.hypot(box.centerX() - currentX, box.centerY() - currentY)
                    if (dist < 0.35f * viewW) {
                        val newX = (0.7f * selected.x + 0.3f * (box.centerX() / viewW)).coerceIn(0f, 1f)
                        val newY = (0.7f * selected.y + 0.3f * (box.centerY() / viewH)).coerceIn(0f, 1f)
                        engine.updateSelectedPosition(newX, newY)
                    }
                }
            }
        }
    }

    private fun evaluateLandscapeGrid(imageProxy: ImageProxy): List<com.dinh.aicamera.composition.Suggestion> {
        return try {
            val yPlane = imageProxy.planes[0]
            val buffer = yPlane.buffer
            val rowStride = yPlane.rowStride
            val width = imageProxy.width
            val height = imageProxy.height

            val cellW = width / 3
            val cellH = height / 3
            val candidates = mutableListOf<com.dinh.aicamera.composition.Suggestion>()

            var idCounter = 101
            for (row in 0 until 3) {
                for (col in 0 until 3) {
                    val startX = col * cellW
                    val endX = (col + 1) * cellW
                    val startY = row * cellH
                    val endY = (row + 1) * cellH

                    var sum = 0L
                    var sumSq = 0L
                    var count = 0
                    val stepX = maxOf(4, cellW / 10)
                    val stepY = maxOf(4, cellH / 10)

                    for (y in startY until endY step stepY) {
                        val rowOffset = y * rowStride
                        for (x in startX until endX step stepX) {
                            val index = rowOffset + x
                            if (index < buffer.limit()) {
                                val v = buffer.get(index).toInt() and 0xFF
                                sum += v
                                sumSq += (v * v).toLong()
                                count++
                            }
                        }
                    }

                    if (count > 0) {
                        val mean = sum.toDouble() / count
                        val variance = (sumSq.toDouble() / count) - (mean * mean)
                        val stdDev = kotlin.math.sqrt(maxOf(0.0, variance)).toFloat()
                        val score = (0.45f + (stdDev / 50f) * 0.45f).coerceIn(0.45f, 0.90f)

                        val normX = (col + 0.5f) / 3f
                        val normY = (row + 0.5f) / 3f

                        candidates.add(
                            com.dinh.aicamera.composition.Suggestion(
                                id = idCounter++,
                                x = normX,
                                y = normY,
                                score = score,
                                boxW = null,
                                boxH = null,
                                isScene = true
                            )
                        )
                    }
                }
            }
            candidates.sortedByDescending { it.score }.take(2)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Map tọa độ bounding box từ YOLO (đã upright) sang PreviewView (FillCenter scale)
     */
    private fun mapUprightBoxToPreviewCoordinates(
        box: RectF,
        uprightW: Int,
        uprightH: Int
    ): RectF {
        val viewW = previewViewWidth.toFloat()
        val viewH = previewViewHeight.toFloat()

        val scale = maxOf(viewW / uprightW.toFloat(), viewH / uprightH.toFloat())
        val scaledW = uprightW * scale
        val scaledH = uprightH * scale
        val dx = (viewW - scaledW) / 2f
        val dy = (viewH - scaledH) / 2f

        return RectF(
            box.left * scale + dx,
            box.top * scale + dy,
            box.right * scale + dx,
            box.bottom * scale + dy
        )
    }

    /**
     * Map tọa độ bounding box từ ML Kit Face sang PreviewView (FillCenter scale)
     */
    private fun mapFaceBoxToPreviewCoordinates(
        sourceBox: Rect,
        imageWidth: Int,
        imageHeight: Int,
        rotationDegrees: Int
    ): RectF {
        val isRotated = rotationDegrees == 90 || rotationDegrees == 270
        val rotatedImgW = if (isRotated) imageHeight.toFloat() else imageWidth.toFloat()
        val rotatedImgH = if (isRotated) imageWidth.toFloat() else imageHeight.toFloat()

        val viewW = previewViewWidth.toFloat()
        val viewH = previewViewHeight.toFloat()

        val scale = maxOf(viewW / rotatedImgW, viewH / rotatedImgH)
        val scaledW = rotatedImgW * scale
        val scaledH = rotatedImgH * scale
        val dx = (viewW - scaledW) / 2f
        val dy = (viewH - scaledH) / 2f

        val matrix = Matrix()
        matrix.postRotate(rotationDegrees.toFloat())
        when (rotationDegrees) {
            90 -> matrix.postTranslate(imageHeight.toFloat(), 0f)
            180 -> matrix.postTranslate(imageWidth.toFloat(), imageHeight.toFloat())
            270 -> matrix.postTranslate(0f, imageWidth.toFloat())
        }
        matrix.postScale(scale, scale)
        matrix.postTranslate(dx, dy)

        val srcRectF = RectF(sourceBox)
        val dstRectF = RectF()
        matrix.mapRect(dstRectF, srcRectF)
        return dstRectF
    }

    private fun calculateAverageLuminance(buffer: ByteBuffer): Float {
        buffer.rewind()
        val step = 32
        var total = 0L
        var count = 0
        var i = 0
        val remaining = buffer.remaining()
        while (i < remaining) {
            val byteVal = buffer.get(i).toInt() and 0xFF
            total += byteVal
            count++
            i += step
        }
        return if (count > 0) (total.toFloat() / count) else 128f
    }

    fun close() {
        try {
            faceDetector.close()
            yoloDetector.close()
        } catch (e: Exception) {
            // Ignore
        }
    }
}
