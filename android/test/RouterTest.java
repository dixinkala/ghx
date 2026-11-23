package com.ghx.accel.core;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

/** 路由测试：模拟 tun 收到的报文，验证"自己答"和"转发上游"两条路径。 */
public class RouterTest {

    static byte[] query(String name, int id) {
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        o.write(id >> 8); o.write(id & 0xFF);
        o.write(0x01); o.write(0x00);
        o.write(0); o.write(1); o.write(0); o.write(0); o.write(0); o.write(0); o.write(0); o.write(0);
        for (String p : name.split("\\.")) {
            o.write(p.length());
            for (char c : p.toCharArray()) o.write(c);
        }
        o.write(0); o.write(0x00); o.write(0x01); o.write(0x00); o.write(0x01);
        return o.toByteArray();
    }

    /** 模拟客户端（10.66.66.2:port）发给 DNS（10.66.66.1:53）的包。 */
    static byte[] fromClient(byte[] dns, int port) {
        return DnsPacket.wrapUdp(dns, DnsPacket.ipv4("10.66.66.2"), port,
                DnsPacket.ipv4("10.66.66.1"), 53);
    }

    static byte[] upstreamSocket(byte[] dns) throws Exception {
        try (DatagramSocket s = new DatagramSocket()) {
            s.setSoTimeout(4000);
            InetAddress up = InetAddress.getByName("223.5.5.5");
            s.send(new DatagramPacket(dns, dns.length, up, 53));
            byte[] buf = new byte[4096];
            DatagramPacket r = new DatagramPacket(buf, buf.length);
            s.receive(r);
            return java.util.Arrays.copyOf(buf, r.getLength());
        }
    }

    static void checkUdpChecksum(byte[] pkt) {
        int u = 20;
        int ulen = ((pkt[u + 4] & 0xFF) << 8) | (pkt[u + 5] & 0xFF);
        int pseudo = DnsPacket.sumWords(pkt, 12, 8, 0) + 17 + ulen;
        int sum = DnsPacket.finish(DnsPacket.sumWords(pkt, u, ulen, pseudo));
        require(sum == 0, "UDP 校验和必须自洽");
        require(DnsPacket.checksum(pkt, 0, 20, 0) == 0, "IP 校验和必须自洽");
    }

    /** 解析应答里的 A 记录。 */
    static List<String> aRecords(byte[] dns) {
        List<String> out = new ArrayList<>();
        int off = 12;
        while ((dns[off] & 0xFF) != 0) off += 1 + (dns[off] & 0xFF);
        off += 5;
        int an = ((dns[6] & 0xFF) << 8) | (dns[7] & 0xFF);
        for (int i = 0; i < an; i++) {
            if ((dns[off] & 0xC0) == 0xC0) off += 2;
            else { while ((dns[off] & 0xFF) != 0) off += 1 + (dns[off] & 0xFF); off++; }
            int type = ((dns[off] & 0xFF) << 8) | (dns[off + 1] & 0xFF);
            off += 8;
            int rdlen = ((dns[off] & 0xFF) << 8) | (dns[off + 1] & 0xFF);
            off += 2;
            if (type == 1 && rdlen == 4) out.add(DnsPacket.ipToString(dns, off));
            off += rdlen;
        }
        return out;
    }

    public static void main(String[] a) throws Exception {
        final List<byte[]> out = new ArrayList<>();
        DnsRouter router = new DnsRouter(new DnsRouter.Mapper() {
            public String ipFor(String host) {
                return "github.com".equals(host) ? "20.27.177.113" : null;
            }
        }, "10.66.66.1", 300);

        DnsRouter.Sink sink = new DnsRouter.Sink() {
            public void send(byte[] p) { out.add(p); }
        };
        DnsRouter.Upstream mustNotCall = new DnsRouter.Upstream() {
            public byte[] resolve(byte[] q) { throw new AssertionError("命中映射时不该访问上游"); }
        };

        // 1) 映射命中：应立即应答
        router.handle(fromClient(query("github.com", 0x1111), 40001), sink, mustNotCall);
        require(out.size() == 1, "命中映射应有 1 个应答");
        byte[] r1 = out.get(0);
        checkUdpChecksum(r1);
        byte[] d1 = java.util.Arrays.copyOfRange(r1, 28, r1.length);
        require(((d1[0] & 0xFF) << 8 | (d1[1] & 0xFF)) == 0x1111, "DNS id 回显");
        require(aRecords(d1).equals(List.of("20.27.177.113")), "应答 IP = 映射值 " + aRecords(d1));
        System.out.println("PASS 命中映射（github.com -> 20.27.177.113，校验和自洽）");

        // 2) 未命中：转发到真实上游
        router.handle(fromClient(query("example.com", 0x2222), 40002), sink, new DnsRouter.Upstream() {
            public byte[] resolve(byte[] q) throws Exception { return upstreamSocket(q); }
        });
        require(out.size() == 2, "未命中应转发并返回 1 个应答");
        byte[] r2 = out.get(1);
        checkUdpChecksum(r2);
        byte[] d2 = java.util.Arrays.copyOfRange(r2, 28, r2.length);
        require(((d2[0] & 0xFF) << 8 | (d2[1] & 0xFF)) == 0x2222, "转发的 id 回显");
        List<String> recs = aRecords(d2);
        require(!recs.isEmpty(), "转发应答里应有 A 记录");
        System.out.println("PASS 转发上游（example.com -> " + recs + "，校验和自洽）");

        // 3) 非 DNS 流量：不处理
        byte[] other = fromClient(query("github.com", 0x3333), 40003);
        other[9] = 6; // 改成 TCP
        router.handle(other, sink, mustNotCall);
        require(out.size() == 2, "非 UDP 包应被忽略");
        System.out.println("PASS 非 DNS 流量直接放行");
        System.out.println("RouterTest OK");
    }

    static void require(boolean ok, String what) {
        if (!ok) throw new AssertionError("FAIL: " + what);
    }
}
