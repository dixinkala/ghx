package com.ghx.accel.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** 公开镜像节点：实测速度排序，贴 GitHub 原链接即用。 */
public final class Mirrors {

    /** 测速用的稳定文件（jq 的 arm64 二进制，约 1.6MB）。 */
    public static final String TEST_URL =
            "https://github.com/jqlang/jq/releases/download/jq-1.7.1/jq-linux-arm64";
    public static final int TEST_BYTES = 512 * 1024;

    public static final String[] HOSTS = {
            "gh-proxy.com", "ghfast.top", "ghproxy.net", "ghproxy.cfd", "gh-proxy.net",
    };

    public static final class Node {
        public final String host;
        public boolean ok;
        public double kbps;
        public String error = "";

        public Node(String host) { this.host = host; }

        public String build(String target) {
            return "https://" + host + "/" + target;
        }
    }

    private Mirrors() {}

    public static Node probe(Node n) {
        long t0 = System.currentTimeMillis();
        try {
            Http.Resp r = Http.get(n.build(TEST_URL),
                    "bytes=0-" + (TEST_BYTES - 1), TEST_BYTES, 8000);
            int got = r.body.length;
            if (got < 32 * 1024) {
                n.error = "内容太小";
                return n;
            }
            n.ok = true;
            n.kbps = got / 1024.0 / Math.max(0.001, (System.currentTimeMillis() - t0) / 1000.0);
        } catch (Exception e) {
            n.error = e.getClass().getSimpleName();
        }
        return n;
    }

    /** 并发测速，返回按速度降序的结果（只含可用的）。 */
    public static List<Node> rank() {
        List<Node> nodes = new ArrayList<>();
        for (String h : HOSTS) nodes.add(new Node(h));
        ExecutorService pool = Executors.newFixedThreadPool(4);
        List<Node> ok = new ArrayList<>();
        // 总预算 25 秒：某个镜像 DNS 卡住时不能拖死整个界面
        long deadline = System.currentTimeMillis() + 25000;
        try {
            List<Future<Node>> fs = new ArrayList<>();
            for (Node n : nodes) {
                fs.add(pool.submit(new Callable<Node>() {
                    public Node call() { return probe(n); }
                }));
            }
            for (Future<Node> f : fs) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) break;
                try {
                    Node n = f.get(left, java.util.concurrent.TimeUnit.MILLISECONDS);
                    if (n.ok) ok.add(n);
                } catch (java.util.concurrent.TimeoutException te) {
                    f.cancel(true);
                } catch (Exception ignored) {
                }
            }
            Collections.sort(ok, new java.util.Comparator<Node>() {
                public int compare(Node a, Node b) { return Double.compare(b.kbps, a.kbps); }
            });
        } finally {
            pool.shutdownNow();
        }
        return ok;
    }

    public static List<String> candidateUrls(String target, List<Node> ranked) {
        List<String> out = new ArrayList<>();
        for (Node n : ranked) out.add(n.build(target));
        out.add(target);
        return out;
    }

    /** 把 github.com 网页链接换成可直下的真实地址。 */
    public static String normalize(String raw) {
        String url = raw.trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://" + url;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^https?://github\\.com/([^/]+)/([^/]+)/blob/(.+)$").matcher(url);
        if (m.matches()) {
            return "https://raw.githubusercontent.com/" + m.group(1) + "/" + m.group(2) + "/" + m.group(3);
        }
        if (url.contains("github.com/") && url.contains("/raw/")) {
            return url.replace("github.com", "raw.githubusercontent.com");
        }
        return url;
    }
}
