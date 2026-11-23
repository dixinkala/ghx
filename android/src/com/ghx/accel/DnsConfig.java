package com.ghx.accel;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.LinkedHashMap;
import java.util.Map;

/** DNS 加速用的 域名 → IP 映射（持久化在 SharedPreferences）。 */
public final class DnsConfig {

    public static final String[] TRACKED = {"github.com", "api.github.com", "codeload.github.com"};
    private static final String PREFS = "ghx";
    private static final Map<String, String> MAP = new LinkedHashMap<>();

    private DnsConfig() {}

    public static synchronized void load(Context c) {
        SharedPreferences sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        MAP.clear();
        for (String host : TRACKED) {
            String ip = sp.getString("ip_" + host, "");
            if (ip != null && !ip.isEmpty()) MAP.put(host, ip);
        }
    }

    public static synchronized void save(Context c, String host, String ip) {
        MAP.put(host, ip);
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("ip_" + host, ip).apply();
    }

    /** 只返回"测速验证过"的 IP；没验证过就返回 null，交给上游正常解析。 */
    public static synchronized String get(String host) {
        return MAP.get(host);
    }

    public static synchronized Map<String, String> snapshot() {
        return new LinkedHashMap<>(MAP);
    }
}
