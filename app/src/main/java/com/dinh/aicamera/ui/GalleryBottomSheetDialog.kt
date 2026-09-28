package com.dinh.aicamera.ui

import android.content.ContentUris
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.viewpager2.widget.ViewPager2
import com.dinh.aicamera.R
import com.dinh.aicamera.databinding.DialogGalleryBinding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class GalleryBottomSheetDialog : BottomSheetDialogFragment() {

    private var _binding: DialogGalleryBinding? = null
    private val binding get() = _binding!!

    override fun getTheme(): Int = R.style.Theme_AICamera_BottomSheetDialog

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogGalleryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Mở rộng toàn màn hình cho trải nghiệm xem ảnh tối ưu
        val behavior = BottomSheetBehavior.from(view.parent as View)
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
        behavior.isFitToContents = false
        behavior.expandedOffset = 40

        binding.btnCloseGallery.setOnClickListener {
            dismiss()
        }

        loadSavedPhotos()
    }

    private fun loadSavedPhotos() {
        val photoList = mutableListOf<Uri>()

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_ADDED
        )
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        try {
            val cursor = requireContext().contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                sortOrder
            )

            cursor?.use {
                val idColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                var count = 0
                while (it.moveToNext() && count < 30) {
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

        if (photoList.isEmpty()) {
            binding.tvEmptyGallery.visibility = View.VISIBLE
            binding.photoViewPager.visibility = View.GONE
            binding.tvPhotoCounter.visibility = View.GONE
        } else {
            binding.tvEmptyGallery.visibility = View.GONE
            binding.photoViewPager.visibility = View.VISIBLE
            binding.tvPhotoCounter.visibility = View.VISIBLE

            val adapter = GalleryAdapter(photoList)
            binding.photoViewPager.adapter = adapter

            binding.tvPhotoCounter.text = "1 / ${photoList.size}"
            binding.photoViewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    binding.tvPhotoCounter.text = "${position + 1} / ${photoList.size}"
                }
            })
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "GalleryBottomSheetDialog"
        fun newInstance() = GalleryBottomSheetDialog()
    }
}
