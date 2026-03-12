package com.localcall.app.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.localcall.app.R
import com.localcall.app.databinding.ItemPeerBinding
import com.localcall.app.network.PeerInfo

class PeerAdapter(
    private val peers: List<PeerInfo>,
    private val onPeerClick: (PeerInfo) -> Unit
) : RecyclerView.Adapter<PeerAdapter.PeerViewHolder>() {

    private var selectedKey: String? = null

    inner class PeerViewHolder(private val binding: ItemPeerBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(peer: PeerInfo) {
            binding.tvPeerName.text = peer.name
            binding.tvPeerIp.text = peer.ip

            val selected = peerKey(peer) == selectedKey
            val bg = if (selected) R.color.selected_peer else R.color.unselected_peer
            binding.root.setBackgroundColor(ContextCompat.getColor(binding.root.context, bg))

            binding.root.setOnClickListener { onPeerClick(peer) }
        }
    }

    fun setSelected(peer: PeerInfo) {
        selectedKey = peerKey(peer)
        notifyDataSetChanged()
    }

    private fun peerKey(peer: PeerInfo): String {
        return if (peer.viaServer) "srv:${peer.id}" else "ip:${peer.ip}"
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PeerViewHolder {
        val binding = ItemPeerBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return PeerViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PeerViewHolder, position: Int) = holder.bind(peers[position])

    override fun getItemCount() = peers.size
}
