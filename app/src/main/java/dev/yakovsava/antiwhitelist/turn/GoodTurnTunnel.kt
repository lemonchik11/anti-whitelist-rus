package dev.yakovsava.antiwhitelist.turn

import android.util.Log
import dev.yakovsava.antiwhitelist.data.LinkType
import dev.yakovsava.antiwhitelist.data.VpnProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class GoodTurnTunnel(
    private val profile: VpnProfile,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    companion object { private const val TAG = "GoodTurnTunnel"; private const val REFRESH_MS = 9L * 60_000 }

    var onReady: ((relayAddr: String, turnIp: String) -> Unit)? = null
    var onError: ((msg: String) -> Unit)? = null
    var onLog:   ((line: String) -> Unit)? = null

    @Volatile private var running = false
    private val jobs = mutableListOf<Job>()
    private val conns = mutableListOf<Conn>()
    private lateinit var sock: DatagramSocket
    private val wgPeer = AtomicReference<InetSocketAddress>()

    fun start() {
        if (running) { log("Уже запущен — игнорируем"); return }
        running = true
        // SO_REUSEADDR — порт переиспользуется если предыдущий сокет ещё не освобождён ОС
        sock = DatagramSocket(null).also {
            it.reuseAddress = true
            it.bind(InetSocketAddress("127.0.0.1", 9000))
        }
        log("Слушаем 127.0.0.1:9000")
        val n = if (profile.connCount > 0) profile.connCount
                else if (profile.linkType == LinkType.VK) 16 else 1
        jobs += scope.launch { wgLoop() }
        jobs += scope.launch { connLoop(0, true) }
        for (i in 1 until n) jobs += scope.launch { delay(200L * i); connLoop(i, false) }
    }

    fun stop() {
        running = false
        jobs.forEach { it.cancel() }; jobs.clear()
        synchronized(conns) { conns.forEach { it.close() }; conns.clear() }
        if (::sock.isInitialized) try { sock.close() } catch (_: Exception) {}
        log("Остановлено")
    }

    fun writeToWg(data: ByteArray) {
        val a = wgPeer.get() ?: return
        try { sock.send(DatagramPacket(data, data.size, a)) }
        catch (e: Exception) { log("writeToWg: ${e.message}") }
    }

    private suspend fun connLoop(idx: Int, notifyReady: Boolean) {
        while (running && scope.isActive) {
            val c = Conn(idx)
            try {
                log("[$idx] Получение TURN credentials...")
                val creds = if (profile.linkType == LinkType.VK)
                    getVkCreds(profile.callLink.substringAfterLast("join/").substringBefore("?"))
                else
                    getYandexCreds(profile.callLink.substringAfterLast("j/").substringBefore("?"))

                val host = profile.turnOverride.ifEmpty { creds.host }
                log("[$idx] TURN: $host:${creds.port}")
                val peerIp   = InetAddress.getByName(profile.effectiveServerHost)
                val peerPort = profile.goodTurnServerPort
                val turn = if (profile.useUdp) TurnConn.udp(host, creds.port) else TurnConn.tcp(host, creds.port)
                c.turn = turn
                turn.allocate(creds.username, creds.password, peerIp, peerPort)
                val relay = turn.allocation?.relayAddress ?: "?"
                log("[$idx] Relay: $relay")

                if (profile.noDtls) {
                    if (notifyReady) onReady?.invoke(relay, host)
                    synchronized(conns) { conns.add(c) }
                    c.directLoop(turn)
                } else {
                    val dtls = DtlsEngine(); c.dtls = dtls
                    val rj = scope.launch { c.turnToWg(turn, dtls) }
                    val sj = scope.launch { c.dtlsToTurn(turn, dtls) }
                    c.rj = rj; c.sj = sj
                    dtls.handshake()
                    log("[$idx] DTLS OK!")
                    if (notifyReady) onReady?.invoke(relay, host)
                    synchronized(conns) { conns.add(c) }
                    scope.launch { refreshLoop(turn) }
                    rj.join()
                }
            } catch (e: Exception) {
                log("[$idx] ${e.message}")
                if (notifyReady && idx == 0) onError?.invoke(e.message ?: "error")
            } finally {
                synchronized(conns) { conns.remove(c) }; c.close()
            }
            if (!running) break
            log("[$idx] Переподключение..."); delay(3000)
        }
    }

    private fun wgLoop() {
        val buf = ByteArray(1600)
        try {
            while (running) {
                val pkt = DatagramPacket(buf, buf.size)
                try { sock.receive(pkt) } catch (e: Exception) { if (!running) return; continue }
                wgPeer.set(InetSocketAddress(pkt.address, pkt.port))
                val data = pkt.data.copyOf(pkt.length)
                synchronized(conns) { conns.forEach { it.send(data) } }
            }
        } catch (e: Exception) { if (running) log("wgLoop: ${e.message}") }
    }

    private suspend fun refreshLoop(t: TurnConn) {
        while (running && scope.isActive) { delay(REFRESH_MS); t.refresh() }
    }

    private fun log(s: String) { Log.d(TAG, s); onLog?.invoke(s) }

    private inner class Conn(val idx: Int) {
        var dtls: DtlsEngine? = null; var turn: TurnConn? = null
        var rj: Job? = null; var sj: Job? = null

        fun send(data: ByteArray) = try {
            dtls?.encrypt(data) ?: turn?.sendChannelData(data)
        } catch (e: Exception) { log("[$idx] send: ${e.message}") }

        fun turnToWg(t: TurnConn, d: DtlsEngine) {
            try {
                while (running) {
                    val enc = t.receive(500) ?: continue
                    d.feed(enc)
                    val pl = d.decrypt(100) ?: continue
                    writeToWg(pl)
                }
            } catch (e: Exception) { log("[$idx] t2w: ${e.message}") }
        }

        fun dtlsToTurn(t: TurnConn, d: DtlsEngine) {
            try {
                while (running) {
                    val enc = d.encryptedOut.poll(500, TimeUnit.MILLISECONDS) ?: continue
                    t.sendChannelData(enc)
                }
            } catch (e: Exception) { log("[$idx] d2t: ${e.message}") }
        }

        fun directLoop(t: TurnConn) {
            scope.launch {
                try { while (running) { val d = t.receive(500) ?: continue; writeToWg(d) } }
                catch (e: Exception) { log("[$idx] direct: ${e.message}") }
            }
        }

        fun close() {
            rj?.cancel(); sj?.cancel()
            try { dtls?.close() } catch (_: Exception) {}
            try { turn?.close() } catch (_: Exception) {}
        }
    }
}
