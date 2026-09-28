package com.dinh.aicamera.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.dinh.aicamera.BuildConfig
import com.dinh.aicamera.R
import com.dinh.aicamera.databinding.DialogSettingsBinding
import com.dinh.aicamera.ui.update.AppUpdateManager
import com.dinh.aicamera.ui.update.UpdateDialogFragment
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.launch

class SettingsBottomSheetDialog : BottomSheetDialogFragment() {

    private var _binding: DialogSettingsBinding? = null
    private val binding get() = _binding!!

    private lateinit var preferences: AppPreferences
    private lateinit var updateManager: AppUpdateManager

    var onAutoZoomToggled: ((Boolean) -> Unit)? = null

    override fun getTheme(): Int = R.style.Theme_AICamera_BottomSheetDialog

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        preferences = AppPreferences(requireContext())
        updateManager = AppUpdateManager(requireContext())

        // Toggle Tự động zoom vừa khung (mặc định bật)
        binding.switchAutoZoom.isChecked = preferences.isAutoZoomEnabled
        binding.switchAutoZoom.setOnCheckedChangeListener { _, isChecked ->
            preferences.isAutoZoomEnabled = isChecked
            onAutoZoomToggled?.invoke(isChecked)
        }

        // Toggle Tự động kiểm tra bản cập nhật
        binding.switchAutoUpdate.isChecked = preferences.isAutoCheckUpdate
        binding.switchAutoUpdate.setOnCheckedChangeListener { _, isChecked ->
            preferences.isAutoCheckUpdate = isChecked
        }

        binding.tvAppVersionInfo.text = "AI Camera v${BuildConfig.VERSION_NAME} (Doka Cam AI)"

        binding.btnCloseSettings.setOnClickListener {
            dismiss()
        }

        binding.btnCheckUpdateNow.setOnClickListener {
            checkUpdateManual()
        }
    }

    private fun checkUpdateManual() {
        binding.btnCheckUpdateNow.isEnabled = false
        binding.btnCheckUpdateNow.text = "Đang kiểm tra..."

        lifecycleScope.launch {
            val updateInfo = updateManager.checkUpdate(isManual = true)
            binding.btnCheckUpdateNow.isEnabled = true
            binding.btnCheckUpdateNow.text = getString(R.string.setting_check_update_now)

            if (updateInfo != null && updateInfo.isNewer) {
                dismiss()
                UpdateDialogFragment.newInstance(
                    version = updateInfo.latestVersion,
                    changelog = updateInfo.changelog,
                    url = updateInfo.downloadUrl
                ).show(parentFragmentManager, UpdateDialogFragment.TAG)
            } else {
                Toast.makeText(context, R.string.no_update_available, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "SettingsBottomSheetDialog"
        fun newInstance() = SettingsBottomSheetDialog()
    }
}
