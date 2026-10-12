package com.vpnhub.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vpnhub.app.data.Prefs

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            if (subtitle.isNotEmpty()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDialog(
    onDismiss: () -> Unit,
    onSaved: (modeChanged: Boolean, urlChanged: Boolean) -> Unit,
) {
    var url by remember { mutableStateOf(Prefs.nodesUrl) }
    var auto by remember { mutableStateOf(Prefs.autoUpdate) }
    var size by remember { mutableStateOf(Prefs.groupSize.toString()) }
    var mode by remember { mutableStateOf(Prefs.mode) }
    var port by remember { mutableStateOf(Prefs.proxyPort.toString()) }
    var allowLan by remember { mutableStateOf(Prefs.proxyAllowLan) }
    var warpPort by remember { mutableStateOf(Prefs.warpPort) }

    var remoteDns by remember { mutableStateOf(Prefs.remoteDns) }
    var pingUrl by remember { mutableStateOf(Prefs.pingUrl) }
    var bootConnect by remember { mutableStateOf(Prefs.autoConnectBoot) }
    var ipv6 by remember { mutableStateOf(Prefs.ipv6Enable) }
    var ipv6Pref by remember { mutableStateOf(Prefs.ipv6Prefer) }
    var bypassLan by remember { mutableStateOf(Prefs.bypassLan) }
    var httpProxyInVpn by remember { mutableStateOf(Prefs.httpProxyInVpn) }
    var sniffing by remember { mutableStateOf(Prefs.sniffing) }
    var routeOnly by remember { mutableStateOf(Prefs.routeOnly) }
    var fragment by remember { mutableStateOf(Prefs.fragment) }
    var hotspotShare by remember { mutableStateOf(Prefs.hotspotShare) }

    val portValue = port.toIntOrNull()
    val portValid = portValue != null && portValue in 1024..65535

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("設定") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("連線模式", style = MaterialTheme.typography.labelLarge)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = mode == Prefs.MODE_VPN,
                        onClick = { mode = Prefs.MODE_VPN },
                        shape = SegmentedButtonDefaults.itemShape(0, 2),
                    ) { Text("VPN 模式") }
                    SegmentedButton(
                        selected = mode == Prefs.MODE_PROXY,
                        onClick = { mode = Prefs.MODE_PROXY },
                        shape = SegmentedButtonDefaults.itemShape(1, 2),
                    ) { Text("代理模式") }
                }
                Text(
                    if (mode == Prefs.MODE_VPN) {
                        "全部 App 經 VPN 連出去。"
                    } else {
                        "唔開 VPN，只開一個 SOCKS5／HTTP 代理端口，畀其他 App（例如 AdGuard）設定使用。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                Text("Cloudflare WARP 連接埠（UDP）", style = MaterialTheme.typography.labelLarge)
                Text(
                    "WARP 連唔到時試吓換端口。部分網絡會截 2408，但放行 500／4500／1701。",
                    style = MaterialTheme.typography.bodySmall,
                )
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    val ports = listOf(2408, 500, 4500, 1701)
                    ports.forEachIndexed { i, pnum ->
                        SegmentedButton(
                            selected = warpPort == pnum,
                            onClick = { warpPort = pnum },
                            shape = SegmentedButtonDefaults.itemShape(i, ports.size),
                        ) { Text("$pnum") }
                    }
                }
                HorizontalDivider()
                if (mode == Prefs.MODE_PROXY) {
                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it.filter(Char::isDigit).take(5) },
                        label = { Text("代理端口（預設 10808）") },
                        isError = !portValid,
                        supportingText = { if (!portValid) Text("請輸入 1024 至 65535") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("容許同一網絡嘅其他裝置連線")
                            Text(
                                "例如經熱點分享畀電腦用。開咗之後同一 Wi-Fi 嘅人都用得，冇密碼保護。",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(checked = allowLan, onCheckedChange = { allowLan = it })
                    }
                    Text(
                        "AdGuard 設定（大約位置）：設定 → 過濾 → 網絡 → 代理 → 新增代理伺服器：類型 SOCKS5，" +
                            "主機 127.0.0.1，端口 ${portValue ?: 10808}。並喺 AdGuard 嘅 App 管理入面，" +
                            "將 VPN Hub 設為唔經 AdGuard，避免流量兜圈。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    HorizontalDivider()
                }

                Text("遠端 DNS", style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(
                    value = remoteDns,
                    onValueChange = { remoteDns = it },
                    label = { Text("DNS 伺服器") },
                    supportingText = { Text("支援 udp:// tcp:// tls:// https:// quic:// h3://，預設 quic://dns.adguard-dns.com/dns-query") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                OutlinedTextField(
                    value = pingUrl,
                    onValueChange = { pingUrl = it },
                    label = { Text("真連接延遲測試 URL") },
                    supportingText = { Text("自動揀最快節點時，經每個節點實測呢條 URL 嘅延遲。預設 Microsoft generate_204。") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                HorizontalDivider()

                Text("進階", style = MaterialTheme.typography.labelLarge)
                ToggleRow("開機後自動連線", "重啟手機後自動接返上次嘅 VPN（要事先批准過 VPN 權限）", bootConnect) { bootConnect = it }
                ToggleRow("啟用 IPv6", "容許 IPv6 經 VPN 出去", ipv6) { ipv6 = it }
                ToggleRow("IPv6 優先", "同時有 IPv4／IPv6 時優先用 IPv6（要先啟用 IPv6）", ipv6Pref) { ipv6Pref = it }
                ToggleRow("VPN 繞過區域網路", "區域網（LAN）流量唔經 VPN，直接連", bypassLan) { bypassLan = it }
                ToggleRow("VPN 模式下開放 HTTP／SOCKS 代理", "開 VPN 嘅同時都開一個本機代理端口（端口 ${portValue ?: 10808}）畀其他 App 用", httpProxyInVpn) { httpProxyInVpn = it }
                ToggleRow("與熱點裝置共用 VPN", "上面個代理端口綁定所有介面，畀經你熱點連線嘅裝置都用到（冇密碼保護）", hotspotShare) { hotspotShare = it }
                ToggleRow("流量探測 (Sniffing)", "辨識連線嘅域名／協定，分流同規則先生效", sniffing) { sniffing = it }
                ToggleRow("Route Only", "只用 sniff 到嘅域名做路由，唔喺本機解析（關咗會用遠端 DNS 解析）", routeOnly) { routeOnly = it }
                ToggleRow("Fragment（TLS 分片）", "分拆 TLS ClientHello，繞過部分按 SNI 嘅封鎖；個別節點可能唔work", fragment) { fragment = it }
                HorizontalDivider()

                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("節點清單網址（nodes.json）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                OutlinedTextField(
                    value = size,
                    onValueChange = { size = it.filter(Char::isDigit).take(3) },
                    label = { Text("自動模式用最快幾多個節點") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("每小時自動更新節點", Modifier.weight(1f))
                    Switch(checked = auto, onCheckedChange = { auto = it })
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = mode != Prefs.MODE_PROXY || portValid,
                onClick = {
                    val urlChanged = url.trim() != Prefs.nodesUrl
                    val modeChanged = mode != Prefs.mode
                    val warpChanged = warpPort != Prefs.warpPort
                    val netChanged = remoteDns.trim() != Prefs.remoteDns || pingUrl.trim() != Prefs.pingUrl ||
                        ipv6 != Prefs.ipv6Enable || ipv6Pref != Prefs.ipv6Prefer || bypassLan != Prefs.bypassLan ||
                        httpProxyInVpn != Prefs.httpProxyInVpn || hotspotShare != Prefs.hotspotShare ||
                        sniffing != Prefs.sniffing || routeOnly != Prefs.routeOnly || fragment != Prefs.fragment ||
                        (portValid && portValue != Prefs.proxyPort)
                    Prefs.nodesUrl = url
                    Prefs.autoUpdate = auto
                    size.toIntOrNull()?.let { Prefs.groupSize = it }
                    Prefs.mode = mode
                    if (portValid) Prefs.proxyPort = portValue!!
                    Prefs.proxyAllowLan = allowLan
                    Prefs.warpPort = warpPort
                    Prefs.remoteDns = remoteDns
                    Prefs.pingUrl = pingUrl
                    Prefs.autoConnectBoot = bootConnect
                    Prefs.ipv6Enable = ipv6
                    Prefs.ipv6Prefer = ipv6Pref
                    Prefs.bypassLan = bypassLan
                    Prefs.httpProxyInVpn = httpProxyInVpn
                    Prefs.hotspotShare = hotspotShare
                    Prefs.sniffing = sniffing
                    Prefs.routeOnly = routeOnly
                    Prefs.fragment = fragment
                    onDismiss()
                    onSaved(modeChanged || warpChanged || netChanged, urlChanged)
                },
            ) { Text("儲存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
