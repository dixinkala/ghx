package com.ghx.accel.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

/** 极简 HTTP 客户端：跟随跳转、可限读字节数。纯 Java，无第三方依赖。 */
public final class Http {

    private Http() {}

    public static final class Resp {
        public int status;
        public final Map<String, String> headers = new HashMap<>();
        public byte[] body = new byte[0];
        public long elapsedMs;

        public String header(String k) {
            String v = headers.get(k.toLowerCase());
            return v == null ? "" : v;
        }
    }

    public static Resp get(String url, String range, int maxBytes, int timeoutMs) throws IOException {
        long t0 = System.currentTimeMillis();
        Resp resp = new Resp();
        String cur = url;
        for (int hop = 0; hop < 5; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(cur).openConnection();
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) ghx/2.0");
            c.setRequestProperty("Accept", "*/*");
            c.setRequestProperty("Accept-Encoding", "identity");
            if (range != null) c.setRequestProperty("Range", range);
            c.setInstanceFollowRedirects(false);
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null) throw new IOException("redirect without location");
                cur = new URL(new URL(cur), loc).toString();
                continue;
            }
            resp.status = code;
            for (Map.Entry<String, java.util.List<String>> e : c.getHeaderFields().entrySet()) {
                if (e.getKey() != null && e.getValue() != null && !e.getValue().isEmpty()) {
                    resp.headers.put(e.getKey().toLowerCase(), e.getValue().get(0));
                }
            }
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in != null) resp.body = readCapped(in, maxBytes);
            c.disconnect();
            resp.elapsedMs = System.currentTimeMillis() - t0;
            return resp;
        }
        throw new IOException("too many redirects");
    }

    public static byte[] readCapped(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        int n;
        while (out.size() < max && (n = in.read(buf)) > 0) {
            out.write(buf, 0, Math.min(n, max - out.size()));
        }
        in.close();
        return out.toByteArray();
    }
}
