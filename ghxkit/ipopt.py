"""hosts 层加速：给 github 相关域名挑一个当前最快的真实 IP。

原理：国内 DNS 常把 github 域名解析到被污染的地址或很远的节点。
这里用国内 DoH 拿真实 IP，再逐个做真实 TLS 握手 + 下载测速，挑最快的写进 /etc/hosts。
注意：被 SNI 阻断的域名（raw.githubusercontent.com、gist.github.com）改 hosts 无效，
只能走镜像，所以这里不收录。
"""
from __future__ import annotations

import concurrent.futures as cf
import os
import shutil

from . import net
from .util import Color

BEGIN = "# >>> ghx 加速 hosts（自动生成，删除本块即恢复）>>>"
END = "# <<< ghx 加速 hosts <<<"

# 已知的真实 GitHub 节点池（Azure / Fastly），作为 DoH 的补充
POOL: dict[str, list[str]] = {
    "github.com": [
        "20.205.243.166", "20.27.177.113", "20.200.245.247", "20.247.166.64",
        "20.6.7.17", "4.237.22.38", "20.207.73.82", "20.233.83.145",
        "140.82.112.3", "140.82.113.3", "140.82.114.3",
    ],
    "api.github.com": [
        "20.205.243.168", "20.200.245.245", "20.27.177.116",
        "140.82.112.5", "140.82.113.5", "4.237.22.34",
    ],
    "codeload.github.com": [
        "20.6.7.16", "20.247.166.65", "20.200.245.246", "20.205.243.165", "140.82.112.9",
    ],
}

# (域名, 测速路径, 说明, 模式)  speed=按下载速度排序，latency=按握手/首字节排序
DOMAINS = [
    ("github.com", "/", "网页 / git 主站", "speed"),
    ("api.github.com", "/", "API / gh 命令行", "latency"),
    ("codeload.github.com", "/jqlang/jq/tar.gz/refs/tags/jq-1.7.1", "仓库压缩包", "speed"),
]

SAMPLE = 128 * 1024


def _domain_candidates(host: str) -> list[str]:
    ips: list[str] = []
    for _, found in net.doh_ips(host):
        ips.extend(found)
    ips.extend(net.system_ips(host))
    ips.extend(POOL.get(host, []))
    seen, out = set(), []
    for ip in ips:
        if ip not in seen:
            seen.add(ip)
            out.append(ip)
    return out[:12]


def _score(host: str, path: str, mode: str, ip: str) -> dict:
    tries = 2 if mode == "speed" else 1
    best = None
    for _ in range(tries):
        r = net.probe_https(ip, host, path=path, timeout=5.0,
                            sample_bytes=SAMPLE if mode == "speed" else 4096)
        r["ip"] = ip
        if best is None or (r["ok"] and not best["ok"]) or (
            r["ok"] and best["ok"] and r["kbps"] > best["kbps"]
        ):
            best = r
        if not r["ok"]:
            break
    r = best
    if not r["ok"]:
        r["score"] = -1
    elif mode == "speed" and r["bytes"] > 64 * 1024:
        r["score"] = r["kbps"] - r["ttfb_ms"] / 20
    else:
        # 响应太小（比如 API 的 404 页）时，速度数字没意义，按首字节延迟排序
        r["score"] = 1000 - r["ttfb_ms"] / 2
    return r


def best_for(host: str, path: str = "/", mode: str = "speed", verbose: bool = False) -> dict:
    cands = _domain_candidates(host)
    if not cands:
        return {"host": host, "ok": False, "error": "没有候选 IP"}
    with cf.ThreadPoolExecutor(max_workers=6) as ex:
        rows = list(ex.map(lambda ip: _score(host, path, mode, ip), cands))
    rows.sort(key=lambda x: x["score"], reverse=True)
    good = [r for r in rows if r["ok"]]
    if verbose:
        print(f"  {host}  候选 {len(rows)} 个，可用 {len(good)} 个")
        for r in rows[:4]:
            if r["ok"]:
                print(f"    {r['ip']:16s} {r['kbps']:7.0f} KiB/s  首字节 {r['ttfb_ms']:.0f}ms")
            else:
                print(f"    {r['ip']:16s} {Color.dim(r['error'][:56])}")
    if not good:
        return {"host": host, "ok": False, "error": "全部候选失败（可能被 SNI 阻断）"}
    top = good[0]
    return {"host": host, "ok": True, "ip": top["ip"], "kbps": top["kbps"],
            "ttfb_ms": top["ttfb_ms"], "score": top["score"],
            "tried": len(rows), "usable": len(good)}


def optimize(verbose: bool = False) -> list[dict]:
    out = []
    for host, path, note, mode in DOMAINS:
        r = best_for(host, path, mode, verbose=verbose)
        r["note"] = note
        out.append(r)
        if verbose:
            if r["ok"]:
                print(Color.green(f"  ✓ {host} -> {r['ip']}  ({r['kbps']:.0f} KiB/s)"))
            else:
                print(Color.yellow(f"  · {host} 跳过：{r['error']}"))
    return out


def read_hosts() -> str:
    with open("/etc/hosts", "r", encoding="utf-8", errors="replace") as f:
        return f.read()


def _strip_block(text: str) -> str:
    out, skip = [], False
    for line in text.splitlines():
        if line.strip() == BEGIN:
            skip = True
            continue
        if line.strip() == END:
            skip = False
            continue
        if not skip:
            out.append(line)
    return "\n".join(out).rstrip("\n") + "\n"


def render(results: list[dict]) -> str:
    lines = [BEGIN]
    for r in results:
        if r.get("ok"):
            lines.append(f"{r['ip']:<16s} {r['host']}  # {r.get('note', '')}")
    lines.append(END)
    return "\n".join(lines)


def apply(results: list[dict]) -> None:
    if os.geteuid() != 0:
        raise SystemExit("写入 /etc/hosts 需要 root（在 proot 里通常就是 root）")
    base = _strip_block(read_hosts())
    block = render(results)
    bak = "/etc/hosts.ghx.bak"
    if not os.path.exists(bak):
        shutil.copy2("/etc/hosts", bak)
    tmp = "/etc/hosts.ghx.tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        f.write(base.rstrip("\n") + "\n" + block + "\n")
    os.replace(tmp, "/etc/hosts")
    print(Color.green(f"  ✓ 已写入 /etc/hosts（原文件备份: {bak}）"))


def restore() -> None:
    new = _strip_block(read_hosts())
    tmp = "/etc/hosts.ghx.tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        f.write(new)
    os.replace(tmp, "/etc/hosts")
    print("已移除 hosts 加速块")


def current_block() -> list[str]:
    out, inside = [], False
    for line in read_hosts().splitlines():
        if line.strip() == BEGIN:
            inside = True
            continue
        if line.strip() == END:
            inside = False
            continue
        if inside and line.strip() and not line.strip().startswith("#"):
            out.append(line.strip())
    return out


def verify() -> dict:
    want = {}
    for line in current_block():
        parts = line.split()
        if len(parts) >= 2:
            want[parts[1]] = parts[0]
    got = {h: net.system_ips(h)[:1] for h in want}
    ok = all(got[h] and want[h] in got[h] for h in want) if want else False
    return {"ok": ok, "want": want, "got": got, "entries": len(want)}
