package com.dinh.aicamera.ui

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.MediaStore
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.ScaleAnimation
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.ImageCapture
import androidx.camera.core.ZoomState
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import coil.load
import coil.transform.CircleCropTransformation
import com.dinh.aicamera.R
import com.dinh.aicamera.camera.CameraManager
import com.dinh.aicamera.camera.FrameAnalyzer
import com.dinh.aicamera.composition.AiMode
import com.dinh.aicamera.composition.AiStage
import com.dinh.aicamera.composition.CompositionEngine
import com.dinh.aicamera.composition.SensorOrientationHelper
import com.dinh.aicamera.composition.SubjectTracker
import com.dinh.aicamera.databinding.ActivityMainBinding
import com.dinh.aicamera.filter.AIFilterRecommender
import com.dinh.aicamera.filter.ColorMatrixFilter
import com.dinh.aicamera.filter.FilterType
import com.dinh.aicamera.overlay.PoseType
import com.dinh.aicamera.ui.gallery.FullscreenPhotoDialog
import com.dinh.aicamera.ui.gallery.GalleryGridAdapter
import com.dinh.aicamera.ui.update.AppUpdateManager
import com.dinh.aicamera.ui.update.UpdateDialogFragment
import com.dinh.aicamera.ui.update.UpdateResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs
import com.dinh.aicamera.util.GoldenHourHelper

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var preferences: AppPreferences
    private lateinit var updateManager: AppUpdateManager

    private lateinit var cameraManager: CameraManager
    private lateinit var frameAnalyzer: FrameAnalyzer
    private lateinit var compositionEngine: CompositionEngine
    private lateinit var sensorOrientationHelper: SensorOrientationHelper
    private var subjectTracker: SubjectTracker? = null

    private var currentRoll: Float = 0f
    private var currentPitch: Float = 0f
    private var isDeviceSteady: Boolean = true

    private var activeFilter: FilterType = FilterType.NONE
    private var isCapturing: Boolean = false
    private var lastAiStage: AiStage = AiStage.SCANNING
    private var isSuggestionLocked: Boolean = false

    // Zoom & Gestures State
    private var manualZoomOverride: Boolean = false
    private var lastVibrateTime: Long = 0L
    private var lastCapturedUri: Uri? = null
    private lateinit var scaleGestureDetector: ScaleGestureDetector
    private lateinit var gestureDetector: GestureDetector

    // Gallery Tab State (Cached in memory, load on IO, 0ms tab switch lag)
    private val cachedGalleryUris = mutableListOf<Uri>()
    private lateinit var galleryAdapter: GalleryGridAdapter
    private var isGalleryLoaded: Boolean = false
    private var isGalleryLoading: Boolean = false
    private var isGalleryDirty: Boolean = true

    private val mediaStoreObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            super.onChange(selfChange, uri)
            isGalleryDirty = true
        }
    }

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            loadGalleryPhotos()
        }
    }

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

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            GoldenHourHelper.scheduleNextGoldenHour(this)
            Toast.makeText(this, "Đã bật nhắc giờ vàng", Toast.LENGTH_SHORT).show()
        } else {
            GoldenHourHelper.scheduleNextGoldenHour(this)
            Toast.makeText(this, "Đã bật nhắc giờ vàng (tọa độ mặc định)", Toast.LENGTH_SHORT).show()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        // Post notifications permission callback
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        preferences = AppPreferences(this)
        preferences.aiMode = AiMode.OFF // Mở app là AI luôn tắt, user bật tay khi cần
        updateManager = AppUpdateManager(this)

        initAIEngines()
        setupUI()
        setupSensors()
        setupGalleryTab()
        checkCameraPermission()

        if (preferences.isGoldenHourEnabled) {
            GoldenHourHelper.scheduleNextGoldenHour(this)
        }

        contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true,
            mediaStoreObserver
        )

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
                if (preferences.aiMode == AiMode.OFF) {
                    return@runOnUiThread
                }

                val screenW = binding.previewView.width.toFloat()
                val screenH = binding.previewView.height.toFloat()

                val tracker = subjectTracker?.takeIf {
                    // recreate nếu kích thước preview đổi (xoay màn hình)
                    it.screenWidth == screenW && it.screenHeight == screenH
                } ?: SubjectTracker(screenW, screenH).also { subjectTracker = it }
                val tracked = tracker.update(subjectBox, isFace)

                val state = compositionEngine.evaluate(
                    subjectBox = tracked?.box,
                    isFace = tracked?.isFace ?: false,
                    screenWidth = screenW,
                    screenHeight = screenH,
                    rollAngle = currentRoll,
                    pitchAngle = currentPitch,
                    isDeviceSteady = isDeviceSteady,
                    suppressAutoCapture = (preferences.aiMode != AiMode.ZOOM || compositionEngine.getSelectedId() != null),
                    aiMode = preferences.aiMode,
                    onAutoCaptureTrigger = {
                        if (preferences.aiMode == AiMode.ZOOM) {
                            triggerShutterCapture(isAuto = true)
                        }
                    }
                )

                // Rung phản hồi nhẹ khi vừa căn chuẩn vào vùng đích (ALIGNED) - debounce 1.5s: chỉ khi aiMode == ZOOM
                val now = System.currentTimeMillis()
                if (preferences.aiMode == AiMode.ZOOM && state.stage == AiStage.ALIGNED && lastAiStage != AiStage.ALIGNED && (now - lastVibrateTime >= 1500L)) {
                    vibrateLight()
                    lastVibrateTime = now
                }
                lastAiStage = state.stage

                // Cập nhật AR overlay (3 Bước: SCANNING -> GUIDING -> ALIGNED)
                binding.compositionOverlay.updateState(state)

                // BƯỚC 3: TỰ ĐỘNG ZOOM legacy (deadband > 0.02, chỉ khi aiMode == ZOOM, stage ALIGNED, shouldZoom và !manualZoomOverride)
                if (preferences.aiMode == AiMode.ZOOM && state.stage == AiStage.ALIGNED && state.shouldZoom && !manualZoomOverride) {
                    val currentRatio = cameraManager.getZoomRatio()
                    if (abs(state.targetZoomRatio - currentRatio) > 0.02f) {
                        cameraManager.setZoomRatio(state.targetZoomRatio, smooth = true)
                    }
                }

                // AI Gợi ý Filter (hiện dưới khung hình, không đè lên AR overlay)
                evaluateAIFilterSuggestion(avgLuminance)

                // Khối zoom mượt theo chấm đã chọn + kiểm tra LOCKED (~8% tâm): chỉ khi aiMode == DOTS
                if (preferences.aiMode == AiMode.DOTS) {
                    val selectedDot = compositionEngine.getSelectedSuggestion()
                    if (selectedDot != null) {
                        val dotDist = kotlin.math.hypot(selectedDot.x - 0.5f, selectedDot.y - 0.5f)
                        if (dotDist <= 0.08f) {
                            if (!isSuggestionLocked) {
                                isSuggestionLocked = true
                                binding.compositionOverlay.setLocked(true)
                                vibrateLight(30L)
                                binding.tvAiSuggestion.text = "Bố cục đẹp!"
                                binding.aiSuggestionBubble.visibility = View.VISIBLE
                            }
                        } else {
                            if (isSuggestionLocked) {
                                isSuggestionLocked = false
                                binding.compositionOverlay.setLocked(false)
                                binding.aiSuggestionBubble.visibility = View.GONE
                            }
                        }
                    } else {
                        if (isSuggestionLocked) {
                            isSuggestionLocked = false
                            binding.compositionOverlay.setLocked(false)
                            binding.aiSuggestionBubble.visibility = View.GONE
                        }
                    }
                }
            }
        }

        frameAnalyzer.compositionEngine = compositionEngine
        frameAnalyzer.isTasteLearningEnabled = preferences.isTasteLearningEnabled
        frameAnalyzer.top3TasteRegions = preferences.getTop3TasteRegions()
        frameAnalyzer.onHistogramUpdated = { hist ->
            if (binding.compositionOverlay.isHistogramEnabled) {
                runOnUiThread {
                    binding.compositionOverlay.histogram = hist
                }
            }
        }

        // Observe engine.suggestions -> overlay.setSuggestions(...) (§6)
        lifecycleScope.launch {
            compositionEngine.suggestions.collect { list ->
                binding.compositionOverlay.setSuggestions(list)
            }
        }

        // overlay.onSuggestionTap -> engine.selectSuggestion(id) -> tính target -> cameraManager.smoothZoomTo(target) -> overlay.setSelected(id) (§6)
        binding.compositionOverlay.onSuggestionTap = { id ->
            val selected = compositionEngine.selectSuggestion(id)
            if (selected != null) {
                binding.compositionOverlay.setSelected(selected.id)
                binding.compositionOverlay.setLocked(false)
                binding.compositionOverlay.isLegacyGuideVisible = false
                isSuggestionLocked = false

                // AI học gu: ghi lại vùng 3x3 khi user chọn dot
                preferences.recordTasteDot(selected.x, selected.y)
                frameAnalyzer.top3TasteRegions = preferences.getTop3TasteRegions()

                // Tính targetRatio khi chọn chấm (§4)
                val currentZoom = cameraManager.getZoomRatio()
                val minZoom = cameraManager.getMinZoomRatio()
                val maxZoom = cameraManager.getMaxZoomRatio()

                val targetRatio = if (!selected.isScene && selected.boxW != null && selected.boxH != null) {
                    val maxBoxDim = maxOf(selected.boxW, selected.boxH)
                    if (maxBoxDim > 0.001f) {
                        currentZoom * (0.6f / maxBoxDim)
                    } else {
                        currentZoom
                    }
                } else {
                    // Phong cảnh (isScene): targetRatio = min(currentZoom, 1.0f)
                    minOf(currentZoom, 1.0f)
                }.coerceIn(minZoom, maxZoom)

                cameraManager.smoothZoomTo(targetRatio)
            } else {
                // Chạm lại chấm đang chọn -> Hủy chọn, về SUGGESTING (§2.5)
                binding.compositionOverlay.setSelected(null)
                binding.compositionOverlay.setLocked(false)
                binding.compositionOverlay.isLegacyGuideVisible = (preferences.aiMode == AiMode.ZOOM)
                isSuggestionLocked = false
                binding.aiSuggestionBubble.visibility = View.GONE
            }
        }

        // Khởi tạo trạng thái mặc định
        val isAiActive = (preferences.aiMode != AiMode.OFF)
        frameAnalyzer.isAiEnabled = isAiActive
        frameAnalyzer.aiMode = preferences.aiMode
        frameAnalyzer.onAiToggled(isAiActive)
        binding.compositionOverlay.isAiEnabled = isAiActive
        binding.compositionOverlay.aiMode = preferences.aiMode
        binding.compositionOverlay.gridMode = preferences.gridMode
        binding.compositionOverlay.isHistogramEnabled = preferences.isHistogramEnabled
        binding.compositionOverlay.isPoseGuideEnabled = preferences.isPoseGuideEnabled
        binding.compositionOverlay.poseIndex = preferences.poseIndex
        updateLeftSideButtonsVisibility()
        binding.compositionOverlay.isLevelEnabled = preferences.isLevelEnabled
    }

    private fun setupSensors() {
        sensorOrientationHelper = SensorOrientationHelper(this) { roll, pitch, isSteady ->
            currentRoll = roll
            currentPitch = pitch
            isDeviceSteady = isSteady
            binding.compositionOverlay.setLevelAngle(roll)
        }
    }

    private fun setupUI() {
        // Thiết lập Liquid Glass đúng chuẩn: clipToOutline = true cho tất cả view kính
        LiquidGlassHelper.setupGlass(binding.topToolbar)
        LiquidGlassHelper.setupGlass(binding.zoomPresetContainer)
        LiquidGlassHelper.setupGlass(binding.bottomNavigationPill)
        LiquidGlassHelper.setupGlass(binding.btnQuickPreview)
        LiquidGlassHelper.setupGlass(binding.aiSuggestionBubble)
        LiquidGlassHelper.setupGlass(binding.filterCarouselScroll)
        LiquidGlassHelper.setupGlass(binding.autoCaptureBadge)
        LiquidGlassHelper.setupGlass(binding.tvGalleryCount)
        LiquidGlassHelper.setupGlass(binding.permissionCard)

        // 1. Nút AI Gợi ý điểm đẹp (DOTS) & AI Tự động zoom (ZOOM)
        updateAiModeUI()
        binding.btnAiDots.setOnClickListener {
            animateButtonClick(it)
            switchAiMode(AiMode.DOTS)
        }
        binding.btnAiZoom.setOnClickListener {
            animateButtonClick(it)
            switchAiMode(AiMode.ZOOM)
        }

        // 2. Toggle Lưới với vòng lặp 5 kiểu (Tắt -> 1/3 -> Golden -> Chéo -> Trung tâm -> Tắt)
        updateGridToggleUI()
        binding.btnGridToggle.setOnClickListener {
            val nextMode = (preferences.gridMode + 1) % 5
            preferences.gridMode = nextMode
            updateGridToggleUI()
            val name = when (nextMode) {
                1 -> "Lưới 1/3"
                2 -> "Golden ratio"
                3 -> "Đường chéo"
                4 -> "Trung tâm"
                else -> "Tắt lưới"
            }
            Toast.makeText(this, name, Toast.LENGTH_SHORT).show()
        }

        // Nút đổi dáng chụp (Cạnh trái preview, chỉ hiện khi bật Pose guide)
        LiquidGlassHelper.setupGlass(binding.btnPoseToggle)
        binding.btnPoseToggle.setOnClickListener {
            animateButtonClick(it)
            val poses = PoseType.values()
            val nextIndex = (preferences.poseIndex + 1) % poses.size
            preferences.poseIndex = nextIndex
            binding.compositionOverlay.poseIndex = nextIndex
            val poseName = poses[nextIndex].displayName
            Toast.makeText(this, "Dáng: $poseName", Toast.LENGTH_SHORT).show()
        }

        // Nút mở nhanh bảng chỉnh Filter Tùy chỉnh (Cạnh trái preview)
        LiquidGlassHelper.setupGlass(binding.btnCustomFilter)
        binding.btnCustomFilter.setOnClickListener {
            animateButtonClick(it)
            if (activeFilter != FilterType.CUSTOM) {
                selectFilter(FilterType.CUSTOM)
            }
            showCustomFilterDialog()
        }

        // Nút Tools (⋯) mở panel công cụ 5 tính năng
        LiquidGlassHelper.setupGlass(binding.btnTools)
        binding.btnTools.setOnClickListener {
            val dialog = ToolsBottomSheetDialog().apply {
                onHistogramToggled = { enabled ->
                    binding.compositionOverlay.isHistogramEnabled = enabled
                    if (!enabled) binding.compositionOverlay.histogram = null
                }
                onPoseGuideToggled = { enabled ->
                    binding.compositionOverlay.isPoseGuideEnabled = enabled
                    updateLeftSideButtonsVisibility()
                }
                onTasteLearningToggled = { enabled ->
                    frameAnalyzer.isTasteLearningEnabled = enabled
                }
                onGoldenHourToggled = { enabled ->
                    if (enabled) {
                        checkAndEnableGoldenHour()
                    } else {
                        GoldenHourHelper.cancelGoldenHour(this@MainActivity)
                        Toast.makeText(this@MainActivity, "Đã tắt nhắc giờ vàng", Toast.LENGTH_SHORT).show()
                    }
                }
                onLevelToggled = { enabled ->
                    binding.compositionOverlay.isLevelEnabled = enabled
                }
            }
            dialog.show(supportFragmentManager, "ToolsBottomSheetDialog")
        }

        // 3. Chụp ảnh (Luôn chụp ngay lập tức không delay)
        binding.btnShutter.setOnClickListener {
            triggerShutterCapture(isAuto = false)
        }

        // 4. Đổi Camera Trước/Sau
        binding.btnSwitchCamera.setOnClickListener {
            animateButtonClick(it)
            cameraManager.switchCamera()
            manualZoomOverride = false
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

        // 8. Xem nhanh ảnh vừa chụp -> Mở tab Thư viện
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

        setupZoomControls()
        setupGestureDetectors()
        populateFilterCarousel()
    }

    private fun switchAiMode(targetMode: AiMode) {
        val newMode = if (preferences.aiMode == targetMode) AiMode.OFF else targetMode
        preferences.aiMode = newMode

        subjectTracker?.reset()
        manualZoomOverride = false
        isSuggestionLocked = false
        cameraManager.cancelSmoothZoom()
        cameraManager.resetZoom()
        compositionEngine.clearSuggestions()
        binding.compositionOverlay.clearSuggestions()
        binding.compositionOverlay.isLegacyGuideVisible = (newMode == AiMode.ZOOM)

        val isAiActive = (newMode != AiMode.OFF)
        frameAnalyzer.isAiEnabled = isAiActive
        frameAnalyzer.aiMode = newMode
        frameAnalyzer.onAiToggled(isAiActive)
        binding.compositionOverlay.isAiEnabled = isAiActive
        binding.compositionOverlay.aiMode = newMode

        if (isAiActive) {
            binding.tvAiSuggestion.text = "Đang quét..."
            binding.aiSuggestionBubble.visibility = View.VISIBLE
            Handler(Looper.getMainLooper()).postDelayed({
                if (preferences.aiMode != AiMode.OFF && compositionEngine.getSelectedId() == null) {
                    binding.aiSuggestionBubble.visibility = View.GONE
                }
            }, 1500L)

            val msg = if (newMode == AiMode.DOTS) getString(R.string.ai_dots_on) else getString(R.string.ai_zoom_on)
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        } else {
            binding.aiSuggestionBubble.visibility = View.GONE
            Toast.makeText(this, "Đã tắt AI - Trở về camera thường", Toast.LENGTH_SHORT).show()
        }

        updateAiModeUI()
    }

    private fun updateAiModeUI() {
        val currentMode = preferences.aiMode
        val pinkColor = ContextCompat.getColor(this, R.color.accent_pink)
        val dimWhiteColor = ContextCompat.getColor(this, R.color.white_50)
        val whiteColor = ContextCompat.getColor(this, R.color.white)

        when (currentMode) {
            AiMode.DOTS -> {
                binding.btnAiDots.setColorFilter(pinkColor)
                binding.btnAiZoom.setColorFilter(dimWhiteColor)
            }
            AiMode.ZOOM -> {
                binding.btnAiDots.setColorFilter(dimWhiteColor)
                binding.btnAiZoom.setColorFilter(pinkColor)
            }
            AiMode.OFF -> {
                binding.btnAiDots.setColorFilter(whiteColor)
                binding.btnAiZoom.setColorFilter(whiteColor)
                binding.aiSuggestionBubble.visibility = View.GONE
            }
        }

        val showBadge = (currentMode == AiMode.ZOOM && preferences.isAutoCaptureEnabled)
        binding.autoCaptureBadge.visibility = if (showBadge) View.VISIBLE else View.GONE
    }

    private fun updateGridToggleUI() {
        val mode = preferences.gridMode
        binding.compositionOverlay.gridMode = mode
        if (mode != 0) {
            binding.btnGridToggle.setColorFilter(ContextCompat.getColor(this, R.color.accent_pink))
        } else {
            binding.btnGridToggle.setColorFilter(ContextCompat.getColor(this, R.color.white_50))
        }
    }

    private fun checkAndEnableGoldenHour() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Cần quyền vị trí để tính giờ mặt trời", Toast.LENGTH_SHORT).show()
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        } else {
            GoldenHourHelper.scheduleNextGoldenHour(this)
            Toast.makeText(this, "Đã bật nhắc giờ vàng", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupZoomControls() {
        binding.btnZoom05.setOnClickListener {
            manualZoomOverride = true
            cameraManager.setZoomRatio(0.5f, smooth = true)
        }

        binding.btnZoom1.setOnClickListener {
            manualZoomOverride = true
            cameraManager.setZoomRatio(1.0f, smooth = true)
        }

        binding.btnZoom3.setOnClickListener {
            manualZoomOverride = true
            cameraManager.setZoomRatio(3.0f, smooth = true)
        }

        binding.btnZoom6.setOnClickListener {
            manualZoomOverride = true
            cameraManager.setZoomRatio(6.0f, smooth = true)
        }
    }

    private fun updateZoomUI(zoomState: ZoomState) {
        val current = zoomState.zoomRatio
        val min = zoomState.minZoomRatio
        val max = zoomState.maxZoomRatio

        binding.tvCurrentZoom.text = String.format(Locale.US, "%.1fx", current)

        // Giới hạn theo khả năng thật của camera: nút preset ngoài khoảng thì ẩn đi
        binding.btnZoom05.visibility = if (min <= 0.5f) View.VISIBLE else View.GONE
        binding.btnZoom1.visibility = if (min <= 1.0f && max >= 1.0f) View.VISIBLE else View.GONE
        binding.btnZoom3.visibility = if (max >= 3.0f) View.VISIBLE else View.GONE
        binding.btnZoom6.visibility = if (max >= 6.0f) View.VISIBLE else View.GONE

        val activeBg = R.drawable.bg_glass_pill_active
        val activeColor = ContextCompat.getColor(this, R.color.black)
        val inactiveColor = ContextCompat.getColor(this, R.color.white_70)

        val is05 = abs(current - 0.5f) < 0.08f
        val is1 = abs(current - 1.0f) < 0.12f
        val is3 = abs(current - 3.0f) < 0.2f
        val is6 = abs(current - 6.0f) < 0.3f

        binding.btnZoom05.background = if (is05) ContextCompat.getDrawable(this, activeBg) else null
        binding.btnZoom05.setTextColor(if (is05) activeColor else inactiveColor)

        binding.btnZoom1.background = if (is1) ContextCompat.getDrawable(this, activeBg) else null
        binding.btnZoom1.setTextColor(if (is1) activeColor else inactiveColor)

        binding.btnZoom3.background = if (is3) ContextCompat.getDrawable(this, activeBg) else null
        binding.btnZoom3.setTextColor(if (is3) activeColor else inactiveColor)

        binding.btnZoom6.background = if (is6) ContextCompat.getDrawable(this, activeBg) else null
        binding.btnZoom6.setTextColor(if (is6) activeColor else inactiveColor)
    }

    private fun setupGestureDetectors() {
        scaleGestureDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                // Người dùng pinch-zoom tay bất cứ lúc nào -> hủy animation zoom tự động, giữ lựa chọn (§2.6)
                cameraManager.cancelSmoothZoom()
                manualZoomOverride = true
                val currentRatio = cameraManager.getZoomRatio()
                val minRatio = cameraManager.getMinZoomRatio()
                val maxRatio = cameraManager.getMaxZoomRatio()
                val newRatio = (currentRatio * detector.scaleFactor).coerceIn(minRatio, maxRatio)
                cameraManager.setZoomRatio(newRatio, smooth = false)
                return true
            }
        })

        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                // Chạm vùng trống -> hủy chọn, về SUGGESTING (§2.5)
                if (compositionEngine.getSelectedId() != null) {
                    compositionEngine.clearSelection()
                    binding.compositionOverlay.setSelected(null)
                    binding.compositionOverlay.setLocked(false)
                    binding.compositionOverlay.isLegacyGuideVisible = true
                    isSuggestionLocked = false
                    binding.aiSuggestionBubble.visibility = View.GONE
                    return true
                }
                if (!scaleGestureDetector.isInProgress) {
                    cameraManager.tapToFocus(e.x, e.y)
                }
                return true
            }
        })

        binding.previewView.setOnTouchListener { v, event ->
            scaleGestureDetector.onTouchEvent(event)
            gestureDetector.onTouchEvent(event)
            if (event.action == MotionEvent.ACTION_UP) {
                v.performClick()
            }
            true
        }
    }

    private fun switchTab(isCamera: Boolean) {
        if (isCamera) {
            binding.cameraTabContainer.visibility = View.VISIBLE
            binding.galleryTabContainer.visibility = View.GONE
            binding.tabBtnCamera.setBackgroundResource(R.drawable.bg_glass_pill_active)
            binding.tabBtnCamera.setTextColor(ContextCompat.getColor(this, R.color.black))
            binding.tabBtnGallery.background = null
            binding.tabBtnGallery.setTextColor(ContextCompat.getColor(this, R.color.white_70))
        } else {
            binding.cameraTabContainer.visibility = View.GONE
            binding.galleryTabContainer.visibility = View.VISIBLE
            binding.tabBtnGallery.setBackgroundResource(R.drawable.bg_glass_pill_active)
            binding.tabBtnGallery.setTextColor(ContextCompat.getColor(this, R.color.black))
            binding.tabBtnCamera.background = null
            binding.tabBtnCamera.setTextColor(ContextCompat.getColor(this, R.color.white_70))

            checkStoragePermissionAndLoadGallery()
        }
    }

    private fun setupGalleryTab() {
        binding.rvGalleryGrid.apply {
            layoutManager = GridLayoutManager(this@MainActivity, 3)
            setHasFixedSize(true)
            itemAnimator = null
        }
        galleryAdapter = GalleryGridAdapter { position ->
            if (position in 0 until cachedGalleryUris.size) {
                FullscreenPhotoDialog.newInstance(
                    uris = cachedGalleryUris.toList(),
                    startPosition = position,
                    onDeleted = { deletedPos ->
                        if (deletedPos in 0 until cachedGalleryUris.size) {
                            cachedGalleryUris.removeAt(deletedPos)
                            galleryAdapter.submitList(cachedGalleryUris.toList())
                            updateGalleryCount(cachedGalleryUris.size)
                            if (cachedGalleryUris.isNotEmpty()) {
                                val first = cachedGalleryUris.first()
                                binding.ivQuickThumbnail.load(first) {
                                    transformations(CircleCropTransformation())
                                    crossfade(true)
                                }
                                lastCapturedUri = first
                            } else {
                                binding.ivQuickThumbnail.setImageResource(R.drawable.ic_gallery)
                                lastCapturedUri = null
                            }
                        }
                    }
                ).show(supportFragmentManager, FullscreenPhotoDialog.TAG)
            }
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
            if (!isGalleryLoaded || isGalleryDirty) {
                loadGalleryPhotos()
            }
        } else {
            storagePermissionLauncher.launch(permission)
        }
    }

    private fun loadGalleryPhotos() {
        if (isGalleryLoading) return
        isGalleryLoading = true

        if (cachedGalleryUris.isEmpty()) {
            binding.galleryLoadingProgress.visibility = View.VISIBLE
            binding.tvEmptyGallery.visibility = View.GONE
        }

        lifecycleScope.launch {
            val photos = withContext(Dispatchers.IO) {
                queryMediaStorePhotos(limit = 100)
            }
            isGalleryLoading = false
            isGalleryLoaded = true
            isGalleryDirty = false
            binding.galleryLoadingProgress.visibility = View.GONE

            cachedGalleryUris.clear()
            cachedGalleryUris.addAll(photos)
            galleryAdapter.submitList(cachedGalleryUris.toList())
            updateGalleryCount(cachedGalleryUris.size)

            if (photos.isNotEmpty()) {
                val first = photos.first()
                binding.ivQuickThumbnail.load(first) {
                    transformations(CircleCropTransformation())
                    crossfade(true)
                }
                lastCapturedUri = first
            }
        }
    }

    private suspend fun queryMediaStorePhotos(limit: Int = 100): List<Uri> = withContext(Dispatchers.IO) {
        val photoList = mutableListOf<Uri>()
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
        try {
            contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                sortOrder
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                var count = 0
                while (cursor.moveToNext() && count < limit) {
                    val id = cursor.getLong(idColumn)
                    val contentUri = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        id
                    )
                    photoList.add(contentUri)
                    count++
                }
            }
        } catch (_: Exception) {}
        photoList
    }

    private fun updateGalleryCount(count: Int) {
        if (count == 0) {
            binding.tvEmptyGallery.visibility = View.VISIBLE
            binding.tvGalleryCount.text = "0 ảnh"
        } else {
            binding.tvEmptyGallery.visibility = View.GONE
            binding.tvGalleryCount.text = "$count ảnh"
        }
    }

    private fun loadLatestThumbnail() {
        lifecycleScope.launch {
            val photos = withContext(Dispatchers.IO) {
                queryMediaStorePhotos(limit = 1)
            }
            if (photos.isNotEmpty()) {
                val uri = photos.first()
                lastCapturedUri = uri
                binding.ivQuickThumbnail.load(uri) {
                    transformations(CircleCropTransformation())
                    crossfade(true)
                }
            }
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
                if (filter == FilterType.CUSTOM) {
                    showCustomFilterDialog()
                } else {
                    val filterName = getString(filter.titleRes)
                    Toast.makeText(
                        this,
                        "Đã chọn filter: $filterName (áp dụng cho ảnh khi chụp)",
                        Toast.LENGTH_SHORT
                    ).show()
                }
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
        updateLeftSideButtonsVisibility()
        updateViewfinderCustomEffect()
    }

    private fun showCustomFilterDialog() {
        val existing = supportFragmentManager.findFragmentByTag(CustomFilterBottomSheetDialog.TAG)
        if (existing == null) {
            val dialog = CustomFilterBottomSheetDialog.newInstance()
            dialog.show(supportFragmentManager, CustomFilterBottomSheetDialog.TAG)
            dialog.onFilterChanged = {
                updateViewfinderCustomEffect()
            }
            updateViewfinderCustomEffect()
        }
    }

    private fun updateViewfinderCustomEffect() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return // RenderEffect cần API 31+
        if (activeFilter != FilterType.CUSTOM) {
            binding.previewView.setRenderEffect(null)
            return
        }
        val matrix = ColorMatrixFilter.getCustomMatrix(
            tone = preferences.customFilterTone,
            warmth = preferences.customFilterWarmth,
            vivid = preferences.customFilterVivid,
            gridX = preferences.customFilterGridX,
            gridY = preferences.customFilterGridY
        )
        binding.previewView.setRenderEffect(
            RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(matrix))
        )
    }

    private fun updateLeftSideButtonsVisibility() {
        val showPose = preferences.isPoseGuideEnabled
        binding.btnPoseToggle.visibility = if (showPose) View.VISIBLE else View.GONE
        binding.btnCustomFilter.visibility = View.VISIBLE
        binding.leftSideButtons.visibility = View.VISIBLE
    }

    private fun updateChipStyle(chip: TextView, isSelected: Boolean) {
        if (isSelected) {
            chip.setBackgroundResource(R.drawable.bg_glass_pill_active)
            chip.setTextColor(ContextCompat.getColor(this, R.color.black))
        } else {
            chip.setBackgroundResource(R.drawable.bg_glass_pill)
            chip.setTextColor(ContextCompat.getColor(this, R.color.white_90))
        }
    }

    private fun evaluateAIFilterSuggestion(luminance: Float) {
        if (preferences.aiMode == AiMode.OFF) {
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

        lastCapturedUri = uri
        cachedGalleryUris.add(0, uri)
        galleryAdapter.submitList(cachedGalleryUris.toList())
        updateGalleryCount(cachedGalleryUris.size)
        isGalleryDirty = false
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

    private fun vibrateLight(durationMs: Long = 75L) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator.vibrate(
                    VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(durationMs)
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
            ).apply {
                onZoomStateChanged = { state ->
                    updateZoomUI(state)
                }
            }
            cameraManager.startCamera {
                loadLatestThumbnail()
            }
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
        try {
            contentResolver.unregisterContentObserver(mediaStoreObserver)
        } catch (_: Exception) {}
        if (::cameraManager.isInitialized) {
            cameraManager.shutdown()
        }
        if (::frameAnalyzer.isInitialized) {
            frameAnalyzer.close()
        }
    }
}
