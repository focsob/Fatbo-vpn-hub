# 用 Cloudflare Tunnel 做「出口（exit）」—— 封閉信任小組

呢份係俾小組入面**肯貢獻自己條線做出口**嘅成員跟嘅。做完之後，其他成員（包括喺中國嘅）喺 Fatbo VPNhub（或 iOS／Windows 嘅官方 sing-box）貼一條連結就連得到，流量會喺你呢部機出 internet。

中國成員 →〔GFW〕→ Cloudflare 邊緣（TLS，過到牆）→ 你嘅 cloudflared 通道 → 你部機嘅 sing-box → internet

---

## ⚠️ 先換走 Global API Key

Cloudflare 嘅 **Global API Key 係成個帳戶嘅萬能鎖匙**（可以改／刪你所有域名、DNS、帳戶設定）。佢一旦打咗出去（例如打入對話），就當洩漏咗，要即刻換：

Cloudflare 控制台 → 右上角頭像 → **My Profile → API Tokens →** 「Global API Key」嗰行撳 **Change / Roll**。

**呢個設定完全唔需要 Global API Key。** 下面用 `cloudflared tunnel login`（瀏覽器授權，鎖匙只留喺你部機）。

---

## 你需要

- 一個放咗落 Cloudflare 嘅域名（例如 `你的域名.pp.ua`）—— ✅ 你已經有。
- 一部**長開、喺牆外**嘅機做出口：家用電腦（Windows／macOS／Linux）或路由器最穩。手機都做得，但會食電、Android 背景限制多，唔建議做主力。

---

## 第 1 步：喺出口機裝 sing-box（做本機 server）

sing-box 下載：<https://github.com/SagerNet/sing-box/releases>（揀你 OS 嗰個）。

建立 `server.json`（VLESS + WebSocket，**唔用 TLS**，因為 TLS 由 Cloudflare 喺邊緣做）：

```json
{
  "log": { "level": "warn" },
  "inbounds": [
    {
      "type": "vless",
      "tag": "in",
      "listen": "127.0.0.1",
      "listen_port": 8080,
      "users": [
        { "uuid": "011fd24e-8c5f-42e9-b8c4-eeec3a0950e2" }
      ],
      "transport": { "type": "ws", "path": "/7e76b6633904" }
    }
  ],
  "outbounds": [ { "type": "direct", "tag": "direct" } ]
}
```

行起佢：`sing-box run -c server.json`
（Windows：`sing-box.exe run -c server.json`）

> 上面個 UUID 同 path 係我幫你隨機生成嘅，可以直接用；想自己換都得，但記得 server 同 client（下面）要一樣。

## 第 2 步：裝 cloudflared、登入

下載 cloudflared：<https://github.com/cloudflare/cloudflared/releases>

```bash
cloudflared tunnel login          # 開瀏覽器，揀你個域名授權（鎖匙只存喺本機）
cloudflared tunnel create fatbo-exit1
```

`create` 會印出一個 Tunnel ID 同一個 `xxxxxxxx.json` 憑證檔路徑，下面會用到。

## 第 3 步：cloudflared 設定（指去本機 sing-box）

建立 `~/.cloudflared/config.yml`（Windows 通常喺 `C:\Users\你\.cloudflared\`）：

```yaml
tunnel: fatbo-exit1
credentials-file: /絕對路徑/到/xxxxxxxx.json

ingress:
  - hostname: exit1.你的域名.pp.ua
    service: ws://127.0.0.1:8080
  - service: http_status:404
```

叫 Cloudflare 幫 `exit1` 開一條 DNS 指去條 tunnel：

```bash
cloudflared tunnel route dns fatbo-exit1 exit1.你的域名.pp.ua
```

行起條 tunnel：`cloudflared tunnel run fatbo-exit1`

（想開機自動行：`cloudflared service install` 裝成系統服務；sing-box 一樣整返做服務／開機啟動。）

## 第 4 步：俾成員連嘅連結

每個成員喺 **Fatbo VPNhub →「🔒 我嘅節點」** 貼呢條（iOS／Windows 用官方 sing-box app 匯入都係呢條）：

```
vless://011fd24e-8c5f-42e9-b8c4-eeec3a0950e2@exit1.你的域名.pp.ua:443?encryption=none&security=tls&sni=exit1.你的域名.pp.ua&type=ws&host=exit1.你的域名.pp.ua&path=%2F7e76b6633904#Exit1
```

把 `你的域名.pp.ua` 全部換成你真正嘅域名。連去 443、行 TLS＋WebSocket，Cloudflare 幫你收 TLS 再經 tunnel 落你部機。

---

## 想多幾位成員各自做出口

每位 host 成員重覆一次，改個名（`fatbo-exit2`、`exit2.你的域名…`）、各自生成自己嘅 UUID／path，就會多一條出口連結。大家都貼入 app，就可以喺唔同成員嘅線之間揀 —— 去中心化、冇陌生人。

## 小提醒

- **出口 IP 係嗰位 host 自己孭**：封閉信任小組大家知情先好做。
- Cloudflare 免費條款唔鍾意用佢 CDN 長時間代理大量／串流流量，日常瀏覽冇問題，但狂煲 BT／大量串流有機會被佢限。
- 手機做 host 唔穩（Doze、轉網、食電），長開家用機／路由器最實。
- REALITY 喺 CF 後面用唔到（CF 會收走 TLS），所以呢度用 VLESS-WS；如果將來想要 REALITY，就要行「直連自己 VPS」嗰條路（唔經 CF）。
