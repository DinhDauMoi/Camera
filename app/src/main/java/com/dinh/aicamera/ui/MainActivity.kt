package com.dinh.aicamera.ui

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.MediaStore
import android.view.View
import android.view.animation.ScaleAnimation
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.ImageCapture
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import coil.load
import coil.transform.CircleCropTransformation
import com.dinh.aicamera.R
import com.dinh.aicamera.camera.CameraManager
import com.dinh.aicamera.camera.FrameAnalyzer
import com.dinh.aicamera.composition.AiStage
import com.dinh.aicamera.composition.CompositionEngine
import com.dinh.aicamera.composition.SensorOrientationHelper
import com.dinh.aicamera.databinding.ActivityMainBinding
import com.dinh.aicamera.filter.AIFilterRecommender
import com.dinh.aicamera.filter.FilterType
import com.dinh.aicamera.ui.gallery.FullscreenPhotoDialog
import com.dinh.aicamera.ui.gallery.GalleryGridAdapter
import com.dinh.aicamera.ui.update.AppUpdateManager
import com.dinh.aicamera.ui.update.UpdateDialogFragment
import com.dinh.aicamera.ui.update.UpdateResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var preferences: AppPreferences
    private lateinit var updateManager: AppUpdateManager

    private lateinit var cameraManager: CameraManager
    private lateinit var frameAnalyzer: FrameAnalyzer
    private lateinit var compositionEngine: CompositionEngine
    private lateinit var sensorOrientationHelper: SensorOrientationHelper

    private var currentRoll: Float = 0f
    private var currentPitch: Float = 0f
    private var isDeviceSteady: Boolean = true

    private var activeFilter: FilterType = FilterType.NONE
    private var isCapturing: Boolean = false
    private var lastAiStage: AiStage = AiStage.SCANNING

    // Gallery Tab State
    private val galleryUris = mutableListOf<Uri>()
    private lateinit var galleryAdapter: GalleryGridAdapter

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

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            loadGalleryPhotos()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        preferences = AppPreferences(this)
        updateManager = AppUpdateManager(this)

        initAIEngines()
        setupUI()
        setupSensors()
        setupGalleryTab()
        checkCameraPermission()

        autoCheckAppUpdate()
    }

    private fun initAIEngines() {
        compositionEngine = CompositionEngine().apply {
            isAutoCaptureEnabled = preferences.isAutoCaptureEnabled
            isAutoZoomEnabled = preferences.isAutoZoomEnabled
        }

        frameAnalyzer = FrameAnalyzer(this) { subjectBox, isFace, avgLuminance ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread

                // Nếu AI TẮT: Bỏ qua mọi phân tích để camera mượt và tiết kiệm pin tối đa
                if (!preferences.isAiEnabled) {
                    return@runOnUiThread
                }

                val screenW = binding.previewView.width.toFloat()
                val screenH = binding.previewView.height.toFloat()

                val state = compositionEngine.evaluate(
                    subjectBox = subjectBox,
                    isFace = isFace,
                    screenWidth = screenW,
                    screenHeight = screenH,
                    rollAngle = currentRoll,
                    pitchAngle = currentPitch,
                    isDeviceSteady = isDeviceSteady,
                    onAutoCaptureTrigger = {
                        if (preferences.isAiEnabled) {
                            triggerShutterCapture(isAuto = true)
                        }
                    }
                )

                // Rung phản hồi nhẹ khi vừa căn chuẩn vào vùng đích (ALIGNED) chuẩn Doka
                if (state.stage == AiStage.ALIGNED && lastAiStage != AiStage.ALIGNED) {
                    vibrateLight()
                }
                lastAiStage = state.stage

                // Cập nhật AR overlay (3 Bước: SCANNING -> GUIDING -> ALIGNED)
                binding.compositionOverlay.updateState(state)

                // BƯỚC 3: TỰ ĐỘNG ZOOM khi đã vào vùng đích (1.0x - 3.0x lerp mượt mà)
                if (state.stage == AiStage.ALIGNED && state.shouldZoom) {
                    cameraManager.setZoomRatio(state.targetZoomRatio)
                }

                // AI Gợi ý Filter (hiện dưới khung hình, không đè lên AR overlay)
                evaluateAIFilterSuggestion(avgLuminance)
            }
        }

        // Khởi tạo trạng thái mặc định
        frameAnalyzer.isAiEnabled = preferences.isAiEnabled
        binding.compositionOverlay.isAiEnabled = preferences.isAiEnabled
        binding.compositionOverlay.isGridEnabled = preferences.isGridEnabled
    }

    private fun setupSensors() {
        sensorOrientationHelper = SensorOrientationHelper(this) { roll, pitch, isSteady ->
            currentRoll = roll
            currentPitch = pitch
            isDeviceSteady = isSteady
        }
    }

    private fun setupUI() {
        // Thiết lập Liquid Glass đúng chuẩn: clipToOutline = true, không làm mờ nút bấm
        LiquidGlassHelper.setupGlassPill(binding.topToolbar)
        LiquidGlassHelper.setupGlassPill(binding.bottomNavigationPill)

        // 1. Nút Bật/Tắt AI (Mặc định TẮT)
        updateAiToggleUI()
        binding.btnAiToggle.setOnClickListener {
            preferences.isAiEnabled = !preferences.isAiEnabled
            frameAnalyzer.isAiEnabled = preferences.isAiEnabled
            binding.compositionOverlay.isAiEnabled = preferences.isAiEnabled

            if (!preferences.isAiEnabled) {
                // Tắt AI -> reset zoom về 1.0x ngay
                cameraManager.resetZoom()
                binding.aiSuggestionBubble.visibility = View.GONE
            }

            updateAiToggleUI()
            val msg = if (preferences.isAiEnabled) "Đã bật AI Hướng dẫn bố cục" else "Đã tắt AI - Trở về camera thường"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        // 2. Toggle Lưới 1/3 riêng biệt
        updateGridToggleUI()
        binding.btnGridToggle.setOnClickListener {
            preferences.isGridEnabled = !preferences.isGridEnabled
            binding.compositionOverlay.isGridEnabled = preferences.isGridEnabled
            updateGridToggleUI()
        }

        // 3. Chụp ảnh (Luôn chụp ngay lập tức không delay)
        binding.btnShutter.setOnClickListener {
            triggerShutterCapture(isAuto = false)
        }

        // 4. Đổi Camera Trước/Sau
        binding.btnSwitchCamera.setOnClickListener {
            animateButtonClick(it)
            cameraManager.switchCamera()
        }

        // 5. Chuyển đổi Flash (OFF -> ON -> AUTO)
        binding.btnFlash.setOnClickListener {
            val mode = cameraManager.cycleFlashMode()
            updateFlashIcon(mode)
            val text = when (mode) {
                ImageCapture.FLASH_MODE_ON -> getString(R.string.flash_on)
                ImageCapture.FLASH_MODE_AUTO -> getString(R.string.flash_auto)
                else -> getString(R.string.flash_off)
            }
            Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
        }

        // 6. Cài đặt (Bật/tắt tự động zoom, tự cập nhật)
        binding.btnSettings.setOnClickListener {
            val dialog = SettingsBottomSheetDialog.newInstance()
            dialog.onAutoZoomToggled = { enabled ->
                compositionEngine.isAutoZoomEnabled = enabled
                if (!enabled) {
                    cameraManager.resetZoom()
                }
            }
            dialog.show(supportFragmentManager, SettingsBottomSheetDialog.TAG)
        }

        // 7. Mở Carousel chọn Filter
        binding.btnFilterToggle.setOnClickListener {
            val isVisible = binding.filterCarouselScroll.visibility == View.VISIBLE
            binding.filterCarouselScroll.visibility = if (isVisible) View.GONE else View.VISIBLE
        }

        // Xem nhanh ảnh vừa chụp
        binding.btnQuickPreview.setOnClickListener {
            switchTab(isCamera = false)
        }

        // Chuyển tab Máy ảnh & Thư viện
        binding.tabBtnCamera.setOnClickListener {
            switchTab(isCamera = true)
        }
        binding.tabBtnGallery.setOnClickListener {
            switchTab(isCamera = false)
        }

        // Cấp quyền camera
        binding.btnGrantPermission.setOnClickListener {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        populateFilterCarousel()
    }

    private fun updateAiToggleUI() {
        val isEnabled = preferences.isAiEnabled
        if (isEnabled) {
            binding.btnAiToggle.setColorFilter(ContextCompat.getColor(this, R.color.accent_gold))
            if (preferences.isAutoCaptureEnabled) {
                binding.autoCaptureBadge.visibility = View.VISIBLE
            }
        } else {
            binding.btnAiToggle.setColorFilter(ContextCompat.getColor(this, R.color.white_50))
            binding.autoCaptureBadge.visibility = View.GONE
            binding.aiSuggestionBubble.visibility = View.GONE
        }
    }

    private fun updateGridToggleUI() {
        val isGridOn = preferences.isGridEnabled
        if (isGridOn) {
            binding.btnGridToggle.setColorFilter(ContextCompat.getColor(this, R.color.accent_gold))
        } else {
            binding.btnGridToggle.setColorFilter(ContextCompat.getColor(this, R.color.white_50))
        }
    }

    private fun switchTab(isCamera: Boolean) {
        if (isCamera) {
            binding.cameraTabContainer.visibility = View.VISIBLE
            binding.galleryTabContainer.visibility = View.GONE
            binding.tabBtnCamera.setBackgroundResource(R.drawable.bg_glass_pill_active)
            binding.tabBtnCamera.setTextColor(ContextCompat.getColor(this, R.color.accent_gold))
            binding.tabBtnGallery.background = null
            binding.tabBtnGallery.setTextColor(ContextCompat.getColor(this, R.color.white_70))
        } else {
            binding.cameraTabContainer.visibility = View.GONE
            binding.galleryTabContainer.visibility = View.VISIBLE
            binding.tabBtnGallery.setBackgroundResource(R.drawable.bg_glass_pill_active)
            binding.tabBtnGallery.setTextColor(ContextCompat.getColor(this, R.color.accent_gold))
            binding.tabBtnCamera.background = null
            binding.tabBtnCamera.setTextColor(ContextCompat.getColor(this, R.color.white_70))

            checkStoragePermissionAndLoadGallery()
        }
    }

    private fun setupGalleryTab() {
        binding.rvGalleryGrid.layoutManager = GridLayoutManager(this, 3)
        galleryAdapter = GalleryGridAdapter(galleryUris) { position ->
            FullscreenPhotoDialog.newInstance(
                uris = galleryUris,
                startPosition = position,
                onDeleted = { deletedPos ->
                    galleryAdapter.removeAt(deletedPos)
                    updateGalleryCount()
                }
            ).show(supportFragmentManager, FullscreenPhotoDialog.TAG)
        }
        binding.rvGalleryGrid.adapter = galleryAdapter
    }

    private fun checkStoragePermissionAndLoadGallery() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            loadGalleryPhotos()
        } else {
            storagePermissionLauncher.launch(permission)
        }
    }

    private fun loadGalleryPhotos() {
        lifecycleScope.launch {
            val list = queryMediaStorePhotos()
            galleryAdapter.updateList(list)
            updateGalleryCount()

            if (list.isNotEmpty()) {
                binding.ivQuickThumbnail.load(list.first()) {
                    transformations(CircleCropTransformation())
                    crossfade(true)
                }
            }
        }
    }

    private suspend fun queryMediaStorePhotos(): List<Uri> = withContext(Dispatchers.IO) {
        val photoList = mutableListOf<Uri>()
        val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_ADDED)
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        try {
            val cursor = contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                sortOrder
            )
            cursor?.use {
                val idColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                var count = 0
                while (it.moveToNext() && count < 60) {
                    val id = it.getLong(idColumn)
                    val contentUri = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        id
                    )
                    photoList.add(contentUri)
                    count++
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext photoList
    }

    private fun updateGalleryCount() {
        if (galleryUris.isEmpty()) {
            binding.tvEmptyGallery.visibility = View.VISIBLE
            binding.tvGalleryCount.text = "0 ảnh"
        } else {
            binding.tvEmptyGallery.visibility = View.GONE
            binding.tvGalleryCount.text = "${galleryUris.size} ảnh"
        }
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
                val filterName = getString(filter.titleRes)
                Toast.makeText(
                    this,
                    "Đã chọn filter: $filterName (áp dụng cho ảnh khi chụp)",
                    Toast.LENGTH_SHORT
                ).show()
            }

            binding.filterChipContainer.addView(chip)
        }
    }

    private fun selectFilter(filter: FilterType) {
        activeFilter = filter
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
        if (!preferences.isAiEnabled) {
            binding.aiSuggestionBubble.visibility = View.GONE
            return
        }

        val suggestion = AIFilterRecommender.recommend(luminance)
        if (suggestion.filterType != activeFilter) {
            binding.aiSuggestionBubble.visibility = View.VISIBLE
            binding.tvAiSuggestion.text = suggestion.message

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

        vibrateLight()

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
                val message = exc.localizedMessage?.takeIf { it.isNotBlank() } ?: "Đã xảy ra lỗi"
                Toast.makeText(this, "Lỗi chụp: $message", Toast.LENGTH_SHORT).show()
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

        binding.ivQuickThumbnail.load(uri) {
            transformations(CircleCropTransformation())
            crossfade(true)
        }

        galleryUris.add(0, uri)
        galleryAdapter.notifyItemInserted(0)
        updateGalleryCount()
    }

    private fun updateFlashIcon(mode: Int) {
        val iconRes = when (mode) {
            ImageCapture.FLASH_MODE_ON -> R.drawable.ic_flash_on
            ImageCapture.FLASH_MODE_AUTO -> R.drawable.ic_flash_auto
            else -> R.drawable.ic_flash_off
        }
        binding.btnFlash.setImageResource(iconRes)
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
            // Ignore
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
            updateFlashIcon(cameraManager.getFlashMode())
        }
    }

    private fun autoCheckAppUpdate() {
        lifecycleScope.launch {
            val result = updateManager.checkUpdate(isManual = false)
            if (result is UpdateResult.UpdateAvailable) {
                UpdateDialogFragment.newInstance(
                    version = result.info.latestVersion,
                    changelog = result.info.changelog,
                    url = result.info.downloadUrl
                ).show(supportFragmentManager, UpdateDialogFragment.TAG)
            }
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
        if (::frameAnalyzer.isInitialized) {
            frameAnalyzer.close()
        }
    }
}
