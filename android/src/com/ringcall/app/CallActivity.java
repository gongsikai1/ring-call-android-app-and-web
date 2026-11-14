package com.ringcall.app;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** 来电 / 通话界面（覆盖锁屏）。 */
public class CallActivity extends Activity {
    private static final int REQ_MIC = 7001;

    private TextView titleView, nameView, timerView, subView, avatarView;
    private LinearLayout incomingRow, talkingRow;
    private Button answerBtn, hangBtn, muteBtn, speakerBtn;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean finished;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (finished) return;
            render();
            handler.postDelayed(this, 500);
        }
    };

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            render();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        applyWindowFlags();
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        setContentView(buildUi());
        CallService.ensureRunning(this);
    }

    private void applyWindowFlags() {
        getWindow().setStatusBarColor(Color.parseColor("#07090d"));
        getWindow().setNavigationBarColor(Color.parseColor("#07090d"));
    }

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.parseColor("#101a24"), Color.parseColor("#07090d")});
        root.setBackground(bg);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(dp(24), dp(60), dp(24), dp(40));
        FrameLayout.LayoutParams colLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        root.addView(col, colLp);

        titleView = new TextView(this);
        titleView.setText("来电");
        titleView.setTextColor(Color.parseColor("#7dd3fc"));
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        titleView.setLetterSpacing(0.18f);
        col.addView(titleView);

        // 头像
        TextView avatar = new TextView(this);
        avatar.setText("来");
        avatar.setTextColor(Color.WHITE);
        avatar.setTextSize(TypedValue.COMPLEX_UNIT_SP, 44);
        avatar.setGravity(Gravity.CENTER);
        GradientDrawable circle = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{Color.parseColor("#2dd4bf"), Color.parseColor("#3b82f6")});
        circle.setShape(GradientDrawable.OVAL);
        avatar.setBackground(circle);
        LinearLayout.LayoutParams avLp = new LinearLayout.LayoutParams(dp(120), dp(120));
        avLp.topMargin = dp(48);
        col.addView(avatar, avLp);
        avatarView = avatar;

        nameView = new TextView(this);
        nameView.setText("未知来电");
        nameView.setTextColor(Color.parseColor("#e8edf3"));
        nameView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        nameView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams nLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nLp.topMargin = dp(22);
        col.addView(nameView, nLp);

        timerView = new TextView(this);
        timerView.setTextColor(Color.parseColor("#7ee7c7"));
        timerView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        timerView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tLp.topMargin = dp(10);
        col.addView(timerView, tLp);

        subView = new TextView(this);
        subView.setTextColor(Color.parseColor("#8fa3b8"));
        subView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        subView.setGravity(Gravity.CENTER);
        subView.setPadding(dp(12), dp(8), dp(12), dp(8));
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sLp.topMargin = dp(6);
        col.addView(subView, sLp);

        View spacer = new View(this);
        col.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1f));

        // 来电按钮行
        incomingRow = new LinearLayout(this);
        incomingRow.setOrientation(LinearLayout.HORIZONTAL);
        incomingRow.setGravity(Gravity.CENTER);
        answerBtn = makeButton("接听", "#22c55e", "#15803d");
        hangBtn = makeButton("挂断", "#ef4444", "#b91c1c");
        incomingRow.addView(wrapBtn(answerBtn));
        incomingRow.addView(wrapBtn(hangBtn));
        col.addView(incomingRow);

        // 通话中按钮行
        talkingRow = new LinearLayout(this);
        talkingRow.setOrientation(LinearLayout.HORIZONTAL);
        talkingRow.setGravity(Gravity.CENTER);
        muteBtn = makeButton("静音", "#475569", "#334155");
        speakerBtn = makeButton("免提", "#475569", "#334155");
        hangBtn2 = makeButton("挂断", "#ef4444", "#b91c1c");
        talkingRow.addView(wrapBtn(muteBtn));
        talkingRow.addView(wrapBtn(speakerBtn));
        talkingRow.addView(wrapBtn(hangBtn2));
        col.addView(talkingRow);

        answerBtn.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= 23
                    && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            } else {
                answerNow();
            }
        });
        hangBtn.setOnClickListener(v -> {
            CallService svc = CallService.get();
            if (svc != null) svc.doReject();
            else finishCall();
        });
        hangBtn2.setOnClickListener(v -> {
            CallService svc = CallService.get();
            if (svc != null) svc.doHangup(false, "已挂断");
            else finishCall();
        });
        muteBtn.setOnClickListener(v -> {
            CallService svc = CallService.get();
            if (svc != null) startService(new Intent(this, CallService.class)
                    .setAction(CallService.ACTION_TOGGLE_MUTE));
            render();
        });
        speakerBtn.setOnClickListener(v -> {
            CallService svc = CallService.get();
            if (svc != null) startService(new Intent(this, CallService.class)
                    .setAction(CallService.ACTION_TOGGLE_SPEAKER));
            render();
        });

        return root;
    }

    private Button hangBtn2;

    private Button makeButton(String text, String c1, String c2) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setAllCaps(false);
        b.setBackground(circleBg(c1, c2));
        b.setPadding(dp(6), dp(6), dp(6), dp(6));
        return b;
    }

    private GradientDrawable circleBg(String c1, String c2) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.parseColor(c1), Color.parseColor(c2)});
        g.setShape(GradientDrawable.OVAL);
        return g;
    }

    private View wrapBtn(Button b) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setGravity(Gravity.CENTER);
        wrap.setPadding(dp(18), 0, dp(18), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(92), dp(92));
        wrap.addView(b, lp);
        return wrap;
    }

    private int dp(float v) {
        return (int) (getResources().getDisplayMetrics().density * v);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQ_MIC) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                answerNow();
            } else {
                Toast.makeText(this, "没有麦克风权限，只能听到对方声音", Toast.LENGTH_LONG).show();
                answerNow();
            }
            return;
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    private void answerNow() {
        CallService svc = CallService.get();
        if (svc == null) {
            CallService.start(this);
            handler.postDelayed(this::answerNow, 400);
            return;
        }
        svc.doAnswer();
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter f = new IntentFilter("com.ringcall.STATE");
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(stateReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(stateReceiver, f);
        handler.post(ticker);
    }

    @Override
    protected void onPause() {
        super.onPause();
        try { unregisterReceiver(stateReceiver); } catch (Exception ignored) {}
        handler.removeCallbacks(ticker);
    }

    private void render() {
        int st = CallService.state;
        String name = CallService.peerName;
        if (st == CallService.STATE_IDLE) {
            finishCall();
            return;
        }
        if (finished) return;
        nameView.setText(name);
        if (avatarView != null && !name.isEmpty()) {
            avatarView.setText(name.substring(0, 1));
        }
        if (st == CallService.STATE_RINGING) {
            titleView.setText("来电");
            timerView.setText("");
            subView.setText("对方正在等待接听");
            incomingRow.setVisibility(View.VISIBLE);
            talkingRow.setVisibility(View.GONE);
        } else {
            titleView.setText("通话中");
            long sec = CallService.callStartAt == 0 ? 0
                    : (System.currentTimeMillis() - CallService.callStartAt) / 1000;
            timerView.setText(String.format("%02d:%02d", sec / 60, sec % 60));
            subView.setText(CallService.speakerOn ? "扬声器已开启" : "使用听筒");
            incomingRow.setVisibility(View.GONE);
            talkingRow.setVisibility(View.VISIBLE);
            ((Button) muteBtn).setText(CallService.micMuted ? "取消静音" : "静音");
            ((Button) speakerBtn).setText(CallService.speakerOn ? "听筒" : "免提");
        }
    }

    private void finishCall() {
        if (finished) return;
        finished = true;
        String ev = CallService.lastEvent;
        if (ev != null && !ev.isEmpty()) {
            Toast.makeText(getApplicationContext(), ev, Toast.LENGTH_SHORT).show();
        }
        handler.removeCallbacks(ticker);
        finish();
    }

    @Override
    public void onBackPressed() {
        // 通话中禁止返回，必须挂断
        if (CallService.state != CallService.STATE_IDLE) {
            Toast.makeText(this, "请先挂断电话", Toast.LENGTH_SHORT).show();
            return;
        }
        super.onBackPressed();
    }
}
