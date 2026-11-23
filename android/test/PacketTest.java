package com.ghx.accel.core;

import java.util.List;

/** 端到端报文测试：模拟 Android 发来的 EDNS 查询，走真实应答路径，输出十六进制给 Python 独立校验。 */
public class PacketTest {

    static byte[] ednsQuery(String name) {
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        o.write(0xAB); o.write(0xCD);            // id
        o.write(0x01); o.write(0x00);            // flags: RD
        o.write(0); o.write(1);                  // qdcount
        o.write(0); o.write(0);                  // ancount
        o.write(0); o.write(0);                  // nscount
        o.write(0); o.write(1);                  // arcount = 1 (EDNS OPT)
        for (String p : name.split("\\.")) {
            o.write(p.length());
            for (char c : p.toCharArray()) o.write(c);
        }
        o.write(0); o.write(0x00); o.write(0x01); o.write(0x00); o.write(0x01);
        // OPT RR: root name, type 41, UDP payload 4096, ttl 0, rdlen 0
        o.write(0); o.write(0); o.write(41); o.write(0x10); o.write(0x00);
        o.write(0); o.write(0); o.write(0); o.write(0); o.write(0); o.write(0);
        return o.toByteArray();
    }

    public static void main(String[] a) throws Exception {
        byte[] q = ednsQuery("github.com");
        DnsPacket.Query parsed = DnsPacket.parseQuery(q);
        require(parsed.name.equals("github.com"), "qname");
        require(parsed.qtype == 1, "qtype");

        List<String> ips = List.of("20.27.177.113", "20.200.245.247");
        byte[] ans = DnsPacket.answer(parsed, ips, 300);
        require(ans.length == 12 + parsed.questionLength + 2 * 16, "answer len");

        byte[] pkt = DnsPacket.wrapUdp(ans, DnsPacket.ipv4("10.66.66.1"), 53,
                DnsPacket.ipv4("10.66.66.2"), 40000);
        require(((pkt[2] & 0xFF) << 8 | (pkt[3] & 0xFF)) == pkt.length, "ip len");
        require(DnsPacket.checksum(pkt, 0, 20, 0) == 0, "ip csum");

        StringBuilder sb = new StringBuilder();
        for (byte b : pkt) sb.append(String.format("%02x", b & 0xFF));
        try (java.io.FileWriter fw = new java.io.FileWriter("/tmp/ghx-packet.hex")) {
            fw.write(sb.toString());
        }
        System.out.println("PacketTest OK  pkt=" + pkt.length + "B  dns=" + ans.length + "B");
    }

    static void require(boolean ok, String what) {
        if (!ok) throw new AssertionError("FAIL: " + what);
    }
}
