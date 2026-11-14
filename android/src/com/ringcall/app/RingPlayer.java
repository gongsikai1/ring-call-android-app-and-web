package com.ringcall.app;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;

/**
 * 铃声播放器。
 * 关键点：使用 USAGE_ALARM + STREAM_ALARM 播放，因此手机处于「静音 / 振动」模式时依然会响铃，
 * 与系统闹钟走同一条音频通道。必要时还会临时把闹钟音量提到 60%，结束后恢复。
 */
public class RingPlayer {
    private static final String TAG = "RingPlayer";
    private MediaPlayer mp;
    private Vibrator vibrator;
    private boolean boosted;
    private int savedVolume = -1;

    public void start(Context ctx, Uri uri, boolean boost) {
        start(ctx, uri, boost, true);
    }

    public void start(Context ctx, Uri uri, boolean boost, boolean vibrate) {
        stop();
        try {
            if (uri == null) {
                uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
                if (uri == null) uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            }
            android.media.AudioManager am =
                    (android.media.AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (boost && am != null) {
                int max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_ALARM);
                int cur = am.getStreamVolume(android.media.AudioManager.STREAM_ALARM);
                if (cur < Math.max(2, (int) (max * 0.5f))) {
                    savedVolume = cur;
                    boosted = true;
                    am.setStreamVolume(android.media.AudioManager.STREAM_ALARM,
                            Math.max(2, (int) (max * 0.6f)), 0);
                }
            }

            mp = new MediaPlayer();
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            mp.setDataSource(ctx, uri);
            mp.setLooping(true);
            mp.setOnErrorListener((m, what, extra) -> {
                Log.w(TAG, "media error " + what + "/" + extra);
                return false;
            });
            mp.prepare();
            mp.start();
            Log.i(TAG, "ringing with " + uri);
        } catch (Exception e) {
            Log.e(TAG, "start ring failed", e);
            // 兜底：直接用系统闹钟铃声
            try {
                mp = new MediaPlayer();
                mp.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build());
                mp.setDataSource(ctx, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM));
                mp.setLooping(true);
                mp.prepare();
                mp.start();
            } catch (Exception e2) {
                Log.e(TAG, "fallback ring failed", e2);
            }
        }
        startVibrate(ctx, vibrate);
    }

    private void startVibrate(Context ctx, boolean enabled) {
        if (!enabled) return;
        try {
            vibrator = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator == null || !vibrator.hasVibrator()) return;
            long[] pattern = {0, 700, 500, 700, 900};
            if (Build.VERSION.SDK_INT >= 26) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0));
            } else {
                vibrator.vibrate(pattern, 0);
            }
        } catch (Exception ignored) {
        }
    }

    public void stop() {
        if (mp != null) {
            try { mp.stop(); } catch (Exception ignored) {}
            try { mp.release(); } catch (Exception ignored) {}
            mp = null;
        }
        if (vibrator != null) {
            try { vibrator.cancel(); } catch (Exception ignored) {}
            vibrator = null;
        }
        if (boosted && savedVolume >= 0) {
            try {
                android.media.AudioManager am = (android.media.AudioManager)
                        RingApp.ctx().getSystemService(Context.AUDIO_SERVICE);
                if (am != null) am.setStreamVolume(android.media.AudioManager.STREAM_ALARM, savedVolume, 0);
            } catch (Exception ignored) {}
            boosted = false;
            savedVolume = -1;
        }
    }

    public boolean isRinging() {
        return mp != null;
    }
}
