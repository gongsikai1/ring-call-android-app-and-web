# RingCall · Web call → Android phone rings and answers

**English** | [简体中文](README.md)

A small self-hosted voice-calling system: **dial from a web page → the Android app rings (even in silent mode) → answer on the phone → two-way voice → either side hangs up and the other side sees the notice**.

```
Browser (web page)  ──wss──►  Signaling server (Node)  ◄──wss──  Android app
        └──────────── WebRTC audio (P2P, falls back to TURN relay) ────────────┘
```

## Features

- **Rings even in silent mode**: audio is played on `USAGE_ALARM` / `STREAM_ALARM` (the same stream as system alarms), so silent/vibrate mode still rings. If the alarm volume is too low it is temporarily raised and restored afterwards
- **Custom ringtone**: pick a folder with SAF (one level of subfolders supported), list audio files, tap to set as incoming ringtone, with preview
- **Full-screen + lock-screen incoming call**: full-screen intent notification + overlay permission + a fallback answer entry
- **Always-on standby**: foreground service (`dataSync|microphone`) with BootReceiver / systemd autostart
- **Access key**: both the web page and the app must pass `?key=`; server rejects with HTTP 401 / WS `close(4001)`
- **Observability**: the page shows a level meter and `received xxKB/xx packets` stats, so you can tell "playback blocked by the browser" from "phone mic is silent"
- **TURN relay**: coturn fallback so calls still work when NAT traversal fails

## Project layout

```
android/            Android project (plain Java, no Gradle; build.sh builds the APK by hand)
  src/com/ringcall/app/
    MainActivity.java     Settings: server/key/device ID, ringtone folder, permissions, log
    CallActivity.java     Incoming-call & in-call UI (lock screen, answer/hang up/mute/speaker)
    CallService.java      Foreground service: WebSocket connection, ringing, call state machine
    WsClient.java         OkHttp WebSocket (accepts self-signed certs)
    Rtc.java              WebRTC audio (with TURN)
    RingPlayer.java       Alarm-stream ringtone + vibration + volume boost
    Prefs.java / RingApp.java / BootReceiver.java
  res/  AndroidManifest.xml  build.sh
server/             Signaling server + web page
  server.js           Static HTTP + WebSocket signaling + access-key check
  public/index.html   Dialer page (WebAudio output, level meter, diagnostics)
  test-signaling.js   Protocol self-test (14 cases)
docs/               Deployment docs
```

## Quick start

### 1. Deploy the server

```bash
cd server
npm install
PORT=8080 AUTH_KEY=your_key HOST=127.0.0.1 node server.js
```

- Put nginx or caddy in front with HTTPS (the `/ws` route must forward `Upgrade` / `Connection`)
- Browsers only expose the microphone on **https or localhost**, so HTTPS is required
- Leave `AUTH_KEY` empty to disable the access key

See [docs/DEPLOY.md](docs/DEPLOY.md) for the nginx snippet.

### 2. Build the APK

```bash
cd android/libs                      # download WebRTC aar + okhttp/okio, see libs/README.md
cd .. && ./build.sh                  # needs JDK 17 + Android SDK 34
# output: android/out/RingCall.apk
```

Default server URL and access key live in `android/res/values/strings.xml`:

```xml
<string name="default_server">https://your-domain</string>
<string name="default_key">your_key</string>
```

### 3. Usage

1. Install the APK and grant everything (notifications/microphone, **overlay**, ignore battery optimization); some Chinese ROMs also need autostart / lock-screen display enabled
2. "Choose folder" to set the ringtone → tap "Start standby"
3. Open `https://your-domain/?from=John&key=your_key` and press "Call"

`from` is the caller's name (also accepts `?name=`); `device` is optional and defaults to `default`.

## Protocol

| Direction | Message | Meaning |
|---|---|---|
| Web → server | `call{from,device}` | Start a call, server replies `calling{callId}` |
| Server → Android | `incoming{callId,from}` | Phone rings; `from` is the caller name |
| Android → server | `answer{callId}` | Answer; the page receives `answered` |
| Both ways | `signal{callId,payload}` | Relay SDP / ICE |
| Android → server | `hangup{callId}` | Page receives `peer-hangup{by:"android"}` → shows "The other party hung up" |
| Android → server | `reject{callId}` | Page receives `rejected` |
| Web → server | `cancel` / `hangup` | Phone receives `cancelled` / `peer-hangup` |

`GET /api/status?key=...` lists online devices and active calls.

## Self-test

```bash
cd server && node test-signaling.js     # start the server first (connects to ws://127.0.0.1:19090)
```

Covers handshake, dialing, answering, SDP/ICE relay both ways, hang-up notice, cancel and device-offline — 14 cases.

## Known limitations

1. Audio is P2P by default; behind symmetric NAT you need TURN (replace the addresses and credentials in `ICE_SERVERS` in `server.js` and in `Rtc.java`)
2. The app must keep its foreground service running; enable background protection on aggressive battery-saving ROMs
3. Besides the access key there is no user system; add rate limiting / IP allow-listing before exposing it to the public internet
4. With a self-signed certificate the browser shows a one-time warning (the app already trusts self-signed certs)

## License

MIT
