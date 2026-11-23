"""多镜像并行下载：把 GitHub 链接换成镜像链接，分片抢速度，失败自动换节点。

支持 raw 文件、release 附件、仓库压缩包；带断点续传与进度显示。
"""
from __future__ import annotations

import http.client
import os
import re
import sys
import threading
import time
import urllib.parse

from . import net
from .util import Color, human_size

ALLOW_HOSTS = {
    "github.com", "raw.githubusercontent.com", "codeload.github.com",
    "objects.githubusercontent.com", "api.github.com",
    "gist.githubusercontent.com", "media.githubusercontent.com",
    "github-releases.githubusercontent.com",
}

MIN_PARALLEL = 1024 * 1024  # 小于 1MB 不值得分片


def normalize(url: str) -> str:
    """把 github.com 的网页链接换成可直下的真实地址。"""
    url = url.strip()
    if not url.startswith(("http://", "https://")):
        url = "https://" + url
    u = urllib.parse.urlsplit(url)
    host = (u.hostname or "").lower()
    if host not in ALLOW_HOSTS:
        raise SystemExit(f"只支持 GitHub 域名，收到: {host or url}")
    path = u.path
    m = re.match(r"^/([^/]+)/([^/]+)/blob/(.+)$", path)
    if m:
        url = f"https://raw.githubusercontent.com/{m.group(1)}/{m.group(2)}/{m.group(3)}"
    elif re.match(r"^/([^/]+)/([^/]+)/raw/", path):
        url = url.replace("github.com", "raw.githubusercontent.com", 1)
    elif re.match(r"^/gist/[^/]+/[^/]+$", path) and host == "gist.github.com":
        raise SystemExit("Gist 页面需要先点 Raw 取到 gist.githubusercontent.com 链接")
    return url


def candidate_urls(url: str, mirror_list=None) -> list[tuple[str, str]]:
    """返回 [(说明, URL)]，镜像优先，直连兜底。"""
    from . import mirrors

    out = []
    ms = mirror_list if mirror_list is not None else mirrors.usable()
    for m in ms:
        built = m.build(url)
        if built:
            out.append((m.name, built))
    out.append(("直连", url))
    return out


def guess_name(url: str, headers: dict) -> str:
    cd = headers.get("content-disposition", "")
    m = re.search(r"filename\*=UTF-8''([^;]+)", cd) or re.search(r'filename="?([^";]+)"?', cd)
    if m:
        return urllib.parse.unquote(m.group(1))
    name = os.path.basename(urllib.parse.urlsplit(url).path)
    name = urllib.parse.unquote(name)
    return name or "download.bin"


class Progress:
    def __init__(self, total: int, label: str = ""):
        self.total = total
        self.done = 0
        self.label = label
        self.t0 = time.monotonic()
        self._lock = threading.Lock()
        self._stop = threading.Event()
        self._t = threading.Thread(target=self._run, daemon=True)

    def start(self):
        self._t.start()
        return self

    def add(self, n: int):
        with self._lock:
            self.done += n

    def stop(self):
        self._stop.set()

    def _run(self):
        while not self._stop.wait(0.4):
            if self.total > 0:
                self.render()

    def render(self, final: bool = False):
        with self._lock:
            done, total = self.done, self.total
        dt = max(time.monotonic() - self.t0, 0.001)
        speed = done / dt
        if total > 0:
            pct = min(100, done * 100 / total)
            bar = "█" * int(pct / 4) + "░" * (25 - int(pct / 4))
            eta = (total - done) / speed if speed > 0 else 0
            line = (f"\r  {bar} {pct:5.1f}%  {human_size(done)}/{human_size(total)}"
                    f"  {human_size(speed)}/s  剩 {int(eta):3d}s {self.label}")
        else:
            line = f"\r  {human_size(done)}  {human_size(speed)}/s {self.label}"
        if sys.stdout.isatty():
            sys.stdout.write(line[:160])
            sys.stdout.flush()
            if final:
                sys.stdout.write("\n")
        elif final:
            print(line.strip())


def _probe_size(url: str, timeout: float = 12.0) -> tuple[int, bool]:
    """返回 (字节数, 是否支持 Range)。0 表示未知。"""
    try:
        r = net.http_get(url, headers={"Range": "bytes=0-0"}, timeout=timeout, max_bytes=1)
        cr = r.headers.get("content-range", "")
        m = re.search(r"/(\d+)$", cr)
        if r.status == 206 and m:
            return int(m.group(1)), True
        cl = r.headers.get("content-length")
        if r.status == 200 and cl and cl.isdigit():
            return int(cl), "bytes" in r.headers.get("accept-ranges", "").lower()
    except Exception:  # noqa: BLE001
        pass
    return 0, False


def _get_range(url: str, start: int, end: int, timeout: float = 20.0) -> bytes:
    u = urllib.parse.urlsplit(url)
    path = urllib.parse.urlunsplit(("", "", u.path or "/", u.query, ""))
    conn = http.client.HTTPSConnection(u.hostname, u.port or 443, timeout=timeout)
    try:
        conn.request("GET", path, headers={
            "User-Agent": net.UA, "Accept-Encoding": "identity",
            "Range": f"bytes={start}-{end}",
        })
        r = conn.getresponse()
        if r.status not in (200, 206):
            raise IOError(f"HTTP {r.status}")
        data = r.read()
        return data
    finally:
        conn.close()


def _parallel(url: str, path: str, size: int, jobs: int) -> None:
    chunk = size // jobs + 1
    ranges = [(i * chunk, min(size, (i + 1) * chunk) - 1) for i in range(jobs)]
    ranges = [r for r in ranges if r[0] <= r[1]]
    with open(path, "wb") as f:
        f.truncate(size)
    prog = Progress(size).start()
    errors: list[str] = []

    def worker(start: int, end: int):
        try:
            for attempt in (1, 2):
                try:
                    data = _get_range(url, start, end)
                    if len(data) != end - start + 1:
                        raise IOError(f"分片长度不符 {len(data)}")
                    with open(path, "r+b") as f:
                        f.seek(start)
                        f.write(data)
                    prog.add(len(data))
                    return
                except Exception as e:  # noqa: BLE001
                    if attempt == 2:
                        errors.append(str(e))
                    else:
                        time.sleep(0.8)
        finally:
            pass

    threads = [threading.Thread(target=worker, args=r, daemon=True) for r in ranges]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    prog.stop()
    prog.render(final=True)
    if errors:
        raise IOError("分片失败: " + errors[0])
    if os.path.getsize(path) != size:
        raise IOError("文件大小不符")


def _stream(url: str, path: str, resume: int, total: int) -> None:
    u = urllib.parse.urlsplit(url)
    headers = {"User-Agent": net.UA, "Accept-Encoding": "identity"}
    if resume:
        headers["Range"] = f"bytes={resume}-"
    conn = http.client.HTTPSConnection(u.hostname, u.port or 443, timeout=30)
    try:
        path_q = urllib.parse.urlunsplit(("", "", u.path or "/", u.query, ""))
        conn.request("GET", path_q, headers=headers)
        r = conn.getresponse()
        if r.status not in (200, 206):
            raise IOError(f"HTTP {r.status}")
        append = resume > 0 and r.status == 206
        mode = "ab" if append else "wb"
        done = resume if append else 0
        prog = Progress(total).start()
        prog.done = done
        with open(path, mode) as f:
            while True:
                b = r.read(262144)
                if not b:
                    break
                f.write(b)
                prog.add(len(b))
        prog.stop()
        prog.render(final=True)
    finally:
        conn.close()


def download(url: str, out: str | None = None, jobs: int = 6,
             quiet: bool = False) -> dict:
    url = normalize(url)
    from . import mirrors

    ms = mirrors.usable()
    cands = candidate_urls(url, ms)
    if not quiet:
        print(f"  目标: {url}")
        print(f"  节点: {', '.join(n for n, _ in cands[:5])}")
    errors = []
    for name, cu in cands:
        final = out
        if final is None:
            try:
                head = net.http_get(cu, method="HEAD", timeout=8)
                final = guess_name(cu, head.headers)
            except Exception:  # noqa: BLE001
                final = guess_name(cu, {})
        part = final + ".ghx-part"
        size, ranges = _probe_size(cu)
        resume = os.path.getsize(part) if os.path.exists(part) else 0
        if resume and size and resume >= size:
            os.replace(part, final)
            if not quiet:
                print(Color.green(f"  ✓ 已存在完整文件: {final}"))
            return {"ok": True, "path": final, "bytes": size, "node": name, "cached": True}
        if not quiet:
            print(f"  → {name}  {human_size(size) if size else '大小未知'}"
                  f"{'  [分片x%d]' % jobs if ranges and size > MIN_PARALLEL and jobs > 1 else ''}")
        t0 = time.monotonic()
        try:
            if ranges and size > MIN_PARALLEL and jobs > 1:
                if os.path.exists(part):
                    os.remove(part)
                _parallel(cu, part, size, jobs)
            else:
                _stream(cu, part, resume, size)
            os.replace(part, final)
            dt = max(time.monotonic() - t0, 0.001)
            got = os.path.getsize(final)
            kbps = got / 1024 / dt
            if not quiet:
                print(Color.green(f"  ✓ 完成: {final}  {human_size(got)}  "
                                  f"均速 {kbps:.0f} KiB/s  节点 {name}"))
            return {"ok": True, "path": final, "bytes": got, "node": name,
                    "kbps": round(kbps, 1), "seconds": round(dt, 2)}
        except KeyboardInterrupt:
            raise
        except Exception as e:  # noqa: BLE001
            errors.append(f"{name}: {e}")
            if not quiet:
                print(Color.yellow(f"  × {name} 失败: {str(e)[:70]}"))
            continue
    raise SystemExit("所有节点都失败了：\n  " + "\n  ".join(errors))


def plan(url: str) -> dict:
    """给网页门户用：返回可选的加速链接列表（不下载）。"""
    url = normalize(url)
    from . import mirrors

    links = []
    for name, cu in candidate_urls(url):
        links.append({"node": name, "url": cu})
    return {"url": url, "links": links}
