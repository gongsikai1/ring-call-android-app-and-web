// 信令协议端到端自测：模拟「安卓端」与「网页端」两条 WebSocket 连接
const WebSocket = require('ws');
const BASE = 'ws://127.0.0.1:19090/ws';

const results = [];
function check(name, ok, extra) {
  results.push({ name, ok, extra });
  console.log((ok ? 'PASS ' : 'FAIL ') + name + (extra ? ' :: ' + extra : ''));
}

function open(url) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(url);
    ws.on('open', () => resolve(ws));
    ws.on('error', reject);
  });
}

function next(ws, type, timeout = 3000) {
  return new Promise((resolve) => {
    const timer = setTimeout(() => resolve(null), timeout);
    const handler = (buf) => {
      let m;
      try { m = JSON.parse(buf.toString()); } catch (e) { return; }
      if (m.type === type) {
        clearTimeout(timer);
        ws.off('message', handler);
        resolve(m);
      }
    };
    ws.on('message', handler);
  });
}

(async () => {
  const app = await open(BASE + '?role=android&device=t1');
  const hello1 = await next(app, 'hello');
  check('安卓端握手 hello', !!hello1 && hello1.role === 'android');

  // 1) 没有手机端时？先测有手机端的情况
  const web = await open(BASE + '?role=web&from=' + encodeURIComponent('张三') + '&device=t1');
  const hello2 = await next(web, 'hello');
  check('网页端握手 hello 且带 from', !!hello2 && hello2.from === '张三');
  check('网页端拿到 iceServers', !!hello2 && Array.isArray(hello2.iceServers) && hello2.iceServers.length > 0);

  // 2) 拨号
  const incomingP = next(app, 'incoming');
  const callingP = next(web, 'calling');
  web.send(JSON.stringify({ type: 'call', from: '张三', device: 't1' }));
  const calling = await callingP;
  const incoming = await incomingP;
  check('网页端收到 calling', !!calling && calling.callId);
  check('安卓端收到 incoming 且 from=张三', !!incoming && incoming.from === '张三');
  check('两端 callId 一致', calling && incoming && calling.callId === incoming.callId);

  // 3) 接听
  const answeredP = next(web, 'answered');
  app.send(JSON.stringify({ type: 'answer', callId: incoming.callId }));
  const answered = await answeredP;
  check('接听后网页端收到 answered', !!answered && answered.callId === incoming.callId);

  // 4) SDP / ICE 双向转发
  const offerP = next(app, 'signal');
  web.send(JSON.stringify({ type: 'signal', callId: calling.callId, payload: { sdp: { type: 'offer', sdp: 'v=0 fake offer' } } }));
  const offer = await offerP;
  check('offer 转发到安卓端', !!offer && offer.payload.sdp.type === 'offer');

  const answerP = next(web, 'signal');
  app.send(JSON.stringify({ type: 'signal', callId: calling.callId, payload: { sdp: { type: 'answer', sdp: 'v=0 fake answer' } } }));
  const answer = await answerP;
  check('answer 转发到网页端', !!answer && answer.payload.sdp.type === 'answer');

  const iceP = next(web, 'signal');
  app.send(JSON.stringify({ type: 'signal', callId: calling.callId, payload: { ice: { candidate: 'candidate:1 1 UDP 1 1.2.3.4 5000 typ host', sdpMid: 'audio', sdpMLineIndex: 0 } } }));
  const ice = await iceP;
  check('ICE 转发到网页端', !!ice && ice.payload.ice && ice.payload.ice.sdpMLineIndex === 0);

  // 5) 安卓挂断 -> 网页收到 peer-hangup by=android
  const peerHangupP = next(web, 'peer-hangup');
  app.send(JSON.stringify({ type: 'hangup', callId: calling.callId }));
  const ph = await peerHangupP;
  check('手机挂断后网页收到 peer-hangup(by=android)', !!ph && ph.by === 'android');

  // 6) 重新拨号 + 网页取消
  const in2P = next(app, 'incoming');
  web.send(JSON.stringify({ type: 'call', from: '张三', device: 't1' }));
  const in2 = await in2P;
  check('可再次拨号', !!in2 && !!in2.callId);
  const cancelledP = next(app, 'cancelled');
  web.send(JSON.stringify({ type: 'cancel', callId: in2.callId }));
  const cancelled = await cancelledP;
  check('网页取消后安卓收到 cancelled', !!cancelled);

  // 7) 无手机端在线 -> unavailable
  app.close();
  await new Promise((r) => setTimeout(r, 800));
  const web2 = await open(BASE + '?role=web&from=李四&device=ghost');
  await next(web2, 'hello');
  const unavailP = next(web2, 'unavailable');
  web2.send(JSON.stringify({ type: 'call', from: '李四', device: 'ghost' }));
  const unavail = await unavailP;
  check('无设备在线时返回 unavailable', !!unavail);

  web.close();
  web2.close();
  await new Promise((r) => setTimeout(r, 300));

  const failed = results.filter((r) => !r.ok);
  console.log('\n==== 结果：' + (results.length - failed.length) + '/' + results.length + ' 通过 ====');
  process.exit(failed.length ? 1 : 0);
})().catch((e) => {
  console.error('test error', e);
  process.exit(2);
});
