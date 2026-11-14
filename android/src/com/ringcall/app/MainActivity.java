package com.ringcall.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import android.database.Cursor;
import android.provider.DocumentsContract;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private static final int REQ_TREE = 4001;
    private static final int REQ_PERM = 4002;

    private TextView statusView, connView, ringNow, logView, folderView;
    private LinearLayout fileList, ringRow;
    private EditText serverInput, deviceInput, keyInput;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final RingPlayer preview = new RingPlayer();
    private boolean previewing;

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            updateStatus();
            h.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.parseColor("#0b1117"));
        buildUi();
        CallService.ensureRunning(this);
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.parseColor("#0b1117"));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(22), dp(16), dp(28));
        scroll.addView(col);

        TextView title = new TextView(this);
        title.setText("RingCall");
        title.setTextColor(Color.parseColor("#e8edf3"));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        col.addView(title);

        TextView sub = new TextView(this);
        sub.setText("手机端接听 · 网页端拨号 · 静音也响铃");
        sub.setTextColor(Color.parseColor("#7d8fa3"));
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        LinearLayout.LayoutParams lp0 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp0.bottomMargin = dp(16);
        col.addView(sub, lp0);

        // ---------- 状态卡 ----------
        LinearLayout st = card(col, "运行状态");
        statusView = new TextView(this);
        statusView.setTextColor(Color.parseColor("#7ee7c7"));
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        st.addView(statusView);

        connView = new TextView(this);
        connView.setTextColor(Color.parseColor("#8fa3b8"));
        connView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp1.topMargin = dp(6);
        st.addView(connView, lp1);

        LinearLayout row1 = hrow();
        Button btnStart = smallBtn("开启待机", "#0ea5e9");
        Button btnStop = smallBtn("停止待机", "#475569");
        row1.addView(btnStart);
        row1.addView(btnStop);
        st.addView(row1);
        btnStart.setOnClickListener(v -> {
            CallService.start(this);
            toast("已开启待机，等待网页拨号");
        });
        btnStop.setOnClickListener(v -> {
            CallService.stop(this);
            toast("已停止待机");
        });

        // 来电兜底入口：万一系统拦了来电全屏界面，也能在这里接听
        ringRow = new LinearLayout(this);
        ringRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lpr = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpr.topMargin = dp(10);
        ringRow.setLayoutParams(lpr);
        Button ans = smallBtn("接听来电", "#22c55e");
        Button rej = smallBtn("挂断", "#ef4444");
        ringRow.addView(ans);
        ringRow.addView(rej);
        ringRow.setVisibility(View.GONE);
        st.addView(ringRow);
        ans.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= 23
                    && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 4003);
            } else {
                answerHere();
            }
        });
        rej.setOnClickListener(v -> {
            CallService svc = CallService.get();
            if (svc != null) svc.doReject();
        });

        // ---------- 服务器卡 ----------
        LinearLayout sv = card(col, "服务器 / 设备");
        sv.addView(label("服务器地址（信令服务器，ws 或 wss 均可）"));
        serverInput = input(Prefs.server(this), InputType.TYPE_TEXT_VARIATION_URI);
        sv.addView(serverInput);
        sv.addView(label("访问口令（key，服务器的 AUTH_KEY，留空表示不校验）"));
        keyInput = input(Prefs.authKey(this), InputType.TYPE_CLASS_TEXT);
        sv.addView(keyInput);
        sv.addView(label("设备 ID（网页用 ?device= 指向这台手机）"));
        deviceInput = input(Prefs.device(this), InputType.TYPE_CLASS_TEXT);
        sv.addView(deviceInput);
        Button save = smallBtn("保存并重连", "#0ea5e9");
        LinearLayout.LayoutParams lps = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lps.topMargin = dp(10);
        sv.addView(save, lps);
        save.setOnClickListener(v -> {
            Prefs.setServer(this, serverInput.getText().toString());
            Prefs.setAuthKey(this, keyInput.getText().toString());
            Prefs.setDevice(this, deviceInput.getText().toString());
            CallService.stop(this);
            h.postDelayed(() -> CallService.start(this), 600);
            toast("已保存，正在重连");
        });

        // ---------- 铃声卡 ----------
        LinearLayout rc = card(col, "铃声");
        ringNow = new TextView(this);
        ringNow.setTextColor(Color.parseColor("#e8edf3"));
        ringNow.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        rc.addView(ringNow);
        folderView = new TextView(this);
        folderView.setTextColor(Color.parseColor("#7d8fa3"));
        folderView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        LinearLayout.LayoutParams lpf = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpf.topMargin = dp(4);
        rc.addView(folderView, lpf);

        LinearLayout row2 = hrow();
        Button pick = smallBtn("选择文件夹", "#0ea5e9");
        Button def = smallBtn("恢复默认", "#475569");
        Button test = smallBtn("测试响铃", "#22c55e");
        row2.addView(pick);
        row2.addView(def);
        row2.addView(test);
        rc.addView(row2);

        fileList = new LinearLayout(this);
        fileList.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lpl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpl.topMargin = dp(8);
        rc.addView(fileList, lpl);

        pick.setOnClickListener(v -> pickFolder());
        def.setOnClickListener(v -> {
            Prefs.setRing(this, "", "系统默认闹钟铃声");
            refreshRing();
            toast("已恢复系统闹钟铃声");
        });
        test.setOnClickListener(v -> {
            if (previewing) {
                preview.stop();
                previewing = false;
                return;
            }
            String u = Prefs.ringUri(this);
            preview.start(this, u.isEmpty() ? null : Uri.parse(u), true, false);
            previewing = true;
            h.postDelayed(() -> {
                preview.stop();
                previewing = false;
            }, 6000);
            toast("正在用闹钟通道播放，静音模式下也能听到");
        });

        // ---------- 权限卡 ----------
        LinearLayout pc = card(col, "权限（缺一不可，请全部允许）");
        Button p1 = smallBtn("通知 / 麦克风", "#0ea5e9");
        Button p2 = smallBtn("悬浮窗（来电弹屏）", "#a855f7");
        Button p3 = smallBtn("忽略电池优化", "#f59e0b");
        LinearLayout pr1 = hrow();
        pr1.addView(p1);
        pr1.addView(p2);
        pr1.addView(p3);
        pc.addView(pr1);
        p1.setOnClickListener(v -> requestRuntime());
        p2.setOnClickListener(v -> {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            try { startActivity(i); } catch (Exception e) { toast("请手动到设置中开启悬浮窗"); }
        });
        p3.setOnClickListener(v -> {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(Uri.parse("package:" + getPackageName()));
            try { startActivity(i); } catch (Exception e) { toast("请手动到设置中允许后台运行"); }
        });

        // ---------- 说明 ----------
        LinearLayout hc = card(col, "使用说明");
        TextView help = new TextView(this);
        help.setText("1. 保持 App 在后台待机（右上角常驻通知即表示运行中）。\n"
                + "2. 网页打开 https://你的服务器/?from=张三 后点“拨打”，手机会立刻响铃。\n"
                + "3. 手机接听后即建立 WebRTC 语音通话；手机挂断后网页会显示“对方已挂掉电话”。\n"
                + "4. 铃声走闹钟通道，手机静音/振动时依旧会响；如系统开启了“静音且禁止闹钟”，请到设置里允许闹钟。\n"
                + "5. 建议双方在同一 WiFi，或都使用 4G/5G 公网，NAT 受限时可自行配置 TURN 服务器。");
        help.setTextColor(Color.parseColor("#8fa3b8"));
        help.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        help.setLineSpacing(dp(4), 1f);
        hc.addView(help);

        // ---------- 日志 ----------
        LinearLayout lc = card(col, "事件日志");
        logView = new TextView(this);
        logView.setTextColor(Color.parseColor("#5f7183"));
        logView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        lc.addView(logView);

        setContentView(scroll);
        refreshRing();
        updateStatus();
    }

    // ---------------- UI helpers ----------------
    private LinearLayout card(LinearLayout parent, String title) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(14), dp(14), dp(14), dp(14));
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.parseColor("#121a23"));
        g.setCornerRadius(dp(14));
        g.setStroke(dp(1), Color.parseColor("#1e2b38"));
        c.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(14);
        parent.addView(c, lp);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(Color.parseColor("#2dd4bf"));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setLetterSpacing(0.1f);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tl.bottomMargin = dp(10);
        c.addView(t, tl);
        return c;
    }

    private TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(Color.parseColor("#7d8fa3"));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        lp.bottomMargin = dp(4);
        t.setLayoutParams(lp);
        return t;
    }

    private EditText input(String value, int type) {
        EditText e = new EditText(this);
        e.setText(value);
        e.setInputType(type);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        e.setTextColor(Color.parseColor("#e8edf3"));
        e.setSingleLine(true);
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.parseColor("#0d141b"));
        g.setCornerRadius(dp(9));
        g.setStroke(dp(1), Color.parseColor("#22303e"));
        e.setBackground(g);
        e.setPadding(dp(10), dp(8), dp(10), dp(8));
        return e;
    }

    private LinearLayout hrow() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        r.setLayoutParams(lp);
        return r;
    }

    private Button smallBtn(String text, String color) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        b.setTextColor(Color.WHITE);
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.parseColor(color));
        g.setCornerRadius(dp(9));
        b.setBackground(g);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(10), dp(8), dp(10), dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    private int dp(float v) {
        return (int) (getResources().getDisplayMetrics().density * v);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // ---------------- 状态 ----------------
    private void updateStatus() {
        int st = CallService.state;
        String s;
        if (st == CallService.STATE_TALKING) s = "通话中：" + CallService.peerName;
        else if (st == CallService.STATE_RINGING) s = "来电响铃：" + CallService.peerName;
        else if (CallService.connected) s = "待机中（已连接服务器）";
        else s = "待机中（未连接）";
        statusView.setText(s);
        if (ringRow != null) ringRow.setVisibility(st == CallService.STATE_RINGING ? View.VISIBLE : View.GONE);
        String info = CallService.connInfo;
        if (CallService.lastEvent != null && !CallService.lastEvent.isEmpty()) {
            info += " · 最近事件：" + CallService.lastEvent;
        }
        connView.setText(info);
        String old = logView.getText().toString();
        if (!CallService.lastEvent.equals(lastLogged)) {
            lastLogged = CallService.lastEvent;
            logView.setText((old.length() > 1000 ? "" : old)
                    + "[" + android.text.format.DateFormat.format("HH:mm:ss", System.currentTimeMillis()) + "] "
                    + lastLogged + "\n");
        }
    }

    private String lastLogged = "";

    private void answerHere() {
        CallService svc = CallService.get();
        if (svc == null) {
            CallService.start(this);
            h.postDelayed(this::answerHere, 500);
            return;
        }
        svc.doAnswer();
        try {
            startActivity(new Intent(this, CallActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == 4003) {
            answerHere();
            return;
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    private void refreshRing() {
        ringNow.setText("当前铃声：" + Prefs.ringName(this));
        String folder = Prefs.folderUri(this);
        folderView.setText(folder.isEmpty() ? "尚未选择文件夹" : "文件夹：" + folder);
    }

    // ---------------- 选文件夹 ----------------
    private void pickFolder() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        try {
            startActivityForResult(i, REQ_TREE);
        } catch (Exception e) {
            toast("当前系统不支持选择文件夹");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_TREE && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri tree = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(tree,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {
            }
            Prefs.setFolder(this, tree.toString());
            refreshRing();
            loadFolderAudio(tree);
        }
    }

    private void loadFolderAudio(Uri tree) {
        fileList.removeAllViews();
        final List<String[]> found = new ArrayList<>();
        try {
            queryDir(tree, tree, 0, found);
        } catch (Exception e) {
            toast("读取文件夹失败：" + e.getMessage());
        }
        if (found.isEmpty()) {
            TextView t = new TextView(this);
            t.setText("该文件夹（含一层子目录）里没有音频文件");
            t.setTextColor(Color.parseColor("#7d8fa3"));
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            fileList.addView(t);
            return;
        }
        for (final String[] f : found) {
            final String name = f[0];
            final Uri uri = Uri.parse(f[1]);
            TextView row = new TextView(this);
            row.setText("♪  " + name);
            row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            row.setTextColor(Color.parseColor("#cbd5e1"));
            row.setPadding(dp(12), dp(11), dp(12), dp(11));
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(dp(9));
            g.setColor(Color.parseColor("#0d141b"));
            g.setStroke(dp(1), Color.parseColor("#1b2733"));
            row.setBackground(g);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(6);
            row.setLayoutParams(lp);
            row.setOnClickListener(v -> {
                Prefs.setRing(this, uri.toString(), name);
                refreshRing();
                if (previewing) { preview.stop(); previewing = false; }
                preview.start(this, uri, false, false);
                previewing = true;
                h.postDelayed(() -> { preview.stop(); previewing = false; }, 4000);
                toast("已设为来电铃声：" + name);
            });
            fileList.addView(row);
        }
        toast("共找到 " + found.size() + " 个音频，点击即可设为铃声");
    }

    /** 用 SAF 的 DocumentsContract 遍历文件夹（纯系统 API，不依赖第三方库）。 */
    private void queryDir(Uri tree, Uri dirUri, int depth, List<String[]> out) {
        String docId;
        try {
            docId = DocumentsContract.getDocumentId(dirUri);
        } catch (Exception e) {
            docId = DocumentsContract.getTreeDocumentId(tree);
        }
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
        Cursor c = null;
        try {
            c = getContentResolver().query(children, new String[]{
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
            }, null, null, null);
            if (c == null) return;
            while (c.moveToNext()) {
                String cid = c.getString(0);
                String nm = c.getString(1);
                String mime = c.getString(2);
                Uri docUri = DocumentsContract.buildDocumentUriUsingTree(tree, cid);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    if (depth < 1) queryDir(tree, docUri, depth + 1, out);
                } else if (isAudio(nm, mime)) {
                    out.add(new String[]{nm == null ? "未命名" : nm, docUri.toString()});
                }
            }
        } finally {
            if (c != null) c.close();
        }
    }

    private boolean isAudio(String name, String mime) {
        if (mime != null && mime.startsWith("audio/")) return true;
        if (name == null) return false;
        String lower = name.toLowerCase();
        return lower.endsWith(".mp3") || lower.endsWith(".wav") || lower.endsWith(".m4a")
                || lower.endsWith(".aac") || lower.endsWith(".ogg") || lower.endsWith(".flac")
                || lower.endsWith(".opus") || lower.endsWith(".amr") || lower.endsWith(".3gp");
    }

    private void requestRuntime() {
        List<String> perms = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            perms.add(Manifest.permission.RECORD_AUDIO);
        }
        if (perms.isEmpty()) {
            toast("通知与麦克风权限已全部授予");
            return;
        }
        requestPermissions(perms.toArray(new String[0]), REQ_PERM);
    }

    @Override
    protected void onResume() {
        super.onResume();
        h.post(refresh);
    }

    @Override
    protected void onPause() {
        super.onPause();
        h.removeCallbacks(refresh);
        if (previewing) { preview.stop(); previewing = false; }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        preview.stop();
    }
}
