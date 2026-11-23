package com.techloungegr.homelabmanagement.session

import com.techloungegr.homelabmanagement.model.*
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

object Docker {
    data class Container(
        val id: String, val name: String, val image: String, val state: String, val status: String, val ports: String,
        val labels: Map<String, String>, val cpu: String?, val memory: String?,
    ) { val running get() = state == "running" }

    fun parse(text: String): List<Container> {
        val base = ArrayList<JsonObject>(); val stats = HashMap<String, Pair<String, String>>()
        var inStats = false
        for (raw in text.lines()) {
            val line = raw.trim('\r')
            if (line == "---STATS---") { inStats = true; continue }
            if (!line.startsWith("{")) continue
            val obj = parseJson(line) as? JsonObject ?: continue
            if (inStats) {
                val name = obj.str("Name") ?: continue
                stats[name] = (obj.str("CPUPerc") ?: "") to (obj.str("MemUsage") ?: "").substringBefore(" / ")
            } else base.add(obj)
        }
        return base.map { o ->
            val labels = (o.str("Labels") ?: "").split(",").mapNotNull { kv -> kv.indexOf('=').takeIf { it > 0 }?.let { kv.substring(0, it) to kv.substring(it + 1) } }.toMap()
            val name = o.str("Names") ?: "?"
            Container(o.str("ID") ?: "", name, o.str("Image") ?: "", o.str("State") ?: "", o.str("Status") ?: "", o.str("Ports") ?: "",
                labels, stats[name]?.first, stats[name]?.second)
        }.sortedBy { it.name.lowercase() }
    }
}

object WireGuard {
    data class Tunnel(val name: String, val up: Boolean, val endpoint: String = "", val allowedIps: String = "",
                      val handshake: String = "", val rx: Long = 0, val tx: Long = 0)

    private val nameRe = Regex("^[A-Za-z0-9_-]+$")
    fun validName(n: String) = n.isNotEmpty() && n.length <= 40 && nameRe.matches(n)

    private fun relative(unix: String): String {
        val t = unix.toDoubleOrNull() ?: return "Never"
        if (t <= 0) return "Never"
        val s = System.currentTimeMillis() / 1000.0 - t
        return when { s < 60 -> "just now"; s < 3600 -> "${(s / 60).toInt()}m ago"; s < 86400 -> "${(s / 3600).toInt()}h ago"; else -> "${(s / 86400).toInt()}d ago" }
    }

    fun tunnels(confNames: List<String>, dump: String): List<Tunnel> {
        val up = LinkedHashMap<String, Tunnel>()
        for (line in dump.lines()) {
            val f = line.split('\t')
            if (f.isEmpty() || f[0].isEmpty()) continue
            val iface = f[0]
            if (f.size == 5) up[iface] = Tunnel(iface, true)
            else if (f.size == 9 && iface in up) {
                up[iface] = up[iface]!!.copy(endpoint = if (f[3] == "(none)") "" else f[3], allowedIps = f[4],
                    handshake = if ((f[5].toLongOrNull() ?: 0) > 0) relative(f[5]) else "Never", rx = f[6].toLongOrNull() ?: 0, tx = f[7].toLongOrNull() ?: 0)
            }
        }
        val all = confNames.map { up[it] ?: Tunnel(it, false) }.toMutableList()
        up.forEach { (n, t) -> if (n !in confNames) all.add(t) }
        return all.sortedBy { it.name.lowercase() }
    }
}

object Tailscale {
    data class Peer(val id: String, val host: String, val ip: String, val os: String, val online: Boolean, val self: Boolean, val exit: Boolean)
    data class Status(val state: String, val peers: List<Peer>)

    fun parse(text: String): Status? {
        val obj = parseJson(text) as? JsonObject ?: return null
        fun make(id: String, p: kotlinx.serialization.json.JsonElement?, self: Boolean) = Peer(id, p.str("HostName") ?: id,
            (p["TailscaleIPs"] as? JsonArray)?.firstOrNull()?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } ?: "",
            p.str("OS") ?: "", if (self) true else p.bool("Online") == true, self, p.bool("ExitNode") == true)
        val peers = ArrayList<Peer>()
        obj["Self"]?.let { peers.add(make(it.str("ID") ?: "self", it, true)) }
        (obj["Peer"] as? JsonObject)?.forEach { (id, p) -> peers.add(make(id, p, false)) }
        return Status(obj.str("BackendState") ?: "Unknown", peers.sortedWith(compareBy({ !it.self }, { it.host.lowercase() })))
    }
}
