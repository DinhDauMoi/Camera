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
import com.dinh.aicamera.ui.update.UpdateResult
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
        LiquidGlassHelper.setupGlass(binding.root)
        LiquidGlassHelper.setupGlass(binding.rowAutoZoom)
        LiquidGlassHelper.setupGlass(binding.rowAutoUpdate)
        LiquidGlassHelper.setupGlass(binding.btnCheckUpdateNow)
        LiquidGlassHelper.setupGlass(binding.btnCloseSettings)

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

        binding.tvAppVersionInfo.text = "Lievis Cam v${BuildConfig.VERSION_NAME}"

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
            val result = updateManager.checkUpdate(isManual = true)
            binding.btnCheckUpdateNow.isEnabled = true
            binding.btnCheckUpdateNow.text = getString(R.string.setting_check_update_now)

            when (result) {
                is UpdateResult.UpdateAvailable -> {
                    dismiss()
                    UpdateDialogFragment.newInstance(
                        version = result.info.latestVersion,
                        changelog = result.info.changelog,
                        url = result.info.downloadUrl
                    ).show(parentFragmentManager, UpdateDialogFragment.TAG)
                }
                is UpdateResult.AlreadyLatest -> {
                    Toast.makeText(context, "Bạn đang ở phiên bản mới nhất (${BuildConfig.VERSION_NAME})", Toast.LENGTH_SHORT).show()
                }
                is UpdateResult.NoReleasesFound -> {
                    Toast.makeText(context, "Chưa có bản phát hành nào trên GitHub (404)", Toast.LENGTH_LONG).show()
                }
                is UpdateResult.RateLimited -> {
                    Toast.makeText(context, "GitHub giới hạn lượt truy cập (403). Vui lòng thử lại sau", Toast.LENGTH_LONG).show()
                }
                is UpdateResult.NoApkAttached -> {
                    Toast.makeText(context, "Bản phát hành mới nhất chưa đính kèm tệp APK", Toast.LENGTH_LONG).show()
                }
                is UpdateResult.NetworkError -> {
                    Toast.makeText(context, "Không có kết nối mạng: ${result.message}", Toast.LENGTH_SHORT).show()
                }
                is UpdateResult.UnknownError -> {
                    Toast.makeText(context, "Lỗi kiểm tra cập nhật: ${result.message}", Toast.LENGTH_SHORT).show()
                }
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
