package com.rw3.directsync.watchface

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
            binding.tvWfName.text = item.name.ifBlank { "Watchface (${item.id.take(8)})" }
            binding.tvWfId.text = if (item.canDelete) "ID: ${item.id} · Kustom" else "ID: ${item.id}"

            if (item.isActive) {
                binding.root.setBackgroundResource(R.drawable.bg_wf_item_active)
                binding.tvWfActiveBadge.visibility = View.VISIBLE
                binding.btnApplyWatchface.visibility = View.GONE
                binding.btnDeleteWatchface.visibility = View.GONE
            } else {
                binding.root.setBackgroundResource(R.drawable.bg_wf_item_inactive)
                binding.tvWfActiveBadge.visibility = View.GONE
                binding.btnApplyWatchface.visibility = View.VISIBLE
                binding.btnApplyWatchface.isEnabled = true
                binding.btnApplyWatchface.text = "Pakai"
                binding.btnDeleteWatchface.visibility = if (item.canDelete) View.VISIBLE else View.GONE
            }

            binding.btnDeleteWatchface.setOnClickListener {
                onDeleteClick(item)
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

