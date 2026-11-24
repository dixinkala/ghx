package com.ghx.accel;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** DNS 加速用的 域名 → IP 映射（持久化在 SharedPreferences）。 */
public final class DnsConfig {

    public static final String[] TRACKED = {"github.com", "api.github.com", "codeload.github.com",
            "store.steampowered.com", "api.steampowered.com", "media.steampowered.com"};
    private static final String PREFS = "ghx";
    private static final Map<String, String> MAP = new LinkedHashMap<>();
    /** 只接管勾选了的域名。 */
    private static final Set<String> ENABLED = new LinkedHashSet<>(Arrays.asList(TRACKED));

    private DnsConfig() {}

    public static synchronized void load(Context c) {
        SharedPreferences sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        MAP.clear();
        for (String host : TRACKED) {
            String ip = sp.getString("ip_" + host, "");
            if (ip != null && !ip.isEmpty()) MAP.put(host, ip);
        }
        ENABLED.clear();
        String saved = sp.getString("enabled", null);
        if (saved == null) {
            ENABLED.addAll(Arrays.asList(TRACKED)); // 首次默认全选
        } else {
            for (String h : saved.split(",")) if (!h.isEmpty()) ENABLED.add(h);
        }
    }

    public static synchronized boolean isEnabled(String host) {
        return ENABLED.contains(host);
    }

    public static synchronized void setEnabled(Context c, String host, boolean on) {
        if (on) ENABLED.add(host); else ENABLED.remove(host);
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("enabled", String.join(",", ENABLED)).apply();
    }

    public static synchronized java.util.List<String> enabledHosts() {
        return new java.util.ArrayList<>(ENABLED);
    }

    public static synchronized void save(Context c, String host, String ip) {
        MAP.put(host, ip);
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("ip_" + host, ip).apply();
    }

    /** 只返回"测速验证过"的 IP；没验证过就返回 null，交给上游正常解析。 */
    public static synchronized String get(String host) {
        if (!ENABLED.contains(host)) return null; // 没勾选就不接管
        return MAP.get(host);
    }

    public static synchronized Map<String, String> snapshot() {
        return new LinkedHashMap<>(MAP);
    }
}
