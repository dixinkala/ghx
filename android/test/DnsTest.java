package com.ghx.accel.core;

import java.util.Arrays;
import java.util.List;

public class DnsTest {
    static byte[] query(String name, int type) {
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        o.write(0x12); o.write(0x34); o.write(0x01); o.write(0x00);
        o.write(0); o.write(1); o.write(0); o.write(0); o.write(0); o.write(0); o.write(0); o.write(0);
        for (String p : name.split("\\.")) { o.write(p.length()); for (char c : p.toCharArray()) o.write(c); }
        o.write(0); o.write(0); o.write(type); o.write(0); o.write(1);
        return o.toByteArray();
    }

    public static void main(String[] a) {
        byte[] q = query("github.com", 1);
        DnsPacket.Query parsed = DnsPacket.parseQuery(q);
        check(parsed.id == 0x1234, "id");
        check(parsed.name.equals("github.com"), "name=" + parsed.name);
        check(parsed.qtype == 1, "qtype");
        check(parsed.questionLength == 16, "qlen=" + parsed.questionLength);

        byte[] ans = DnsPacket.answer(parsed, List.of("20.27.177.113", "20.200.245.247"), 120);
        check((ans[0] & 0xFF) == 0x12 && (ans[1] & 0xFF) == 0x34, "ans id");
        check((ans[2] & 0xFF) == 0x81, "flags");
        int an = ((ans[6] & 0xFF) << 8) | (ans[7] & 0xFF);
        check(an == 2, "ancount=" + an);
        check(ans.length == 12 + 16 + 32, "len=" + ans.length);
        int off = 12 + 16;
        check((ans[off + 10] & 0xFF) == 0 && (ans[off + 11] & 0xFF) == 4, "rdlen");
        check(DnsPacket.ipToString(ans, off + 12).equals("20.27.177.113"), "ip1");
        check(DnsPacket.ipToString(ans, off + 28).equals("20.200.245.247"), "ip2");

        byte[] src = DnsPacket.ipv4("10.66.66.1");
        byte[] dst = DnsPacket.ipv4("10.66.66.2");
        byte[] pkt = DnsPacket.wrapUdp(ans, src, 53, dst, 40000);
        check((pkt[0] & 0xFF) == 0x45, "ver/ihl");
        int tot = ((pkt[2] & 0xFF) << 8) | (pkt[3] & 0xFF);
        check(tot == pkt.length, "total len");
        check((pkt[9] & 0xFF) == 17, "proto");
        check(DnsPacket.checksum(pkt, 0, 20, 0) == 0, "ip checksum");
        int u = 20;
        int ulen = ((pkt[u + 4] & 0xFF) << 8) | (pkt[u + 5] & 0xFF);
        int pseudo = DnsPacket.sumWords(pkt, 12, 8, 0) + 17 + ulen;
        check(DnsPacket.finish(DnsPacket.sumWords(pkt, u, ulen, pseudo)) == 0, "udp checksum");

        byte[] chunk = Arrays.copyOfRange(pkt, u + 8, pkt.length);
        DnsPacket.Query echoed = DnsPacket.parseQuery(chunk);
        check(echoed.name.equals("github.com"), "payload intact");
        // 与独立实现（Python RFC1071/768）对照：单答案、TTL 300、端口 53→40000
        byte[] refDns = DnsPacket.answer(DnsPacket.parseQuery(query("github.com", 1)),
                List.of("20.27.177.113"), 300);
        byte[] refPkt = DnsPacket.wrapUdp(refDns, DnsPacket.ipv4("10.66.66.1"), 53,
                DnsPacket.ipv4("10.66.66.2"), 40000);
        int ipField = ((refPkt[10] & 0xFF) << 8) | (refPkt[11] & 0xFF);
        int udpField = ((refPkt[26] & 0xFF) << 8) | (refPkt[27] & 0xFF);
        check(ipField == 0x8FEA, "ip sum vs reference: " + Integer.toHexString(ipField));
        check(udpField == 0xA541, "udp sum vs reference: " + Integer.toHexString(udpField));
        check(refDns.length == 44 && refPkt.length == 72, "ref sizes");

        System.out.println("DNS tests passed");
    }

    static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError("FAIL: " + what);
    }
}
