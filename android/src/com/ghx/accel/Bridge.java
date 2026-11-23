package com.ghx.accel;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.JavascriptInterface;

import com.ghx.accel.core.Downloader;
import com.ghx.accel.core.Http;
import com.ghx.accel.core.IpPicker;
import com.ghx.accel.core.Mirrors;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 网页界面与原生能力的桥：所有耗时操作都在后台线程跑，结果回推给 JS。 */
public class Bridge {

    private final MainActivity act;
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private volatile List<Mirrors.Node> ranked = new ArrayList<>();

    public Bridge(MainActivity act) {
        this.act = act;
    }

    @JavascriptInterface
    public void call(String action, String payload, String id) {
        pool.submit(new Runnable() {
            public void run() {
                try {
                    JSONObject p = payload == null || payload.isEmpty()
                            ? new JSONObject() : new JSONObject(payload);
                    JSONObject r = dispatch(action, p);
                    reply(id, r);
                } catch (Exception e) {
                    replyError(id, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                }
            }
        });
    }

    private JSONObject dispatch(String action, JSONObject p) throws Exception {
        switch (action) {
            case "state": return state();
            case "mirrors": return refreshMirrors();
            case "ips": return optimizeIps();
            case "resolve": return resolve(p.getString("url"));
            case "download": return download(p.getString("url"));
            case "cancel": Downloader.requestCancel(); return new JSONObject().put("ok", true);
            case "read": return read(p.getString("url"));
            case "vpn": return vpn(p.optBoolean("on", false), p.optString("id"));
            default: throw new IllegalArgumentException("未知操作 " + action);
        }
    }

    /** 复用测速结果（30 分钟内），避免每次下载都先等一轮测速。 */
    private List<Mirrors.Node> ensureRanked() {
        if (!ranked.isEmpty()) return ranked;
        android.content.SharedPreferences sp =
                act.getSharedPreferences("ghx", android.content.Context.MODE_PRIVATE);
        long ts = sp.getLong("rank_ts", 0);
        String data = sp.getString("rank_data", "");
        if (!data.isEmpty() && System.currentTimeMillis() - ts < 12L * 60 * 60 * 1000) {
            List<Mirrors.Node> list = new ArrayList<>();
            for (String part : data.split(",")) {
                String[] kv = part.split("\\|");
                if (kv.length != 2) continue;
                try {
                    Mirrors.Node n = new Mirrors.Node(kv[0]);
                    n.ok = true;
                    n.kbps = Double.parseDouble(kv[1]);
                    list.add(n);
                } catch (NumberFormatException ignored) { }
            }
            if (!list.isEmpty()) {
                ranked = list;
                return list;
            }
        }
        pushNote("正在测速镜像节点…");
        ranked = Mirrors.rank();
        saveRank();
        return ranked;
    }

    private void saveRank() {
        StringBuilder sb = new StringBuilder();
        for (Mirrors.Node n : ranked) {
            if (sb.length() > 0) sb.append(',');
            sb.append(n.host).append('|').append(Math.round(n.kbps));
        }
        act.getSharedPreferences("ghx", android.content.Context.MODE_PRIVATE).edit()
                .putString("rank_data", sb.toString())
                .putLong("rank_ts", System.currentTimeMillis())
                .apply();
    }

    private JSONObject state() throws Exception {
        JSONObject o = new JSONObject();
        o.put("vpn", DnsVpn.isRunning());
        JSONArray ms = new JSONArray();
        for (Mirrors.Node n : ranked) {
            ms.put(new JSONObject().put("host", n.host).put("kbps", n.kbps));
        }
        o.put("mirrors", ms);
        JSONObject ips = new JSONObject();
        for (Map.Entry<String, String> e : DnsConfig.snapshot().entrySet()) ips.put(e.getKey(), e.getValue());
        o.put("ips", ips);
        if (DnsVpn.isRunning()) {
            try {
                String ip = java.net.InetAddress.getByName("github.com").getHostAddress();
                o.put("resolved", ip);
                String want = DnsConfig.get("github.com");
                if (want != null && !want.equals(ip)) o.put("resolvedStale", true);
            } catch (Exception ignored) { }
        }
        o.put("version", "2.0");
        o.put("dir", "下载/ghx");
        return o;
    }

    private JSONObject refreshMirrors() throws Exception {
        ranked = Mirrors.rank();
        saveRank();
        JSONArray ms = new JSONArray();
        for (Mirrors.Node n : ranked) {
            ms.put(new JSONObject().put("host", n.host).put("kbps", Math.round(n.kbps)));
        }
        try {
            return new JSONObject().put("ok", true).put("mirrors", ms);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private JSONObject optimizeIps() throws Exception {
        JSONObject ips = new JSONObject();
        for (String host : DnsConfig.TRACKED) {
            List<IpPicker.Hit> hits = IpPicker.best(host, 1);
            if (!hits.isEmpty()) {
                DnsConfig.save(act, host, hits.get(0).ip);
                ips.put(host, hits.get(0).ip);
            }
        }
        return new JSONObject().put("ok", true).put("ips", ips);
    }

    private JSONObject resolve(String url) throws Exception {
        String target = Mirrors.normalize(url);
        List<Mirrors.Node> nodes = ensureRanked();
        String first = nodes.isEmpty() ? target : nodes.get(0).build(target);
        Http.Resp r = Http.get(first, "bytes=0-0", 1, 12000);
        long size = -1;
        String cr = r.header("content-range");
        if (cr.contains("/")) {
            try { size = Long.parseLong(cr.substring(cr.lastIndexOf('/') + 1)); } catch (Exception ignored) { }
        } else if (!r.header("content-length").isEmpty()) {
            try { size = Long.parseLong(r.header("content-length")); } catch (Exception ignored) { }
        }
        String name = nameFrom(nodes.isEmpty() ? first : first);
        JSONArray arr = new JSONArray();
        for (String cu : Mirrors.candidateUrls(target, nodes)) arr.put(cu);
        return new JSONObject().put("ok", true).put("name", name)
                .put("size", size).put("target", target).put("links", arr);
    }

    private JSONObject download(String url) throws Exception {
        String target = Mirrors.normalize(url);
        List<Mirrors.Node> nodes = ensureRanked();
        String name = nameFrom(target);
        // 固定文件名：进程被杀后重来能接着下（旧版是时间戳，等于每次都从头）
        String safe = name.replaceAll("[^A-Za-z0-9._-]", "_");
        if (safe.length() > 80) safe = safe.substring(safe.length() - 80);
        // files 目录不会被系统当缓存清掉，续传更可靠
        File cache = new File(act.getFilesDir(), "part-" + safe);
        Downloader.resetCancel();
        Downloader.Result res;
        try {
            res = Downloader.fetch(target, cache, 4, nodes, new Downloader.Progress() {
                public void progress(long done, long total, String node) {
                    pushProgress(done, total, node);
                }
                public void note(String message) { pushNote(message); }
            });
        } catch (Downloader.Paused e) {
            pushNote("已暂停，分片已保留");
            return new JSONObject().put("ok", false).put("paused", true);
        }
        String saved = saveToDownloads(cache, name);
        //noinspection ResultOfMethodCallIgnored
        cache.delete();
        for (File f : Downloader.chunkFiles(cache, 4)) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
        return new JSONObject().put("ok", true).put("name", saved)
                .put("bytes", res.bytes).put("node", res.node);
    }

    private JSONObject read(String url) throws Exception {
        String u = url.trim();
        // 支持三种写法：owner/repo、github.com/owner/repo、完整 URL
        if (u.matches("[\\w.-]+/[\\w.-]+")) {
            u = "https://github.com/" + u;
        } else if (u.startsWith("github.com/")) {
            u = "https://" + u;
        } else if (!u.startsWith("http")) {
            u = "https://" + u;
        }
        List<String> candidates = new ArrayList<>();
        if (u.matches(".*github\\.com/[^/]+/[^/]+/?$")
                || u.matches(".*github\\.com/[^/]+/[^/]+/tree/.*")) {
            String base = u.replaceAll("/+$", "");
            int i = base.indexOf("github.com/");
            String repo = base.substring(i + "github.com/".length());
            String[] parts = repo.split("/");
            String root = "https://raw.githubusercontent.com/" + parts[0] + "/" + parts[1] + "/HEAD/";
            for (String f : new String[]{"README.md", "README.rst", "README.txt", "README", "readme.md"}) {
                candidates.add(root + f);
            }
        } else {
            candidates.add(Mirrors.normalize(u));
        }

        List<Mirrors.Node> nodes = ensureRanked();

        ExecutorService pool = Executors.newFixedThreadPool(6);
        java.util.concurrent.CompletionService<Object[]> cs =
                new java.util.concurrent.ExecutorCompletionService<>(pool);
        for (int i = 0; i < candidates.size(); i++) {
            final int idx = i;
            final String cand = candidates.get(i);
            for (final String cu : Mirrors.candidateUrls(cand, nodes)) {
                cs.submit(new java.util.concurrent.Callable<Object[]>() {
                    public Object[] call() {
                        try {
                            Http.Resp r = Http.get(cu, null, 400 * 1024, 6000);
                            if (r.status != 200) return null;
                            String text = new String(r.body, "UTF-8");
                            String low = text.trim().toLowerCase();
                            if (text.trim().length() < 8) return null;
                            if (low.equals("404: not found") || low.equals("not found")
                                    || low.equals("404 page not found")) return null;
                            if (low.startsWith("<!doctype html") || low.startsWith("<html")) return null;
                            return new Object[]{idx, text, cand};
                        } catch (Exception e) {
                            return null;
                        }
                    }
                });
            }
        }

        Object[] best = null;
        long deadline = System.currentTimeMillis() + 12000;
        int remaining = candidates.size() * 6;
        while (remaining-- > 0) {
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) break;
            try {
                java.util.concurrent.Future<Object[]> f =
                        cs.poll(left, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (f == null) break;
                Object[] r = f.get();
                if (best == null || (int) r[0] < (int) best[0]) best = r;
                if (best != null && (int) best[0] == 0) break;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        pool.shutdownNow();
        if (best == null) throw new Exception("抓不到内容（仓库不存在或镜像都失败）");
        String src = (String) best[2];
        return new JSONObject().put("ok", true).put("text", best[1])
                .put("name", src.substring(src.lastIndexOf('/') + 1))
                .put("source", src);
    }

    private JSONObject vpn(boolean on, String callId) {
        act.toggleVpn(on);
        try {
            JSONObject o = new JSONObject().put("ok", true).put("vpn", on);
            if (on && DnsVpn.LAST_ERROR != null) o.put("error", DnsVpn.LAST_ERROR);
            return o;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private String saveToDownloads(File file, String name) throws Exception {
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        v.put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream");
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ghx");
        v.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = act.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        if (uri == null) throw new Exception("无法写入下载目录");
        try (OutputStream os = act.getContentResolver().openOutputStream(uri);
             FileInputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[128 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        }
        ContentValues done = new ContentValues();
        done.put(MediaStore.MediaColumns.IS_PENDING, 0);
        act.getContentResolver().update(uri, done, null, null);
        return name;
    }

    private void pushProgress(long done, long total, String node) {
        try {
            JSONObject o = new JSONObject().put("done", done).put("total", total).put("node", node);
            evalJs("window.__ghxProgress&&window.__ghxProgress(" + o + ");");
        } catch (Exception ignored) { }
    }

    /** 进度文案（"尝试 xx 节点"/"开始分片"），让用户看到卡在哪一步。 */
    private void pushNote(String message) {
        try {
            evalJs("window.__ghxNote&&window.__ghxNote(" + JSONObject.quote(message) + ");");
        } catch (Exception ignored) { }
    }

    /** Activity 可能已销毁，避免在 UI 线程上 NPE。 */
    private void evalJs(final String js) {
        final MainActivity a = act;
        a.runOnUiThread(new Runnable() {
            public void run() {
                android.webkit.WebView w = a.webView;
                if (w != null) w.evaluateJavascript(js, null);
            }
        });
    }

    private static String nameFrom(String url) {
        try {
            String p = new java.net.URL(url).getPath();
            String name = p.substring(p.lastIndexOf('/') + 1);
            return name.isEmpty() ? "download.bin" : name;
        } catch (Exception e) {
            return "download.bin";
        }
    }

    private void reply(String id, JSONObject obj) {
        evalJs("window.__ghx&&window.__ghx('" + id + "'," + obj + ");");
    }

    private void replyError(String id, String msg) {
        try {
            reply(id, new JSONObject().put("error", msg));
        } catch (Exception ignored) { }
    }
}
