package dev.yakovsava.antiwhitelist.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.yakovsava.antiwhitelist.data.ProfileRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        CoroutineScope(Dispatchers.IO).launch {
            val profiles = ProfileRepository(ctx).profilesFlow.first()
            val boot = profiles.firstOrNull { it.startOnBoot && it.callLink.isNotEmpty() } ?: return@launch
            ctx.startForegroundService(Intent(ctx, GoodTurnVpnService::class.java).apply {
                action = GoodTurnVpnService.ACTION_START
                putExtra(GoodTurnVpnService.EXTRA_PROFILE, boot.id)
            })
        }
    }
}
