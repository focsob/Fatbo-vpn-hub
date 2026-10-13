package com.vpnhub.app.data

import android.net.Uri
import android.util.Base64
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.URLDecoder
import java.security.MessageDigest

/**
 * Parses share links (vless / vmess / trojan / hysteria2 / tuic / shadowsocks / … ) into sing-box
 * outbound configs — the on-device twin of the collector's parse.py, so members can paste their own
 * private node (e.g. VLESS-REALITY / Hysteria2) straight into the app.
 */
object LinkParser {

    private class Unsupported : Exception()

    private val UTLS = setOf("chrome", "firefox", "edge", "safari", "360", "qq", "ios", "android", "random", "randomized")
    private val VMESS_SECURITY = setOf("auto", "none", "zero", "aes-128-gcm", "chacha20-poly1305", "aes-128-ctr")
    private val SS_METHODS = setOf(
        "aes-128-gcm", "aes-192-gcm", "aes-256-gcm", "chacha20-ietf-poly1305", "xchacha20-ietf-poly1305",
        "2022-blake3-aes-128-gcm", "2022-blake3-aes-256-gcm", "2022-blake3-chacha20-poly1305", "none",
        "aes-128-ctr", "aes-192-ctr", "aes-256-ctr", "aes-128-cfb", "aes-192-cfb", "aes-256-cfb",
        "rc4-md5", "chacha20-ietf", "xchacha20",
    )
    private val UUID_RE = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    private val HEX_RE = Regex("^[0-9a-fA-F]*$")

    /** Split a blob (possibly base64) of one or more links into individual links. */
    fun extractLinks(raw0: String): List<String> {
        var raw = raw0.trim()
        if (!raw.take(4096).contains("://")) {
            runCatching { String(b64(raw)) }.getOrNull()?.let { if (it.contains("://")) raw = it }
        }
        raw = raw.replace("&amp;", "&")
        val out = mutableListOf<String>()
        for (line in raw.split(Regex("[\\r\\n\\s]+"))) {
            val t = line.trim()
            if (t.length > 12 && t.take(12).contains("://")) out += t
        }
        return out
    }

    /** Parse one link into a private Node, or null if malformed / unsupported. */
    fun parse(link0: String): Node? {
        val link = link0.trim()
        val scheme = link.substringBefore("://", "").lowercase()
        return try {
            val parsed = when (scheme) {
                "vless" -> vless(link)
                "vmess" -> vmess(link)
                "trojan" -> trojan(link)
                "hysteria2", "hy2" -> hysteria2(link)
                "tuic" -> tuic(link)
                "ss" -> ss(link)
                "socks", "socks5" -> plainProxy(link, "socks")
                "http", "https" -> plainProxy(link, "http")
                "anytls" -> anytls(link)
                else -> null
            } ?: return null
            val (protocol, kind, config) = parsed
            val name = remark(link).ifBlank { "${Protocols.label(protocol)} ${config["server"]?.jp() ?: ""}".trim() }
            Node(
                id = "mine-" + sha1Hex(config.toString()).take(16),
                name = name,
                protocol = protocol,
                country = PRIVATE_CC,
                kind = kind,
                config = config,
                link = link,
                private = true,
            )
        } catch (e: Exception) {
            null
        }
    }

    const val PRIVATE_CC = "MINE"

    // ------------------------------------------------------------------ helpers

    private fun JsonElement.jp(): String = (this as? JsonPrimitive)?.content ?: ""

    private fun b64(text: String): ByteArray {
        var s = text.replace(Regex("\\s"), "").replace('-', '+').replace('_', '/')
        s += "=".repeat((4 - s.length % 4) % 4)
        return Base64.decode(s, Base64.DEFAULT)
    }

    private fun dec(s: String?): String = if (s == null) "" else runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

    private fun truthy(v: String?): Boolean = v?.trim()?.lowercase() in setOf("1", "true", "yes")

    private fun port(v: String?): Int? = v?.trim()?.toIntOrNull()?.takeIf { it in 1..65535 }

    private fun host(h0: String?): String? {
        val h = h0?.trim()?.trim('[', ']') ?: return null
        if (h.isEmpty() || h.length > 253 || h.any { it in " /\\@#?" }) return null
        return h
    }

    private fun isIp(h: String): Boolean = h.matches(Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")) || h.contains(":")

    private fun first(v: String?): String = (v ?: "").split(",").firstOrNull()?.trim() ?: ""

    private fun remark(link: String): String = dec(link.substringAfter("#", "")).trim()

    /** Query params of a uri-style link, lower-cased keys, "amp;" stripped. */
    private fun query(uri: Uri): Map<String, String> {
        val out = HashMap<String, String>()
        val raw = uri.encodedQuery ?: return out
        for (part in raw.split("&")) {
            if (part.isEmpty()) continue
            val k = part.substringBefore("=").lowercase().removePrefix("amp;")
            val v = dec(part.substringAfter("=", ""))
            if (k.isNotEmpty()) out[k] = v
        }
        return out
    }

    private fun uuidNorm(raw0: String): String? {
        val raw = raw0.trim()
        if (UUID_RE.matches(raw)) return raw.lowercase()
        if (raw.isNotEmpty() && raw.length <= 30) return uuid5Nil(raw) // Xray maps short ids with UUIDv5(nil, id)
        return null
    }

    /** RFC 4122 v5 (SHA-1) with the nil namespace, matching Xray's short-id mapping. */
    private fun uuid5Nil(name: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        md.update(ByteArray(16)) // nil namespace
        val h = md.digest(name.toByteArray(Charsets.UTF_8))
        h[6] = ((h[6].toInt() and 0x0f) or 0x50).toByte()
        h[8] = ((h[8].toInt() and 0x3f) or 0x80).toByte()
        val hex = h.take(16).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20, 32)}"
    }

    private fun sha1Hex(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    /** TLS block. Returns null for plain; throws Unsupported for xtls / bad reality. */
    private fun tls(q: Map<String, String>, server: String, security0: String, transportType: String?): JsonObject? {
        val security = security0.lowercase()
        if (security in setOf("", "none", "false", "0")) return null
        if (security !in setOf("tls", "reality", "xtls")) throw Unsupported()
        var sni = first(q["sni"] ?: q["peer"] ?: q["servername"]).ifEmpty { first(q["host"]) }
        if (sni.isEmpty() && !isIp(server)) sni = server
        val b = LinkedHashMap<String, JsonElement>()
        b["enabled"] = JsonPrimitive(true)
        if (sni.isNotEmpty()) b["server_name"] = JsonPrimitive(sni)
        if (truthy(q["allowinsecure"]) || truthy(q["insecure"]) || truthy(q["allow_insecure"]) || truthy(q["skip-cert-verify"])) {
            b["insecure"] = JsonPrimitive(true)
        }
        var alpn = (q["alpn"] ?: "").split(",").map { it.trim() }.filter { it in setOf("h2", "http/1.1", "h3") }
        if (transportType == "ws" || transportType == "httpupgrade") alpn = alpn.filter { it == "http/1.1" }
        if (alpn.isNotEmpty()) b["alpn"] = buildJsonArray { alpn.forEach { add(it) } }
        val fp = (q["fp"] ?: "").lowercase()
        if (fp in UTLS) b["utls"] = buildJsonObject { put("enabled", true); put("fingerprint", fp) }
        if (security == "reality") {
            val pbk = q["pbk"] ?: q["publickey"] ?: ""
            val sid = q["sid"] ?: ""
            if (pbk.isEmpty() || sid.length > 16 || !HEX_RE.matches(sid)) throw Unsupported()
            if (runCatching { b64(pbk).size }.getOrDefault(0) != 32) throw Unsupported()
            b.remove("insecure")
            b["reality"] = buildJsonObject { put("enabled", true); put("public_key", pbk); put("short_id", sid) }
            if (!b.containsKey("utls")) b["utls"] = buildJsonObject { put("enabled", true); put("fingerprint", "chrome") }
        }
        return JsonObject(b)
    }

    /** Transport block. Returns null for raw TCP; throws Unsupported for kcp/quic/xhttp/http-header. */
    private fun transport(net0: String, q: Map<String, String>): JsonObject? {
        val net = net0.lowercase().ifEmpty { "tcp" }
        val header = (q["headertype"] ?: q["type_header"] ?: "").lowercase()
        val host = first(q["host"])
        val path = q["path"].takeUnless { it.isNullOrEmpty() } ?: "/"
        when (net) {
            "tcp", "raw", "none", "" -> { if (header == "http") throw Unsupported(); return null }
            "ws" -> return buildJsonObject {
                var p = path
                val m = Regex("[?&]ed=(\\d+)").find(path)
                put("type", "ws")
                if (m != null) {
                    p = path.replace(Regex("[?&]ed=\\d+"), "").ifEmpty { "/" }
                    put("path", p)
                    put("max_early_data", m.groupValues[1].toInt())
                    put("early_data_header_name", "Sec-WebSocket-Protocol")
                } else {
                    put("path", p)
                }
                if (host.isNotEmpty()) putJsonObject("headers") { put("Host", host) }
            }
            "httpupgrade" -> return buildJsonObject {
                put("type", "httpupgrade"); put("path", path)
                if (host.isNotEmpty()) put("host", host)
            }
            "grpc" -> return buildJsonObject {
                put("type", "grpc")
                put("service_name", (q["servicename"] ?: q["path"] ?: "").trim('/'))
            }
            "http", "h2" -> return buildJsonObject {
                put("type", "http"); put("path", path)
                val hosts = (q["host"] ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }
                if (hosts.isNotEmpty()) putJsonArray("host") { hosts.forEach { add(it) } }
            }
            else -> throw Unsupported()
        }
    }

    private fun simpleTls(q: Map<String, String>, server: String, defaultAlpn: List<String> = emptyList()): JsonObject =
        buildJsonObject {
            put("enabled", true)
            val sni = first(q["sni"] ?: q["peer"] ?: q["servername"])
            if (sni.isNotEmpty() || !isIp(server)) put("server_name", sni.ifEmpty { server })
            if (listOf("insecure", "allowinsecure", "allow_insecure", "skip-cert-verify").any { truthy(q[it]) }) put("insecure", true)
            val alpn = (q["alpn"] ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { defaultAlpn }
            if (alpn.isNotEmpty()) putJsonArray("alpn") { alpn.forEach { add(it) } }
        }

    private data class P(val protocol: String, val kind: String, val config: JsonObject)

    // ------------------------------------------------------------------ parsers

    private fun vless(link: String): P? {
        val u = Uri.parse(link)
        val server = host(u.host) ?: return null
        val p = port(u.port.toString().takeIf { u.port > 0 } ?: "") ?: return null
        val uid = uuidNorm(dec(u.userInfo ?: "")) ?: return null
        val q = query(u)
        if ((q["encryption"] ?: "none").lowercase() !in setOf("none", "")) return null
        val transport = transport(q["type"] ?: "tcp", q)
        val tls = tls(q, server, q["security"] ?: "", transport?.get("type")?.jp())
        val cfg = LinkedHashMap<String, JsonElement>()
        cfg["type"] = JsonPrimitive("vless"); cfg["server"] = JsonPrimitive(server)
        cfg["server_port"] = JsonPrimitive(p); cfg["uuid"] = JsonPrimitive(uid)
        cfg["packet_encoding"] = JsonPrimitive("xudp")
        val flow = (q["flow"] ?: "").lowercase().removeSuffix("-udp443")
        if (flow == "xtls-rprx-vision") {
            if (tls == null || transport != null) return null
            cfg["flow"] = JsonPrimitive(flow)
        } else if (flow.isNotEmpty()) return null
        if (tls != null) cfg["tls"] = tls
        if (transport != null) cfg["transport"] = transport
        return P("vless", "outbound", JsonObject(cfg))
    }

    private fun trojan(link: String): P? {
        val u = Uri.parse(link)
        val server = host(u.host) ?: return null
        val p = port(u.port.toString().takeIf { u.port > 0 } ?: "") ?: return null
        val pw = dec(u.userInfo ?: "")
        if (pw.isEmpty() || pw.length > 128) return null
        val q = query(u)
        val transport = transport(q["type"] ?: "tcp", q)
        val tls = tls(q, server, (q["security"] ?: "").ifEmpty { "tls" }, transport?.get("type")?.jp())
        val cfg = LinkedHashMap<String, JsonElement>()
        cfg["type"] = JsonPrimitive("trojan"); cfg["server"] = JsonPrimitive(server)
        cfg["server_port"] = JsonPrimitive(p); cfg["password"] = JsonPrimitive(pw)
        if (tls != null) cfg["tls"] = tls
        if (transport != null) cfg["transport"] = transport
        return P("trojan", "outbound", JsonObject(cfg))
    }

    private fun vmess(link: String): P? {
        val body = link.removePrefix("vmess://").substringBefore("#")
        val d = runCatching {
            val txt = String(b64(body), Charsets.UTF_8)
            json.parseToJsonElement(txt).let { it as? JsonObject }
        }.getOrNull()
        val m: Map<String, String> = if (d != null) {
            d.mapValues { (_, v) -> (v as? JsonPrimitive)?.content ?: "" }.mapKeys { it.key.lowercase() }
        } else {
            val u = Uri.parse(link)
            val q = query(u)
            mapOf(
                "add" to (u.host ?: ""), "port" to (if (u.port > 0) u.port.toString() else ""),
                "id" to dec(u.userInfo ?: ""), "net" to (q["type"] ?: "tcp"), "tls" to (q["security"] ?: ""),
                "host" to (q["host"] ?: ""), "path" to (q["path"] ?: ""), "sni" to (q["sni"] ?: ""),
                "type" to (q["headertype"] ?: ""), "scy" to (q["encryption"] ?: "auto"), "fp" to (q["fp"] ?: ""),
                "alpn" to (q["alpn"] ?: ""), "allowinsecure" to (q["allowinsecure"] ?: ""),
            )
        }
        val server = host(m["add"]) ?: return null
        val p = port(m["port"]) ?: return null
        val uid = uuidNorm(m["id"] ?: "") ?: return null
        val q = mapOf(
            "host" to (m["host"] ?: ""), "path" to (m["path"] ?: ""), "headertype" to (m["type"] ?: ""),
            "servicename" to (m["servicename"] ?: m["path"] ?: ""), "sni" to (m["sni"] ?: ""),
            "alpn" to (m["alpn"] ?: ""), "fp" to (m["fp"] ?: ""),
            "allowinsecure" to (m["allowinsecure"] ?: m["insecure"] ?: m["skip-cert-verify"] ?: ""),
        )
        val transport = transport((m["net"] ?: "tcp"), q)
        val tls = tls(q, server, m["tls"] ?: "", transport?.get("type")?.jp())
        if (tls != null && tls.containsKey("reality")) return null
        var scy = (m["scy"] ?: "auto").lowercase()
        if (scy !in VMESS_SECURITY) scy = "auto"
        val aid = (m["aid"] ?: "0").toIntOrNull()?.coerceIn(0, 65535) ?: 0
        val cfg = LinkedHashMap<String, JsonElement>()
        cfg["type"] = JsonPrimitive("vmess"); cfg["server"] = JsonPrimitive(server)
        cfg["server_port"] = JsonPrimitive(p); cfg["uuid"] = JsonPrimitive(uid)
        cfg["security"] = JsonPrimitive(scy); cfg["alter_id"] = JsonPrimitive(aid)
        cfg["packet_encoding"] = JsonPrimitive("xudp")
        if (tls != null) cfg["tls"] = tls
        if (transport != null) cfg["transport"] = transport
        return P("vmess", "outbound", JsonObject(cfg))
    }

    private fun hysteria2(link0: String): P? {
        var link = if (link0.lowercase().startsWith("hy2://")) "hysteria2://" + link0.substring(6) else link0
        var hop: String? = null
        val m = Regex("^(hysteria2://[^@/?#]*@[^:/?#\\[\\]]+|hysteria2://[^@/?#]*@\\[[^\\]]+\\]):(\\d+)-(\\d+)(.*)$", RegexOption.IGNORE_CASE).find(link)
        if (m != null) {
            hop = "${m.groupValues[2]}:${m.groupValues[3]}"
            link = "${m.groupValues[1]}:${m.groupValues[2]}${m.groupValues[4]}"
        }
        val u = Uri.parse(link)
        val server = host(u.host) ?: return null
        val p = port(if (u.port > 0) u.port.toString() else "") ?: 443
        val pw = dec(u.userInfo ?: "")
        if (pw.isEmpty()) return null
        val q = query(u)
        val sni = first(q["sni"] ?: q["peer"])
        val tlsB = LinkedHashMap<String, JsonElement>()
        tlsB["enabled"] = JsonPrimitive(true)
        val sn = sni.ifEmpty { if (!isIp(server)) server else "" }
        if (sn.isNotEmpty()) tlsB["server_name"] = JsonPrimitive(sn)
        if (truthy(q["insecure"]) || truthy(q["allowinsecure"]) || truthy(q["allow_insecure"])) tlsB["insecure"] = JsonPrimitive(true)
        val alpn = (q["alpn"] ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (alpn.isNotEmpty()) tlsB["alpn"] = buildJsonArray { alpn.forEach { add(it) } }
        val cfg = LinkedHashMap<String, JsonElement>()
        cfg["type"] = JsonPrimitive("hysteria2"); cfg["server"] = JsonPrimitive(server)
        cfg["server_port"] = JsonPrimitive(p); cfg["password"] = JsonPrimitive(pw)
        cfg["tls"] = JsonObject(tlsB)
        val obfs = (q["obfs"] ?: "").lowercase()
        if (obfs == "salamander") {
            val op = q["obfs-password"] ?: return null
            cfg["obfs"] = buildJsonObject { put("type", "salamander"); put("password", op) }
        } else if (obfs !in setOf("", "none")) return null
        val ports = mutableListOf<String>()
        for (part0 in (q["mport"] ?: "").split(",")) {
            val part = part0.trim().replace("-", ":")
            if (Regex("^\\d+:\\d+$").matches(part)) ports += part
        }
        hop?.let { ports += it }
        if (ports.isNotEmpty()) cfg["server_ports"] = buildJsonArray { ports.toSortedSet().forEach { add(it) } }
        return P("hysteria2", "outbound", JsonObject(cfg))
    }

    private fun tuic(link: String): P? {
        val u = Uri.parse(link)
        val server = host(u.host) ?: return null
        val p = port(if (u.port > 0) u.port.toString() else "") ?: return null
        val ui = dec(u.userInfo ?: "")
        val uid = ui.substringBefore(":"); val pw = ui.substringAfter(":", "")
        if (!UUID_RE.matches(uid) || pw.isEmpty()) return null
        val q = query(u)
        val cfg = LinkedHashMap<String, JsonElement>()
        cfg["type"] = JsonPrimitive("tuic"); cfg["server"] = JsonPrimitive(server)
        cfg["server_port"] = JsonPrimitive(p); cfg["uuid"] = JsonPrimitive(uid.lowercase())
        cfg["password"] = JsonPrimitive(pw); cfg["tls"] = simpleTls(q, server, listOf("h3"))
        val cc = (q["congestion_control"] ?: q["congestion-control"] ?: "").lowercase()
        if (cc in setOf("cubic", "new_reno", "bbr")) cfg["congestion_control"] = JsonPrimitive(cc)
        val mode = (q["udp_relay_mode"] ?: q["udp-relay-mode"] ?: "").lowercase()
        if (mode in setOf("native", "quic")) cfg["udp_relay_mode"] = JsonPrimitive(mode)
        return P("tuic", "outbound", JsonObject(cfg))
    }

    private fun anytls(link: String): P? {
        val u = Uri.parse(link)
        val server = host(u.host) ?: return null
        val p = port(if (u.port > 0) u.port.toString() else "") ?: return null
        val pw = dec(u.userInfo ?: "")
        if (pw.isEmpty()) return null
        return P("anytls", "outbound", buildJsonObject {
            put("type", "anytls"); put("server", server); put("server_port", p); put("password", pw)
            put("tls", simpleTls(query(u), server))
        })
    }

    private fun ss(link: String): P? {
        var body = link.removePrefix("ss://")
        body = body.substringBefore("#")
        val query = body.substringAfter("?", "").let { qs ->
            qs.split("&").filter { it.isNotEmpty() }.associate {
                it.substringBefore("=").lowercase() to dec(it.substringAfter("=", ""))
            }
        }
        body = body.substringBefore("?").trimEnd('/')
        val userinfo: String
        val hostport: String
        if (body.contains("@")) {
            var ui = dec(body.substringBeforeLast("@"))
            hostport = body.substringAfterLast("@")
            if (!ui.contains(":")) ui = runCatching { String(b64(ui), Charsets.UTF_8) }.getOrNull() ?: return null
            userinfo = ui
        } else {
            val decoded = runCatching { String(b64(body), Charsets.UTF_8) }.getOrNull() ?: return null
            if (!decoded.contains("@")) return null
            userinfo = decoded.substringBeforeLast("@")
            hostport = decoded.substringAfterLast("@")
        }
        var method = userinfo.substringBefore(":").trim().lowercase()
        val password = userinfo.substringAfter(":", "")
        method = mapOf(
            "chacha20-poly1305" to "chacha20-ietf-poly1305",
            "xchacha20-poly1305" to "xchacha20-ietf-poly1305",
        ).getOrDefault(method, method)
        if (method !in SS_METHODS || password.isEmpty()) return null
        val m = Regex("^\\[?([^\\]]+?)\\]?:(\\d+)$").find(hostport.trim().trimEnd('/', '?')) ?: return null
        val server = host(m.groupValues[1]) ?: return null
        val p = port(m.groupValues[2]) ?: return null
        val cfg = LinkedHashMap<String, JsonElement>()
        cfg["type"] = JsonPrimitive("shadowsocks"); cfg["server"] = JsonPrimitive(server)
        cfg["server_port"] = JsonPrimitive(p); cfg["method"] = JsonPrimitive(method); cfg["password"] = JsonPrimitive(password)
        val pluginRaw = query["plugin"] ?: ""
        if (pluginRaw.isNotEmpty()) {
            val name = pluginRaw.substringBefore(";")
            val opts = pluginRaw.substringAfter(";", "")
            when (name) {
                "obfs-local", "simple-obfs" -> { cfg["plugin"] = JsonPrimitive("obfs-local"); cfg["plugin_opts"] = JsonPrimitive(opts) }
                "v2ray-plugin" -> { cfg["plugin"] = JsonPrimitive("v2ray-plugin"); cfg["plugin_opts"] = JsonPrimitive(opts) }
                else -> return null
            }
        }
        return P("shadowsocks", "outbound", JsonObject(cfg))
    }

    private fun plainProxy(link: String, kind: String): P? {
        val u = Uri.parse(link)
        val server = host(u.host) ?: return null
        val p = port(if (u.port > 0) u.port.toString() else "") ?: return null
        if (!(u.path.isNullOrEmpty() || u.path == "/") || !u.query.isNullOrEmpty()) return null
        val ui = u.userInfo ?: ""
        val user = dec(ui.substringBefore(":")).let { if (it.lowercase() == "none") "" else it }
        val pw = dec(ui.substringAfter(":", "")).let { if (it.lowercase() == "none") "" else it }
        val cfg = LinkedHashMap<String, JsonElement>()
        if (kind == "socks") {
            cfg["type"] = JsonPrimitive("socks"); cfg["server"] = JsonPrimitive(server)
            cfg["server_port"] = JsonPrimitive(p); cfg["version"] = JsonPrimitive("5")
        } else {
            cfg["type"] = JsonPrimitive("http"); cfg["server"] = JsonPrimitive(server); cfg["server_port"] = JsonPrimitive(p)
            if (link.substringBefore("://").lowercase() == "https") {
                cfg["tls"] = buildJsonObject { put("enabled", true); put("insecure", true) }
            }
        }
        if (user.isNotEmpty()) cfg["username"] = JsonPrimitive(user)
        if (pw.isNotEmpty()) cfg["password"] = JsonPrimitive(pw)
        return P(kind, "outbound", JsonObject(cfg))
    }
}
