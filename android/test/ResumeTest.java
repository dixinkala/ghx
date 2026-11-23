package com.ghx.accel.core;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.util.List;

/** 续传测试：伪造"已经下了一半"的文件，验证分片与单流都能接着下。 */
public class ResumeTest {
    static final String URL =
            "https://github.com/jqlang/jq/releases/download/jq-1.7.1/jq-linux-arm64";
    static final String BIG =
            "https://github.com/neovim/neovim/releases/download/v0.10.2/nvim-linux64.tar.gz";

    public static void main(String[] a) throws Exception {
        List<Mirrors.Node> nodes = Mirrors.rank();
        require(!nodes.isEmpty(), "需要至少一个镜像");

        // 1) 分片续传：第 0 片写满占位数据，应被跳过；其余片重新下载
        File dir = new File(System.getProperty("java.io.tmpdir"), "ghx-resume");
        dir.mkdirs();
        File out = new File(dir, "chunked.bin");
        out.delete();
        File[] parts = Downloader.chunkFiles(out, 4);
        for (File f : parts) f.delete();
        long size = 1709616L;
        long chunk = (size + 3) / 4;
        long c0 = chunk;
        try (FileOutputStream os = new FileOutputStream(parts[0])) {
            os.write(new byte[(int) c0]);
        }
        Downloader.Result r = Downloader.fetch(URL, out, 4, nodes, null);
        require(out.length() == size, "分片续传后大小不对: " + out.length());
        byte[] head = new byte[16];
        try (RandomAccessFile raf = new RandomAccessFile(out, "r")) { raf.readFully(head); }
        boolean zeros = true;
        for (byte b : head) if (b != 0) zeros = false;
        require(zeros, "第 0 片应被复用（占位零字节），说明没有重新下载");
        require(!parts[0].exists(), "完成后分片文件应清理");
        System.out.printf("PASS 分片续传（复用已完成的片，最终 %d 字节）%n", r.bytes);

        // 2) 单流续传：先把目标文件截到一半，再下应只补后半段
        File single = new File(dir, "single.bin");
        single.delete();
        try (FileOutputStream os = new FileOutputStream(single)) {
            os.write(new byte[(int) (size / 2)]);
        }
        System.out.println("  before fetch: len=" + single.length());
        Downloader.Result r2 = Downloader.fetch(URL, single, 1, nodes, null);
        try (RandomAccessFile raf = new RandomAccessFile(single, "r")) {
            long firstNonZero = -1;
            for (long i = 0; i < Math.min(single.length(), 2000000L); i += 4096) {
                raf.seek(i);
                if (raf.read() != 0) { firstNonZero = i; break; }
            }
            System.out.println("  first non-zero byte at " + firstNonZero + ", len=" + single.length());
        }
        require(single.length() == size, "单流续传后大小不对: " + single.length());
        byte[] mid = new byte[8];
        try (RandomAccessFile raf = new RandomAccessFile(single, "r")) {
            raf.seek(size / 2 - 64);
            raf.readFully(mid);
        }
        boolean frontZeros = true;
        for (byte b : mid) if (b != 0) frontZeros = false;
        require(frontZeros, "前半段应保留（占位零字节）");
        System.out.printf("PASS 单流续传（只补后半段，最终 %d 字节）%n", r2.bytes);
        // 3) 片内续传：第 1 片只写了一小段，应从该长度继续追加
        File out3 = new File(dir, "inchunk.bin");
        out3.delete();
        for (File f : Downloader.chunkFiles(out3, 4)) f.delete();
        File[] parts3 = Downloader.chunkFiles(out3, 4);
        long chunk3 = (size + 3) / 4;
        int keep = 100 * 1024;
        try (FileOutputStream os = new FileOutputStream(parts3[1])) {
            os.write(new byte[keep]);
        }
        Downloader.Result r3 = Downloader.fetch(URL, out3, 4, nodes, null);
        require(out3.length() == size, "片内续传后大小不对");
        byte[] probe = new byte[8];
        try (RandomAccessFile raf = new RandomAccessFile(out3, "r")) {
            raf.seek(chunk3 + keep / 2);
            raf.readFully(probe);
        }
        boolean kept = true;
        for (byte b : probe) if (b != 0) kept = false;
        require(kept, "第 1 片已写部分应被保留");
        try (RandomAccessFile raf = new RandomAccessFile(out3, "r")) {
            raf.seek(chunk3 + keep + 256 * 1024);
            raf.readFully(probe);
        }
        boolean filled = false;
        for (byte b : probe) if (b != 0) filled = true;
        require(filled, "第 1 片剩余部分应重新下载");
        System.out.printf("PASS 片内续传（保留已写 %d KB，继续补齐）%n", keep / 1024);

        // 4) 暂停/继续：下到一半取消，分片保留；再下能直接接着完成
        File out4 = new File(dir, "pause.bin");
        out4.delete();
        for (File f : Downloader.chunkFiles(out4, 4)) f.delete();
        Downloader.resetCancel();
        final boolean[] paused = {false};
        final Exception[] err = {null};
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    Downloader.fetch(BIG, out4, 4, nodes, null);
                } catch (Downloader.Paused e) {
                    paused[0] = true;
                } catch (Exception e) {
                    err[0] = e;
                }
            }
        });
        t.start();
        // 等到确实写了一些分片数据再暂停（之前 700ms 太早，还在探测阶段）
        long waitUntil = System.currentTimeMillis() + 30000;
        while (System.currentTimeMillis() < waitUntil) {
            long sz = 0;
            for (File f : Downloader.chunkFiles(out4, 4)) if (f.exists()) sz += f.length();
            if (sz > 256 * 1024) break;
            Thread.sleep(100);
        }
        Downloader.requestCancel();
        t.join(30000);
        require(err[0] == null, "暂停时不应有其他异常: " + err[0]);
        require(paused[0], "点暂停后应抛 Paused");
        require(out4.length() == 0, "未合并前不应生成最终文件");
        long keptBytes = 0;
        for (File f : Downloader.chunkFiles(out4, 4)) if (f.exists()) keptBytes += f.length();
        require(keptBytes > 0, "分片应保留（当前 " + keptBytes + " 字节）");
        Downloader.resetCancel();
        final long[] firstDone = {-1};
        final long[] maxDone = {0};
        Downloader.Progress pp = new Downloader.Progress() {
            public void progress(long d, long t, String n) {
                if (firstDone[0] < 0) firstDone[0] = d;
                maxDone[0] = d;
            }
            public void note(String m) { }
        };
        Downloader.Result r4 = Downloader.fetch(BIG, out4, 4, nodes, pp);
        require(firstDone[0] >= keptBytes, "继续后进度应从已有字节起算（first="
                + firstDone[0] + " kept=" + keptBytes + "）");
        require(maxDone[0] == r4.bytes, "最终进度应等于文件大小");
        require(out4.length() == r4.bytes && r4.bytes > 10_000_000, "继续后应完成整个文件");
        System.out.printf("PASS 暂停/继续（暂停时保留 %d KB，继续后完成 %d MB）%n",
                keptBytes / 1024, r4.bytes / 1024 / 1024);

        System.out.println("ResumeTest OK");
    }

    static void require(boolean ok, String what) {
        if (!ok) throw new AssertionError("FAIL: " + what);
    }
}
