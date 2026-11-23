package com.ghx.accel.core;

import java.util.Arrays;
import java.util.Collections;

/**
 * DNS 路由：从 tun 收到 IPv4/UDP 包后决定"自己答"还是"转发上游"。
 * 抽成纯 Java 是为了能脱离 Android 单测（校验和/转发是曾经出过问题的地方）。
 */
public final class DnsRouter {

    public interface Sink {
        void send(byte[] packet);
    }

    public interface Upstream {
        /** 返回上游 DNS 应答载荷；失败返回 null。 */
        byte[] resolve(byte[] dnsQuery) throws Exception;
    }

    public interface Mapper {
        /** 返回要下发的 IP；null 表示不接管。 */
        String ipFor(String host);
    }

    private final Mapper mapper;
    private final String dnsIp;
    private final byte[] dnsIpBytes;
    private final int ttl;

    public DnsRouter(Mapper mapper, String dnsIp, int ttl) {
        this.mapper = mapper;
        this.dnsIp = dnsIp;
        this.dnsIpBytes = DnsPacket.ipv4(dnsIp);
        this.ttl = ttl;
    }

    public void handle(byte[] pkt, Sink sink, Upstream upstream) {
        if (pkt.length < 28) return;
        if (((pkt[0] >> 4) & 0xF) != 4) return;
        int ihl = (pkt[0] & 0xF) * 4;
        if (pkt.length < ihl + 8) return;
        if ((pkt[9] & 0xFF) != 17) return;                       // 只处理 UDP
        if (!dnsIp.equals(DnsPacket.ipToString(pkt, 16))) return; // 只处理发往本机 DNS 的包
        int sport = ((pkt[ihl] & 0xFF) << 8) | (pkt[ihl + 1] & 0xFF);
        int dport = ((pkt[ihl + 2] & 0xFF) << 8) | (pkt[ihl + 3] & 0xFF);
        if (dport != 53) return;

        byte[] dns = Arrays.copyOfRange(pkt, ihl + 8, pkt.length);
        DnsPacket.Query q;
        try {
            q = DnsPacket.parseQuery(dns);
        } catch (Exception e) {
            return;
        }
        byte[] srcIp = Arrays.copyOfRange(pkt, 12, 16);

        String mapped = q.qtype == 1 ? mapper.ipFor(q.name) : null;
        if (mapped != null && DnsPacket.ipv4(mapped) != null) {
            byte[] ans = DnsPacket.answer(q, Collections.singletonList(mapped), ttl);
            sink.send(DnsPacket.wrapUdp(ans, dnsIpBytes, 53, srcIp, sport));
            return;
        }
        try {
            byte[] resp = upstream.resolve(dns);
            if (resp != null && resp.length >= 12) {
                sink.send(DnsPacket.wrapUdp(resp, dnsIpBytes, 53, srcIp, sport));
            }
        } catch (Exception ignored) {
            // 上游失败就让客户端自己重试
        }
    }
}
