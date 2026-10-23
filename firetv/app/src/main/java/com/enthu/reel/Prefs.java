package com.enthu.reel;

import android.content.Context;
import android.content.SharedPreferences;

public class Prefs {
    private static final String NAME = "reel_prefs";

    private static SharedPreferences prefs;

    public static void init(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public static String getToken() {
        return prefs != null ? prefs.getString("token", "") : "";
    }

    public static void setToken(String token) {
        prefs.edit().putString("token", token).apply();
    }

    public static String getServerUrl() {
        return prefs != null ? prefs.getString("serverUrl", "") : "";
    }

    public static void setServerUrl(String serverUrl) {
        prefs.edit().putString("serverUrl", serverUrl).apply();
    }

    public static String getClientId() {
        return prefs != null ? prefs.getString("clientId", "") : "";
    }

    public static void setClientId(String clientId) {
        prefs.edit().putString("clientId", clientId).apply();
    }

    public static boolean hasLogin() {
        return prefs != null && !getToken().isEmpty() && !getServerUrl().isEmpty();
    }

    public static void clear() {
        if (prefs != null) prefs.edit().clear().apply();
    }
}
