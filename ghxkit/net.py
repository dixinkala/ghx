"""底层网络能力：TLS 探测、带重定向的 HTTP、DoH 解析。

只依赖标准库，方便在手机 proot 环境里直接跑。
"""
from __future__ import annotations

import http.client
import json
import socket
import ssl
import time
import urllib.parse
import urllib.request

UA = "Mozilla/5.0 (Linux; Android 14) ghx/1.0"

DOH_ENDPOINTS = (
    ("dnspod", "https://1.12.12.12/resolve?name={name}&type=A"),
    ("alidns", "https://223.5.5.5/resolve?name={name}&type=A"),
    ("doh.pub", "https://doh.pub/dns-query?name={name}&type=A"),
)


def ssl_context() -> ssl.SSLContext:
    ctx = ssl.create_default_context()
    # 部分镜像站证书链不全，这里不放松校验（宁可失败，避免中间人风险）
    return ctx


class Resp:
    def __init__(self, status: int, headers: dict, body: bytes, url: str, elapsed: float):
        self.status = status
        self.headers = headers
        self.body = body
        self.url = url
        self.elapsed = elapsed

    @property
    def ok(self) -> bool:
        return 200 <= self.status < 400


def http_get(
    url: str,
    headers: dict | None = None,
    timeout: float = 15.0,
    max_bytes: int | None = None,
    method: str = "GET",
) -> Resp:
    """GET/HEAD，自动跟随最多 5 次跳转，最多读 max_bytes 字节。"""
    start = time.monotonic()
    cur = url
    for _ in range(5):
        u = urllib.parse.urlsplit(cur)
        if u.scheme not in ("http", "https"):
            raise ValueError(f"不支持的协议: {u.scheme}")
        host = u.hostname
        port = u.port or (443 if u.scheme == "https" else 80)
        conn: http.client.HTTPConnection
        if u.scheme == "https":
            conn = http.client.HTTPSConnection(host, port, timeout=timeout, context=ssl_context())
        else:
            conn = http.client.HTTPConnection(host, port, timeout=timeout)
        path = urllib.parse.urlunsplit(("", "", u.path or "/", u.query, ""))
        hdrs = {"User-Agent": UA, "Accept": "*/*", "Accept-Encoding": "identity"}
        if headers:
            hdrs.update(headers)
        try:
            conn.request(method, path, headers=hdrs)
            r = conn.getresponse()
            if r.status in (301, 302, 303, 307, 308):
                loc = r.getheader("Location")
                if loc:
                    cur = urllib.parse.urljoin(cur, loc)
                    conn.close()
                    continue
            body = r.read(max_bytes) if max_bytes else r.read()
            hdict = {k.lower(): v for k, v in r.getheaders()}
            return Resp(r.status, hdict, body, cur, time.monotonic() - start)
        finally:
            conn.close()
    raise RuntimeError("重定向次数过多")


def probe_https(
    ip: str,
    sni: str,
    path: str = "/",
    timeout: float = 6.0,
    sample_bytes: int = 0,
    hard_deadline: float | None = None,
) -> dict:
    """对一个 IP 做真实 TLS + HTTP 探测（SNI 用域名，这样才能绕过 DNS 污染）。

    返回 {ok, tls_ms, ttfb_ms, bytes, kbps, status, error}
    """
    out = {"ok": False, "tls_ms": 0.0, "ttfb_ms": 0.0, "bytes": 0, "kbps": 0.0,
           "status": 0, "error": ""}
    t0 = time.monotonic()
    deadline = t0 + (hard_deadline or timeout * 3)
    try:
        raw = socket.create_connection((ip, 443), timeout=timeout)
        out["tls_ms"] = (time.monotonic() - t0) * 1000
        sock = ssl_context().wrap_socket(raw, server_hostname=sni)
        out["tls_ms"] = (time.monotonic() - t0) * 1000
        req = (
            f"GET {path} HTTP/1.1\r\nHost: {sni}\r\nUser-Agent: {UA}\r\n"
            "Accept: */*\r\nAccept-Encoding: identity\r\nConnection: close\r\n\r\n"
        )
        sock.sendall(req.encode())
        buf = b""
        first = True
        while time.monotonic() < deadline and len(buf) < max(65536, sample_bytes) + 4096:
            chunk = sock.recv(65536)
            if not chunk:
                break
            if first:
                out["ttfb_ms"] = (time.monotonic() - t0) * 1000
                first = False
            buf += chunk
            if sample_bytes and len(buf) >= sample_bytes:
                break
        sock.close()
        head, _, body = buf.partition(b"\r\n\r\n")
        status = 0
        try:
            status = int(head.split(b"\r\n", 1)[0].split()[1])
        except (IndexError, ValueError):
            pass
        out.update(ok=200 <= status < 400, status=status, bytes=len(buf))
        dt = time.monotonic() - t0
        out["kbps"] = len(buf) / 1024 / dt if dt > 0 else 0.0
        if not out["ok"] and not out["error"]:
            out["error"] = f"HTTP {status}"
    except Exception as e:  # noqa: BLE001 网络异常种类多，统一记录
        out["error"] = f"{type(e).__name__}: {e}"
        out["ok"] = False
    return out


def system_ips(host: str, timeout: float = 3.0) -> list[str]:
    old = socket.getdefaulttimeout()
    socket.setdefaulttimeout(timeout)
    try:
        infos = socket.getaddrinfo(host, 443, socket.AF_INET, socket.SOCK_STREAM)
        return sorted({i[4][0] for i in infos})
    except OSError:
        return []
    finally:
        socket.setdefaulttimeout(old)


def doh_ips(host: str, timeout: float = 6.0) -> list[tuple[str, list[str]]]:
    """DoH 解析，返回 [(来源, IP 列表)]。国内 DoH 不会被 DNS 污染。"""
    results = []
    for name, tmpl in DOH_ENDPOINTS:
        url = tmpl.format(name=host)
        try:
            req = urllib.request.Request(
                url, headers={"Accept": "application/dns-json", "User-Agent": UA}
            )
            with urllib.request.urlopen(req, timeout=timeout) as r:
                data = json.load(r)
            ips = [
                a.get("data")
                for a in data.get("Answer", [])
                if a.get("type") == 1 and a.get("data")
            ]
            if ips:
                results.append((name, ips))
        except Exception:  # noqa: BLE001
            continue
    return results


def is_sni_blocked(host: str, timeout: float = 5.0) -> tuple[bool, str]:
    """判断某域名是否被 SNI 阻断（无论换哪个 IP 都握手失败）。"""
    ips = system_ips(host) or []
    if not ips:
        return True, "DNS 解析失败"
    errors = []
    for ip in ips[:3]:
        r = probe_https(ip, host, timeout=timeout)
        if r["ok"]:
            return False, ""
        errors.append(f"{ip}: {r['error']}")
    return True, "; ".join(errors)
