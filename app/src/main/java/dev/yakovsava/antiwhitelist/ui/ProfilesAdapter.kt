package dev.yakovsava.antiwhitelist.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import dev.yakovsava.antiwhitelist.R
import dev.yakovsava.antiwhitelist.data.VpnProfile

class ProfilesAdapter(
    private val onConnect:    (VpnProfile) -> Unit,
    private val onDisconnect: (VpnProfile) -> Unit,
    private val onEdit:       (VpnProfile) -> Unit,
    private val onDelete:     (VpnProfile) -> Unit,
) : ListAdapter<ProfilesAdapter.ProfileItem, ProfilesAdapter.ViewHolder>(DIFF) {

    data class ProfileItem(val profile: VpnProfile, val isActive: Boolean)

    inner class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
        val tvName:       TextView    = v.findViewById(R.id.tvProfileName)
        val tvSubtitle:   TextView    = v.findViewById(R.id.tvProfileSubtitle)
        val ivStatus:     ImageView   = v.findViewById(R.id.ivStatus)
        val btnToggle:    ImageButton = v.findViewById(R.id.btnToggle)
        val btnEdit:      ImageButton = v.findViewById(R.id.btnEdit)
        val btnDelete:    ImageButton = v.findViewById(R.id.btnDelete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_profile, parent, false))

    override fun onBindViewHolder(h: ViewHolder, pos: Int) {
        val item = getItem(pos)
        val p    = item.profile
        val ctx  = h.itemView.context

        h.tvName.text     = p.name
        h.tvSubtitle.text = buildSubtitle(p)

        if (item.isActive) {
            h.ivStatus.setImageResource(R.drawable.ic_connected)
            h.ivStatus.setColorFilter(ContextCompat.getColor(ctx, R.color.status_ok))
            h.btnToggle.setImageResource(R.drawable.ic_stop)
            h.btnToggle.setOnClickListener { onDisconnect(p) }
        } else {
            h.ivStatus.setImageResource(R.drawable.ic_disconnected)
            h.ivStatus.setColorFilter(ContextCompat.getColor(ctx, R.color.status_idle))
            h.btnToggle.setImageResource(R.drawable.ic_play)
            h.btnToggle.setOnClickListener { onConnect(p) }
        }

        h.btnEdit.setOnClickListener   { onEdit(p) }
        h.btnDelete.setOnClickListener { onDelete(p) }
    }

    private fun buildSubtitle(p: VpnProfile): String {
        val server = p.effectiveServerHost.ifEmpty { "сервер не задан" }
        val link   = if (p.callLink.isNotEmpty()) p.callLink.take(40) + "…" else "ссылка не задана"
        return "$server  •  ${p.linkType.name}  •  $link"
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<ProfileItem>() {
            override fun areItemsTheSame(a: ProfileItem, b: ProfileItem) = a.profile.id == b.profile.id
            override fun areContentsTheSame(a: ProfileItem, b: ProfileItem) = a == b
        }
    }
}
