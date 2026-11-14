/**
 * RingCall 信令服务器
 * ------------------------------------------------------------
 * 职责：
 *  1. 提供静态网页（打电话的页面）
 *  2. 维护 Android 端(role=android) 与 网页端(role=web) 的长连接
 *  3. 转发呼叫 / 接听 / 挂断 / WebRTC SDP & ICE 信令
 *
 * 协议（全部为 JSON 文本帧）：
 *  网页 -> 服务器 : call / cancel / hangup / signal
 *  安卓 -> 服务器 : answer / reject / hangup / signal / pong
 *  服务器 -> 网页 : calling / ringing / answered / peer-hangup / rejected / unavailable / signal
 *  服务器 -> 安卓 : incoming / cancelled / peer-hangup / signal
 */
const http = require('http');
const fs = require('fs');
const path = require('path');
const url = require('url');
const { WebSocketServer, WebSocket } = require('ws');

const PORT = parseInt(process.env.PORT || '8080', 10);
const HOST = process.env.HOST || '0.0.0.0';
// 访问口令：设置后网页和安卓端都必须带 ?key= 才能连；留空则不校验
const AUTH_KEY = (process.env.AUTH_KEY || '').trim();
const PUBLIC_DIR = path.join(__dirname, 'public');

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'application/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.png': 'image/png',
  '.svg': 'image/svg+xml',
  '.ico': 'image/x-icon',
  '.json': 'application/json; charset=utf-8'
};

function keyOk(query) {
  if (!AUTH_KEY) return true;
  return (query && query.key) === AUTH_KEY;
}

function denyPage(res, code, msg) {
  res.writeHead(code, { 'content-type': 'text/html; charset=utf-8', 'cache-control': 'no-cache' });
  res.end(`<!DOCTYPE html><html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>RingCall</title>
<style>body{margin:0;height:100vh;display:flex;align-items:center;justify-content:center;
background:#0b1117;color:#e8edf3;font-family:-apple-system,"PingFang SC",sans-serif;text-align:center;padding:24px}
h2{font-size:19px;margin:0 0 10px}p{color:#8fa3b8;font-size:13.5px;line-height:1.8;margin:0}</style></head>
<body><div><h2>${msg}</h2><p>请在链接后面加上 <b>?key=你的访问口令</b><br>例如 /?from=张三&amp;key=xxxx</p></div></body></html>`);
}

const server = http.createServer((req, res) => {
  const parsed = url.parse(req.url, true);
  let pathname = parsed.pathname || '/';

  if (!keyOk(parsed.query)) {
    denyPage(res, 401, '缺少或错误的访问口令');
    return;
  }

  if (pathname === '/api/status') {
    const body = JSON.stringify({
      ok: true,
      devices: [...devices.keys()],
      calls: [...calls.values()].map((c) => ({ id: c.id, from: c.from, state: c.state }))
    });
    res.writeHead(200, { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-cache' });
    return res.end(body);
  }

  if (pathname === '/' || pathname === '/index.html') pathname = '/index.html';
  const file = path.join(PUBLIC_DIR, path.normalize(pathname).replace(/^\/+/, ''));
  if (!file.startsWith(PUBLIC_DIR)) {
    res.writeHead(403).end('forbidden');
    return;
  }
  fs.readFile(file, (err, data) => {
    if (err) {
      res.writeHead(404, { 'content-type': 'text/plain; charset=utf-8' }).end('not found');
      return;
    }
    res.writeHead(200, {
      'content-type': MIME[path.extname(file)] || 'application/octet-stream',
      'cache-control': 'no-cache'
    });
    res.end(data);
  });
});

const wss = new WebSocketServer({ server, path: '/ws' });

/** 设备 id -> 安卓端 socket（同一 id 后来者覆盖） */
const devices = new Map();
/** callId -> { id, from, deviceId, caller, callee, state } */
const calls = new Map();

function send(ws, obj) {
  if (ws && ws.readyState === WebSocket.OPEN) {
    try {
      ws.send(JSON.stringify(obj));
    } catch (e) {
      console.error('send error', e);
    }
  }
}

function newId() {
  return Date.now().toString(36) + Math.random().toString(36).slice(2, 8);
}

function cleanupCall(callId, reason) {
  const c = calls.get(callId);
  if (!c) return;
  c.state = 'closed';
  calls.delete(callId);
  if (reason) console.log(`[call ${callId}] closed: ${reason}`);
}

wss.on('connection', (ws, req) => {
  const q = url.parse(req.url, true).query;
  const role = (q.role || 'web').toString();
  const deviceId = (q.device || 'default').toString();
  if (!keyOk(q)) {
    console.log(`[${role}] rejected: bad key (${q.key || 'none'})`);
    send(ws, { type: 'unauthorized', message: '访问口令错误' });
    ws.close(4001, 'unauthorized');
    return;
  }

  ws.role = role;
  ws.deviceId = deviceId;
  ws.alive = true;

  if (role === 'android') {
    const old = devices.get(deviceId);
    if (old && old !== ws) {
      // 同一个设备重连（比如进程重启），保留旧连接直到新连接就绪
      try { old.close(); } catch (e) {}
    }
    devices.set(deviceId, ws);
    console.log(`[android] connected device=${deviceId} total=${devices.size}`);
    send(ws, { type: 'hello', role: 'android', device: deviceId, serverTime: Date.now() });
  } else {
    console.log(`[web] connected from=${q.from || ''} device=${deviceId}`);
    send(ws, {
      type: 'hello',
      role: 'web',
      from: q.from || '',
      device: deviceId,
      online: devices.has(deviceId) || devices.size > 0,
      iceServers: ICE_SERVERS
    });
  }

  ws.on('pong', () => { ws.alive = true; });

  ws.on('message', (buf) => {
    let msg;
    try { msg = JSON.parse(buf.toString()); } catch (e) { return; }
    if (!msg || !msg.type) return;

    // ---- 网页端发起呼叫 ----
    if (msg.type === 'call' && role === 'web') {
      const targetDevice = (msg.device || ws.deviceId || 'default').toString();
      let target = devices.get(targetDevice);
      if (!target) target = [...devices.values()].find((s) => s.readyState === WebSocket.OPEN);
      if (!target || target.readyState !== WebSocket.OPEN) {
        send(ws, { type: 'unavailable', message: '手机端未连接' });
        return;
      }
      const callId = newId();
      const from = (msg.from || '未知来电').toString().slice(0, 40);
      calls.set(callId, { id: callId, from, deviceId: targetDevice, caller: ws, callee: target, state: 'ringing' });
      ws.callId = callId;
      send(ws, { type: 'calling', callId });
      send(target, { type: 'incoming', callId, from });
      console.log(`[call ${callId}] from=${from}`);
      return;
    }

    // ---- 网页取消（响铃中挂断）----
    if (msg.type === 'cancel' && role === 'web') {
      const c = calls.get(msg.callId || ws.callId);
      if (c) {
        send(c.callee, { type: 'cancelled', callId: c.id });
        send(c.caller, { type: 'peer-hangup', callId: c.id, by: 'web' });
        cleanupCall(c.id, 'caller cancelled');
      }
      return;
    }

    // ---- 安卓接听 ----
    if (msg.type === 'answer' && role === 'android') {
      const c = calls.get(msg.callId);
      if (!c) { send(ws, { type: 'call-gone', callId: msg.callId }); return; }
      c.state = 'answered';
      send(c.caller, { type: 'answered', callId: c.id });
      console.log(`[call ${c.id}] answered`);
      return;
    }

    // ---- 安卓拒接 ----
    if (msg.type === 'reject' && role === 'android') {
      const c = calls.get(msg.callId);
      if (c) {
        send(c.caller, { type: 'rejected', callId: c.id });
        cleanupCall(c.id, 'callee rejected');
      }
      return;
    }

    // ---- 挂断（双向）----
    if (msg.type === 'hangup') {
      const c = calls.get(msg.callId || ws.callId);
      if (c) {
        const peer = role === 'web' ? c.callee : c.caller;
        send(peer, { type: 'peer-hangup', callId: c.id, by: role });
        send(ws, { type: 'hangup-ok', callId: c.id });
        cleanupCall(c.id, `${role} hung up`);
      } else {
        send(ws, { type: 'peer-hangup', callId: msg.callId, by: role });
      }
      return;
    }

    // ---- WebRTC 信令（SDP / ICE）----
    if (msg.type === 'signal') {
      const c = calls.get(msg.callId || ws.callId);
      if (!c) return;
      const peer = role === 'web' ? c.callee : c.caller;
      send(peer, { type: 'signal', callId: c.id, payload: msg.payload });
      return;
    }

    if (msg.type === 'ping') send(ws, { type: 'pong' });
  });

  ws.on('close', () => {
    console.log(`[${role}] disconnected device=${deviceId}`);
    if (role === 'android') {
      if (devices.get(deviceId) === ws) devices.delete(deviceId);
      // 该设备的通话全部通知网页端
      for (const c of [...calls.values()]) {
        if (c.callee === ws) {
          send(c.caller, { type: 'peer-hangup', callId: c.id, by: 'android', reason: 'device-offline' });
          cleanupCall(c.id, 'device offline');
        }
      }
    } else {
      for (const c of [...calls.values()]) {
        if (c.caller === ws) {
          send(c.callee, { type: 'cancelled', callId: c.id });
          cleanupCall(c.id, 'caller page closed');
        }
      }
    }
  });

  ws.on('error', (e) => console.error('ws error', e.message));
});

const ICE_SERVERS = [
  { urls: 'stun:your-server.example.com:3478' },
  {
    urls: ['turn:your-server.example.com:3478?transport=udp', 'turn:your-server.example.com:3478?transport=tcp'],
    username: 'rcuser',
    credential: 'YOUR_TURN_PASS'
  },
  { urls: 'stun:stun.l.google.com:19302' }
];

// 心跳，防止中间设备掐断空闲连接
setInterval(() => {
  wss.clients.forEach((ws) => {
    if (ws.alive === false) return ws.terminate();
    ws.alive = false;
    try { ws.ping(); } catch (e) {}
  });
}, 25000);

server.listen(PORT, HOST, () => {
  console.log(`RingCall signaling server listening on ${HOST}:${PORT}`);
});
