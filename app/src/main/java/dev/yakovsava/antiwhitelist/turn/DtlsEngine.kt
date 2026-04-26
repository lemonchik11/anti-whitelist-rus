package dev.yakovsava.antiwhitelist.turn

import android.util.Log
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.crypto.util.PrivateKeyFactory
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.tls.Certificate
import org.bouncycastle.tls.CertificateRequest
import org.bouncycastle.tls.CipherSuite
import org.bouncycastle.tls.DTLSClientProtocol
import org.bouncycastle.tls.DTLSTransport
import org.bouncycastle.tls.DatagramTransport
import org.bouncycastle.tls.DefaultTlsClient
import org.bouncycastle.tls.HashAlgorithm
import org.bouncycastle.tls.SignatureAlgorithm
import org.bouncycastle.tls.SignatureAndHashAlgorithm
import org.bouncycastle.tls.TlsAuthentication
import org.bouncycastle.tls.TlsCredentials
import org.bouncycastle.tls.TlsServerCertificate
import org.bouncycastle.tls.crypto.TlsCryptoParameters
import org.bouncycastle.tls.crypto.impl.bc.BcDefaultTlsCredentialedSigner
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCertificate
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.util.Date
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * DTLS 1.2 client — BouncyCastle.
 * Cipher suite: TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256 (matches pion/dtls server).
 * Self-signed P-256 certificate. InsecureSkipVerify.
 */
class DtlsEngine : AutoCloseable {
    companion object { private const val TAG = "DtlsEngine"; private const val MAX = 1600 }

    /** Encrypted frames produced after encrypt() — send via TURN. */
    val encryptedOut = LinkedBlockingQueue<ByteArray>(512)
    /** Feed encrypted frames received from TURN here. */
    val encryptedIn  = LinkedBlockingQueue<ByteArray>(512)
    private val plainOut = LinkedBlockingQueue<ByteArray>(512)

    @Volatile private var dt: DTLSTransport? = null
    @Volatile private var ready = false
    @Volatile private var closed = false

    /** Blocking handshake — call once before encrypt/decrypt. */
    fun handshake() {
        Log.i(TAG, "DTLS handshake start")
        val bcCrypto = BcTlsCrypto(SecureRandom())
        dt = DTLSClientProtocol().connect(Client(bcCrypto), QueueTransport())
        ready = true
        Log.i(TAG, "DTLS handshake complete")
        Thread { recvLoop() }.apply { isDaemon = true; start() }
    }

    fun encrypt(data: ByteArray, off: Int = 0, len: Int = data.size) {
        check(ready) { "not ready" }; dt?.send(data, off, len)
    }

    fun decrypt(timeoutMs: Long = 2000): ByteArray? = plainOut.poll(timeoutMs, TimeUnit.MILLISECONDS)

    fun feed(data: ByteArray) { encryptedIn.offer(data, 100, TimeUnit.MILLISECONDS) }

    override fun close() { closed = true; try { dt?.close() } catch (_: Exception) {} }

    private fun recvLoop() {
        val t = dt ?: return; val buf = ByteArray(MAX)
        try {
            while (!closed) {
                val n = try { t.receive(buf, 0, MAX, 100) } catch (_: Exception) { -1 }
                if (n > 0) plainOut.offer(buf.copyOf(n), 100, TimeUnit.MILLISECONDS)
            }
        } catch (e: Exception) { if (!closed) Log.e(TAG, "recv: ${e.message}") }
    }

    private inner class QueueTransport : DatagramTransport {
        override fun getReceiveLimit() = MAX; override fun getSendLimit() = MAX
        override fun receive(buf: ByteArray, off: Int, len: Int, wait: Int): Int {
            val d = encryptedIn.poll(wait.toLong(), TimeUnit.MILLISECONDS) ?: return -1
            val n = minOf(d.size, len); System.arraycopy(d, 0, buf, off, n); return n
        }
        override fun send(buf: ByteArray, off: Int, len: Int) {
            encryptedOut.offer(buf.copyOfRange(off, off+len), 100, TimeUnit.MILLISECONDS)
        }
        override fun close() {}
    }

    private inner class Client(private val bcCrypto: BcTlsCrypto) : DefaultTlsClient(bcCrypto) {
        private val kp: KeyPair = KeyPairGenerator.getInstance("EC")
            .also { it.initialize(ECGenParameterSpec("secp256r1"), SecureRandom()) }.generateKeyPair()
        private val cert: Certificate = buildCert(kp)

        override fun getCipherSuites() = intArrayOf(CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256)

        override fun getAuthentication() = object : TlsAuthentication {
            // InsecureSkipVerify — accept any server cert
            override fun notifyServerCertificate(serverCert: TlsServerCertificate?) {}
            override fun getClientCredentials(r: CertificateRequest?): TlsCredentials {
                val privKeyParams = PrivateKeyFactory.createKey(kp.private.encoded)
                val sigAlg = SignatureAndHashAlgorithm(HashAlgorithm.sha256, SignatureAlgorithm.ecdsa)
                return BcDefaultTlsCredentialedSigner(
                    TlsCryptoParameters(context),
                    bcCrypto,
                    privKeyParams,
                    cert,
                    sigAlg
                )
            }
        }

        private fun buildCert(kp: KeyPair): Certificate {
            val now = System.currentTimeMillis(); val issuer = X500Name("CN=antiwhitelist")
            val spki = SubjectPublicKeyInfo.getInstance(kp.public.encoded)
            val holder = X509v3CertificateBuilder(
                issuer, BigInteger.valueOf(SecureRandom().nextLong().and(Long.MAX_VALUE)),
                Date(now - 60_000), Date(now + 365L * 86_400_000), issuer, spki
            ).build(JcaContentSignerBuilder("SHA256withECDSA").build(kp.private))
            val jcaCert = JcaX509CertificateConverter().getCertificate(holder)
            return Certificate(arrayOf(BcTlsCertificate(bcCrypto, jcaCert.encoded)))
        }
    }
}
