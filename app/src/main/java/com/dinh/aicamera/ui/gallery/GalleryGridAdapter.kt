package com.dinh.aicamera.ui.gallery

import android.net.Uri
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.dinh.aicamera.databinding.ItemGalleryGridBinding

class GalleryGridAdapter(
    private val photoUris: MutableList<Uri>,
    private val onPhotoClicked: (Int) -> Unit
) : RecyclerView.Adapter<GalleryGridAdapter.ViewHolder>() {

    inner class ViewHolder(val binding: ItemGalleryGridBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemGalleryGridBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val uri = photoUris[position]
        holder.binding.ivThumbnail.load(uri) {
            crossfade(true)
        }
        holder.itemView.setOnClickListener {
            onPhotoClicked(holder.bindingAdapterPosition)
        }
    }

    override fun getItemCount(): Int = photoUris.size

    fun removeAt(position: Int) {
        if (position in 0 until photoUris.size) {
            photoUris.removeAt(position)
            notifyItemRemoved(position)
            notifyItemRangeChanged(position, photoUris.size - position)
        }
    }

    fun updateList(newList: List<Uri>) {
        photoUris.clear()
        photoUris.addAll(newList)
        notifyDataSetChanged()
    }
}
