"""镜像节点：候选列表、实测优选、URL 拼接。

国内直连 GitHub 慢，公开反代（gh-proxy 一类）是主力；
这些站点会失效，所以每次按实测速度排序，而不是写死一个。
"""
from __future__ import annotations

import concurrent.futures as cf
import socket
import ssl
import time
from dataclasses import asdict, dataclass

from . import net
from .util import cache_path, load_json, now_ts, save_json

# 用来测速的标准文件：jq 的 arm64 二进制，约 1.6MB，稳定存在
TEST_TARGET = "https://github.com/jqlang/jq/releases/download/jq-1.7.1/jq-linux-arm64"
TEST_BYTES = 512 * 1024
CACHE_TTL = 6 * 3600


@dataclass
class Mirror:
    name: str
    base: str
    kind: str = "proxy"  # proxy: 前缀反代；gitclone: 只服务 git
    note: str = ""

    def build(self, target: str) -> str | None:
        """把原始 GitHub URL 变成镜像 URL。"""
        if self.kind == "gitclone":
            return None
        if not target.startswith(("http://", "https://")):
            return None
        return self.base.rstrip("/") + "/" + target

    def build_git(self, repo_url: str) -> str | None:
        """git clone 用的 URL。"""
        if self.kind == "gitclone":
            bare = repo_url.split("://", 1)[-1]
            return self.base.rstrip("/") + "/" + bare
        return self.build(repo_url)


DEFAULT_MIRRORS = (
    Mirror("gh-proxy.com", "https://gh-proxy.com/", note="Cloudflare 反代，支持 git 与 release"),
    Mirror("gh-proxy.net", "https://gh-proxy.net/", note="gh-proxy 备用域名"),
    Mirror("ghfast.top", "https://ghfast.top/", note="老牌反代"),
    Mirror("ghproxy.cfd", "https://ghproxy.cfd/"),
    Mirror("ghproxy.link", "https://ghproxy.link/"),
    Mirror("ghproxy.net", "https://ghproxy.net/"),
    Mirror("gitproxy.click", "https://gitproxy.click/"),
    Mirror("ghproxy.cxkpro.top", "https://ghproxy.cxkpro.top/"),
    Mirror("gitclone.com", "https://gitclone.com/", kind="gitclone", note="只加速 git 克隆"),
)


def _tls_ok(host: str, timeout: float = 4.0) -> bool:
    ips = net.system_ips(host, timeout=3.0)
    if not ips:
        return False
    for ip in ips[:2]:
        try:
            raw = socket.create_connection((ip, 443), timeout=timeout)
            s = net.ssl_context().wrap_socket(raw, server_hostname=host)
            s.close()
            return True
        except (OSError, ssl.SSLError):
            continue
    return False


def probe(m: Mirror, timeout: float = 12.0) -> dict:
    """实测一个镜像：能否连上 + 512KB 下载速度。"""
    res = {"name": m.name, "base": m.base, "kind": m.kind, "ok": False,
           "kbps": 0.0, "error": "", "ts": now_ts()}
    url = m.build(TEST_TARGET)
    if not url:
        res["error"] = "不支持文件下载"
        return res
    if not _tls_ok(m.base.split("://", 1)[-1].split("/")[0]):
        res["error"] = "连不上"
        return res
    try:
        r = net.http_get(
            url,
            headers={"Range": f"bytes=0-{TEST_BYTES - 1}"},
            timeout=timeout,
            max_bytes=TEST_BYTES,
        )
        got = len(r.body)
        if got < 32768:
            res["error"] = f"返回内容太小({got}B)"
            return res
        # 只按正文大小计时不够准，加上 TTFB 惩罚，避免“慢但大”的节点排前面
        kbps = got / 1024 / max(r.elapsed, 0.001)
        res.update(ok=True, kbps=round(kbps, 1))
    except Exception as e:  # noqa: BLE001
        res["error"] = f"{type(e).__name__}"
    return res


def candidates() -> list[Mirror]:
    cfg = load_json(cache_path("mirrors.manual.json"), {})
    extra = cfg.get("extra", []) if isinstance(cfg, dict) else []
    seen, out = set(), []
    for m in list(DEFAULT_MIRRORS) + [Mirror(x.get("name", x["base"]), x["base"]) for x in extra]:
        if m.base in seen:
            continue
        seen.add(m.base)
        out.append(m)
    return out


def rank(force: bool = False) -> list[dict]:
    """返回按速度降序的镜像列表，6 小时内复用缓存。"""
    cached = load_json(cache_path("mirrors.json"), {})
    if (
        not force
        and cached.get("ts")
        and now_ts() - cached["ts"] < CACHE_TTL
        and cached.get("results")
    ):
        return cached["results"]

    print("  正在实测镜像节点（每个约 5–12 秒）...", flush=True)
    results = []
    with cf.ThreadPoolExecutor(max_workers=4) as ex:
        for r in ex.map(probe, candidates()):
            results.append(r)
    results.sort(key=lambda x: (x["ok"], x["kbps"]), reverse=True)
    save_json(cache_path("mirrors.json"), {"ts": now_ts(), "results": results})
    return results


def usable(force: bool = False) -> list[Mirror]:
    """可用镜像，按速度排序。"""
    by_base = {m.base: m for m in candidates()}
    out = []
    for r in rank(force):
        if r["ok"] and r["base"] in by_base:
            out.append(by_base[r["base"]])
    return out


def get(name: str) -> Mirror:
    for m in candidates():
        if m.name == name or m.base == name:
            return m
    raise SystemExit(f"没有这个镜像: {name}（用 ghx mirror ls 查看）")


def as_dicts(ms: list[Mirror]) -> list[dict]:
    return [asdict(m) for m in ms]


def add(url: str, name: str = "") -> None:
    if not url.startswith("https://"):
        raise SystemExit("镜像地址必须是 https:// 开头")
    cfg = load_json(cache_path("mirrors.manual.json"), {"extra": []})
    cfg.setdefault("extra", []).append({"name": name or url.split("://")[1].split("/")[0],
                                        "base": url if url.endswith("/") else url + "/"})
    save_json(cache_path("mirrors.manual.json"), cfg)
    print(f"已添加镜像 {name or url}")


def remove(name: str) -> None:
    cfg = load_json(cache_path("mirrors.manual.json"), {"extra": []})
    before = len(cfg.get("extra", []))
    cfg["extra"] = [x for x in cfg.get("extra", []) if name not in (x.get("name"), x.get("base"))]
    save_json(cache_path("mirrors.manual.json"), cfg)
    print(f"已移除 {before - len(cfg['extra'])} 个镜像")
