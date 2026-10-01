package com.dinh.aicamera.camera

import android.animation.ValueAnimator
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.hardware.camera2.CaptureRequest
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.animation.DecelerateInterpolator
import kotlin.OptIn
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.ZoomState
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.LifecycleOwner
import com.dinh.aicamera.filter.ColorMatrixFilter
import com.dinh.aicamera.filter.FilterType
import com.dinh.aicamera.ui.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCamera2Interop::class)
class CameraManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView,
    private val frameAnalyzer: FrameAnalyzer
) {
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var preview: Preview? = null

    private var lensFacing: Int = CameraSelector.LENS_FACING_BACK
    private var flashMode: Int = ImageCapture.FLASH_MODE_OFF

    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    // Smooth Zoom state
    private var currentZoomRatio: Float = 1.0f
    private var zoomAnimator: ValueAnimator? = null

    var onZoomStateChanged: ((ZoomState) -> Unit)? = null

    fun startCamera(onReady: () -> Unit = {}) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases()
            onReady()
        }, ContextCompat.getMainExecutor(context))
    }

    private fun bindCameraUseCases() {
        val provider = cameraProvider ?: return

        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(lensFacing)
            .build()

        val previewBuilder = Preview.Builder()
        Camera2Interop.Extender(previewBuilder)
            .setCaptureRequestOption(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            )
        preview = previewBuilder.build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }

        val captureBuilder = ImageCapture.Builder()
            .setFlashMode(flashMode)
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
        Camera2Interop.Extender(captureBuilder)
            .setCaptureRequestOption(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            )
        imageCapture = captureBuilder.build()

        imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()
            .also {
                it.setAnalyzer(cameraExecutor, frameAnalyzer)
            }

        try {
            provider.unbindAll()
            camera = provider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageCapture,
                imageAnalysis
            )
            // Lắng nghe zoomState để cập nhật UI & min/max thực tế
            camera?.cameraInfo?.zoomState?.observe(lifecycleOwner) { state ->
                if (state != null) {
                    currentZoomRatio = state.zoomRatio
                    onZoomStateChanged?.invoke(state)
                }
            }
            // Khởi tạo zoom về 1.0x
            currentZoomRatio = 1.0f
            camera?.cameraControl?.setZoomRatio(1.0f)
        } catch (exc: Exception) {
            Log.e("CameraManager", "Use case binding failed", exc)
        }
    }

    fun switchCamera() {
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        bindCameraUseCases()
    }

    fun isBackCamera(): Boolean = lensFacing == CameraSelector.LENS_FACING_BACK

    fun cycleFlashMode(): Int {
        flashMode = when (flashMode) {
            ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_ON
            ImageCapture.FLASH_MODE_ON -> ImageCapture.FLASH_MODE_AUTO
            else -> ImageCapture.FLASH_MODE_OFF
        }
        imageCapture?.flashMode = flashMode
        return flashMode
    }

    fun getFlashMode(): Int = flashMode

    fun getMinZoomRatio(): Float = camera?.cameraInfo?.zoomState?.value?.minZoomRatio ?: 1.0f
    fun getMaxZoomRatio(): Float = camera?.cameraInfo?.zoomState?.value?.maxZoomRatio ?: 1.0f
    fun getZoomRatio(): Float = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: currentZoomRatio

    fun setZoomRatio(ratio: Float, smooth: Boolean = false) {
        val minRatio = getMinZoomRatio()
        val maxRatio = getMaxZoomRatio()
        val targetRatio = ratio.coerceIn(minRatio, maxRatio)

        zoomAnimator?.cancel()
        zoomAnimator = null

        if (smooth) {
            val startRatio = getZoomRatio()
            if (kotlin.math.abs(targetRatio - startRatio) < 0.01f) return

            zoomAnimator = ValueAnimator.ofFloat(startRatio, targetRatio).apply {
                duration = 200L
                interpolator = DecelerateInterpolator()
                addUpdateListener { animator ->
                    val animatedVal = animator.animatedValue as Float
                    currentZoomRatio = animatedVal
                    try {
                        camera?.cameraControl?.setZoomRatio(animatedVal)
                    } catch (_: Exception) {}
                }
                start()
            }
        } else {
            currentZoomRatio = targetRatio
            try {
                camera?.cameraControl?.setZoomRatio(targetRatio)
            } catch (e: Exception) {
                Log.e("CameraManager", "setZoomRatio error", e)
            }
        }
    }

    fun smoothZoomTo(targetRatio: Float) {
        val minRatio = getMinZoomRatio()
        val maxRatio = getMaxZoomRatio()
        val clampedTarget = targetRatio.coerceIn(minRatio, maxRatio)

        zoomAnimator?.cancel()
        zoomAnimator = null

        val startRatio = getZoomRatio()
        if (kotlin.math.abs(clampedTarget - startRatio) < 0.01f) return

        zoomAnimator = ValueAnimator.ofFloat(startRatio, clampedTarget).apply {
            duration = 600L
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                val animatedVal = animator.animatedValue as Float
                currentZoomRatio = animatedVal
                try {
                    camera?.cameraControl?.setZoomRatio(animatedVal)
                } catch (_: Exception) {}
            }
            start()
        }
    }

    fun cancelSmoothZoom() {
        zoomAnimator?.cancel()
        zoomAnimator = null
    }

    fun resetZoom() {
        cancelSmoothZoom()
        setZoomRatio(1.0f, smooth = false)
    }

    fun tapToFocus(x: Float, y: Float) {
        val control = camera?.cameraControl ?: return
        try {
            val factory = previewView.meteringPointFactory
            val point = factory.createPoint(x, y)
            val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                .setAutoCancelDuration(3, TimeUnit.SECONDS)
                .build()
            control.startFocusAndMetering(action)
        } catch (e: Exception) {
            Log.e("CameraManager", "tapToFocus failed", e)
        }
    }

    /**
     * Chụp ảnh và lưu vào MediaStore (Pictures/AICamera), hỗ trợ nướng filter trực tiếp
     */
    fun takePhoto(
        currentFilter: FilterType,
        onSuccess: (Uri) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        try {
            val capture = imageCapture ?: run {
                onError(IllegalStateException("Camera chưa sẵn sàng"))
                return
            }
            capture.flashMode = flashMode

            val name = SimpleDateFormat("'AICAM'_yyyyMMdd_HHmmss", Locale.US)
                .format(System.currentTimeMillis())

            val tempFile = File.createTempFile("temp_cam_", ".jpg", context.cacheDir)
            val outputOptions = ImageCapture.OutputFileOptions.Builder(tempFile).build()

            capture.takePicture(
                outputOptions,
                cameraExecutor,
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                        CoroutineScope(Dispatchers.IO).launch {
                            try {
                                val savedUri = processAndSaveToMediaStore(tempFile, name, currentFilter)
                                tempFile.delete()
                                withContext(Dispatchers.Main) {
                                    onSuccess(savedUri)
                                }
                            } catch (t: Throwable) {
                                Log.e("CameraManager", "Lỗi xử lý và lưu ảnh", t)
                                tempFile.delete()
                                withContext(Dispatchers.Main) {
                                    onError(t)
                                }
                            }
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        tempFile.delete()
                        ContextCompat.getMainExecutor(context).execute {
                            onError(exception)
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            Log.e("CameraManager", "Lỗi khởi chạy takePhoto", t)
            onError(t)
        }
    }

    private fun processAndSaveToMediaStore(
        tempFile: File,
        displayName: String,
        filter: FilterType
    ): Uri {
        var bitmap: Bitmap? = null
        try {
            val boundsOptions = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(tempFile.absolutePath, boundsOptions)

            var inSampleSize = 1
            val maxDimension = maxOf(boundsOptions.outWidth, boundsOptions.outHeight)
            if (maxDimension > 0) {
                while (maxDimension.toFloat() / inSampleSize > 2560f) {
                    inSampleSize *= 2
                }
            }

            val decodeOptions = BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize
            }
            bitmap = BitmapFactory.decodeFile(tempFile.absolutePath, decodeOptions)
                ?: throw IllegalStateException("Không thể giải mã ảnh từ file tạm")

            val exif = ExifInterface(tempFile.absolutePath)
            val orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
            val rotationDegrees = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }

            if (rotationDegrees != 0f) {
                val matrix = Matrix().apply { postRotate(rotationDegrees) }
                val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                bitmap.recycle()
                bitmap = rotated
            }

            if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
                val mirrorMatrix = Matrix().apply { postScale(-1f, 1f) }
                val mirrored = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, mirrorMatrix, true)
                bitmap.recycle()
                bitmap = mirrored
            }

            if (filter != FilterType.NONE) {
                val matrix = if (filter == FilterType.CUSTOM) {
                    val prefs = AppPreferences(context)
                    ColorMatrixFilter.getCustomMatrix(
                        tone = prefs.customFilterTone,
                        warmth = prefs.customFilterWarmth,
                        vivid = prefs.customFilterVivid,
                        gridX = prefs.customFilterGridX,
                        gridY = prefs.customFilterGridY
                    )
                } else {
                    ColorMatrixFilter.getColorMatrix(filter)
                }
                val filtered = ColorMatrixFilter.applyFilterWithMatrix(bitmap, matrix)
                bitmap.recycle()
                bitmap = filtered
            }

            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "$displayName.jpg")
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/AICamera")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }

            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                ?: throw IllegalStateException("Không thể tạo MediaStore URI")

            resolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }
            bitmap.recycle()
            bitmap = null

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)
            }

            return uri
        } catch (t: Throwable) {
            bitmap?.recycle()
            throw t
        }
    }

    fun shutdown() {
        zoomAnimator?.cancel()
        zoomAnimator = null
        cameraExecutor.shutdown()
    }
}
