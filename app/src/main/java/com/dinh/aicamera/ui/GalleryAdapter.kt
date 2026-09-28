package com.dinh.aicamera.ui

import android.net.Uri
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.dinh.aicamera.databinding.ItemGalleryPhotoBinding

class GalleryAdapter(
    private val photoUris: List<Uri>
) : RecyclerView.Adapter<GalleryAdapter.PhotoViewHolder>() {

    inner class PhotoViewHolder(val binding: ItemGalleryPhotoBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoViewHolder {
        val binding = ItemGalleryPhotoBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return PhotoViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PhotoViewHolder, position: Int) {
        val uri = photoUris[position]
        holder.binding.ivPhoto.load(uri) {
            crossfade(true)
        }
    }

    override fun getItemCount(): Int = photoUris.size
}
