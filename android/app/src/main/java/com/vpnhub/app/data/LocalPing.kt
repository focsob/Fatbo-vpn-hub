package com.vpnhub.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetSocketAddress
import java.net.Socket

/**
 * After each list refresh (and on demand), measure the real round-trip time from THIS phone to each
 * candidate's server with a TCP handshake, and drop the ones that don't answer. The cloud test uses a
 * US runner, so its latency and liveness don't match the user's own network; this is the real local
 * latency the UI sorts by.
 */
object LocalPing {
    private val _dead = MutableStateFlow<Set<String>>(emptySet())

    /** Ids of nodes the phone could not reach in the last check. */
    val dead: StateFlow<Set<String>> = _dead

    /** Real latency (ms) measured from this phone, per node id. */
    private val _pings = MutableStateFlow<Map<String, Int>>(emptyMap())
    val pings: StateFlow<Map<String, Int>> = _pings

    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking

    /** Measured latency if we have it, otherwise the cloud-tested latency. */
    fun latency(node: Node): Int = _pings.value[node.id] ?: node.latency

    fun hostPort(node: Node): Pair<String, Int>? {
        val c = node.config ?: return null
        return try {
            when (c["type"]?.jsonPrimitive?.content) {
                "wireguard" -> {
                    val peer = c["peers"]!!.jsonArray[0].jsonObject
                    peer["address"]!!.jsonPrimitive.content to peer["port"]!!.jsonPrimitive.content.toInt()
                }
                "openvpn-client" -> {
                    val server = c["server"]?.jsonPrimitive?.content
                    if (server != null) {
                        server to c["server_port"]!!.jsonPrimitive.content.toInt()
                    } else {
                        val s = c["servers"]!!.jsonArray[0].jsonObject
                        s["server"]!!.jsonPrimitive.content to s["server_port"]!!.jsonPrimitive.content.toInt()
                    }
                }
                else -> c["server"]!!.jsonPrimitive.content to c["server_port"]!!.jsonPrimitive.content.toInt()
            }
        } catch (e: Exception) {
            null
        }
    }

    /** TCP-connect round-trip in ms, or -1 if it did not answer in time. */
    private suspend fun connectMs(host: String, port: Int, timeoutMs: Int): Int = withContext(Dispatchers.IO) {
        withTimeoutOrNull(timeoutMs + 500L) {
            runCatching {
                val s = Socket()
                val t0 = System.nanoTime()
                s.connect(InetSocketAddress(host, port), timeoutMs)
                val ms = ((System.nanoTime() - t0) / 1_000_000).toInt()
                runCatching { s.close() }
                ms.coerceAtLeast(1)
            }.getOrDefault(-1)
        } ?: -1
    }

    /**
     * Measure up to [cap] nodes (fastest first by whatever latency we already know), [concurrency] at a
     * time. Records real latency and which nodes are unreachable. Nodes not measured keep their old value.
     */
    suspend fun measure(nodes: List<Node>, cap: Int = 1000, concurrency: Int = 64, timeoutMs: Int = 2000) {
        if (nodes.isEmpty()) return
        _checking.value = true
        try {
            val targets = nodes.sortedBy { latency(it).let { l -> if (l <= 0) Int.MAX_VALUE else l } }.take(cap)
            val gate = Semaphore(concurrency)
            val dead = HashSet<String>()
            val got = HashMap<String, Int>()
            coroutineScope {
                targets.map { node ->
                    async {
                        gate.withPermit {
                            val hp = hostPort(node)
                            val ms = if (hp == null) -1 else connectMs(hp.first, hp.second, timeoutMs)
                            if (ms < 0) {
                                synchronized(dead) { dead.add(node.id) }
                            } else {
                                synchronized(got) { got[node.id] = ms }
                            }
                        }
                    }
                }.forEach { it.await() }
            }
            _pings.value = _pings.value + got
            _dead.value = dead
        } finally {
            _checking.value = false
        }
    }

    /** Backwards-compatible name used right after a list download. */
    suspend fun prune(nodes: List<Node>) = measure(nodes)

    fun clear() {
        _dead.value = emptySet()
        _pings.value = emptyMap()
    }
}
