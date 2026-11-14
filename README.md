# RingCall · 网页打电话 → 安卓手机响铃接听

**简体中文** | [English](README.en.md)

一个自建的语音通话小系统：**网页拨号 → 安卓 App 响铃（静音也响）→ 手机接听 → 双向语音 → 任一方挂断，另一方看到提示**。

```
浏览器(网页)  ──wss──►  信令服务器(Node)  ◄──wss──  安卓 App
      └──────── WebRTC 音频（P2P 直连，走不通时回落 TURN 中继） ────────┘
```

## 特性

- **静音也响铃**：走 `USAGE_ALARM` / `STREAM_ALARM`（与系统闹钟同通道），静音/振动模式下依旧响；闹钟音量过低会自动临时提升，响铃结束还原
- **自定义铃声**：SAF 选文件夹（支持一层子目录），列出音频文件点选即设为来电铃声，可试听
- **来电全屏 + 锁屏亮屏**：全屏 Intent 通知 + 悬浮窗权限 + 兜底接听入口
- **常驻待机**：前台服务（`dataSync|microphone`）+ BootReceiver / systemd 自启
- **访问口令**：网页与 App 均须带 `?key=`，服务端校验，HTTP 401 / WS `close(4001)`
- **可观测**：网页端有电平条 + `接收 xxKB/xx 包` 统计，便于区分「播放被浏览器拦掉」和「手机麦克风无声」
- **TURN 中继**：coturn 兜底，NAT 打洞失败也能通

## 目录结构

```
android/            安卓工程（纯 Java，无需 Gradle，build.sh 手工出包）
  src/com/ringcall/app/
    MainActivity.java     设置页：服务器/口令/设备ID、选铃声文件夹、权限、日志
    CallActivity.java     来电与通话界面（锁屏弹出、接听/挂断/静音/免提）
    CallService.java      前台服务：WebSocket 长连接、响铃、通话状态机
    WsClient.java         OkHttp WebSocket（接受自签证书）
    Rtc.java              WebRTC 音频（含 TURN）
    RingPlayer.java       闹钟通道响铃 + 震动 + 音量提升
    Prefs.java / RingApp.java / BootReceiver.java
  res/  AndroidManifest.xml  build.sh
server/             信令服务器 + 网页
  server.js           HTTP 静态 + WebSocket 信令 + 口令校验
  public/index.html   拨号页面（WebAudio 输出、电平表、诊断）
  test-signaling.js   协议自测（14 项）
docs/               部署文档
```

## 快速开始

### 1. 部署服务器

```bash
cd server
npm install
PORT=8080 AUTH_KEY=你的口令 HOST=127.0.0.1 node server.js
```

- 生产环境前面套 nginx 或 caddy 开 HTTPS（`/ws` 必须转发 `Upgrade` / `Connection`）
- 浏览器只在 **https 或 localhost** 下给麦克风权限，必须上 HTTPS
- `AUTH_KEY` 留空则不校验口令

nginx 关键配置见 [docs/DEPLOY.md](docs/DEPLOY.md)。

### 2. 构建 APK

```bash
cd android/libs                      # 按 libs/README.md 下载 WebRTC aar + okhttp/okio
cd .. && ./build.sh                  # 需要 JDK17 + Android SDK 34
# 产物 android/out/RingCall.apk
```

App 默认服务器地址与口令在 `android/res/values/strings.xml` 里改：

```xml
<string name="default_server">https://你的域名</string>
<string name="default_key">你的口令</string>
```

### 3. 使用

1. 装 APK，权限全给（通知/麦克风、**悬浮窗**、忽略电池优化），部分国产 ROM 还要开自启动/锁屏显示
2. 「选择文件夹」设铃声 → 点「开启待机」
3. 浏览器打开 `https://你的域名/?from=张三&key=你的口令`，点「拨打」

`from` 即来电人名字（也支持 `?name=`），`device` 可选，默认 `default`。

## 协议

| 方向 | 消息 | 说明 |
|---|---|---|
| 网页→服务器 | `call{from,device}` | 发起呼叫，回 `calling{callId}` |
| 服务器→安卓 | `incoming{callId,from}` | 手机响铃，`from` 为来电人名 |
| 安卓→服务器 | `answer{callId}` | 接听 → 网页收到 `answered` |
| 双向 | `signal{callId,payload}` | 转发 SDP / ICE |
| 安卓→服务器 | `hangup{callId}` | 网页收到 `peer-hangup{by:"android"}` → 显示「对方已挂掉电话」 |
| 安卓→服务器 | `reject{callId}` | 网页收到 `rejected` |
| 网页→服务器 | `cancel` / `hangup` | 手机端收到 `cancelled` / `peer-hangup` |

`GET /api/status?key=...` 可查看在线设备与进行中的通话。

## 自测

```bash
cd server && node test-signaling.js     # 先启动服务器（默认连 ws://127.0.0.1:19090）
```

覆盖：握手、拨号、接听、SDP/ICE 双向转发、挂断通知、取消、设备离线共 14 项。

## 已知限制

1. 音频默认 P2P，对称 NAT 下需 TURN（`Rtc.java` 与 `server.js` 的 `ICE_SERVERS` 替换成自己的地址与账号）
2. App 需保持前台服务运行，省电策略强的机型建议锁后台
3. 除口令外没有用户体系，公网部署建议再加频率限制/白名单
4. 自签证书场景浏览器会提示一次证书警告（App 端已信任自签）

## License

MIT
