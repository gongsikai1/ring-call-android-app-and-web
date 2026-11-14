package com.ringcall.app;

import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

/**
 * 常驻前台服务：维持与信令服务器的 WebSocket、负责响铃、接听、挂断和 WebRTC 通话。
 */
public class CallService extends Service implements WsClient.Listener, Rtc.Listener {
    private static final String TAG = "CallService";

    public static final String ACTION_START = "com.ringcall.START";
    public static final String ACTION_STOP = "com.ringcall.STOP";
    public static final String ACTION_ANSWER = "com.ringcall.ANSWER";
    public static final String ACTION_HANGUP = "com.ringcall.HANGUP";
    public static final String ACTION_REJECT = "com.ringcall.REJECT";
    public static final String ACTION_TOGGLE_MUTE = "com.ringcall.MUTE";
    public static final String ACTION_TOGGLE_SPEAKER = "com.ringcall.SPEAKER";

    public static final int STATE_IDLE = 0;
    public static final int STATE_RINGING = 1;
    public static final int STATE_TALKING = 2;

    // 供界面读取的静态状态
    public static volatile int state = STATE_IDLE;
    public static volatile String peerName = "";
    public static volatile long callStartAt = 0L;
    public static volatile String lastEvent = "";
    public static volatile boolean connected = false;
    public static volatile boolean micMuted = false;
    public static volatile boolean speakerOn = true;
    public static volatile String connInfo = "未连接";

    private static CallService instance;

    private static final int NOTIF_ID = 1001;
    private static final String CH_SERVICE = "ringcall_service";
    private static final String CH_CALL = "ringcall_call";

    private WsClient ws;
    private Rtc rtc;
    private final RingPlayer ring = new RingPlayer();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private String callId;
    private boolean running;
    private boolean answeredSent;
    private Runnable reconnectTask;

    public static CallService get() {
        return instance;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        createChannels();
        startForegroundCompat(buildNotification("待机中 · 等待呼叫", false));
        running = true;
        Log.i(TAG, "service created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (action == null) action = ACTION_START;
        switch (action) {
            case ACTION_STOP:
                shutdown();
                return START_NOT_STICKY;
            case ACTION_ANSWER:
                doAnswer();
                break;
            case ACTION_HANGUP:
                doHangup(false, "已挂断");
                break;
            case ACTION_REJECT:
                doReject();
                break;
            case ACTION_TOGGLE_MUTE:
                micMuted = !micMuted;
                if (rtc != null) rtc.setMicEnabled(!micMuted);
                break;
            case ACTION_TOGGLE_SPEAKER:
                speakerOn = !speakerOn;
                Prefs.setSpeaker(this, speakerOn);
                if (rtc != null) rtc.setSpeaker(speakerOn);
                else setSpeakerRaw(speakerOn);
                break;
            case ACTION_START:
            default:
                connect();
                break;
        }
        return START_STICKY;
    }

    // ------------------------------------------------------------------
    // 信令连接
    // ------------------------------------------------------------------
    private void connect() {
        if (ws == null) ws = new WsClient(this);
        if (connected && ws.isOpen()) {
            // 已经在线就不要重连，否则会把正在振铃的通话踢掉
            return;
        }
        connInfo = "连接中…";
        broadcast();
        ws.connect(Prefs.server(this), Prefs.device(this), Prefs.authKey(this));
    }

    /** 只保证服务在跑，不会打断已有连接。 */
    public static void ensureRunning(Context ctx) {
        if (instance == null) start(ctx);
    }

    private void scheduleReconnect() {
        if (!running) return;
        if (reconnectTask != null) handler.removeCallbacks(reconnectTask);
        reconnectTask = () -> {
            if (running && !connected) connect();
        };
        handler.postDelayed(reconnectTask, 3000);
    }

    @Override
    public void onWsOpen() {
        connected = true;
        connInfo = "在线 · 等待呼叫";
        broadcast();
        updateStandbyNotification();
    }

    @Override
    public void onWsClosed(String reason) {
        connected = false;
        connInfo = "连接断开：" + reason;
        broadcast();
        updateStandbyNotification();
        scheduleReconnect();
    }

    @Override
    public void onWsText(String text) {
        try {
            JSONObject m = new JSONObject(text);
            String type = m.optString("type");
            Log.i(TAG, "recv " + type);
            switch (type) {
                case "hello":
                    connected = true;
                    connInfo = "在线 · 设备 " + m.optString("device", "default");
                    broadcast();
                    updateStandbyNotification();
                    break;
                case "incoming":
                    onIncoming(m.optString("callId"), m.optString("from", "未知来电"));
                    break;
                case "cancelled":
                    if (state == STATE_RINGING) stopCall("对方已取消呼叫");
                    break;
                case "peer-hangup": {
                    String by = m.optString("by");
                    String reason = m.optString("reason");
                    String txt = "device-offline".equals(reason) ? "对方已离线" : "对方已挂掉电话";
                    if (state != STATE_IDLE) stopCall(txt);
                    break;
                }
                case "call-gone":
                    if (state != STATE_IDLE) stopCall("通话已结束");
                    break;
                case "signal":
                    if (rtc != null) rtc.handleSignal(m.optJSONObject("payload"));
                    break;
                case "hangup-ok":
                    break;
            }
        } catch (Exception e) {
            Log.e(TAG, "parse error", e);
        }
    }

    private void send(JSONObject obj) {
        if (ws != null) ws.send(obj);
    }

    // ------------------------------------------------------------------
    // 来电
    // ------------------------------------------------------------------
    private void onIncoming(String id, String from) {
        if (state != STATE_IDLE) {
            // 已有通话，先结束旧的
            stopCall("通话已结束");
        }
        callId = id;
        peerName = from;
        state = STATE_RINGING;
        callStartAt = 0;
        answeredSent = false;
        lastEvent = from + " 来电";
        String uriStr = Prefs.ringUri(this);
        Uri uri = uriStr.isEmpty() ? null : Uri.parse(uriStr);
        ring.start(this, uri, Prefs.boostVolume(this), true);
        notifyForeground(buildNotification("来电：" + from + " · 正在响铃", true));
        broadcast();
        launchCallActivity();
    }

    private void launchCallActivity() {
        try {
            Intent i = new Intent(this, CallActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP
                    | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                    | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
            startActivity(i);
            Log.i(TAG, "CallActivity launched");
        } catch (Exception e) {
            Log.w(TAG, "launch failed (需悬浮窗权限): " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // 接听 / 挂断
    // ------------------------------------------------------------------
    public void doAnswer() {
        if (state != STATE_RINGING) return;
        ring.stop();
        try {
            if (rtc == null) {
                rtc = new Rtc(this, this);
                rtc.create();
            }
            rtc.setSpeaker(Prefs.speaker(this));
            speakerOn = Prefs.speaker(this);
            rtc.setMicEnabled(!micMuted);
        } catch (Throwable t) {
            Log.e(TAG, "rtc create failed", t);
        }
        state = STATE_TALKING;
        callStartAt = System.currentTimeMillis();
        lastEvent = "通话中：" + peerName;
        JSONObject msg = new JSONObject();
        try {
            msg.put("type", "answer");
            msg.put("callId", callId);
        } catch (Exception ignored) {
        }
        send(msg);
        answeredSent = true;
        // 通话需要麦克风类型的服务
        notifyForeground(buildNotification("通话中：" + peerName, false), true);
        broadcast();
    }

    public void doReject() {
        ring.stop();
        JSONObject msg = new JSONObject();
        try {
            msg.put("type", "reject");
            msg.put("callId", callId);
        } catch (Exception ignored) {
        }
        send(msg);
        stopCall("已拒接");
    }

    public void doHangup(boolean remote, String reason) {
        stopCall(reason == null ? (remote ? "对方已挂掉电话" : "已挂断") : reason);
    }

    private void stopCall(String reason) {
        ring.stop();
        if (state == STATE_TALKING && answeredSent) {
            JSONObject msg = new JSONObject();
            try {
                msg.put("type", "hangup");
                msg.put("callId", callId);
            } catch (Exception ignored) {
            }
            send(msg);
        }
        if (rtc != null) {
            try { rtc.close(); } catch (Exception ignored) {}
            rtc = null;
        }
        state = STATE_IDLE;
        callStartAt = 0;
        answeredSent = false;
        callId = null;
        micMuted = false;
        setSpeakerRaw(false);
        lastEvent = reason;
        notifyForeground(buildNotification("待机中 · 等待呼叫", false), false);
        broadcast();
    }

    private void setSpeakerRaw(boolean on) {
        try {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                am.setMode(on ? AudioManager.MODE_IN_COMMUNICATION : AudioManager.MODE_NORMAL);
                am.setSpeakerphoneOn(on);
            }
        } catch (Exception ignored) {}
    }

    // ------------------------------------------------------------------
    // WebRTC 回调
    // ------------------------------------------------------------------
    @Override
    public void onRtcSdp(JSONObject sdpJson) {
        sendSignal(sdpJson);
    }

    @Override
    public void onRtcIce(JSONObject iceJson) {
        sendSignal(iceJson);
    }

    private void sendSignal(JSONObject payload) {
        try {
            JSONObject msg = new JSONObject();
            msg.put("type", "signal");
            msg.put("callId", callId);
            msg.put("payload", payload);
            send(msg);
        } catch (Exception e) {
            Log.e(TAG, "sendSignal", e);
        }
    }

    @Override
    public void onRtcState(String s) {
        Log.i(TAG, "rtc state " + s);
        if ("connected".equals(s)) {
            lastEvent = "已接通音频";
        } else if ("failed".equals(s)) {
            lastEvent = "音频通道建立失败（可能需要同一网络或 TURN 服务器）";
        }
        broadcast();
    }

    // ------------------------------------------------------------------
    // 通知
    // ------------------------------------------------------------------
    private void createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm == null) return;
            NotificationChannel svc = new NotificationChannel(CH_SERVICE, "通话待机",
                    NotificationManager.IMPORTANCE_LOW);
            svc.setShowBadge(false);
            svc.setSound(null, null);
            nm.createNotificationChannel(svc);

            NotificationChannel call = new NotificationChannel(CH_CALL, "来电响铃",
                    NotificationManager.IMPORTANCE_HIGH);
            call.setSound(null, null);
            call.enableVibration(false);
            call.setBypassDnd(true);
            call.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            nm.createNotificationChannel(call);
        }
    }

    private Notification buildNotification(String text, boolean fullScreen) {
        Intent open = new Intent(this, CallActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 10, open,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, fullScreen ? CH_CALL : CH_SERVICE)
                : new Notification.Builder(this);
        b.setContentTitle(fullScreen ? "来电" : "RingCall")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_call)
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(false)
                .setPriority(fullScreen ? Notification.PRIORITY_MAX : Notification.PRIORITY_LOW)
                .setCategory(fullScreen ? Notification.CATEGORY_CALL : Notification.CATEGORY_SERVICE)
                .setVisibility(Notification.VISIBILITY_PUBLIC);

        if (fullScreen) {
            Intent fsIntent = new Intent(this, CallActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent fs = PendingIntent.getActivity(this, 11, fsIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
            b.setFullScreenIntent(fs, true);
            b.setOngoing(false);
            b.setAutoCancel(false);
        }
        return b.build();
    }

    private void notifyForeground(Notification n) {
        notifyForeground(n, false);
    }

    private void notifyForeground(Notification n, boolean mic) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 34) {
            int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
            if (mic) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            startForeground(NOTIF_ID, n, type);
        } else {
            startForeground(NOTIF_ID, n);
        }
        if (nm != null) nm.notify(NOTIF_ID, n);
    }

    /** 首次前台化（后台启动只能用 dataSync 类型，麦克风类型在通话时再追加） */
    private void startForegroundCompat(Notification n) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private void updateStandbyNotification() {
        if (state != STATE_IDLE) return;
        notifyForeground(buildNotification(connected ? "待机中 · 已连接服务器" : "待机中 · 等待连接", false));
    }

    private void broadcast() {
        // 通知界面刷新（简单事件，不传数据，界面自行读取静态字段）
        sendBroadcast(new Intent("com.ringcall.STATE").setPackage(getPackageName()));
    }

    // ------------------------------------------------------------------
    private void shutdown() {
        running = false;
        ring.stop();
        if (rtc != null) { try { rtc.close(); } catch (Exception ignored) {} rtc = null; }
        if (ws != null) { ws.disconnect(); ws = null; }
        if (reconnectTask != null) handler.removeCallbacks(reconnectTask);
        connected = false;
        state = STATE_IDLE;
        connInfo = "已停止";
        stopForeground(true);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        running = false;
        ring.stop();
        if (rtc != null) { try { rtc.close(); } catch (Exception ignored) {} rtc = null; }
        if (ws != null) ws.disconnect();
        instance = null;
        super.onDestroy();
    }

    // 供界面调用的小工具
    public static void start(Context ctx) {
        Intent i = new Intent(ctx, CallService.class).setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
        else ctx.startService(i);
    }

    public static void stop(Context ctx) {
        ctx.stopService(new Intent(ctx, CallService.class));
    }
}
