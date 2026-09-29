package com.dinh.aicamera.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.LifecycleOwner
import com.dinh.aicamera.filter.ColorMatrixFilter
import com.dinh.aicamera.filter.FilterType
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
    private var flashMode: Int = ImageCapture.FLASH_MODE_AUTO

    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    // Smooth Zoom state
    private var currentZoomRatio: Float = 1.0f

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

        preview = Preview.Builder()
            .build()
            .also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

        imageCapture = ImageCapture.Builder()
            .setFlashMode(flashMode)
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()

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
            ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
            ImageCapture.FLASH_MODE_ON -> ImageCapture.FLASH_MODE_OFF
            else -> ImageCapture.FLASH_MODE_AUTO
        }
        imageCapture?.flashMode = flashMode
        return flashMode
    }

    fun getFlashMode(): Int = flashMode

    // Zoom controls (1.0x - 3.0x lerp mượt mà)
    fun setZoomRatio(targetRatio: Float) {
        val clamped = targetRatio.coerceIn(1.0f, 3.0f)
        // Lerp mượt để chống giật hình
        currentZoomRatio += 0.15f * (clamped - currentZoomRatio)
        try {
            camera?.cameraControl?.setZoomRatio(currentZoomRatio)
        } catch (e: Exception) {
            // Thiết bị không hỗ trợ mức zoom này
        }
    }

    fun resetZoom() {
        currentZoomRatio = 1.0f
        try {
            camera?.cameraControl?.setZoomRatio(1.0f)
        } catch (e: Exception) {
            // Ignore
        }
    }

    fun getZoomRatio(): Float = currentZoomRatio

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

            val name = SimpleDateFormat("AICAM_yyyyMMdd_HHmmss", Locale.US)
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
                val filtered = ColorMatrixFilter.applyFilterToBitmap(bitmap, filter)
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
        cameraExecutor.shutdown()
    }
}
