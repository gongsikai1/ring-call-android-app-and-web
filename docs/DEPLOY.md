# 部署说明

以 Ubuntu + nginx + systemd 为例，把占位值换成自己的（`<KEY>` 访问口令、`<DOMAIN>` 域名、`<TURN_PASS>` 中继密码）。

## 1. 信令服务（systemd 常驻 + 开机自启）

```bash
mkdir -p /opt/ringcall && cd /opt/ringcall
# 放入 server.js、package.json、public/
npm install --omit=dev
```

`/etc/systemd/system/ringcall.service`：

```ini
[Unit]
Description=RingCall WebRTC signaling server
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
WorkingDirectory=/opt/ringcall
ExecStart=/usr/bin/node /opt/ringcall/server.js
Environment=PORT=8080
Environment=HOST=127.0.0.1
Environment=AUTH_KEY=<KEY>
Restart=always
RestartSec=3
StandardOutput=append:/var/log/ringcall.log
StandardError=append:/var/log/ringcall.err

[Install]
WantedBy=multi-user.target
```

```bash
systemctl daemon-reload && systemctl enable --now ringcall
```

## 2. nginx + HTTPS（WebSocket 必须转发 Upgrade）

```nginx
map $http_upgrade $rc_upgrade {
    default upgrade;
    ''      close;
}

server {
    listen 80;
    server_name <DOMAIN>;
    # Let's Encrypt 校验目录（不能被 301 跳走）
    location /.well-known/acme-challenge/ { root /var/www/ringcall; }
    location / { return 301 https://$host$request_uri; }
}

server {
    listen 443 ssl;
    http2 on;
    server_name <DOMAIN>;

    ssl_certificate     /etc/letsencrypt/live/<DOMAIN>/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/<DOMAIN>/privkey.pem;

    location / {
        proxy_pass         http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header   Upgrade $http_upgrade;
        proxy_set_header   Connection $rc_upgrade;
        proxy_set_header   Host $host;
        proxy_read_timeout 3600s;
        proxy_send_timeout 3600s;
        proxy_buffering    off;
    }
}
```

证书：

```bash
certbot certonly --webroot -w /var/www/ringcall -d <DOMAIN> --agree-tos -m <EMAIL>
```

> 只有 IP 没有域名时只能用自签证书，浏览器会警告一次（点「继续前往」即可）。
> 国内机房若域名未备案，80 端口回连可能被重置，导致 HTTP-01 校验失败。

## 3. TURN 中继（可选，NAT 严格时必需）

```bash
apt install -y coturn
```

`/etc/turnserver.conf`：

```ini
listening-port=3478
listening-ip=<内网 IP>
relay-ip=<内网 IP>
external-ip=<公网 IP>/<内网 IP>     # 云主机公网 IP 不在网卡上时必须写
relay-threads=4

fingerprint
lt-cred-mech                       # 必须鉴权，别开成匿名公共中继
user=rcuser:<TURN_PASS>
realm=ringcall
stale-nonce

min-port=49152
max-port=65500
no-multicast-peers
no-cli
total-quota=100
max-bps=1000000
```

```bash
sed -i 's/#TURNSERVER_ENABLED=1/TURNSERVER_ENABLED=1/' /etc/default/coturn
systemctl enable --now coturn
```

安全组/防火墙需放行：`3478 udp+tcp`、`49152-65500 udp`。

自测：`turnutils_stunclient -p 3478 <公网IP>`，或从外部发一个 STUN Binding 请求应收到 `0x0101` 响应。

把地址与账号填进两处：

- `server.js` 的 `ICE_SERVERS`
- `android/src/com/ringcall/app/Rtc.java` 的 `ICE_URLS` / `TURN_USER` / `TURN_PASS`

## 4. 验收清单

| 检查 | 命令 / 现象 |
|---|---|
| 服务常驻 | `systemctl is-enabled --now ringcall` → enabled / active |
| 口令生效 | 无 key 请求 `https://<DOMAIN>/` 返回 401 |
| 长连接 | `wss://<DOMAIN>/ws?...&key=<KEY>` 能收到 `hello` |
| 信令全流程 | `node server/test-signaling.js` 14/14 通过 |
| 响铃 | 手机静音状态下拨号，仍能听到自定义铃声 |
| 挂断提示 | 手机挂断 → 网页显示「对方已挂掉电话」 |
