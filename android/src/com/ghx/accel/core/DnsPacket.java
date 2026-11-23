package com.ghx.accel.core;

import java.util.ArrayList;
import java.util.List;

/** DNS 报文解析/构造 + IPv4/UDP 封装。纯 Java，可在 JVM 上单测。 */
public final class DnsPacket {

    private DnsPacket() {}

    public static final class Query {
        public int id;
        public String name;
        public int qtype;
        public int questionLength;
        public byte[] raw;
    }

    /** 解析查询报文；只管单问题、A/AAAA 记录。 */
    public static Query parseQuery(byte[] dns) {
        if (dns.length < 12) throw new IllegalArgumentException("dns too short");
        Query q = new Query();
        q.id = ((dns[0] & 0xFF) << 8) | (dns[1] & 0xFF);
        int qd = ((dns[4] & 0xFF) << 8) | (dns[5] & 0xFF);
        if (qd < 1) throw new IllegalArgumentException("no question");
        int off = 12;
        StringBuilder sb = new StringBuilder();
        while (off < dns.length) {
            int len = dns[off] & 0xFF;
            if (len == 0) { off++; break; }
            if ((len & 0xC0) != 0) throw new IllegalArgumentException("compressed qname");
            if (off + 1 + len > dns.length) throw new IllegalArgumentException("truncated");
            if (sb.length() > 0) sb.append('.');
            sb.append(new String(dns, off + 1, len, java.nio.charset.StandardCharsets.US_ASCII));
            off += 1 + len;
        }
        if (off + 4 > dns.length) throw new IllegalArgumentException("truncated qtype");
        q.qtype = ((dns[off] & 0xFF) << 8) | (dns[off + 1] & 0xFF);
        q.questionLength = off + 4 - 12;
        q.name = sb.toString().toLowerCase();
        q.raw = dns;
        return q;
    }

    /** 构造应答：问题区原样带回，附加若干 A 记录。 */
    public static byte[] answer(Query q, List<String> ips, int ttl) {
        List<byte[]> addrs = new ArrayList<>();
        for (String ip : ips) {
            byte[] b = ipv4(ip);
            if (b != null) addrs.add(b);
        }
        int size = 12 + q.questionLength + addrs.size() * 16;
        byte[] out = new byte[size];
        out[0] = (byte) (q.id >> 8);
        out[1] = (byte) q.id;
        out[2] = (byte) 0x81; // QR=1 RD=1
        out[3] = (byte) 0x80; // RA=1, NOERROR
        out[4] = 0; out[5] = 1;                    // QDCOUNT
        int an = addrs.size();
        out[6] = (byte) (an >> 8); out[7] = (byte) an;
        out[8] = 0; out[9] = 0; out[10] = 0; out[11] = 0;
        System.arraycopy(q.raw, 12, out, 12, q.questionLength);
        int off = 12 + q.questionLength;
        for (byte[] a : addrs) {
            out[off] = (byte) 0xC0; out[off + 1] = 0x0C; // name -> offset 12
            out[off + 2] = 0; out[off + 3] = 1;          // A
            out[off + 4] = 0; out[off + 5] = 1;          // IN
            out[off + 6] = (byte) (ttl >> 24); out[off + 7] = (byte) (ttl >> 16);
            out[off + 8] = (byte) (ttl >> 8); out[off + 9] = (byte) ttl;
            out[off + 10] = 0; out[off + 11] = 4;
            System.arraycopy(a, 0, out, off + 12, 4);
            off += 16;
        }
        return out;
    }

    /** 把 DNS 应答封装成 IPv4 + UDP 包，用于写回 tun。 */
    public static byte[] wrapUdp(byte[] payload, byte[] srcIp, int srcPort,
                                 byte[] dstIp, int dstPort) {
        int total = 20 + 8 + payload.length;
        byte[] p = new byte[total];
        p[0] = 0x45;
        p[2] = (byte) (total >> 8); p[3] = (byte) total;
        p[4] = 0x12; p[5] = 0x34;
        p[6] = 0x40; // DF
        p[8] = 64;   // TTL
        p[9] = 17;   // UDP
        System.arraycopy(srcIp, 0, p, 12, 4);
        System.arraycopy(dstIp, 0, p, 16, 4);
        int ipSum = finish(sumWords(p, 0, 20, 0));
        p[10] = (byte) (ipSum >> 8); p[11] = (byte) ipSum;

        int u = 20;
        p[u] = (byte) (srcPort >> 8); p[u + 1] = (byte) srcPort;
        p[u + 2] = (byte) (dstPort >> 8); p[u + 3] = (byte) dstPort;
        int len = 8 + payload.length;
        p[u + 4] = (byte) (len >> 8); p[u + 5] = (byte) len;
        System.arraycopy(payload, 0, p, u + 8, payload.length);

        // UDP 伪首部：源 IP + 目的 IP + 0 + 协议号(17) + UDP 长度（RFC 768）
        int pseudo = sumWords(p, 12, 8, 0) + 17 + len;
        int sum = finish(sumWords(p, u, len, pseudo));
        if (sum == 0) sum = 0xFFFF;
        p[u + 6] = (byte) (sum >> 8); p[u + 7] = (byte) sum;
        return p;
    }

    /** 16 位累加（未取反），返回折叠后的和。 */
    public static int sumWords(byte[] data, int off, int len, int initial) {
        long sum = initial & 0xFFFF;
        for (int i = 0; i < len; i += 2) {
            int hi = data[off + i] & 0xFF;
            int lo = (i + 1 < len) ? (data[off + i + 1] & 0xFF) : 0;
            sum += ((hi << 8) | lo);
            while (sum > 0xFFFF) sum = (sum & 0xFFFF) + (sum >> 16);
        }
        return (int) sum;
    }

    /** 取反得到线上校验和。 */
    public static int finish(int sum) {
        return (~sum) & 0xFFFF;
    }

    /** Internet checksum（RFC 1071）。 */
    public static int checksum(byte[] data, int off, int len, int initial) {
        return finish(sumWords(data, off, len, initial));
    }

    public static byte[] ipv4(String s) {
        String[] parts = s.split("\\.");
        if (parts.length != 4) return null;
        byte[] b = new byte[4];
        try {
            for (int i = 0; i < 4; i++) {
                int v = Integer.parseInt(parts[i]);
                if (v < 0 || v > 255) return null;
                b[i] = (byte) v;
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return b;
    }

    public static String ipToString(byte[] p, int off) {
        return (p[off] & 0xFF) + "." + (p[off + 1] & 0xFF) + "." +
               (p[off + 2] & 0xFF) + "." + (p[off + 3] & 0xFF);
    }
}
