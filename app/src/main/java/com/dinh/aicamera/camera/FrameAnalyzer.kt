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
                    // Chọn mặt lớn nhất làm chủ thể chính
                    val primaryFace = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                    val mappedFaceBox = primaryFace?.boundingBox?.let { box ->
                        mapFaceBoxToPreviewCoordinates(box, imageProxy.width, imageProxy.height, rotationDegrees)
                    }

                    lastCandidateBox = mappedFaceBox
                    lastIsFace = true

                    onFrameAnalyzed(mappedFaceBox, true, avgLuminance)
                    isProcessing.set(false)
                    imageProxy.close()
                } else {
                    // Không có mặt người -> chạy YOLO11n tìm vật thể
                    detectObjectsWithYolo(imageProxy, rotationDegrees, avgLuminance)
                }
            }
            .addOnFailureListener {
                detectObjectsWithYolo(imageProxy, rotationDegrees, avgLuminance)
            }
    }

    private fun detectObjectsWithYolo(
        imageProxy: ImageProxy,
        rotationDegrees: Int,
        avgLuminance: Float
    ) {
        try {
            val detections = yoloDetector.detect(imageProxy)
            if (detections.isNotEmpty()) {
                // Chọn box có diện tích lớn nhất (hoặc confidence cao nhất) làm candidate
                val primaryObj = detections.maxByOrNull { it.box.width() * it.box.height() }

                val isRotated = rotationDegrees == 90 || rotationDegrees == 270
                val uprightW = if (isRotated) imageProxy.height else imageProxy.width
                val uprightH = if (isRotated) imageProxy.width else imageProxy.height

                val mappedObjBox = primaryObj?.let {
                    mapUprightBoxToPreviewCoordinates(it.box, uprightW, uprightH)
                }

                lastCandidateBox = mappedObjBox
                lastIsFace = false

                onFrameAnalyzed(mappedObjBox, false, avgLuminance)
            } else {
                lastCandidateBox = null
                lastIsFace = false
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
