package dev.yakovsava.antiwhitelist.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.util.Log
import androidx.core.app.NotificationCompat
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import com.wireguard.config.InetEndpoint
import com.wireguard.config.InetNetwork
import com.wireguard.config.Interface
import com.wireguard.config.Peer
import com.wireguard.crypto.Key
import com.wireguard.crypto.KeyPair
import dev.yakovsava.antiwhitelist.AntiWhitelistApp
import dev.yakovsava.antiwhitelist.R
import dev.yakovsava.antiwhitelist.data.ProfileRepository
import dev.yakovsava.antiwhitelist.data.VpnProfile
import dev.yakovsava.antiwhitelist.turn.GoodTurnTunnel
import dev.yakovsava.antiwhitelist.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetAddress

class GoodTurnVpnService : VpnService() {

    companion object {
        private const val TAG = "VpnService"
        const val ACTION_START  = "dev.yakovsava.antiwhitelist.START"
        const val ACTION_STOP   = "dev.yakovsava.antiwhitelist.STOP"
        const val EXTRA_PROFILE = "profile_id"
        const val NOTIF_ID      = 1001

        @Volatile var state: VpnState = VpnState.Idle
        @Volatile var logLines: List<String> = emptyList()
        @Volatile var activeProfileId: String? = null
        var onStateChanged: ((VpnState) -> Unit)? = null
        var onLog: ((String) -> Unit)? = null

        fun appendLog(line: String) {
            val c = logLines.toMutableList(); c.add(line)
            if (c.size > 500) c.removeAt(0)
            logLines = c; onLog?.invoke(line)
        }
    }

    sealed class VpnState {
        object Idle     : VpnState()
        object Starting : VpnState()
        data class Ready(val relay: String, val profileName: String) : VpnState()
        data class Error(val msg: String) : VpnState()
        object Stopping : VpnState()
    }

    // Manual coroutine scope (replaces LifecycleService)
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    private var tunnel:    GoodTurnTunnel? = null
    private var wgBackend: GoBackend?       = null
    private var wgTunnel:  AwTunnel?        = null
    private val repo by lazy { ProfileRepository(applicationContext) }

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotif("Инициализация..."))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> intent.getStringExtra(EXTRA_PROFILE)?.let { id ->
                serviceScope.launch { doStart(id) }
            }
            ACTION_STOP  -> serviceScope.launch { doStop() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        serviceScope.launch { doStop() }
        serviceJob.cancel()
        super.onDestroy()
    }

    override fun onRevoke() {
        serviceScope.launch { doStop() }
        super.onRevoke()
    }

    private suspend fun doStart(profileId: String) {
        // Guard: если уже запускается или подключено — игнорируем повторное нажатие
        if (state is VpnState.Starting || state is VpnState.Ready) {
            appendLog("Уже запущен, игнорируем повторный запуск")
            return
        }

        setState(VpnState.Starting)
        val profile = repo.profilesFlow.first().firstOrNull { it.id == profileId }
            ?: run { setState(VpnState.Error("Профиль не найден")); return }

        if (profile.callLink.isEmpty())            { setState(VpnState.Error("Не задана ссылка")); return }
        if (profile.effectiveServerHost.isEmpty()) { setState(VpnState.Error("Нет IP сервера")); return }

        activeProfileId = profileId
        appendLog("Профиль: ${profile.name}")
        appendLog("Сервер: ${profile.effectiveServerHost}:${profile.goodTurnServerPort}")

        val t = GoodTurnTunnel(profile)
        tunnel = t
        t.onLog   = { appendLog(it) }
        t.onError = { msg -> setState(VpnState.Error(msg)) }
        t.onReady = { relay, turnIp ->
            appendLog("✓ Туннель: $relay  (TURN: $turnIp)")
            serviceScope.launch {
                if (profile.wgEnabled) startWg(profile, turnIp)
                setState(VpnState.Ready(relay, profile.name))
            }
        }
        try {
            withContext(Dispatchers.IO) { t.start() }
        } catch (e: Exception) {
            appendLog("Ошибка запуска: ${e.message}")
            setState(VpnState.Error(e.message ?: "Ошибка запуска"))
            tunnel = null
        }
    }

    private suspend fun doStop() {
        setState(VpnState.Stopping)
        appendLog("Остановка...")
        stopWg(); tunnel?.stop(); tunnel = null
        activeProfileId = null
        setState(VpnState.Idle)
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }

    private suspend fun startWg(p: VpnProfile, turnIp: String) {
        if (p.wgPrivateKey.isEmpty() || p.wgServerPubKey.isEmpty()) {
            appendLog("⚠ WireGuard ключи не заданы — VPN не поднят"); return
        }
        try {
            val be  = GoBackend(this)
            val tun = AwTunnel("antiwhitelist")
            wgBackend = be; wgTunnel = tun
            withContext(Dispatchers.IO) { be.setState(tun, Tunnel.State.UP, buildWgConfig(p, turnIp)) }
            appendLog("✓ WireGuard поднят")
        } catch (e: Exception) { appendLog("⚠ WG: ${e.message}"); Log.e(TAG, "wg", e) }
    }

    private suspend fun stopWg() {
        try { wgTunnel?.let { wgBackend?.setState(it, Tunnel.State.DOWN, null) } }
        catch (e: Exception) { Log.e(TAG, "stopWg", e) }
        finally { wgBackend = null; wgTunnel = null }
    }

    private fun buildWgConfig(p: VpnProfile, turnIp: String): Config {
        val iface = Interface.Builder().apply {
            setKeyPair(KeyPair(Key.fromBase64(p.wgPrivateKey)))
            addAddress(InetNetwork.parse(p.wgAddress))
            p.wgDns.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                .forEach { addDnsServer(InetAddress.getByName(it)) }
            setMtu(p.wgMtu)
        }.build()

        val allowedIps = if (turnIp.isNotEmpty() && p.wgAllowedIps.contains("0.0.0.0/0")) {
            try {
                CidrExclusion.exclude("0.0.0.0/0", turnIp) +
                    p.wgAllowedIps.split(",").map { it.trim() }.filter { it != "0.0.0.0/0" }
            } catch (_: Exception) { p.wgAllowedIps.split(",").map { it.trim() } }
        } else p.wgAllowedIps.split(",").map { it.trim() }

        val peer = Peer.Builder().apply {
            setPublicKey(Key.fromBase64(p.wgServerPubKey))
            if (p.wgPresharedKey.isNotEmpty()) setPreSharedKey(Key.fromBase64(p.wgPresharedKey))
            setEndpoint(InetEndpoint.parse("127.0.0.1:9000"))
            allowedIps.filter { it.isNotEmpty() }.forEach { addAllowedIp(InetNetwork.parse(it)) }
            setPersistentKeepalive(p.wgKeepalive)
        }.build()

        return Config.Builder().setInterface(iface).addPeer(peer).build()
    }

    private fun setState(s: VpnState) {
        state = s; onStateChanged?.invoke(s)
        updateNotif(when (s) {
            is VpnState.Idle     -> "Отключено"
            is VpnState.Starting -> "Подключение..."
            is VpnState.Ready    -> "✓ ${s.profileName}"
            is VpnState.Error    -> "Ошибка: ${s.msg}"
            is VpnState.Stopping -> "Отключение..."
        })
    }

    private fun updateNotif(text: String) =
        getSystemService(android.app.NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotif(text))

    private fun buildNotif(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, GoodTurnVpnService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, AntiWhitelistApp.CHANNEL_VPN)
            .setSmallIcon(R.drawable.ic_vpn_key)
            .setContentTitle("AntiWhitelist VPN")
            .setContentText(text)
            .setContentIntent(open)
            .addAction(0, "Стоп", stop)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private class AwTunnel(private val n: String) : Tunnel {
        override fun getName() = n
        override fun onStateChange(s: Tunnel.State) { Log.i("WgTunnel", "state=$s") }
    }
}
