package dev.yakovsava.antiwhitelist.turn

import android.util.Log
import com.fasterxml.uuid.Generators
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

data class TurnCreds(val host: String, val port: Int, val username: String, val password: String)

private val http = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()

private val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:144.0) Gecko/20100101 Firefox/144.0"

private fun post(url: String, body: String): JSONObject {
    val resp = http.newCall(
        Request.Builder().url(url).addHeader("User-Agent", UA)
            .post(body.toRequestBody("application/x-www-form-urlencoded".toMediaType())).build()
    ).execute()
    return JSONObject(resp.body?.string() ?: throw IOException("empty body"))
}

fun getVkCreds(code: String): TurnCreds {
    val t1 = post("https://login.vk.ru/?act=get_anonym_token",
        "client_id=6287487&token_type=messages&client_secret=QbYic1K3lEV5kTGiqlq2&version=1&app_id=6287487"
    ).getJSONObject("data").getString("access_token")

    val t2 = post("https://api.vk.ru/method/calls.getAnonymousToken?v=5.274&client_id=6287487",
        "vk_join_link=https://vk.com/call/join/$code&name=123&access_token=$t1"
    ).getJSONObject("response").getString("token")

    val t3 = post("https://calls.okcdn.ru/fb.do",
        "session_data=%7B%22version%22%3A2%2C%22device_id%22%3A%22${Generators.timeBasedGenerator().generate()}%22%2C%22client_version%22%3A1.1%2C%22client_type%22%3A%22SDK_JS%22%7D&method=auth.anonymLogin&format=JSON&application_key=CGMMEJLGDIHBABABA"
    ).getString("session_key")

    val turn = post("https://calls.okcdn.ru/fb.do",
        "joinLink=$code&isVideo=false&protocolVersion=5&anonymToken=$t2&method=vchat.joinConversationByLink&format=JSON&application_key=CGMMEJLGDIHBABABA&session_key=$t3"
    ).getJSONObject("turn_server")

    val (h, p) = parseTurnUrl(turn.getJSONArray("urls").getString(0))
    return TurnCreds(h, p, turn.getString("username"), turn.getString("credential"))
}

fun getYandexCreds(code: String): TurnCreds {
    val conf = http.newCall(
        Request.Builder()
            .url("https://cloud-api.yandex.ru/telemost_front/v2/telemost/conferences/https%3A%2F%2Ftelemost.yandex.ru%2Fj%2F$code/connection?next_gen_media_platform_allowed=false")
            .addHeader("User-Agent", UA)
            .addHeader("Referer", "https://telemost.yandex.ru/")
            .addHeader("Origin", "https://telemost.yandex.ru")
            .addHeader("Client-Instance-Id", Generators.timeBasedGenerator().generate().toString())
            .get().build()
    ).execute()
    val cj = JSONObject(conf.body?.string() ?: throw IOException("empty"))
    val peerId = cj.getString("peer_id"); val roomId = cj.getString("room_id")
    val creds  = cj.getString("credentials")
    val wss    = cj.getJSONObject("client_configuration").getString("media_server_url")

    val latch = CountDownLatch(1)
    val result = AtomicReference<TurnCreds>()
    val err    = AtomicReference<Throwable>()

    http.newWebSocket(
        Request.Builder().url(wss).addHeader("Origin","https://telemost.yandex.ru").addHeader("User-Agent",UA).build(),
        object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, r: okhttp3.Response) {
                ws.send(buildYandexHello(peerId, roomId, creds).toString())
            }
            override fun onMessage(ws: WebSocket, text: String) {
                try {
                    val msg = JSONObject(text)
                    if (msg.has("ack")) return
                    if (!msg.has("serverHello")) return
                    val ice = msg.getJSONObject("serverHello").getJSONObject("rtcConfiguration").getJSONArray("iceServers")
                    for (i in 0 until ice.length()) {
                        val s = ice.getJSONObject(i); val u = s.optString("username"); if (u.isEmpty()) continue
                        val urls = when (val raw = s.get("urls")) {
                            is JSONArray -> (0 until raw.length()).map { raw.getString(it) }
                            is String -> listOf(raw); else -> emptyList()
                        }
                        val url = urls.firstOrNull { it.startsWith("turn:") && !it.contains("tcp") } ?: continue
                        val (h,p) = parseTurnUrl(url)
                        result.set(TurnCreds(h, p, u, s.getString("credential")))
                        ws.close(1000, null); latch.countDown(); return
                    }
                } catch (e: Exception) { err.set(e); ws.close(1000,null); latch.countDown() }
            }
            override fun onFailure(ws: WebSocket, t: Throwable, r: okhttp3.Response?) { err.set(t); latch.countDown() }
        }
    )
    if (!latch.await(15, TimeUnit.SECONDS)) throw IOException("Yandex WS timeout")
    err.get()?.let { throw it }
    return result.get() ?: throw IOException("No TURN in Yandex response")
}

fun parseTurnUrl(url: String): Pair<String, Int> {
    val clean = url.substringBefore("?").removePrefix("turns:").removePrefix("turn:")
    val last  = clean.lastIndexOf(':')
    return (if (last > 0) clean.substring(0, last) else clean) to
           (if (last > 0) clean.substring(last+1).toIntOrNull() ?: 3478 else 3478)
}

private fun buildYandexHello(peerId: String, roomId: String, credentials: String): JSONObject {
    fun sl(vararg v: String) = JSONArray(v.toList())
    val caps = JSONObject().apply {
        put("offerAnswerMode",sl("SEPARATE")); put("initialSubscriberOffer",sl("ON_HELLO"))
        put("slotsMode",sl("FROM_CONTROLLER")); put("simulcastMode",sl("DISABLED"))
        put("selfVadStatus",sl("FROM_SERVER")); put("dataChannelSharing",sl("TO_RTP"))
        put("videoEncoderConfig",sl("NO_CONFIG")); put("dataChannelVideoCodec",sl("VP8"))
        put("bandwidthLimitationReason",sl("BANDWIDTH_REASON_DISABLED"))
        put("sdkDefaultDeviceManagement",sl("SDK_DEFAULT_DEVICE_MANAGEMENT_DISABLED"))
        put("joinOrderLayout",sl("JOIN_ORDER_LAYOUT_DISABLED")); put("pinLayout",sl("PIN_LAYOUT_DISABLED"))
        put("sendSelfViewVideoSlot",sl("SEND_SELF_VIEW_VIDEO_SLOT_DISABLED"))
        put("serverLayoutTransition",sl("SERVER_LAYOUT_TRANSITION_DISABLED"))
        put("sdkPublisherOptimizeBitrate",sl("SDK_PUBLISHER_OPTIMIZE_BITRATE_DISABLED"))
        put("sdkNetworkLostDetection",sl("SDK_NETWORK_LOST_DETECTION_DISABLED"))
        put("sdkNetworkPathMonitor",sl("SDK_NETWORK_PATH_MONITOR_DISABLED"))
        put("publisherVp9",sl("PUBLISH_VP9_DISABLED")); put("svcMode",sl("SVC_MODE_DISABLED"))
        put("subscriberOfferAsyncAck",sl("SUBSCRIBER_OFFER_ASYNC_ACK_DISABLED"))
        put("svcModes",sl("FALSE")); put("reportTelemetryModes",sl("TRUE")); put("keepDefaultDevicesModes",sl("TRUE"))
    }
    return JSONObject().apply {
        put("uid", Generators.timeBasedGenerator().generate().toString())
        put("hello", JSONObject().apply {
            put("participantMeta", JSONObject().apply { put("name","Гость"); put("role","SPEAKER"); put("description",""); put("sendAudio",false); put("sendVideo",false) })
            put("participantAttributes", JSONObject().apply { put("name","Гость"); put("role","SPEAKER"); put("description","") })
            put("sendAudio",false); put("sendVideo",false); put("sendSharing",false)
            put("participantId",peerId); put("roomId",roomId); put("serviceName","telemost"); put("credentials",credentials)
            put("sdkInfo", JSONObject().apply { put("implementation","browser"); put("version","5.15.0"); put("userAgent",UA); put("hwConcurrency",4) })
            put("sdkInitializationId",Generators.timeBasedGenerator().generate().toString())
            put("disablePublisher",false); put("disableSubscriber",false); put("disableSubscriberAudio",false)
            put("capabilitiesOffer",caps)
        })
    }
}
