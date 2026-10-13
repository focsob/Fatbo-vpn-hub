package com.vpnhub.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vpnhub.app.data.LinkParser
import com.vpnhub.app.data.PrivateNodes
import com.vpnhub.app.data.Protocols

@Composable
fun PrivateNodesScreen(onDismiss: () -> Unit, onChanged: () -> Unit) {
    val nodes by PrivateNodes.nodes.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("🔒 我嘅節點") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "貼自己嘅分享連結（支援 vless://、vmess://、trojan://、hysteria2://（hy2://）、tuic://、ss://、" +
                        "socks://、http://）。可以一次貼多條（每行一條），或者貼成段 base64 訂閱。你加嘅節點淨係留喺你部機，" +
                        "唔會上傳。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text("貼連結") },
                    placeholder = { Text("vless://…\nhy2://…") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
                )
                Button(
                    onClick = {
                        val found = LinkParser.extractLinks(input)
                        val added = PrivateNodes.add(input)
                        val bad = found.size - found.count { LinkParser.parse(it) != null }
                        message = when {
                            found.isEmpty() -> "搵唔到有效連結"
                            else -> "加咗 $added 個" + if (bad > 0) "，$bad 個解析唔到／唔支援" else ""
                        }
                        if (added > 0) {
                            input = ""
                            onChanged()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("加入") }
                message?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                if (nodes.isNotEmpty()) {
                    HorizontalDivider()
                    Text("已加入 ${nodes.size} 個", style = MaterialTheme.typography.labelLarge)
                    nodes.forEach { node ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(node.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    Protocols.label(node.protocol) +
                                        (node.config?.get("server")?.toString()?.trim('"')?.let { " · $it" } ?: ""),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            IconButton(onClick = {
                                node.link?.let { PrivateNodes.remove(it) }
                                onChanged()
                            }) { Icon(Icons.Filled.Close, contentDescription = "刪除") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
    )
}
