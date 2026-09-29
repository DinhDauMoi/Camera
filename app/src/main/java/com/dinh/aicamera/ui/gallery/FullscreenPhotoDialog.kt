package com.dinh.aicamera.ui.gallery

import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.DialogFragment
import androidx.viewpager2.widget.ViewPager2
import com.dinh.aicamera.R
import com.dinh.aicamera.databinding.DialogFullscreenPhotoBinding
import com.dinh.aicamera.ui.GalleryAdapter

class FullscreenPhotoDialog : DialogFragment() {

    private var _binding: DialogFullscreenPhotoBinding? = null
    private val binding get() = _binding!!

    private val photoUris = mutableListOf<Uri>()
    private var initialPosition = 0
    private var onPhotoDeletedCallback: ((Int) -> Unit)? = null

    private lateinit var deleteIntentSenderLauncher: ActivityResultLauncher<IntentSenderRequest>
    private var pendingDeleteIndex: Int = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, android.R.style.Theme_Black_NoTitleBar_Fullscreen)

        deleteIntentSenderLauncher = registerForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                if (pendingDeleteIndex >= 0) {
                    executeDeleteSuccess(pendingDeleteIndex)
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        dialog?.window?.apply {
            requestFeature(Window.FEATURE_NO_TITLE)
            setBackgroundDrawable(ColorDrawable(Color.BLACK))
        }
        _binding = DialogFullscreenPhotoBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        com.dinh.aicamera.ui.LiquidGlassHelper.setupGlass(binding.topBar)
        com.dinh.aicamera.ui.LiquidGlassHelper.setupGlass(binding.btnClose)
        com.dinh.aicamera.ui.LiquidGlassHelper.setupGlass(binding.btnShare)
        com.dinh.aicamera.ui.LiquidGlassHelper.setupGlass(binding.btnDelete)

        val adapter = GalleryAdapter(photoUris)
        binding.photoViewPager.adapter = adapter
        binding.photoViewPager.setCurrentItem(initialPosition, false)
        updateCounter(initialPosition)

        binding.photoViewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateCounter(position)
            }
        })

        binding.btnClose.setOnClickListener {
            dismiss()
        }

        binding.btnShare.setOnClickListener {
            val currentPos = binding.photoViewPager.currentItem
            if (currentPos in 0 until photoUris.size) {
                sharePhoto(photoUris[currentPos])
            }
        }

        binding.btnDelete.setOnClickListener {
            val currentPos = binding.photoViewPager.currentItem
            if (currentPos in 0 until photoUris.size) {
                requestDeletePhoto(currentPos)
            }
        }
    }

    private fun updateCounter(pos: Int) {
        if (photoUris.isEmpty()) {
            dismiss()
            return
        }
        binding.tvCounter.text = "${pos + 1} / ${photoUris.size}"
    }

    private fun sharePhoto(uri: Uri) {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, getString(R.string.action_share)))
    }

    private fun requestDeletePhoto(index: Int) {
        val uri = photoUris[index]
        pendingDeleteIndex = index

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Android 11+: Dùng createDeleteRequest theo chuẩn MediaStore
                val pendingIntent = MediaStore.createDeleteRequest(
                    requireContext().contentResolver,
                    listOf(uri)
                )
                val request = IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                deleteIntentSenderLauncher.launch(request)
            } else {
                // Android 10 hoặc cũ hơn
                val deleted = requireContext().contentResolver.delete(uri, null, null)
                if (deleted > 0) {
                    executeDeleteSuccess(index)
                }
            }
        } catch (secEx: SecurityException) {
            // Xử lý RecoverableSecurityException trên Android 10 (API 29)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && secEx is RecoverableSecurityException) {
                val intentSender = secEx.userAction.actionIntent.intentSender
                val request = IntentSenderRequest.Builder(intentSender).build()
                deleteIntentSenderLauncher.launch(request)
            } else {
                Toast.makeText(context, "Không có quyền xóa ảnh: ${secEx.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(context, "Lỗi xóa ảnh: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun executeDeleteSuccess(index: Int) {
        if (index in 0 until photoUris.size) {
            photoUris.removeAt(index)
            binding.photoViewPager.adapter?.notifyItemRemoved(index)
            onPhotoDeletedCallback?.invoke(index)

            Toast.makeText(context, "Đã xóa ảnh", Toast.LENGTH_SHORT).show()

            if (photoUris.isEmpty()) {
                dismiss()
            } else {
                val nextPos = minOf(index, photoUris.size - 1)
                binding.photoViewPager.setCurrentItem(nextPos, false)
                updateCounter(nextPos)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "FullscreenPhotoDialog"

        fun newInstance(
            uris: List<Uri>,
            startPosition: Int,
            onDeleted: (Int) -> Unit = {}
        ) = FullscreenPhotoDialog().apply {
            photoUris.clear()
            photoUris.addAll(uris)
            initialPosition = startPosition
            onPhotoDeletedCallback = onDeleted
        }
    }
}
