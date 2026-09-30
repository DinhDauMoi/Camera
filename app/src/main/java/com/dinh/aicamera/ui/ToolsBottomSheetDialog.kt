package com.dinh.aicamera.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.dinh.aicamera.R
import com.dinh.aicamera.databinding.DialogToolsBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class ToolsBottomSheetDialog : BottomSheetDialogFragment() {

    private var _binding: DialogToolsBinding? = null
    private val binding get() = _binding!!

    private lateinit var preferences: AppPreferences

    var onHistogramToggled: ((Boolean) -> Unit)? = null
    var onPoseGuideToggled: ((Boolean) -> Unit)? = null
    var onTasteLearningToggled: ((Boolean) -> Unit)? = null
    var onGoldenHourToggled: ((Boolean) -> Unit)? = null
    var onLevelToggled: ((Boolean) -> Unit)? = null

    override fun getTheme(): Int = R.style.Theme_AICamera_BottomSheetDialog

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogToolsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        LiquidGlassHelper.setupGlass(binding.root)
        LiquidGlassHelper.setupGlass(binding.rowHistogram)
        LiquidGlassHelper.setupGlass(binding.rowPoseGuide)
        LiquidGlassHelper.setupGlass(binding.rowTasteLearning)
        LiquidGlassHelper.setupGlass(binding.rowGoldenHour)
        LiquidGlassHelper.setupGlass(binding.rowLevel)
        LiquidGlassHelper.setupGlass(binding.btnCloseTools)

        preferences = AppPreferences(requireContext())

        // 1. Histogram
        binding.switchHistogram.isChecked = preferences.isHistogramEnabled
        binding.switchHistogram.setOnCheckedChangeListener { _, isChecked ->
            preferences.isHistogramEnabled = isChecked
            onHistogramToggled?.invoke(isChecked)
        }

        // 2. Pose guide
        binding.switchPoseGuide.isChecked = preferences.isPoseGuideEnabled
        binding.switchPoseGuide.setOnCheckedChangeListener { _, isChecked ->
            preferences.isPoseGuideEnabled = isChecked
            onPoseGuideToggled?.invoke(isChecked)
        }

        // 3. AI học gu
        binding.switchTasteLearning.isChecked = preferences.isTasteLearningEnabled
        binding.switchTasteLearning.setOnCheckedChangeListener { _, isChecked ->
            preferences.isTasteLearningEnabled = isChecked
            onTasteLearningToggled?.invoke(isChecked)
        }

        // 4. Nhắc giờ vàng
        binding.switchGoldenHour.isChecked = preferences.isGoldenHourEnabled
        binding.switchGoldenHour.setOnCheckedChangeListener { _, isChecked ->
            preferences.isGoldenHourEnabled = isChecked
            onGoldenHourToggled?.invoke(isChecked)
        }

        // 5. Thước cân bằng
        binding.switchLevel.isChecked = preferences.isLevelEnabled
        binding.switchLevel.setOnCheckedChangeListener { _, isChecked ->
            preferences.isLevelEnabled = isChecked
            onLevelToggled?.invoke(isChecked)
        }

        binding.btnCloseTools.setOnClickListener {
            dismiss()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
