package com.dinh.aicamera.ui.gallery

import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.util.Size
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.dinh.aicamera.databinding.ItemGalleryGridBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GalleryGridAdapter(
    private val onPhotoClicked: (Int) -> Unit
) : ListAdapter<Uri, GalleryGridAdapter.ViewHolder>(UriDiffCallback) {

    object UriDiffCallback : DiffUtil.ItemCallback<Uri>() {
        override fun areItemsTheSame(oldItem: Uri, newItem: Uri): Boolean = oldItem == newItem
        override fun areContentsTheSame(oldItem: Uri, newItem: Uri): Boolean = oldItem == newItem
    }

    inner class ViewHolder(val binding: ItemGalleryGridBinding) :
        RecyclerView.ViewHolder(binding.root) {
        var loadJob: Job? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemGalleryGridBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val uri = getItem(position)
        holder.loadJob?.cancel()
        val context = holder.itemView.context

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Dùng ContentResolver.loadThumbnail() size nhỏ (256x256), không decode full-res
            holder.binding.ivThumbnail.setImageDrawable(null)
            holder.loadJob = CoroutineScope(Dispatchers.IO).launch {
                val bitmap: Bitmap? = try {
                    context.contentResolver.loadThumbnail(uri, Size(256, 256), null)
                } catch (_: Exception) {
                    null
                }
                withContext(Dispatchers.Main) {
                    if (holder.bindingAdapterPosition == position) {
                        if (bitmap != null) {
                            holder.binding.ivThumbnail.setImageBitmap(bitmap)
                        } else {
                            holder.binding.ivThumbnail.load(uri) {
                                size(256, 256)
                                crossfade(true)
                            }
                        }
                    }
                }
            }
        } else {
            holder.binding.ivThumbnail.load(uri) {
                size(256, 256)
                crossfade(true)
            }
        }

        holder.itemView.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                onPhotoClicked(pos)
            }
        }
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        holder.loadJob?.cancel()
        holder.binding.ivThumbnail.setImageDrawable(null)
    }
}
