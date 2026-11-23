package com.techloungegr.homelabmanagement.net

import com.techloungegr.homelabmanagement.model.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

class NetError(message: String) : Exception(message)

suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: java.io.IOException) { cont.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) { cont.resume(response) }
    })
}

// ---------------------------------------------------------------- TrueNAS (JSON-RPC over WebSocket)

class TrueNasClient {
    private var ws: WebSocket? = null
    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonElement?>>()
    @Volatile private var closed = true
    var onRealtime: ((cpu: Double?, memUsed: Double?) -> Unit)? = null
    var onClose: ((String) -> Unit)? = null

    suspend fun connect(host: String, port: Int, tls: Boolean, allowSelfSigned: Boolean, apiKey: String?, user: String?, password: String?) {
        val http = Http.client(host, port, tls, allowSelfSigned, timeoutSec = 0)
        val url = "${if (tls) "wss" else "ws"}://$host:$port/api/current"
        val opened = CompletableDeferred<Unit>()
        closed = false
        val socket = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { opened.complete(Unit) }
            override fun onMessage(webSocket: WebSocket, text: String) = handle(text)
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                opened.completeExceptionally(NetError("Couldn't connect to $host:$port — ${describeNetError(t)}"))
                failAll(NetError("Connection lost: ${describeNetError(t)}"))
                if (!closed) onClose?.invoke(describeNetError(t))
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                failAll(NetError("Connection closed")); if (!closed) onClose?.invoke("Connection closed")
            }
        })
        ws = socket
        withTimeout(15_000) { opened.await() }
        val ok = if (apiKey != null) call("auth.login_with_api_key", buildJsonArray { add(apiKey) })
        else call("auth.login", buildJsonArray { add(user ?: ""); add(password ?: "") })
        if ((ok as? JsonPrimitive)?.booleanOrNull != true) { disconnect(); throw NetError("Authentication failed: credentials rejected") }
        try { call("core.subscribe", buildJsonArray { add("reporting.realtime") }) } catch (_: Exception) {}
    }

    fun disconnect() {
        closed = true
        try { ws?.close(1000, null) } catch (_: Exception) {}
        ws = null
        failAll(NetError("Not connected to TrueNAS."))
    }

    suspend fun call(method: String, params: JsonArray = JsonArray(emptyList()), timeoutMs: Long = 45_000): JsonElement? {
        val w = ws ?: throw NetError("Not connected to TrueNAS.")
        val id = nextId.getAndIncrement()
        val d = CompletableDeferred<JsonElement?>()
        pending[id] = d
        val payload = buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("method", method); put("params", params) }
        if (!w.send(payload.toString())) { pending.remove(id); throw NetError("Not connected to TrueNAS.") }
        try { return withTimeout(timeoutMs) { d.await() } }
        catch (e: TimeoutCancellationException) { pending.remove(id); throw NetError("The request timed out.") }
    }

    private fun failAll(e: Exception) {
        val all = pending.values.toList(); pending.clear()
        all.forEach { it.completeExceptionally(e) }
    }

    private fun handle(raw: String) {
        val obj = try { jsonParser.parseToJsonElement(raw) as? JsonObject } catch (_: Exception) { null } ?: return
        val id = (obj["id"] as? JsonPrimitive)?.intOrNull
        if (id != null) {
            val d = pending.remove(id) ?: return
            val err = obj["error"].orNull()
            if (err != null) {
                var msg = err.str("message") ?: "Unknown error"
                err["data"].str("reason")?.let { msg = it }
                d.completeExceptionally(NetError(msg))
            } else d.complete(obj["result"].orNull())
        } else if ((obj["method"] as? JsonPrimitive)?.content == "collection_update") {
            val params = obj["params"]
            if (params.str("collection") != "reporting.realtime") return
            val fields = params["fields"]
            val cpu = fields["cpu"]["cpu"].num("usage")
            val mem = fields["memory"]
            val total = mem.num("physical_memory_total"); val avail = mem.num("physical_memory_available")
            val used = if (total != null && total > 0 && avail != null) 1 - avail / total else null
            onRealtime?.invoke(cpu, used)
        }
    }
}

// ---------------------------------------------------------------- OPNsense (REST, basic auth)

class OpnClient(private val host: String, private val port: Int, key: String, secret: String, private val tls: Boolean, private val selfSigned: Boolean) {
    private val auth = Credentials.basic(key, secret)
    private val http = Http.client(host, port, tls, selfSigned, 15)
    private val base get() = "${if (tls) "https" else "http"}://$host:$port/api"

    suspend fun get(path: String): JsonElement? = send("GET", path, null)
    suspend fun post(path: String, body: JsonObject? = null): JsonElement? = send("POST", path, body)

    private suspend fun send(method: String, path: String, body: JsonObject?): JsonElement? {
        val url = "$base/${path.trimStart('/')}"
        val rb = if (method == "POST") Http.jsonBody((body ?: buildJsonObject { }).toString()) else null
        val req = Request.Builder().url(url).header("Authorization", auth).method(method, rb).build()
        val resp = try { http.newCall(req).await() } catch (e: Exception) { throw NetError("Couldn't reach $url: ${describeNetError(e)}") }
        resp.use {
            if (it.code == 401) throw NetError("Authentication failed: check the API key and secret.")
            if (it.code >= 400) throw NetError("OPNsense returned HTTP ${it.code} for $path.")
            return try { jsonParser.parseToJsonElement(it.body?.string().orEmpty()) } catch (_: Exception) { null }
        }
    }
}

// ---------------------------------------------------------------- Pi-hole v6 API

class PiholeClient(private val host: String, private val password: String) {
    private val http = Http.plain(10)
    private val base = "http://$host/api"
    private var sid: String? = null
    private var csrf: String? = null

    private suspend fun login() {
        val req = Request.Builder().url("$base/auth").post(Http.jsonBody(buildJsonObject { put("password", password) }.toString())).build()
        val r = try { http.newCall(req).await() } catch (e: Exception) { throw NetError("Couldn't reach $base/auth: ${e.message}") }
        r.use {
            if (it.code >= 400) throw NetError("Pi-hole login returned HTTP ${it.code} — check the password.")
            val data = try { jsonParser.parseToJsonElement(it.body?.string().orEmpty()) } catch (_: Exception) { throw NetError("Pi-hole's /auth response wasn't JSON.") }
            val s = data["session"]
            if (s.bool("valid") == false) throw NetError("Pi-hole rejected that password.")
            sid = s.str("sid") ?: throw NetError("Pi-hole's /auth response didn't include a session id.")
            csrf = s.str("csrf")
        }
    }

    suspend fun get(path: String): JsonElement? = request("GET", path, null)
    suspend fun patch(path: String, body: JsonObject): JsonElement? = request("PATCH", path, body)

    private suspend fun request(method: String, path: String, body: JsonObject?, retry: Boolean = true): JsonElement? {
        if (sid == null) login()
        val rb = body?.let { Http.jsonBody(it.toString()) }
        val b = Request.Builder().url("$base/${path.trimStart('/')}").header("X-FTL-SID", sid!!).method(method, rb)
        csrf?.let { b.header("X-FTL-CSRF", it) }
        val r = try { http.newCall(b.build()).await() } catch (e: Exception) { throw NetError("Couldn't reach Pi-hole: ${e.message}") }
        r.use {
            if (it.code == 401 && retry) { sid = null; return request(method, path, body, false) }
            if (it.code >= 400) throw NetError("Pi-hole returned HTTP ${it.code} for $path.")
            return try { jsonParser.parseToJsonElement(it.body?.string().orEmpty()) } catch (_: Exception) { null }
        }
    }
}

// ---------------------------------------------------------------- fanctl (optional TrueNAS container)

data class FanConfig(
    val mode: String, val manualPct: Double, val minPct: Double, val cpuOffset: Double,
    val hysteresis: Double, val curve: List<List<Double>>, val interval: Int?,
) {
    fun toJson() = buildJsonObject {
        put("mode", mode); put("manual_pct", manualPct); put("min_pct", minPct); put("cpu_offset", cpuOffset)
        put("hysteresis", hysteresis); put("curve", buildJsonArray { curve.sortedBy { it[0] }.forEach { p -> add(buildJsonArray { p.forEach { add(it) } }) } })
        interval?.let { put("interval", it) }
    }
    companion object {
        fun from(d: JsonElement?) = FanConfig(
            d.str("mode") ?: "auto", d.num("manual_pct") ?: 0.0, d.num("min_pct") ?: 0.0, d.num("cpu_offset") ?: 0.0,
            d.num("hysteresis") ?: 0.0,
            d.list("curve").map { p -> (p as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull } ?: emptyList() }.filter { it.size >= 2 },
            d.num("interval")?.toInt(),
        )
    }
}

data class FanStatus(val temps: Map<String, Pair<String, Double>>, val ctlTemp: Double?, val pwmPct: Double?, val rpm: Int?, val error: String?, val src: String?)

class FanClient(baseUrl: String, private val password: String) {
    private val base = baseUrl.trimEnd('/')
    private val http = Http.plain(15)

    private suspend fun send(path: String, method: String = "GET", body: JsonObject? = null): JsonElement? {
        val b = Request.Builder().url("$base/$path").method(method, body?.let { Http.jsonBody(it.toString()) })
        if (password.isNotEmpty()) b.header("Authorization", Credentials.basic("admin", password))
        val r = try { http.newCall(b.build()).await() } catch (e: Exception) { throw NetError(e.message ?: "network error") }
        r.use {
            if (it.code >= 400) {
                if (it.code == 401) throw NetError("The fan controller needs a password.")
                val msg = try { parse(it.body?.string().orEmpty()).str("error") } catch (_: Exception) { null }
                throw NetError(msg ?: "Fan controller returned HTTP ${it.code}.")
            }
            return try { jsonParser.parseToJsonElement(it.body?.string().orEmpty()) } catch (_: Exception) { null }
        }
    }

    suspend fun status(): FanStatus {
        val o = send("api/status")
        val temps = (o["temps"] as? JsonObject)?.mapValues { (_, v) -> (v.str("kind") ?: "") to (v.num("temp") ?: 0.0) } ?: emptyMap()
        return FanStatus(temps, o.num("ctl_temp"), o.num("pwm_pct"), o.num("rpm")?.toInt(), o.str("error"), o.str("src"))
    }
    suspend fun config() = FanConfig.from(send("api/config"))
    suspend fun save(c: FanConfig) = FanConfig.from(send("api/config", "POST", c.toJson()))
}

// ---------------------------------------------------------------- Wake-on-LAN

object Wol {
    fun parseMac(mac: String): ByteArray {
        val hex = mac.replace(Regex("[-:.\\s]"), "")
        if (!Regex("[0-9A-Fa-f]{12}").matches(hex)) throw IllegalArgumentException("“$mac” isn't a valid MAC address (expected like AA:BB:CC:DD:EE:FF).")
        return ByteArray(6) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
    suspend fun wake(mac: String) = withContext(Dispatchers.IO) {
        val m = parseMac(mac)
        val pkt = ByteArray(6) { 0xFF.toByte() } + ByteArray(16 * 6) { m[it % 6] }
        DatagramSocket().use { s ->
            s.broadcast = true
            s.send(DatagramPacket(pkt, pkt.size, InetAddress.getByName("255.255.255.255"), 9))
        }
    }
}
