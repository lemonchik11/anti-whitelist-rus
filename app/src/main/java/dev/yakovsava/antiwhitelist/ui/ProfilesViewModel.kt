package dev.yakovsava.antiwhitelist.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wireguard.crypto.KeyPair
import dev.yakovsava.antiwhitelist.data.ProfileRepository
import dev.yakovsava.antiwhitelist.data.VpnProfile
import dev.yakovsava.antiwhitelist.service.GoodTurnVpnService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ProfilesViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx  = app.applicationContext
    val repo = ProfileRepository(ctx)

    val profiles: StateFlow<List<VpnProfile>> =
        repo.profilesFlow.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _vpnState = MutableStateFlow<GoodTurnVpnService.VpnState>(GoodTurnVpnService.state)
    val vpnState: StateFlow<GoodTurnVpnService.VpnState> = _vpnState

    private val _logs = MutableStateFlow(GoodTurnVpnService.logLines)
    val logs: StateFlow<List<String>> = _logs

    init {
        GoodTurnVpnService.onStateChanged = { _vpnState.value = it }
        GoodTurnVpnService.onLog = { line ->
            val c = _logs.value.toMutableList(); c.add(line)
            if (c.size > 400) c.removeAt(0); _logs.value = c
        }
    }

    fun saveProfile(p: VpnProfile)  = viewModelScope.launch { repo.save(p) }
    fun deleteProfile(id: String)   = viewModelScope.launch { repo.delete(id) }

    fun connect(profileId: String) {
        ctx.startForegroundService(Intent(ctx, GoodTurnVpnService::class.java).apply {
            action = GoodTurnVpnService.ACTION_START
            putExtra(GoodTurnVpnService.EXTRA_PROFILE, profileId)
        })
    }

    fun disconnect() {
        ctx.startService(Intent(ctx, GoodTurnVpnService::class.java).apply {
            action = GoodTurnVpnService.ACTION_STOP
        })
    }

    fun clearLogs() { _logs.value = emptyList(); GoodTurnVpnService.logLines = emptyList() }

    fun generateKeyPair(): Pair<String, String> {
        val kp = KeyPair(); return kp.privateKey.toBase64() to kp.publicKey.toBase64()
    }

    val isRunning get() = vpnState.value.let {
        it !is GoodTurnVpnService.VpnState.Idle && it !is GoodTurnVpnService.VpnState.Error
    }
    val activeProfileId get() = GoodTurnVpnService.activeProfileId
}
