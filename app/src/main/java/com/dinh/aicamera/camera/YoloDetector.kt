package com.dinh.aicamera.camera

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

data class YoloDetection(
    val box: RectF, // Coordinates in upright image space
    val confidence: Float,
    val classId: Int
)

/**
 * YOLO11s On-Device Detector using TensorFlow Lite
 * Runs offline with GPU acceleration and 4-thread CPU fallback.
 */
class YoloDetector(
    context: Context,
    private val modelPath: String = "yolo11s.tflite",
    private val confThreshold: Float = 0.35f,
    private val iouThreshold: Float = 0.45f
) {
    companion object {
        private const val TAG = "YoloDetector"
        // Giảm xuống 416 nếu YOLO11s vẫn chậm trên máy yếu; 11s@416 vẫn chuẩn hơn 11n@640
        private const val INPUT_SIZE = 640
        private const val NUM_CLASSES = 80
        private const val NUM_COORDINATES = 4
        private const val NUM_FEATURES = 84 // [cx, cy, w, h] + 80 class scores
    }

    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    private val inferenceLock = Any()
    private val isWarmingUp = AtomicBoolean(false)

    // Direct ByteBuffer for model input: 1 * 640 * 640 * 3 * 4 bytes (Float32)
    private val inputBuffer: ByteBuffer = ByteBuffer.allocateDirect(1 * INPUT_SIZE * INPUT_SIZE * 3 * 4).apply {
        order(ByteOrder.nativeOrder())
    }

    // Letterbox working resources (reused to prevent GC overhead)
    private val letterboxBitmap: Bitmap = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)
    private val letterboxCanvas: Canvas = Canvas(letterboxBitmap)
    private val letterboxPaint: Paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val bgPaint: Paint = Paint().apply {
        color = Color.rgb(114, 114, 114) // YOLO standard padding 114/255 gray
        style = Paint.Style.FILL
    }
    private val pixelValues = IntArray(INPUT_SIZE * INPUT_SIZE)

    // Tensor format flags
    private var isBchwInput: Boolean = true
    private var isTransposedOutput: Boolean = true
    private var numPredictions: Int = 8400
    private var outputArray3D: Array<Array<FloatArray>>? = null

    init {
        try {
            val modelBuffer = loadModelFile(context, modelPath)
            try {
                val delegate = GpuDelegate()
                val gpuOptions = Interpreter.Options().apply {
                    addDelegate(delegate)
                }
                interpreter = Interpreter(modelBuffer, gpuOptions)
                gpuDelegate = delegate
                Log.i(TAG, "YOLO11 initialized successfully with GPU Delegate")
            } catch (e: Throwable) {
                Log.w(TAG, "GPU Delegate init failed, falling back to CPU (4 threads): ${e.message}")
                gpuDelegate?.close()
                gpuDelegate = null
                val cpuOptions = Interpreter.Options().apply {
                    setNumThreads(4)
                }
                interpreter = Interpreter(modelBuffer, cpuOptions)
            }

            // Inspect input and output tensor shapes dynamically
            interpreter?.let { interp ->
                val inShape = interp.getInputTensor(0).shape()
                Log.i(TAG, "YOLO Input tensor shape: ${inShape.joinToString()}")
                isBchwInput = inShape.size == 4 && inShape[1] == 3

                val outShape = interp.getOutputTensor(0).shape()
                Log.i(TAG, "YOLO Output tensor shape: ${outShape.joinToString()}")
                if (outShape.size == 3) {
                    if (outShape[1] == NUM_FEATURES) {
                        // [1, 84, 8400]
                        isTransposedOutput = true
                        numPredictions = outShape[2]
                        outputArray3D = Array(1) { Array(NUM_FEATURES) { FloatArray(numPredictions) } }
                    } else {
                        // [1, 8400, 84]
                        isTransposedOutput = false
                        numPredictions = outShape[1]
                        outputArray3D = Array(1) { Array(numPredictions) { FloatArray(NUM_FEATURES) } }
                    }
                } else {
                    isTransposedOutput = true
                    numPredictions = 8400
                    outputArray3D = Array(1) { Array(NUM_FEATURES) { FloatArray(8400) } }
                }

                // Warmup dummy inference on background thread
                startWarmup(interp)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load YOLO model: $modelPath", e)
        }
    }

    private fun startWarmup(interp: Interpreter) {
        isWarmingUp.set(true)
        Thread({
            val t0 = SystemClock.elapsedRealtime()
            try {
                val dummyInput = ByteBuffer.allocateDirect(1 * INPUT_SIZE * INPUT_SIZE * 3 * 4).apply {
                    order(ByteOrder.nativeOrder())
                }
                val dummyOutput = if (isTransposedOutput) {
                    Array(1) { Array(NUM_FEATURES) { FloatArray(numPredictions) } }
                } else {
                    Array(1) { Array(numPredictions) { FloatArray(NUM_FEATURES) } }
                }
                synchronized(inferenceLock) {
                    interp.run(dummyInput, dummyOutput)
                }
                val ms = SystemClock.elapsedRealtime() - t0
                Log.i(TAG, "YOLO warmup done in ${ms}ms")
            } catch (e: Throwable) {
                Log.w(TAG, "YOLO warmup failed: ${e.message}")
            } finally {
                isWarmingUp.set(false)
            }
        }, "yolo-warmup").start()
    }

    private fun loadModelFile(context: Context, path: String): MappedByteBuffer {
        val afd: AssetFileDescriptor = context.assets.openFd(path)
        val inputStream = FileInputStream(afd.fileDescriptor)
        val fileChannel = inputStream.channel
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
    }

    /**
     * Detect objects from CameraX ImageProxy.
     * Returns detection boxes in upright image space.
     */
    fun detect(imageProxy: ImageProxy): List<YoloDetection> {
        if (isWarmingUp.get()) {
            return emptyList()
        }
        val interp = interpreter ?: return emptyList()
        val outBuffer = outputArray3D ?: return emptyList()

        val tStart = SystemClock.elapsedRealtime()
        var sourceBitmap: Bitmap? = null
        try {
            sourceBitmap = imageProxy.toBitmap()
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees

            val rawW = sourceBitmap.width
            val rawH = sourceBitmap.height

            val isRotated = rotationDegrees == 90 || rotationDegrees == 270
            val uprightW = if (isRotated) rawH else rawW
            val uprightH = if (isRotated) rawW else rawH

            // Scale to fit within 640x640 keeping aspect ratio
            val scale = min(INPUT_SIZE.toFloat() / uprightW, INPUT_SIZE.toFloat() / uprightH)
            val scaledW = uprightW * scale
            val scaledH = uprightH * scale
            val padX = (INPUT_SIZE - scaledW) / 2f
            val padY = (INPUT_SIZE - scaledH) / 2f

            // Fill canvas with grey-114 background
            letterboxCanvas.drawRect(0f, 0f, INPUT_SIZE.toFloat(), INPUT_SIZE.toFloat(), bgPaint)

            // Combine rotation and letterbox scaling into one single matrix
            val drawMatrix = Matrix()
            drawMatrix.postRotate(rotationDegrees.toFloat())
            when (rotationDegrees) {
                90 -> drawMatrix.postTranslate(rawH.toFloat(), 0f)
                180 -> drawMatrix.postTranslate(rawW.toFloat(), rawH.toFloat())
                270 -> drawMatrix.postTranslate(0f, rawW.toFloat())
            }
            drawMatrix.postScale(scale, scale)
            drawMatrix.postTranslate(padX, padY)

            letterboxCanvas.drawBitmap(sourceBitmap, drawMatrix, letterboxPaint)

            // Extract pixels and load into inputBuffer
            letterboxBitmap.getPixels(pixelValues, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
            inputBuffer.rewind()

            val totalPixels = INPUT_SIZE * INPUT_SIZE
            if (isBchwInput) {
                // BCHW: Red channel, then Green channel, then Blue channel
                for (i in 0 until totalPixels) {
                    val p = pixelValues[i]
                    inputBuffer.putFloat(((p shr 16) and 0xFF) / 255.0f)
                }
                for (i in 0 until totalPixels) {
                    val p = pixelValues[i]
                    inputBuffer.putFloat(((p shr 8) and 0xFF) / 255.0f)
                }
                for (i in 0 until totalPixels) {
                    val p = pixelValues[i]
                    inputBuffer.putFloat((p and 0xFF) / 255.0f)
                }
            } else {
                // BHWC: RGB interleaved
                for (i in 0 until totalPixels) {
                    val p = pixelValues[i]
                    inputBuffer.putFloat(((p shr 16) and 0xFF) / 255.0f)
                    inputBuffer.putFloat(((p shr 8) and 0xFF) / 255.0f)
                    inputBuffer.putFloat((p and 0xFF) / 255.0f)
                }
            }

            val tBeforeInf = SystemClock.elapsedRealtime()
            val pp = tBeforeInf - tStart

            // Run inference
            val tInfStart = SystemClock.elapsedRealtime()
            synchronized(inferenceLock) {
                interp.run(inputBuffer, outBuffer)
            }
            val tInfEnd = SystemClock.elapsedRealtime()
            val inf = tInfEnd - tInfStart

            // Parse predictions
            val tParseStart = tInfEnd
            val candidates = mutableListOf<YoloDetection>()

            if (isTransposedOutput) {
                // Shape: [1, 84, numPredictions]
                val tensor = outBuffer[0]
                for (i in 0 until numPredictions) {
                    var maxScore = 0f
                    var bestClassId = -1

                    for (c in 0 until NUM_CLASSES) {
                        var score = tensor[NUM_COORDINATES + c][i]
                        // Apply sigmoid if output values are logits
                        if (score < 0f || score > 1f) {
                            score = 1.0f / (1.0f + exp(-score.coerceIn(-50f, 50f)))
                        }
                        if (score > maxScore) {
                            maxScore = score
                            bestClassId = c
                        }
                    }

                    if (maxScore >= confThreshold) {
                        val cx = tensor[0][i]
                        val cy = tensor[1][i]
                        val w = tensor[2][i]
                        val h = tensor[3][i]

                        // Map box from letterbox 640x640 back to upright image space
                        val x1 = (cx - w / 2f - padX) / scale
                        val y1 = (cy - h / 2f - padY) / scale
                        val x2 = (cx + w / 2f - padX) / scale
                        val y2 = (cy + h / 2f - padY) / scale

                        val clampedBox = RectF(
                            max(0f, min(uprightW.toFloat(), x1)),
                            max(0f, min(uprightH.toFloat(), y1)),
                            max(0f, min(uprightW.toFloat(), x2)),
                            max(0f, min(uprightH.toFloat(), y2))
                        )

                        candidates.add(YoloDetection(clampedBox, maxScore, bestClassId))
                    }
                }
            } else {
                // Shape: [1, numPredictions, 84]
                val tensor = outBuffer[0]
                for (i in 0 until numPredictions) {
                    val row = tensor[i]
                    var maxScore = 0f
                    var bestClassId = -1

                    for (c in 0 until NUM_CLASSES) {
                        var score = row[NUM_COORDINATES + c]
                        if (score < 0f || score > 1f) {
                            score = 1.0f / (1.0f + exp(-score.coerceIn(-50f, 50f)))
                        }
                        if (score > maxScore) {
                            maxScore = score
                            bestClassId = c
                        }
                    }

                    if (maxScore >= confThreshold) {
                        val cx = row[0]
                        val cy = row[1]
                        val w = row[2]
                        val h = row[3]

                        val x1 = (cx - w / 2f - padX) / scale
                        val y1 = (cy - h / 2f - padY) / scale
                        val x2 = (cx + w / 2f - padX) / scale
                        val y2 = (cy + h / 2f - padY) / scale

                        val clampedBox = RectF(
                            max(0f, min(uprightW.toFloat(), x1)),
                            max(0f, min(uprightH.toFloat(), y1)),
                            max(0f, min(uprightW.toFloat(), x2)),
                            max(0f, min(uprightH.toFloat(), y2))
                        )

                        candidates.add(YoloDetection(clampedBox, maxScore, bestClassId))
                    }
                }
            }

            // Apply Non-Maximum Suppression (NMS)
            val result = applyNMS(candidates, iouThreshold, maxDetections = 10)
            val tParseEnd = SystemClock.elapsedRealtime()
            val pa = tParseEnd - tParseStart
            val tot = tParseEnd - tStart

            if (inf > 150) {
                Log.d(TAG, "detect: preprocess=${pp}ms inference=${inf}ms parse=${pa}ms total=${tot}ms")
            }

            return result
        } catch (e: Throwable) {
            Log.e(TAG, "Detection error: ${e.message}", e)
            return emptyList()
        } finally {
            sourceBitmap?.recycle()
        }
    }

    private fun applyNMS(
        candidates: List<YoloDetection>,
        iouThresh: Float,
        maxDetections: Int
    ): List<YoloDetection> {
        val sorted = candidates.sortedByDescending { it.confidence }
        val selected = mutableListOf<YoloDetection>()
        val active = BooleanArray(sorted.size) { true }

        for (i in sorted.indices) {
            if (!active[i]) continue
            val current = sorted[i]
            selected.add(current)
            if (selected.size >= maxDetections) break

            for (j in i + 1 until sorted.size) {
                if (!active[j]) continue
                val iou = calculateIoU(current.box, sorted[j].box)
                if (iou > iouThresh) {
                    active[j] = false
                }
            }
        }
        return selected
    }

    private fun calculateIoU(a: RectF, b: RectF): Float {
        val left = max(a.left, b.left)
        val top = max(a.top, b.top)
        val right = min(a.right, b.right)
        val bottom = min(a.bottom, b.bottom)

        if (right <= left || bottom <= top) return 0f

        val intersection = (right - left) * (bottom - top)
        val areaA = a.width() * a.height()
        val areaB = b.width() * b.height()
        val union = areaA + areaB - intersection
        if (union <= 0f) return 0f
        return intersection / union
    }

    fun close() {
        try {
            synchronized(inferenceLock) {
                interpreter?.close()
                interpreter = null
                gpuDelegate?.close()
                gpuDelegate = null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error closing YoloDetector: ${e.message}")
        }
    }
}
