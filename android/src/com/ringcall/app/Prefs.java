package com.ringcall.app;

import android.content.Context;
import android.content.SharedPreferences;

public final class Prefs {
    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("ringcall", Context.MODE_PRIVATE);
    }

    public static String server(Context c) {
        return sp(c).getString("server", c.getString(R.string.default_server));
    }

    public static void setServer(Context c, String v) {
        sp(c).edit().putString("server", v == null ? "" : v.trim()).apply();
    }

    public static String authKey(Context c) {
        return sp(c).getString("auth_key", c.getString(R.string.default_key));
    }

    public static void setAuthKey(Context c, String v) {
        sp(c).edit().putString("auth_key", v == null ? "" : v.trim()).apply();
    }

    public static String device(Context c) {
        return sp(c).getString("device", "default");
    }

    public static void setDevice(Context c, String v) {
        String d = (v == null || v.trim().isEmpty()) ? "default" : v.trim();
        sp(c).edit().putString("device", d).apply();
    }

    public static String ringUri(Context c) {
        return sp(c).getString("ring_uri", "");
    }

    public static void setRing(Context c, String uri, String name) {
        sp(c).edit().putString("ring_uri", uri).putString("ring_name", name).apply();
    }

    public static String ringName(Context c) {
        return sp(c).getString("ring_name", "系统默认闹钟铃声");
    }

    public static String folderUri(Context c) {
        return sp(c).getString("folder_uri", "");
    }

    public static void setFolder(Context c, String uri) {
        sp(c).edit().putString("folder_uri", uri).apply();
    }

    public static boolean autoStart(Context c) {
        return sp(c).getBoolean("autostart", true);
    }

    public static void setAutoStart(Context c, boolean v) {
        sp(c).edit().putBoolean("autostart", v).apply();
    }

    public static boolean boostVolume(Context c) {
        return sp(c).getBoolean("boost", true);
    }

    public static void setBoostVolume(Context c, boolean v) {
        sp(c).edit().putBoolean("boost", v).apply();
    }

    public static boolean speaker(Context c) {
        return sp(c).getBoolean("speaker", true);
    }

    public static void setSpeaker(Context c, boolean v) {
        sp(c).edit().putBoolean("speaker", v).apply();
    }
}
