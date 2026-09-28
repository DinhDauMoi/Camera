package com.dinh.aicamera.camera

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
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class FrameAnalyzer(
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

    // Stream Mode Object Detector (On-device, offline, free)
    private val objectDetector = ObjectDetection.getClient(
        ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
            .enableMultipleObjects()
            .build()
    )

    private val isProcessing = AtomicBoolean(false)

    // View dimensions for coordinate scaling
    var previewViewWidth: Int = 1080
    var previewViewHeight: Int = 1920

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

        val rotationDegrees = imageProxy.imageInfo.rotationDegrees

        // Tính độ sáng trung bình nhanh từ Y-plane (không tốn tài nguyên)
        val avgLuminance = calculateAverageLuminance(imageProxy.planes[0].buffer)

        val inputImage = InputImage.fromMediaImage(mediaImage, rotationDegrees)

        // Bước 1: Ưu tiên tìm mặt người trước
        faceDetector.process(inputImage)
            .addOnSuccessListener { faces ->
                if (faces.isNotEmpty()) {
                    // Chọn mặt lớn nhất làm chủ thể chính
                    val primaryFace = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                    val transformedBox = primaryFace?.boundingBox?.let { box ->
                        mapBoxToPreviewCoordinates(box, imageProxy.width, imageProxy.height, rotationDegrees)
                    }
                    onFrameAnalyzed(transformedBox, true, avgLuminance)
                    isProcessing.set(false)
                    imageProxy.close()
                } else {
                    // Không có mặt người -> tìm vật thể
                    detectObjects(inputImage, imageProxy, rotationDegrees, avgLuminance)
                }
            }
            .addOnFailureListener {
                // Fallback tìm vật thể
                detectObjects(inputImage, imageProxy, rotationDegrees, avgLuminance)
            }
    }

    private fun detectObjects(
        inputImage: InputImage,
        imageProxy: ImageProxy,
        rotationDegrees: Int,
        avgLuminance: Float
    ) {
        objectDetector.process(inputImage)
            .addOnSuccessListener { objects ->
                val primaryObj = objects.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                val transformedBox = primaryObj?.boundingBox?.let { box ->
                    mapBoxToPreviewCoordinates(box, imageProxy.width, imageProxy.height, rotationDegrees)
                }
                onFrameAnalyzed(transformedBox, false, avgLuminance)
            }
            .addOnFailureListener {
                onFrameAnalyzed(null, false, avgLuminance)
            }
            .addOnCompleteListener {
                isProcessing.set(false)
                imageProxy.close()
            }
    }

    /**
     * Map tọa độ bounding box từ ảnh camera sang PreviewView (FillCenter scale)
     */
    private fun mapBoxToPreviewCoordinates(
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

        // Scale kiểu fillCenter
        val scale = maxOf(viewW / rotatedImgW, viewH / rotatedImgH)
        val scaledW = rotatedImgW * scale
        val scaledH = rotatedImgH * scale
        val dx = (viewW - scaledW) / 2f
        val dy = (viewH - scaledH) / 2f

        // Chuẩn hóa tọa độ theo góc xoay
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

    /**
     * Đọc nhanh độ sáng trung bình qua Y-plane của YUV420
     */
    private fun calculateAverageLuminance(buffer: ByteBuffer): Float {
        buffer.rewind()
        val step = 32 // Sample 1/32 số pixel để cực nhanh và nhẹ
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
}
