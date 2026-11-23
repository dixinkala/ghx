package com.ghx.accel.core;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/** 给 github 相关域名挑选真实、可用、较快的 IP（hosts/DNS 加速的数据来源）。 */
public final class IpPicker {

    public static final String[][] POOLS = {
            {"github.com",
             "20.27.177.113", "20.200.245.247", "20.205.243.166", "20.247.166.64",
             "20.6.7.17", "4.237.22.38", "140.82.113.3"},
            {"api.github.com",
             "20.205.243.168", "20.200.245.245", "20.27.177.116", "140.82.112.5"},
            {"codeload.github.com",
             "20.6.7.16", "20.247.166.65", "20.200.245.246", "20.205.243.165"},
    };

    public static final class Hit {
        public String host;
        public String ip;
        public long ms;
        public boolean ok;
        public String error = "";
    }

    private IpPicker() {}

    public static List<String> pool(String host) {
        for (String[] row : POOLS) {
            if (row[0].equals(host)) {
                List<String> out = new ArrayList<>();
                for (int i = 1; i < row.length; i++) out.add(row[i]);
                return out;
            }
        }
        return Collections.emptyList();
    }

    /** 连 IP、按域名做 SNI 和证书校验，再发一个 GET / 看响应。 */
    public static Hit probe(String host, String ip, int timeoutMs) {
        Hit h = new Hit();
        h.host = host;
        h.ip = ip;
        long t0 = System.currentTimeMillis();
        try {
            SSLSocketFactory f = (SSLSocketFactory) SSLSocketFactory.getDefault();
            Socket raw = new Socket();
            raw.connect(new InetSocketAddress(ip, 443), timeoutMs);
            raw.setSoTimeout(timeoutMs);
            SSLSocket s = (SSLSocket) f.createSocket(raw, host, 443, true);
            SSLParameters params = s.getSSLParameters();
            params.setServerNames(Collections.singletonList(
                    new javax.net.ssl.SNIHostName(host)));
            s.setSSLParameters(params);
            s.startHandshake();
            SSLSession session = s.getSession();
            Certificate[] chain = session.getPeerCertificates();
            if (chain.length == 0) throw new java.security.cert.CertificateException("无证书");
            if (!(chain[0] instanceof java.security.cert.X509Certificate)
                    || !hostMatches((java.security.cert.X509Certificate) chain[0], host)) {
                throw new java.security.cert.CertificateException("证书域名不符");
            }
            OutputStream os = s.getOutputStream();
            os.write(("GET / HTTP/1.1\r\nHost: " + host + "\r\nUser-Agent: ghx\r\n"
                    + "Accept-Encoding: identity\r\nConnection: close\r\n\r\n").getBytes("ASCII"));
            os.flush();
            InputStream in = s.getInputStream();
            byte[] buf = new byte[2048];
            int n = in.read(buf);
            h.ms = System.currentTimeMillis() - t0;
            h.ok = n > 0 && (buf[9] == '2' || buf[9] == '3');
            if (!h.ok) h.error = n <= 0 ? "无响应" : "HTTP 异常";
            s.close();
        } catch (Exception e) {
            h.ms = System.currentTimeMillis() - t0;
            h.error = e.getClass().getSimpleName();
        }
        return h;
    }

    /** 校验证书里是否包含该域名（支持一级通配符）。 */
    private static boolean hostMatches(java.security.cert.X509Certificate cert, String host) {
        try {
            java.util.Collection<java.util.List<?>> sans = cert.getSubjectAlternativeNames();
            if (sans != null) {
                for (java.util.List<?> san : sans) {
                    if (san.size() < 2 || !(san.get(0) instanceof Integer)) continue;
                    if (((Integer) san.get(0)) != 2) continue;
                    String dns = String.valueOf(san.get(1)).toLowerCase();
                    if (dns.equals(host)) return true;
                    if (dns.startsWith("*.") && host.endsWith(dns.substring(1))) {
                        String head = host.substring(0, host.length() - dns.length() + 1);
                        if (head.indexOf('.') < 0) return true;
                    }
                }
            }
        } catch (Exception ignored) { }
        return false;
    }

    /** 并发探测某个域名的候选 IP，按延迟升序返回可用的。 */
    public static List<Hit> best(String host, int limit) {
        List<String> ips = pool(host);
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(6, Math.max(1, ips.size())));
        List<Hit> ok = new ArrayList<>();
        try {
            List<Future<Hit>> fs = new ArrayList<>();
            for (String ip : ips) {
                fs.add(pool.submit(new Callable<Hit>() {
                    public Hit call() { return probe(host, ip, 5000); }
                }));
            }
            for (Future<Hit> f : fs) {
                try {
                    Hit h = f.get();
                    if (h.ok) ok.add(h);
                } catch (Exception ignored) { }
            }
        } finally {
            pool.shutdownNow();
        }
        Collections.sort(ok, new java.util.Comparator<Hit>() {
            public int compare(Hit a, Hit b) { return Long.compare(a.ms, b.ms); }
        });
        return ok.size() > limit ? ok.subList(0, limit) : ok;
    }
}
