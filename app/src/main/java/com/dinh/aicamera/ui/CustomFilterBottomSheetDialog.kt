package com.dinh.aicamera.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import com.dinh.aicamera.R
import com.dinh.aicamera.databinding.DialogCustomFilterBinding
import com.dinh.aicamera.filter.ColorMatrixFilter
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * Bottom sheet chỉnh filter "Tùy chỉnh" phong cách iPhone:
 * - Lưới chọn màu 2D (ấm/lạnh ↔ cường độ)
 * - 3 thanh trượt (TÔNG, ẤM, RỰC RỠ)
 * - Ảnh xem trước trực tiếp thời gian thực
 * - Nút Đặt lại & Nút Xong
 */
class CustomFilterBottomSheetDialog : BottomSheetDialogFragment() {

    private var _binding: DialogCustomFilterBinding? = null
    private val binding get() = _binding!!

    private lateinit var preferences: AppPreferences
    private var sampleBitmap: Bitmap? = null

    private var currentTone: Int = 50
    private var currentWarmth: Int = 50
    private var currentVivid: Int = 50
    private var currentGridX: Float = 0.5f
    private var currentGridY: Float = 0.5f

    var onFilterChanged: (() -> Unit)? = null

    override fun getTheme(): Int = R.style.Theme_AICamera_BottomSheetDialog

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogCustomFilterBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        LiquidGlassHelper.setupGlass(binding.root)
        LiquidGlassHelper.setupGlass(binding.btnResetCustomFilter)
        LiquidGlassHelper.setupGlass(binding.btnDoneCustomFilter)

        preferences = AppPreferences(requireContext())

        // 1. Đọc thông số đã lưu từ preferences
        currentTone = preferences.customFilterTone
        currentWarmth = preferences.customFilterWarmth
        currentVivid = preferences.customFilterVivid
        currentGridX = preferences.customFilterGridX
        currentGridY = preferences.customFilterGridY

        // 2. Chuẩn bị ảnh preview mẫu tự generate bằng code
        setupSamplePreview()

        // 3. Khởi tạo giá trị trên UI
        binding.sbTone.progress = currentTone
        binding.sbWarmth.progress = currentWarmth
        binding.sbVivid.progress = currentVivid
        binding.colorGridView.setPoint(currentGridX, currentGridY)

        updateToneText(currentTone)
        updateWarmthText(currentWarmth)
        updateVividText(currentVivid)

        // Áp dụng preview ban đầu
        updateLivePreview()

        // 4. Lắng nghe thay đổi từ SeekBar TÔNG
        binding.sbTone.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    currentTone = progress
                    preferences.customFilterTone = progress
                    updateToneText(progress)
                    updateLivePreview()
                    onFilterChanged?.invoke()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // 5. Lắng nghe thay đổi từ SeekBar ẤM
        binding.sbWarmth.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    currentWarmth = progress
                    preferences.customFilterWarmth = progress
                    updateWarmthText(progress)
                    updateLivePreview()
                    onFilterChanged?.invoke()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // 6. Lắng nghe thay đổi từ SeekBar RỰC RỠ
        binding.sbVivid.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    currentVivid = progress
                    preferences.customFilterVivid = progress
                    updateVividText(progress)
                    updateLivePreview()
                    onFilterChanged?.invoke()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // 7. Lắng nghe thay đổi từ Lưới màu 2D
        binding.colorGridView.onPointChanged = { gx, gy ->
            currentGridX = gx
            currentGridY = gy
            preferences.customFilterGridX = gx
            preferences.customFilterGridY = gy
            updateLivePreview()
            onFilterChanged?.invoke()
        }

        // 8. Nút Đặt lại (về 50/50/50 + điểm giữa lưới 0.5f/0.5f)
        binding.btnResetCustomFilter.setOnClickListener {
            resetToDefault()
        }

        // 9. Nút Xong (đóng sheet)
        binding.btnDoneCustomFilter.setOnClickListener {
            dismiss()
        }
    }

    private fun resetToDefault() {
        currentTone = 50
        currentWarmth = 50
        currentVivid = 50
        currentGridX = 0.5f
        currentGridY = 0.5f

        preferences.customFilterTone = 50
        preferences.customFilterWarmth = 50
        preferences.customFilterVivid = 50
        preferences.customFilterGridX = 0.5f
        preferences.customFilterGridY = 0.5f

        binding.sbTone.progress = 50
        binding.sbWarmth.progress = 50
        binding.sbVivid.progress = 50
        binding.colorGridView.setPoint(0.5f, 0.5f)

        updateToneText(50)
        updateWarmthText(50)
        updateVividText(50)

        updateLivePreview()
        onFilterChanged?.invoke()
    }

    private fun updateToneText(value: Int) {
        val diff = value - 50
        binding.tvToneValue.text = if (diff > 0) "+$diff" else "$diff"
    }

    private fun updateWarmthText(value: Int) {
        val diff = value - 50
        binding.tvWarmthValue.text = if (diff > 0) "+$diff" else "$diff"
    }

    private fun updateVividText(value: Int) {
        val diff = value - 50
        binding.tvVividValue.text = if (diff > 0) "+$diff" else "$diff"
    }

    /**
     * Cập nhật tức thì ColorMatrixColorFilter lên ImageView xem trước
     */
    private fun updateLivePreview() {
        val matrix = ColorMatrixFilter.getCustomMatrix(
            tone = currentTone,
            warmth = currentWarmth,
            vivid = currentVivid,
            gridX = currentGridX,
            gridY = currentGridY
        )
        binding.ivCustomFilterPreview.colorFilter = ColorMatrixColorFilter(matrix)
    }

    /**
     * Tự generate bitmap mẫu gồm:
     * - Dải màu đa sắc (Đỏ, Cam, Vàng, Xanh lá, Xanh dương, Tím)
     * - Tone màu da tự nhiên / chân dung
     * - Ảnh xám gradient (đen -> trắng)
     */
    private fun setupSamplePreview() {
        val width = 480
        val height = 240
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Nền tối
        canvas.drawColor(Color.parseColor("#12141A"))

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // 1. Dải màu chuyển tiếp rực rỡ ở phía trên (0..130)
        val spectrumShader = LinearGradient(
            0f, 0f, width.toFloat(), 0f,
            intArrayOf(
                Color.parseColor("#FF3B30"), // Đỏ
                Color.parseColor("#FF9500"), // Cam
                Color.parseColor("#FFCC00"), // Vàng
                Color.parseColor("#34C759"), // Xanh lá
                Color.parseColor("#00C7BE"), // Xanh ngọc
                Color.parseColor("#007AFF"), // Xanh dương
                Color.parseColor("#AF52DE")  // Tím
            ),
            null,
            Shader.TileMode.CLAMP
        )
        paint.shader = spectrumShader
        canvas.drawRect(0f, 0f, width.toFloat(), 130f, paint)

        // 2. Khối mẫu màu da chân dung và màu thiên nhiên nổi bật ở giữa
        paint.shader = null
        // Màu da tự nhiên
        paint.color = Color.parseColor("#F5D0C5")
        canvas.drawRoundRect(RectF(16f, 30f, 130f, 100f), 12f, 12f, paint)
        paint.color = Color.parseColor("#E09F84")
        canvas.drawRoundRect(RectF(145f, 30f, 245f, 100f), 12f, 12f, paint)
        // Màu xanh foliage
        paint.color = Color.parseColor("#28A745")
        canvas.drawRoundRect(RectF(260f, 30f, 350f, 100f), 12f, 12f, paint)
        // Màu trời xanh dương
        paint.color = Color.parseColor("#17A2B8")
        canvas.drawRoundRect(RectF(365f, 30f, 464f, 100f), 12f, 12f, paint)

        // 3. Dải xám gradient ở nửa dưới (130..240: Đen -> Trắng)
        val grayscaleShader = LinearGradient(
            0f, 0f, width.toFloat(), 0f,
            intArrayOf(
                Color.parseColor("#050505"),
                Color.parseColor("#333333"),
                Color.parseColor("#666666"),
                Color.parseColor("#999999"),
                Color.parseColor("#CCCCCC"),
                Color.parseColor("#FFFFFF")
            ),
            null,
            Shader.TileMode.CLAMP
        )
        paint.shader = grayscaleShader
        canvas.drawRect(0f, 130f, width.toFloat(), 240f, paint)

        sampleBitmap = bitmap
        binding.ivCustomFilterPreview.setImageBitmap(bitmap)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        sampleBitmap?.recycle()
        sampleBitmap = null
        _binding = null
    }

    companion object {
        const val TAG = "CustomFilterBottomSheetDialog"

        fun newInstance(): CustomFilterBottomSheetDialog {
            return CustomFilterBottomSheetDialog()
        }
    }
}
