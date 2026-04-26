package dev.yakovsava.antiwhitelist.turn

import android.util.Log
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

data class TurnAllocation(
    val relayIp: InetAddress, val relayPort: Int,
    val username: String, val realm: String,
    val nonce: ByteArray, val password: String,
    val channel: Int = 0x4000,
) { val relayAddress get() = "${relayIp.hostAddress}:$relayPort" }

class TurnConn private constructor(
    private val addr: InetSocketAddress,
    private val tcp: Boolean,
) : AutoCloseable {
    companion object {
        private const val TAG = "TurnConn"
        const val CHANNEL = 0x4000
        fun tcp(h: String, p: Int) = TurnConn(InetSocketAddress(h, p), true)
        fun udp(h: String, p: Int) = TurnConn(InetSocketAddress(h, p), false)
    }

    private var tcpSock: Socket? = null
    private var udpSock: DatagramSocket? = null
    private val queue = LinkedBlockingQueue<ByteArray>(512)
    @Volatile private var closed = false
    var allocation: TurnAllocation? = null

    fun allocate(user: String, pass: String, peerIp: InetAddress, peerPort: Int): TurnAllocation {
        connect()
        // Probe — get realm/nonce
        send(StunMsg.build(StunMsg.ALLOCATE_REQUEST) {
            addRequestedTransport(0x11); addRequestedAddressFamily(0x01); addLifetime(600)
        }.encode())
        val e401 = read()
        require(e401.type == StunMsg.ALLOCATE_ERROR) { "Expected 401, got 0x${e401.type.toString(16)}" }
        val realm = e401.realm() ?: error("No REALM")
        val nonce = e401.nonce() ?: error("No NONCE")

        // Authenticated Allocate
        send(StunMsg.build(StunMsg.ALLOCATE_REQUEST) {
            addRequestedTransport(0x11); addRequestedAddressFamily(0x01); addLifetime(600)
            addUsername(user); addRealm(realm); addNonce(nonce)
            addMessageIntegrity(user, realm, pass); addFingerprint()
        }.encode())
        val ar = read()
        if (ar.type == StunMsg.ALLOCATE_ERROR) { val (c,r)=ar.errorCode()!!; error("Allocate: $c $r") }
        val (relayIp, relayPort) = ar.xorRelayedAddress() ?: error("No relay address")
        Log.i(TAG, "Relay: ${relayIp.hostAddress}:$relayPort")

        // CreatePermission
        send(StunMsg.build(StunMsg.CREATE_PERM_REQUEST) {
            addXorPeerAddress(peerIp, peerPort); addUsername(user); addRealm(realm); addNonce(nonce)
            addMessageIntegrity(user, realm, pass); addFingerprint()
        }.encode())
        val pr = read()
        if (pr.type != StunMsg.CREATE_PERM_SUCCESS) { val (c,r)=pr.errorCode() ?: (0 to "?"); error("Perm: $c $r") }

        // ChannelBind
        send(StunMsg.build(StunMsg.CHANNEL_BIND_REQUEST) {
            addChannelNumber(CHANNEL); addXorPeerAddress(peerIp, peerPort)
            addUsername(user); addRealm(realm); addNonce(nonce)
            addMessageIntegrity(user, realm, pass); addFingerprint()
        }.encode())
        val br = read()
        if (br.type != StunMsg.CHANNEL_BIND_SUCCESS) { val (c,r)=br.errorCode() ?: (0 to "?"); error("Bind: $c $r") }

        return TurnAllocation(relayIp, relayPort, user, realm, nonce, pass, CHANNEL).also { allocation = it }
    }

    fun sendChannelData(data: ByteArray, off: Int = 0, len: Int = data.size) {
        val frame = StunMsg.channelData(CHANNEL, data, off, len)
        if (tcp) tcpSock?.getOutputStream()?.write(frame) ?: throw IOException("not connected")
        else { val u = udpSock ?: throw IOException("not connected"); u.send(DatagramPacket(frame, frame.size, addr)) }
    }

    fun receive(timeoutMs: Long = 5000): ByteArray? = queue.poll(timeoutMs, TimeUnit.MILLISECONDS)

    fun refresh() {
        val a = allocation ?: return
        try {
            send(StunMsg.build(0x0004) {
                addLifetime(600); addUsername(a.username); addRealm(String(a.realm.toByteArray()))
                addNonce(a.nonce); addMessageIntegrity(a.username, String(a.realm.toByteArray()), a.password); addFingerprint()
            }.encode()); read()
        } catch (e: Exception) { Log.w(TAG, "refresh: ${e.message}") }
    }

    override fun close() {
        closed = true
        try { tcpSock?.close() } catch (_: Exception) {}
        try { udpSock?.close() } catch (_: Exception) {}
    }

    private fun connect() {
        if (tcp) {
            val s = Socket(); s.connect(addr, 30_000); s.soTimeout = 30_000; tcpSock = s
            Thread { tcpLoop() }.apply { isDaemon = true; start() }
        } else {
            val s = DatagramSocket(); s.soTimeout = 30_000; udpSock = s
            Thread { udpLoop() }.apply { isDaemon = true; start() }
        }
    }

    private fun send(b: ByteArray) {
        if (tcp) tcpSock?.getOutputStream()?.write(b) ?: throw IOException("not connected")
        else udpSock?.send(DatagramPacket(b, b.size, addr)) ?: throw IOException("not connected")
    }

    private fun read(): StunMsg {
        return if (tcp) {
            val raw = StunMsg.readTcp(tcpSock!!.getInputStream()) ?: throw IOException("closed")
            StunMsg.decode(raw)
        } else {
            val buf = ByteArray(65535); val pkt = DatagramPacket(buf, buf.size)
            udpSock!!.receive(pkt); StunMsg.decode(buf.copyOf(pkt.length))
        }
    }

    private fun tcpLoop() {
        val inp = tcpSock?.getInputStream() ?: return
        try {
            while (!closed) {
                val frame = StunMsg.readTcp(inp) ?: break
                if (frame.size < 4) continue
                val ch = ((frame[0].toInt() and 0xFF) shl 8) or (frame[1].toInt() and 0xFF)
                if (ch in 0x4000..0x7FFF) {
                    val len = ((frame[2].toInt() and 0xFF) shl 8) or (frame[3].toInt() and 0xFF)
                    queue.offer(frame.copyOfRange(4, 4 + len), 1, TimeUnit.SECONDS)
                }
            }
        } catch (e: Exception) { if (!closed) Log.e(TAG, "tcp: ${e.message}") }
    }

    private fun udpLoop() {
        val u = udpSock ?: return; val buf = ByteArray(65535)
        try {
            while (!closed) {
                val pkt = DatagramPacket(buf, buf.size)
                try { u.receive(pkt) } catch (_: SocketTimeoutException) { continue }
                val ch = ((buf[0].toInt() and 0xFF) shl 8) or (buf[1].toInt() and 0xFF)
                if (ch in 0x4000..0x7FFF) {
                    val dl = ((buf[2].toInt() and 0xFF) shl 8) or (buf[3].toInt() and 0xFF)
                    queue.offer(buf.copyOfRange(4, 4 + dl.coerceAtMost(pkt.length-4)), 1, TimeUnit.SECONDS)
                }
            }
        } catch (e: Exception) { if (!closed) Log.e(TAG, "udp: ${e.message}") }
    }
}


