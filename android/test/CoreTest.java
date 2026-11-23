package com.ghx.accel.core;

import java.io.File;
import java.util.List;

/** 真实网络测试：镜像测速 → 分片下载 → IP 优选。需要联网，约 40 秒。 */
public class CoreTest {
    public static void main(String[] a) throws Exception {
        List<Mirrors.Node> nodes = Mirrors.rank();
        require(!nodes.isEmpty(), "至少要有一个可用镜像");
        System.out.printf("PASS 镜像测速（最优 %s %.0f KiB/s，共 %d 个可用）%n",
                nodes.get(0).host, nodes.get(0).kbps, nodes.size());

        File out = File.createTempFile("ghx-core-", ".tar.gz");
        out.delete();
        long t = System.currentTimeMillis();
        Downloader.Result r = Downloader.fetch(
                "https://github.com/jqlang/jq/archive/refs/tags/jq-1.7.1.tar.gz",
                out, 4, nodes, null);
        long ms = System.currentTimeMillis() - t;
        require(r.bytes > 1_000_000, "下载大小异常 " + r.bytes);
        require(out.length() == r.bytes, "落盘大小不一致");
        System.out.printf("PASS 分片下载（%.0f KiB，%.1f 秒，节点 %s）%n",
                r.bytes / 1024.0, ms / 1000.0, r.node);
        //noinspection ResultOfMethodCallIgnored
        out.delete();

        List<IpPicker.Hit> hits = IpPicker.best("github.com", 3);
        require(!hits.isEmpty(), "IP 优选没有结果");
        System.out.printf("PASS IP 优选（%d 个候选通过 TLS+证书校验，最快 %s %dms）%n",
                hits.size(), hits.get(0).ip, hits.get(0).ms);

        System.out.println("CoreTest OK");
    }

    static void require(boolean ok, String what) {
        if (!ok) throw new AssertionError("FAIL: " + what);
    }
}
