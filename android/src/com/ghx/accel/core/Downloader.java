package com.ghx.accel.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 多镜像分片下载。
 * 分片写成独立的 .cN 文件（不是预分配大文件），所以进程被杀掉后重来能接着下；
 * 单流模式直接追加到目标文件，同样可续传。
 */
public final class Downloader {

    public interface Progress {
        void progress(long done, long total, String node);

        void note(String message);
    }

    public static final class Result {
        public long bytes;
        public String node = "";
        public double kbps;
    }

    private static final int CHUNK_MIN = 1 << 20; // 小于 1MB 不分片

    /** 用户点"暂停"时抛出；分片文件保留，下次接着下。 */
    public static final class Paused extends IOException {
        public Paused(String m) { super(m); }
    }

    private static volatile boolean cancelRequested = false;

    public static void resetCancel() { cancelRequested = false; }

    public static void requestCancel() { cancelRequested = true; }

    private static void checkCancel() throws IOException {
        if (cancelRequested) throw new Paused("已暂停");
    }

    private Downloader() {}

    private static HttpURLConnection open(String url, String range, int connectMs, int readMs)
            throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(connectMs);
        c.setReadTimeout(readMs);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) ghx/2.6");
        c.setRequestProperty("Accept-Encoding", "identity");
        if (range != null) c.setRequestProperty("Range", range);
        c.setInstanceFollowRedirects(true);
        return c;
    }

    private static long[] probeSize(String url) {
        HttpURLConnection c = null;
        try {
            c = open(url, "bytes=0-0", 10000, 10000);
            int code = c.getResponseCode();
            if (code == 206) {
                String cr = c.getHeaderField("Content-Range");
                if (cr != null && cr.contains("/")) {
                    try {
                        long total = Long.parseLong(cr.substring(cr.lastIndexOf('/') + 1).trim());
                        return new long[]{total, 1};
                    } catch (NumberFormatException ignored) { }
                }
            }
            long len = c.getContentLengthLong();
            boolean ranges = "bytes".equalsIgnoreCase(c.getHeaderField("Accept-Ranges"));
            return new long[]{len > 0 ? len : 0, ranges ? 1 : 0};
        } catch (Exception e) {
            return new long[]{0, 0};
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** 下载 [start,end] 到文件；支持片内续传（从已有长度继续追加）。 */
    private static void fetchRange(String url, File file, long start, long end,
                                   AtomicLong done, long total, String node, Progress p)
            throws IOException {
        long want = end - start + 1;
        long have = file.exists() ? file.length() : 0;
        if (have > want) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
            have = 0;
        }
        if (have == want) {
            return; // 已计入初始进度，不再重复加
        }
        long from = start + have;
        HttpURLConnection c = open(url, "bytes=" + from + "-" + end, 15000, 30000);
        try {
            int code = c.getResponseCode();
            if (code != 206) {
                // 该节点不支持分段（或区间被忽略），交给上层换节点
                throw new IOException("节点不支持分段 HTTP " + code);
            }
            FileOutputStream os = new FileOutputStream(file, have > 0);
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[64 * 1024];
                int n;
                long last = 0;
                while ((n = in.read(buf)) > 0) {
                    checkCancel();
                    os.write(buf, 0, n);
                    long d = done.addAndGet(n);
                    if (d - last > 256 * 1024) {
                        last = d;
                        if (p != null) p.progress(d, total, node);
                    }
                }
            } finally {
                os.close();
            }
            if (file.length() != want) throw new IOException("分片不完整");
            if (p != null) p.progress(done.get(), total, node);
        } finally {
            c.disconnect();
        }
    }

    private static void copyStream(String url, File out, long have, long size,
                                   String node, Progress p) throws IOException {
        // 用闭区间 Range：部分镜像不支持 "bytes=N-" 这种开放区间，会直接回 200 全量
        String range = null;
        if (have > 0) {
            range = size > 0 ? "bytes=" + have + "-" + (size - 1) : "bytes=" + have + "-";
        }
        HttpURLConnection c = open(url, range, 15000, 30000);
        try {
            int code = c.getResponseCode();
            if (code != 200 && code != 206) throw new IOException("HTTP " + code);
            boolean append = have > 0 && code == 206;
            long done = append ? have : 0;
            FileOutputStream os = new FileOutputStream(out, append);
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[64 * 1024];
                int n;
                long last = 0;
                while ((n = in.read(buf)) > 0) {
                    checkCancel();
                    os.write(buf, 0, n);
                    done += n;
                    if (done - last > 256 * 1024) {
                        last = done;
                        if (p != null) p.progress(done, size, node);
                    }
                }
            } finally {
                os.close();
            }
            if (p != null) p.progress(done, size, node);
        } finally {
            c.disconnect();
        }
    }

    private static void concat(File[] parts, File out) throws IOException {
        FileOutputStream os = new FileOutputStream(out);
        try {
            byte[] buf = new byte[128 * 1024];
            for (File part : parts) {
                try (FileInputStream in = new FileInputStream(part)) {
                    int n;
                    while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                }
            }
        } finally {
            os.close();
        }
    }

    public static File[] chunkFiles(File out, int n) {
        File[] parts = new File[n];
        for (int i = 0; i < n; i++) {
            parts[i] = new File(out.getParentFile(), out.getName() + ".c" + i);
        }
        return parts;
    }

    public static Result fetch(String rawUrl, File out, int jobs, List<Mirrors.Node> ranked,
                               Progress p) throws IOException {
        String target = Mirrors.normalize(rawUrl);
        List<String> cands = Mirrors.candidateUrls(target, ranked);
        List<String> errors = new ArrayList<>();
        long t0 = System.currentTimeMillis();

        for (String cu : cands) {
            String node = nodeOf(cu);
            if (p != null) p.note("尝试 " + node);
            long[] meta = probeSize(cu);
            long size = meta[0];
            boolean ranges = meta[1] == 1;
            File[] parts = null;

            try {
                if (size > 0 && ranges && size > CHUNK_MIN && jobs > 1) {
                    int n = jobs;
                    long chunk = (size + n - 1) / n;
                    parts = chunkFiles(out, n);
                    AtomicLong done = new AtomicLong(0);
                    // 初始进度 = 磁盘上已有的分片字节（暂停后续传时进度不会归零）
                    for (int i = 0; i < n; i++) {
                        long start = i * chunk;
                        long end = Math.min(size - 1, start + chunk - 1);
                        if (start > end) continue;
                        File f = parts[i];
                        if (f.exists()) done.addAndGet(Math.min(f.length(), end - start + 1));
                    }
                    List<IOException> errs = new ArrayList<>();
                    java.util.List<Thread> ts = new ArrayList<>();
                    for (int i = 0; i < n; i++) {
                        final long start = i * chunk;
                        final long end = Math.min(size - 1, start + chunk - 1);
                        if (start > end) continue;
                        final File f = parts[i];
                        Thread t = new Thread(new Runnable() {
                            public void run() {
                                try {
                                    fetchRange(cu, f, start, end, done, size, node, p);
                                } catch (IOException ex) {
                                    synchronized (errs) { errs.add(ex); }
                                }
                            }
                        });
                        ts.add(t);
                        t.start();
                    }
                    for (Thread t : ts) {
                        try { t.join(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    }
                    if (!errs.isEmpty()) {
                        for (IOException ex : errs) {
                            if (ex instanceof Paused) throw ex;
                        }
                        throw errs.get(0);
                    }
                    concat(parts, out);
                    for (File f : parts) {
                        //noinspection ResultOfMethodCallIgnored
                        f.delete();
                    }
                } else {
                    long have = out.exists() ? out.length() : 0;
                    if (size > 0 && have >= size) {
                        Result r = new Result();
                        r.bytes = have;
                        r.node = node;
                        if (p != null) p.note("已存在完整文件");
                        return r;
                    }
                    copyStream(cu, out, have, size, node, p);
                }
                long got = out.length();
                if (size > 0 && got != size) throw new IOException("大小不符 " + got + "/" + size);
                Result r = new Result();
                r.bytes = got;
                r.node = node;
                r.kbps = got / 1024.0 / Math.max(0.001, (System.currentTimeMillis() - t0) / 1000.0);
                return r;
            } catch (Paused e) {
                throw e;
            } catch (IOException e) {
                errors.add(node + ": " + e.getMessage());
            }
        }
        throw new IOException("所有节点失败\n" + String.join("\n", errors));
    }

    public static String nodeOf(String url) {
        try {
            String host = new URL(url).getHost();
            return host.contains("github.com") || host.contains("githubusercontent.com")
                    ? "直连" : host;
        } catch (Exception e) {
            return "?";
        }
    }
}
