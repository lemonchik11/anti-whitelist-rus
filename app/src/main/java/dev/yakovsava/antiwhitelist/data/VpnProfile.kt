package dev.yakovsava.antiwhitelist.data

import org.json.JSONObject
import java.util.UUID

enum class LinkType { VK, YANDEX }

data class VpnProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Новый профиль",
    val wgPrivateKey: String = "",
    val wgAddress: String = "10.8.0.2/32",
    val wgDns: String = "1.1.1.1",
    val wgMtu: Int = 1280,
    val wgServerPubKey: String = "",
    val wgPresharedKey: String = "",
    val wgAllowedIps: String = "0.0.0.0/0, ::/0",
    val wgKeepalive: Int = 25,
    /** Original Endpoint from .conf, e.g. "188.17.159.51:51820" */
    val wgRealEndpoint: String = "",
    val goodTurnServerHost: String = "",
    val goodTurnServerPort: Int = 56000,
    val linkType: LinkType = LinkType.VK,
    val callLink: String = "",
    val turnOverride: String = "",
    val useUdp: Boolean = false,
    val noDtls: Boolean = false,
    val connCount: Int = 0,
    val wgEnabled: Boolean = true,
    val startOnBoot: Boolean = false,
) {
    /** IP for the GoodTurn peer: explicit override or parsed from wgRealEndpoint. */
    val effectiveServerHost: String
        get() = goodTurnServerHost.ifEmpty { wgRealEndpoint.substringBeforeLast(":").trim() }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name)
        put("wgPrivateKey", wgPrivateKey); put("wgAddress", wgAddress)
        put("wgDns", wgDns); put("wgMtu", wgMtu)
        put("wgServerPubKey", wgServerPubKey); put("wgPresharedKey", wgPresharedKey)
        put("wgAllowedIps", wgAllowedIps); put("wgKeepalive", wgKeepalive)
        put("wgRealEndpoint", wgRealEndpoint)
        put("goodTurnServerHost", goodTurnServerHost); put("goodTurnServerPort", goodTurnServerPort)
        put("linkType", linkType.name); put("callLink", callLink)
        put("turnOverride", turnOverride); put("useUdp", useUdp)
        put("noDtls", noDtls); put("connCount", connCount)
        put("wgEnabled", wgEnabled); put("startOnBoot", startOnBoot)
    }

    companion object {
        fun fromJson(j: JSONObject) = VpnProfile(
            id             = j.optString("id", UUID.randomUUID().toString()),
            name           = j.optString("name", "Профиль"),
            wgPrivateKey   = j.optString("wgPrivateKey"),
            wgAddress      = j.optString("wgAddress", "10.8.0.2/32"),
            wgDns          = j.optString("wgDns", "1.1.1.1"),
            wgMtu          = j.optInt("wgMtu", 1280),
            wgServerPubKey = j.optString("wgServerPubKey"),
            wgPresharedKey = j.optString("wgPresharedKey"),
            wgAllowedIps   = j.optString("wgAllowedIps", "0.0.0.0/0, ::/0"),
            wgKeepalive    = j.optInt("wgKeepalive", 25),
            wgRealEndpoint = j.optString("wgRealEndpoint"),
            goodTurnServerHost = j.optString("goodTurnServerHost"),
            goodTurnServerPort = j.optInt("goodTurnServerPort", 56000),
            linkType       = runCatching { LinkType.valueOf(j.optString("linkType")) }.getOrDefault(LinkType.VK),
            callLink       = j.optString("callLink"),
            turnOverride   = j.optString("turnOverride"),
            useUdp         = j.optBoolean("useUdp"),
            noDtls         = j.optBoolean("noDtls"),
            connCount      = j.optInt("connCount"),
            wgEnabled      = j.optBoolean("wgEnabled", true),
            startOnBoot    = j.optBoolean("startOnBoot"),
        )
    }
}
