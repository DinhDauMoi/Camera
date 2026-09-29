package com.dinh.aicamera.ui.update

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.dinh.aicamera.R
import com.dinh.aicamera.databinding.DialogUpdateBinding
import com.dinh.aicamera.ui.AppPreferences
import kotlinx.coroutines.launch

class UpdateDialogFragment : DialogFragment() {

    private var _binding: DialogUpdateBinding? = null
    private val binding get() = _binding!!

    private var latestVersion: String = ""
    private var changelog: String = ""
    private var downloadUrl: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        latestVersion = arguments?.getString(ARG_VERSION) ?: ""
        changelog = arguments?.getString(ARG_CHANGELOG) ?: ""
        downloadUrl = arguments?.getString(ARG_URL) ?: ""
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        dialog?.window?.apply {
            requestFeature(Window.FEATURE_NO_TITLE)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(0.45f)
        }
        _binding = DialogUpdateBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.9).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        com.dinh.aicamera.ui.LiquidGlassHelper.setupGlass(binding.root)
        com.dinh.aicamera.ui.LiquidGlassHelper.setupGlass(binding.changelogScroll)
        com.dinh.aicamera.ui.LiquidGlassHelper.setupGlass(binding.btnLater)
        com.dinh.aicamera.ui.LiquidGlassHelper.setupGlass(binding.btnUpdateNow)

        binding.tvUpdateVersion.text = "Phiên bản: $latestVersion"
        binding.tvChangelog.text = cleanChangelog(changelog)

        binding.btnLater.setOnClickListener {
            // Lưu version bỏ qua vào Preferences để tránh spam
            AppPreferences(requireContext()).ignoredUpdateVersion =
                latestVersion.removePrefix("v").removePrefix("V")
            dismiss()
        }

        binding.btnUpdateNow.setOnClickListener {
            startDownload()
        }
    }

    private fun cleanChangelog(raw: String): String {
        return raw.lines()
            .filterNot { line ->
                val trimmed = line.trim()
                trimmed.contains("Full Changelog", ignoreCase = true)
            }
            .joinToString("\n")
            .replace("**", "")
            .trim()
            .ifEmpty { "Cập nhật tính năng mới và sửa lỗi" }
    }

    private fun startDownload() {
        val updateManager = AppUpdateManager(requireContext())

        binding.btnLater.isEnabled = false
        binding.btnUpdateNow.isEnabled = false
        binding.downloadProgressBar.visibility = View.VISIBLE
        binding.tvDownloadStatus.visibility = View.VISIBLE
        binding.tvDownloadStatus.text = getString(R.string.update_downloading)

        lifecycleScope.launch {
            val apkFile = updateManager.downloadApk(downloadUrl) { percent ->
                binding.downloadProgressBar.progress = percent
                binding.tvDownloadStatus.text = "Đang tải: $percent%"
            }

            if (apkFile != null && apkFile.exists()) {
                binding.tvDownloadStatus.text = getString(R.string.update_download_success)
                updateManager.openInstallApk(apkFile)
                dismiss()
            } else {
                binding.btnLater.isEnabled = true
                binding.btnUpdateNow.isEnabled = true
                binding.tvDownloadStatus.text = getString(R.string.update_download_failed)
                Toast.makeText(context, R.string.update_download_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "UpdateDialogFragment"
        private const val ARG_VERSION = "arg_version"
        private const val ARG_CHANGELOG = "arg_changelog"
        private const val ARG_URL = "arg_url"

        fun newInstance(version: String, changelog: String, url: String) =
            UpdateDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_VERSION, version)
                    putString(ARG_CHANGELOG, changelog)
                    putString(ARG_URL, url)
                }
            }
    }
}
