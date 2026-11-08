package com.rw3.directsync.watchface

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.rw3.directsync.R
import com.rw3.directsync.ble.protocol.XiaomiWearProtocolEngine
import com.rw3.directsync.databinding.ItemWatchfaceEntryBinding

class WatchfaceAdapter(
    private val onApplyClick: (XiaomiWearProtocolEngine.WatchfaceItem) -> Unit,
    private val onDeleteClick: (XiaomiWearProtocolEngine.WatchfaceItem) -> Unit
) : ListAdapter<XiaomiWearProtocolEngine.WatchfaceItem, WatchfaceAdapter.ViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemWatchfaceEntryBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemWatchfaceEntryBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: XiaomiWearProtocolEngine.WatchfaceItem) {
            val context = binding.root.context
            binding.tvWfName.text = item.name.ifBlank { "Watchface (${item.id.take(8)})" }
            binding.tvWfId.text = "ID: ${item.id}"

            if (item.isActive) {
                binding.tvWfActiveBadge.visibility = View.VISIBLE
                binding.btnApplyWatchface.isEnabled = false
                binding.btnApplyWatchface.text = "Aktif"
                binding.btnApplyWatchface.setTextColor(ContextCompat.getColor(context, R.color.on_surface_muted))
                binding.btnApplyWatchface.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#15222E"))
            } else {
                binding.tvWfActiveBadge.visibility = View.GONE
                binding.btnApplyWatchface.isEnabled = true
                binding.btnApplyWatchface.text = "Pakai"
                binding.btnApplyWatchface.setTextColor(ContextCompat.getColor(context, R.color.primary_mint))
                binding.btnApplyWatchface.backgroundTintList = ContextCompat.getColorStateList(context, R.color.btn_dark_bg)
            }

            if (item.canDelete && !item.isActive) {
                binding.btnDeleteWatchface.visibility = View.VISIBLE
                binding.btnDeleteWatchface.setOnClickListener {
                    onDeleteClick(item)
                }
            } else {
                binding.btnDeleteWatchface.visibility = View.GONE
            }

            binding.btnApplyWatchface.setOnClickListener {
                if (!item.isActive) {
                    onApplyClick(item)
                }
            }
        }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<XiaomiWearProtocolEngine.WatchfaceItem>() {
        override fun areItemsTheSame(
            oldItem: XiaomiWearProtocolEngine.WatchfaceItem,
            newItem: XiaomiWearProtocolEngine.WatchfaceItem
        ): Boolean = oldItem.id == newItem.id

        override fun areContentsTheSame(
            oldItem: XiaomiWearProtocolEngine.WatchfaceItem,
            newItem: XiaomiWearProtocolEngine.WatchfaceItem
        ): Boolean = oldItem == newItem
    }
}

