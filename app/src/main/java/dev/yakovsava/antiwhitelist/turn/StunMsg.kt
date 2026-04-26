package dev.yakovsava.antiwhitelist.turn

import java.net.InetAddress
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class StunMsg private constructor(val type: Int, val txId: ByteArray) {
    private val attrs = mutableListOf<Pair<Int, ByteArray>>()

    fun addUsername(u: String) = apply { add(ATTR_USERNAME, u.toByteArray(Charsets.UTF_8)) }
    fun addRealm(r: String)    = apply { add(ATTR_REALM,    r.toByteArray(Charsets.UTF_8)) }
    fun addNonce(n: ByteArray) = apply { add(ATTR_NONCE,    n) }
    fun addLifetime(s: Int)    = apply { add(ATTR_LIFETIME, ByteBuffer.allocate(4).putInt(s).array()) }
    fun addRequestedTransport(p: Int) = apply { add(ATTR_REQUESTED_TRANSPORT, byteArrayOf(p.toByte(),0,0,0)) }
    fun addRequestedAddressFamily(f: Int) = apply { add(ATTR_REQUESTED_ADDRESS_FAMILY, byteArrayOf(f.toByte(),0,0,0)) }
    fun addChannelNumber(ch: Int) = apply { add(ATTR_CHANNEL_NUMBER, byteArrayOf((ch shr 8).toByte(), ch.toByte(),0,0)) }

    fun addXorPeerAddress(ip: InetAddress, port: Int) = apply {
        val buf = ByteArray(8); buf[1] = 0x01
        val xp = port xor (MAGIC ushr 16); buf[2] = (xp shr 8).toByte(); buf[3] = xp.toByte()
        val m = ByteBuffer.allocate(4).putInt(MAGIC).array()
        for (i in 0..3) buf[4+i] = (ip.address[i].toInt() xor m[i].toInt()).toByte()
        add(ATTR_XOR_PEER_ADDRESS, buf)
    }

    fun addMessageIntegrity(u: String, r: String, p: String) = apply {
        val key = md5("$u:$r:$p")
        val partial = encLen(totalAttrLen() + 24)
        val mac = Mac.getInstance("HmacSHA1").also { it.init(SecretKeySpec(key,"HmacSHA1")) }
        add(ATTR_MESSAGE_INTEGRITY, mac.doFinal(partial))
    }

    fun addFingerprint() = apply {
        val partial = encLen(totalAttrLen() + 8)
        add(ATTR_FINGERPRINT, ByteBuffer.allocate(4).putInt(crc32(partial) xor 0x5354_554E).array())
    }

    fun encode(): ByteArray = encLen(totalAttrLen())

    private fun encLen(attrLen: Int): ByteArray {
        val buf = ByteBuffer.allocate(20 + attrLen)
        buf.putShort(type.toShort()); buf.putShort(attrLen.toShort())
        buf.putInt(MAGIC); buf.put(txId)
        for ((t, v) in attrs) {
            buf.putShort(t.toShort()); buf.putShort(v.size.toShort()); buf.put(v)
            repeat((4 - v.size % 4) % 4) { buf.put(0) }
        }
        return buf.array()
    }

    private fun totalAttrLen() = attrs.sumOf { (_,v) -> 4 + v.size + (4 - v.size%4)%4 }
    private fun add(t: Int, v: ByteArray) { attrs.add(t to v) }

    fun errorCode(): Pair<Int,String>? {
        val v = getAttr(ATTR_ERROR_CODE) ?: return null
        return (v[2].toInt() and 7)*100 + (v[3].toInt() and 0xFF) to String(v, 4, v.size-4, Charsets.UTF_8)
    }
    fun xorRelayedAddress() = xorDecode(getAttr(ATTR_XOR_RELAYED_ADDRESS))
    fun realm(): String? = getAttr(ATTR_REALM)?.let { String(it, Charsets.UTF_8) }
    fun nonce(): ByteArray? = getAttr(ATTR_NONCE)
    fun getAttr(t: Int): ByteArray? = attrs.firstOrNull { it.first == t }?.second

    private fun xorDecode(v: ByteArray?): Pair<InetAddress,Int>? {
        if (v == null || v.size < 8 || v[1].toInt() and 0xFF != 1) return null
        val xp = ((v[2].toInt() and 0xFF) shl 8) or (v[3].toInt() and 0xFF)
        val m  = ByteBuffer.allocate(4).putInt(MAGIC).array()
        return InetAddress.getByAddress(ByteArray(4){ i -> (v[4+i].toInt() xor m[i].toInt()).toByte() }) to (xp xor (MAGIC ushr 16))
    }

    companion object {
        const val MAGIC = 0x2112A442.toInt()
        const val ALLOCATE_REQUEST=0x0003; const val ALLOCATE_SUCCESS=0x0103; const val ALLOCATE_ERROR=0x0113
        const val CREATE_PERM_REQUEST=0x0008; const val CREATE_PERM_SUCCESS=0x0108
        const val CHANNEL_BIND_REQUEST=0x0009; const val CHANNEL_BIND_SUCCESS=0x0109
        const val ATTR_USERNAME=0x0006; const val ATTR_MESSAGE_INTEGRITY=0x0008; const val ATTR_ERROR_CODE=0x0009
        const val ATTR_CHANNEL_NUMBER=0x000C; const val ATTR_LIFETIME=0x000D
        const val ATTR_XOR_PEER_ADDRESS=0x0012; const val ATTR_REALM=0x0014; const val ATTR_NONCE=0x0015
        const val ATTR_XOR_RELAYED_ADDRESS=0x0016; const val ATTR_REQUESTED_ADDRESS_FAMILY=0x0017
        const val ATTR_REQUESTED_TRANSPORT=0x0019; const val ATTR_FINGERPRINT=0x8028
        private val rng = SecureRandom()
        fun randomTxId() = ByteArray(12).also { rng.nextBytes(it) }
        fun build(type: Int, txId: ByteArray = randomTxId(), block: StunMsg.() -> Unit) = StunMsg(type, txId).apply(block)
        fun decode(data: ByteArray): StunMsg {
            require(data.size >= 20)
            val buf = ByteBuffer.wrap(data)
            val type = buf.short.toInt() and 0xFFFF; val len = buf.short.toInt() and 0xFFFF
            require(buf.int == MAGIC) { "Bad magic" }
            val txId = ByteArray(12).also { buf.get(it) }
            val msg = StunMsg(type, txId)
            var rem = len
            while (rem >= 4 && buf.remaining() >= 4) {
                val at = buf.short.toInt() and 0xFFFF; val al = buf.short.toInt() and 0xFFFF
                msg.add(at, ByteArray(al).also { buf.get(it) })
                val pad = (4 - al%4)%4; repeat(pad) { if (buf.hasRemaining()) buf.get() }
                rem -= 4+al+pad
            }
            return msg
        }
        fun readTcp(inp: java.io.InputStream): ByteArray? {
            val h = inp.readFully(4) ?: return null
            val b0 = h[0].toInt() and 0xFF
            return if (b0 in 0x40..0x7F) {
                val len = ((h[2].toInt() and 0xFF) shl 8) or (h[3].toInt() and 0xFF)
                val body = inp.readFully(len) ?: return null
                val pad = (4-len%4)%4; if (pad>0) inp.readFully(pad)
                ByteBuffer.allocate(4+len).put(h).put(body).array()
            } else {
                val al = ((h[2].toInt() and 0xFF) shl 8) or (h[3].toInt() and 0xFF)
                val rest = inp.readFully(16+al) ?: return null
                ByteBuffer.allocate(4+rest.size).put(h).put(rest).array()
            }
        }
        fun channelData(ch: Int, data: ByteArray, off: Int=0, len: Int=data.size): ByteArray =
            ByteBuffer.allocate(4+len).putShort(ch.toShort()).putShort(len.toShort()).put(data,off,len).array()
        fun md5(s: String): ByteArray = MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
        fun crc32(data: ByteArray): Int {
            var crc = 0xFFFF_FFFF.toInt()
            for (b in data) { var c=(crc xor b.toInt()) and 0xFF; repeat(8){c=if(c and 1!=0)(c ushr 1) xor 0xEDB8_8320.toInt() else c ushr 1}; crc=(crc ushr 8) xor c }
            return crc.inv()
        }
        private fun java.io.InputStream.readFully(n: Int): ByteArray? {
            if (n==0) return ByteArray(0)
            val buf=ByteArray(n); var off=0
            while (off<n) { val r=read(buf,off,n-off); if(r<0) return null; off+=r }
            return buf
        }
    }
}
