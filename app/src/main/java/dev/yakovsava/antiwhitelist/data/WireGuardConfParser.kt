package dev.yakovsava.antiwhitelist.data

import java.io.InputStream

/**
 * Parses a standard WireGuard .conf into [VpnProfile].
 * MTU is forced to 1280 (required for the TURN tunnel).
 * Endpoint → [VpnProfile.wgRealEndpoint]; runtime endpoint is overridden to 127.0.0.1:9000.
 */
object WireGuardConfParser {

    fun parse(stream: InputStream, suggestedName: String = "Профиль"): VpnProfile {
        val kv = mutableMapOf<String, String>()
        var section = ""
        stream.bufferedReader(Charsets.UTF_8).forEachLine { raw ->
            val line = raw.trim()
            when {
                line.startsWith("[") -> section = line.lowercase()
                line.contains("=") && !line.startsWith("#") -> {
                    val key   = "${section}_${line.substringBefore("=").trim().lowercase()}"
                    val value = line.substringAfter("=").trim()
                    kv[key] = value
                }
            }
        }
        val endpoint  = kv["[peer]_endpoint"] ?: ""
        val keepalive = kv["[peer]_persistentkeepalive"]?.toIntOrNull() ?: 25
        return VpnProfile(
            name           = suggestedName,
            wgPrivateKey   = kv["[interface]_privatekey"] ?: "",
            wgAddress      = kv["[interface]_address"] ?: "10.8.0.2/32",
            wgDns          = kv["[interface]_dns"] ?: "1.1.1.1",
            wgMtu          = 1280,
            wgServerPubKey = kv["[peer]_publickey"] ?: "",
            wgPresharedKey = kv["[peer]_presharedkey"] ?: "",
            wgAllowedIps   = kv["[peer]_allowedips"] ?: "0.0.0.0/0, ::/0",
            wgKeepalive    = if (keepalive == 0) 25 else keepalive,
            wgRealEndpoint = endpoint,
            goodTurnServerPort = 56000,
        )
    }

    fun parse(text: String, suggestedName: String = "Профиль"): VpnProfile =
        parse(text.byteInputStream(Charsets.UTF_8), suggestedName)
}
