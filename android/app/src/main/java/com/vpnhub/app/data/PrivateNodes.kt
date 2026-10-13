package com.vpnhub.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The user's own nodes, parsed from the share links they pasted in (Prefs.privateLinks).
 * These are pinned, always shown, and never dropped by the cloud/local checks.
 */
object PrivateNodes {
    private val _nodes = MutableStateFlow<List<Node>>(emptyList())
    val nodes: StateFlow<List<Node>> = _nodes

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Start mirroring Prefs.privateLinks into parsed nodes. */
    fun start() {
        scope.launch {
            Prefs.privateLinks.collect { links ->
                _nodes.value = links.mapNotNull { LinkParser.parse(it) }.distinctBy { it.id }
            }
        }
    }

    /**
     * Add one or more pasted links (a single link, many lines, or a base64 subscription blob).
     * Returns how many new, valid nodes were added.
     */
    fun add(text: String): Int {
        val links = LinkParser.extractLinks(text)
        if (links.isEmpty()) return 0
        val before = Prefs.privateLinks.value
        val valid = links.filter { LinkParser.parse(it) != null }
        Prefs.setPrivateLinks(before + valid)
        return Prefs.privateLinks.value.size - before.size
    }

    fun remove(link: String) {
        Prefs.setPrivateLinks(Prefs.privateLinks.value - link)
    }
}
