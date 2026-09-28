package com.dinh.aicamera.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.View
import android.view.animation.ScaleAnimation
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.ImageCapture
import androidx.core.content.ContextCompat
import coil.load
import coil.transform.CircleCropTransformation
import com.dinh.aicamera.R
import com.dinh.aicamera.camera.CameraManager
import com.dinh.aicamera.camera.FrameAnalyzer
import com.dinh.aicamera.composition.CompositionEngine
import com.dinh.aicamera.composition.SensorOrientationHelper
import com.dinh.aicamera.databinding.ActivityMainBinding
import com.dinh.aicamera.filter.AIFilterRecommender
import com.dinh.aicamera.filter.FilterType

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private lateinit var cameraManager: CameraManager
    private lateinit var frameAnalyzer: FrameAnalyzer
    private lateinit var compositionEngine: CompositionEngine
    private lateinit var sensorOrientationHelper: SensorOrientationHelper

    private var currentRoll: Float = 0f
    private var currentPitch: Float = 0f

    private var activeFilter: FilterType = FilterType.NONE
    private var isAutoCaptureEnabled: Boolean = true
    private var isCapturing: Boolean = false

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            binding.permissionCard.visibility = View.GONE
            setupCamera()
        } else {
            binding.permissionCard.visibility = View.VISIBLE
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initAIEngines()
        setupUI()
        setupSensors()
        checkCameraPermission()
    }

    private fun initAIEngines() {
        compositionEngine = CompositionEngine()

        frameAnalyzer = FrameAnalyzer { subjectBox, isFace, avgLuminance ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread

                // Đánh giá bố cục thông minh theo thời gian thực
                val screenW = binding.previewView.width.toFloat()
                val screenH = binding.previewView.height.toFloat()

                val state = compositionEngine.evaluate(
                    subjectBox = subjectBox,
                    isFace = isFace,
                    screenWidth = screenW,
                    screenHeight = screenH,
                    rollAngle = currentRoll,
                    pitchAngle = currentPitch,
                    onAutoCaptureTrigger = {
                        triggerShutterCapture(isAuto = true)
                    }
                )

                // Cập nhật AR overlay (lưới 1/3, target vàng, mũi tên, thước chân trời, vòng điểm)
                binding.compositionOverlay.updateState(state)

                // AI Gợi ý filter thông minh dựa vào độ sáng khung hình & thời gian
                evaluateAIFilterSuggestion(avgLuminance)
            }
        }
    }

    private fun setupSensors() {
        sensorOrientationHelper = SensorOrientationHelper(this) { roll, pitch ->
            currentRoll = roll
            currentPitch = pitch
        }
    }

    private fun setupUI() {
        // Áp dụng Liquid Glass blur effect (Android 12+)
        LiquidGlassHelper.applyBlurEffect(binding.topToolbar, 25f)
        LiquidGlassHelper.applyBlurEffect(binding.bottomBar, 25f)

        // Nút chụp thủ công
        binding.btnShutter.setOnClickListener {
            triggerShutterCapture(isAuto = false)
        }

        // Đổi camera Trước / Sau
        binding.btnSwitchCamera.setOnClickListener {
            animateButtonClick(it)
            cameraManager.switchCamera()
        }

        // Chuyển đổi Flash
        binding.btnFlash.setOnClickListener {
            val mode = cameraManager.cycleFlashMode()
            updateFlashIcon(mode)
        }

        // Bật / Tắt Tự động chụp
        binding.btnAutoCapture.setOnClickListener {
            isAutoCaptureEnabled = !isAutoCaptureEnabled
            compositionEngine.isAutoCaptureEnabled = isAutoCaptureEnabled
            updateAutoCaptureUI()
        }

        // Mở Carousel chọn Filter
        binding.btnFilterToggle.setOnClickListener {
            val isVisible = binding.filterCarouselScroll.visibility == View.VISIBLE
            binding.filterCarouselScroll.visibility = if (isVisible) View.GONE else View.VISIBLE
        }

        // Nút xem Thư viện ảnh vừa chụp
        binding.btnGallery.setOnClickListener {
            GalleryBottomSheetDialog.newInstance().show(supportFragmentManager, GalleryBottomSheetDialog.TAG)
        }

        // Cấp quyền
        binding.btnGrantPermission.setOnClickListener {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        populateFilterCarousel()
        updateAutoCaptureUI()
    }

    private fun populateFilterCarousel() {
        binding.filterChipContainer.removeAllViews()

        for (filter in FilterType.values()) {
            val chip = layoutInflater.inflate(
                R.layout.item_filter_chip,
                binding.filterChipContainer,
                false
            ) as TextView

            chip.setText(filter.titleRes)
            updateChipStyle(chip, filter == activeFilter)

            chip.setOnClickListener {
                selectFilter(filter)
            }

            binding.filterChipContainer.addView(chip)
        }
    }

    private fun selectFilter(filter: FilterType) {
        activeFilter = filter
        binding.tvCurrentFilterName.setText(filter.titleRes)

        for (i in 0 until binding.filterChipContainer.childCount) {
            val child = binding.filterChipContainer.getChildAt(i) as TextView
            val f = FilterType.values()[i]
            updateChipStyle(child, f == activeFilter)
        }
    }

    private fun updateChipStyle(chip: TextView, isSelected: Boolean) {
        if (isSelected) {
            chip.setBackgroundResource(R.drawable.bg_glass_pill_active)
            chip.setTextColor(ContextCompat.getColor(this, R.color.accent_gold))
        } else {
            chip.setBackgroundResource(R.drawable.bg_glass_pill)
            chip.setTextColor(ContextCompat.getColor(this, R.color.white_90))
        }
    }

    private fun evaluateAIFilterSuggestion(luminance: Float) {
        val suggestion = AIFilterRecommender.recommend(luminance)
        if (suggestion.filterType != activeFilter) {
            binding.aiSuggestionBubble.visibility = View.VISIBLE
            binding.tvAiSuggestion.text = suggestion.message

            // Chạm vào bóng gợi ý để kích hoạt ngay filter AI đề xuất
            binding.aiSuggestionBubble.setOnClickListener {
                selectFilter(suggestion.filterType)
                binding.aiSuggestionBubble.visibility = View.GONE
                Toast.makeText(this, "Đã áp dụng ${getString(suggestion.filterType.titleRes)}", Toast.LENGTH_SHORT).show()
            }
        } else {
            binding.aiSuggestionBubble.visibility = View.GONE
        }
    }

    private fun triggerShutterCapture(isAuto: Boolean) {
        if (isCapturing) return
        isCapturing = true

        // Hiệu ứng rung nhẹ
        vibrateLight()

        // Animation nút chụp
        val scaleAnim = ScaleAnimation(
            1f, 0.85f, 1f, 0.85f,
            ScaleAnimation.RELATIVE_TO_SELF, 0.5f,
            ScaleAnimation.RELATIVE_TO_SELF, 0.5f
        ).apply {
            duration = 80
            repeatCount = 1
            repeatMode = ScaleAnimation.REVERSE
        }
        binding.shutterInnerCircle.startAnimation(scaleAnim)

        // Flash màn hình giả lập chụp ảnh
        showCaptureFlash()

        cameraManager.takePhoto(
            currentFilter = activeFilter,
            onSuccess = { uri ->
                isCapturing = false
                compositionEngine.resetAutoCapture()
                onPhotoCaptured(uri, isAuto)
            },
            onError = { exc ->
                isCapturing = false
                compositionEngine.resetAutoCapture()
                Toast.makeText(this, "Lỗi chụp: ${exc.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
        )
    }

    private fun showCaptureFlash() {
        val flashView = View(this).apply {
            setBackgroundColor(0xCCFFFFFF.toInt())
            layoutParams = binding.rootContainer.layoutParams
        }
        binding.rootContainer.addView(flashView)
        flashView.animate()
            .alpha(0f)
            .setDuration(120)
            .withEndAction {
                binding.rootContainer.removeView(flashView)
            }
            .start()
    }

    private fun onPhotoCaptured(uri: Uri, isAuto: Boolean) {
        val message = if (isAuto) "AI Tự động chụp & lưu ảnh!" else getString(R.string.photo_saved)
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

        // Hiển thị thumbnail vào nút Gallery tròn
        binding.ivGalleryThumbnail.load(uri) {
            transformations(CircleCropTransformation())
            crossfade(true)
        }
    }

    private fun updateFlashIcon(mode: Int) {
        val iconRes = when (mode) {
            ImageCapture.FLASH_MODE_ON -> R.drawable.ic_flash_on
            ImageCapture.FLASH_MODE_OFF -> R.drawable.ic_flash_off
            else -> R.drawable.ic_flash_auto
        }
        binding.btnFlash.setImageResource(iconRes)
    }

    private fun updateAutoCaptureUI() {
        if (isAutoCaptureEnabled) {
            binding.btnAutoCapture.setImageResource(R.drawable.ic_auto_capture_on)
            binding.autoCaptureBadge.visibility = View.VISIBLE
            binding.autoCaptureDot.setBackgroundTintList(
                ContextCompat.getColorStateList(this, R.color.score_green)
            )
            binding.tvAutoCaptureStatus.text = "AUTO ON"
        } else {
            binding.btnAutoCapture.setImageResource(R.drawable.ic_auto_capture_off)
            binding.autoCaptureBadge.visibility = View.GONE
        }
    }

    private fun animateButtonClick(view: View) {
        view.animate()
            .rotationBy(180f)
            .setDuration(250)
            .start()
    }

    private fun vibrateLight() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator.vibrate(
                    VibrationEffect.createOneShot(75, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(VibrationEffect.createOneShot(75, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(75)
                }
            }
        } catch (e: Exception) {
            // Ignore if vibration is not supported
        }
    }

    private fun checkCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            binding.permissionCard.visibility = View.GONE
            setupCamera()
        } else {
            binding.permissionCard.visibility = View.VISIBLE
        }
    }

    private fun setupCamera() {
        binding.previewView.post {
            frameAnalyzer.previewViewWidth = binding.previewView.width
            frameAnalyzer.previewViewHeight = binding.previewView.height

            cameraManager = CameraManager(
                context = this,
                lifecycleOwner = this,
                previewView = binding.previewView,
                frameAnalyzer = frameAnalyzer
            )
            cameraManager.startCamera()
        }
    }

    override fun onResume() {
        super.onResume()
        sensorOrientationHelper.start()
    }

    override fun onPause() {
        super.onPause()
        sensorOrientationHelper.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::cameraManager.isInitialized) {
            cameraManager.shutdown()
        }
    }
}
