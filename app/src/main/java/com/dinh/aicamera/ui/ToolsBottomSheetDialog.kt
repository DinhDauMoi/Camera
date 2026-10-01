package com.dinh.aicamera.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.dinh.aicamera.R
import com.dinh.aicamera.databinding.DialogToolsBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton

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
        LiquidGlassHelper.setupGlass(binding.btnCloseTools)

        preferences = AppPreferences(requireContext())

        // 1. Histogram realtime
        bindTool(
            itemContainer = binding.itemToolHistogram,
            button = binding.btnToolHistogram,
            label = binding.tvToolHistogram,
            initialChecked = preferences.isHistogramEnabled
        ) { isChecked ->
            preferences.isHistogramEnabled = isChecked
            onHistogramToggled?.invoke(isChecked)
        }

        // 2. Pose guide
        bindTool(
            itemContainer = binding.itemToolPoseGuide,
            button = binding.btnToolPoseGuide,
            label = binding.tvToolPoseGuide,
            initialChecked = preferences.isPoseGuideEnabled
        ) { isChecked ->
            preferences.isPoseGuideEnabled = isChecked
            onPoseGuideToggled?.invoke(isChecked)
        }

        // 3. AI học gu
        bindTool(
            itemContainer = binding.itemToolTasteLearning,
            button = binding.btnToolTasteLearning,
            label = binding.tvToolTasteLearning,
            initialChecked = preferences.isTasteLearningEnabled
        ) { isChecked ->
            preferences.isTasteLearningEnabled = isChecked
            onTasteLearningToggled?.invoke(isChecked)
        }

        // 4. Nhắc giờ vàng
        bindTool(
            itemContainer = binding.itemToolGoldenHour,
            button = binding.btnToolGoldenHour,
            label = binding.tvToolGoldenHour,
            initialChecked = preferences.isGoldenHourEnabled
        ) { isChecked ->
            preferences.isGoldenHourEnabled = isChecked
            onGoldenHourToggled?.invoke(isChecked)
        }

        // 5. Thước cân bằng
        bindTool(
            itemContainer = binding.itemToolLevel,
            button = binding.btnToolLevel,
            label = binding.tvToolLevel,
            initialChecked = preferences.isLevelEnabled
        ) { isChecked ->
            preferences.isLevelEnabled = isChecked
            onLevelToggled?.invoke(isChecked)
        }

        binding.btnCloseTools.setOnClickListener {
            dismiss()
        }
    }

    private fun bindTool(
        itemContainer: View,
        button: MaterialButton,
        label: TextView,
        initialChecked: Boolean,
        onToggled: (Boolean) -> Unit
    ) {
        button.isChecked = initialChecked
        updateToolUi(label, initialChecked)

        button.addOnCheckedChangeListener { btn, isChecked ->
            animateButtonPress(btn)
            updateToolUi(label, isChecked)
            onToggled(isChecked)
        }

        itemContainer.setOnClickListener {
            button.toggle()
        }
    }

    private fun updateToolUi(label: TextView, isChecked: Boolean) {
        val color = if (isChecked) {
            ContextCompat.getColor(requireContext(), R.color.accent_pink)
        } else {
            ContextCompat.getColor(requireContext(), R.color.white_70)
        }
        label.setTextColor(color)
    }

    private fun animateButtonPress(view: View) {
        view.animate()
            .scaleX(0.92f)
            .scaleY(0.92f)
            .setDuration(80)
            .withEndAction {
                view.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .setDuration(80)
                    .start()
            }
            .start()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
