package com.ringcall.app;

import android.util.Log;

import org.json.JSONObject;

import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/** 极简 WebSocket 客户端（信令通道）。 */
public class WsClient {
    private static final String TAG = "WsClient";

    public interface Listener {
        void onWsOpen();

        void onWsText(String text);

        void onWsClosed(String reason);
    }

    private final Listener listener;
    private final OkHttpClient client;
    private WebSocket ws;
    private String url;
    private volatile boolean closedByUser;
    private volatile boolean open;

    public WsClient(Listener l) {
        this.listener = l;
        this.client = buildTrustingClient();
    }

    /**
     * 自己的服务器常用自签证书（IP 直连），这里接受自签证书，
     * 避免 wss 握手因证书链不完整而失败。仅用于自建私有信令服务器。
     */
    private static OkHttpClient buildTrustingClient() {
        OkHttpClient.Builder b = new OkHttpClient.Builder()
                .pingInterval(20, TimeUnit.SECONDS)
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(true);
        try {
            TrustManager[] tms = new TrustManager[]{new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }};
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, tms, new SecureRandom());
            b.sslSocketFactory(ctx.getSocketFactory(), (X509TrustManager) tms[0]);
            b.hostnameVerifier((hostname, session) -> true);
        } catch (Exception ignored) {
        }
        return b.build();
    }

    public static String normalize(String server) {
        String s = server == null ? "" : server.trim();
        if (s.isEmpty()) return null;
        if (s.startsWith("https://")) s = "wss://" + s.substring(8);
        else if (s.startsWith("http://")) s = "ws://" + s.substring(7);
        else if (!s.startsWith("ws://") && !s.startsWith("wss://")) s = "wss://" + s;
        if (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    public void connect(String server, String deviceId, String key) {
        disconnect();
        String base = normalize(server);
        if (base == null) {
            listener.onWsClosed("服务器地址为空");
            return;
        }
        url = base + "/ws?role=android&device=" + android.net.Uri.encode(deviceId)
                + (key == null || key.isEmpty() ? "" : "&key=" + android.net.Uri.encode(key));
        closedByUser = false;
        Log.i(TAG, "connecting " + url);
        Request req = new Request.Builder().url(url).build();
        client.newWebSocket(req, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                Log.i(TAG, "ws open");
                ws = webSocket;
                open = true;
                listener.onWsOpen();
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                listener.onWsText(text);
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                open = false;
                ws = null;
                Log.w(TAG, "ws failure: " + t);
                if (!closedByUser) listener.onWsClosed(t.getMessage() == null ? "连接失败" : t.getMessage());
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                open = false;
                ws = null;
                Log.i(TAG, "ws closed " + code + " " + reason);
                if (!closedByUser) listener.onWsClosed("连接已关闭 " + code);
            }
        });
    }

    public boolean isOpen() {
        return open && ws != null;
    }

    public void send(JSONObject obj) {
        if (ws != null) {
            boolean ok = ws.send(obj.toString());
            if (!ok) Log.w(TAG, "send dropped");
        }
    }

    public void disconnect() {
        closedByUser = true;
        open = false;
        if (ws != null) {
            try {
                ws.close(1000, "bye");
            } catch (Exception ignored) {
            }
            ws = null;
        }
    }
}
