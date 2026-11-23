"""只读网页视图：把 GitHub 仓库/文件抓成干净的 Markdown 页面。

手机浏览器打不开 github.com 页面时，用它读 README、源码；
内容由服务端走镜像抓取，浏览器只渲染文本，几百 KB 就够。
"""
from __future__ import annotations

import html
import re
import urllib.parse

from . import net

RAW = "https://raw.githubusercontent.com"
README_CANDIDATES = ("README.md", "README.rst", "README.txt", "README", "readme.md")

TEXT_EXT = {
    ".md", ".markdown", ".rst", ".txt", ".py", ".js", ".ts", ".tsx", ".jsx",
    ".go", ".rs", ".c", ".h", ".cpp", ".hpp", ".java", ".kt", ".rb", ".php",
    ".sh", ".bash", ".zsh", ".yml", ".yaml", ".toml", ".ini", ".cfg", ".json",
    ".html", ".css", ".sql", ".lua", ".swift", ".dart", ".vue", ".xml",
}


def resolve_target(url: str) -> dict:
    """解析 GitHub 链接，返回 kind/owner/repo/ref/path。"""
    url = url.strip()
    if not url.startswith(("http://", "https://")):
        url = "https://" + url
    u = urllib.parse.urlsplit(url)
    host = (u.hostname or "").lower()
    parts = [p for p in u.path.split("/") if p]

    if host == "raw.githubusercontent.com" and len(parts) >= 4:
        return {"kind": "file", "owner": parts[0], "repo": parts[1],
                "ref": parts[2], "path": "/".join(parts[3:])}
    if host != "github.com":
        raise SystemExit("只读模式只支持 github.com 链接")
    if len(parts) < 2:
        raise SystemExit("链接里缺少 owner/repo")
    owner, repo = parts[0], parts[1]
    repo = repo[:-4] if repo.endswith(".git") else repo
    if len(parts) >= 3 and parts[2] in ("blob", "raw") and len(parts) >= 5:
        return {"kind": "file", "owner": owner, "repo": repo,
                "ref": parts[3], "path": "/".join(parts[4:])}
    if len(parts) >= 3 and parts[2] == "tree":
        ref = parts[3] if len(parts) > 3 else "HEAD"
        path = "/".join(parts[4:]) if len(parts) > 4 else ""
        return {"kind": "dir", "owner": owner, "repo": repo, "ref": ref, "path": path}
    return {"kind": "repo", "owner": owner, "repo": repo, "ref": "HEAD", "path": ""}


def _raw_url(t: dict, path: str) -> str:
    return f"{RAW}/{t['owner']}/{t['repo']}/{t['ref']}/{path}"


def _try(cu: str, allow_html: bool) -> str | None:
    try:
        r = net.http_get(cu, timeout=6, max_bytes=600 * 1024)
        if r.status != 200:
            return None
        text = r.body.decode("utf-8", "replace")
        low = text.strip().lower()
        if len(text.strip()) < 8 or low in ("404: not found", "not found",
                                            "404 page not found", "error"):
            return None
        # 镜像站 404 时回自己的 HTML 页面，别当正文
        if not allow_html and low.startswith(("<!doctype html", "<html")):
            return None
        return text
    except Exception:  # noqa: BLE001
        return None


def _first(urls: list[str], allow_html: bool = False, budget: float = 8.0
           ) -> tuple[int, str] | None:
    """多个候选并发抢，返回 (候选序号, 正文)，优先序号小、返回快的结果。"""
    import concurrent.futures as cf

    from . import mirrors

    pairs: list[tuple[int, str]] = []
    for idx, u in enumerate(urls):
        for m in mirrors.usable()[:2]:
            cu = m.build(u)
            if cu:
                pairs.append((idx, cu))
        pairs.append((idx, u))

    ex = cf.ThreadPoolExecutor(max_workers=6)
    futs = {ex.submit(_try, cu, allow_html): idx for idx, cu in pairs}
    best: tuple[int, str] | None = None
    try:
        for f in cf.as_completed(futs, timeout=budget):
            idx = futs[f]
            if best is not None and idx >= best[0]:
                continue
            try:
                got = f.result()
            except Exception:  # noqa: BLE001
                got = None
            if got is not None:
                best = (idx, got)
                if idx == 0:
                    break
    except cf.TimeoutError:
        pass
    finally:
        ex.shutdown(wait=False, cancel_futures=True)
    return best


def load(url: str) -> dict:
    t = resolve_target(url)
    base = f"https://github.com/{t['owner']}/{t['repo']}"
    if t["kind"] == "file":
        hit = _first([_raw_url(t, t["path"])],
                     allow_html=t["path"].lower().endswith((".html", ".htm")))
        if hit is None:
            raise SystemExit("文件抓取失败（不存在，或镜像暂时不可用）")
        return {"target": t, "base": base, "name": t["path"].split("/")[-1],
                "text": hit[1], "file_path": t["path"],
                "link": f"{base}/blob/{t['ref']}/{t['path']}"}
    names = list(README_CANDIDATES)
    hit = _first([_raw_url(t, n) for n in names])
    if hit is None:
        raise SystemExit("这个仓库没找到 README")
    name = names[hit[0]] if hit[0] < len(names) else names[0]
    return {"target": t, "base": base, "name": name, "text": hit[1],
            "file_path": name, "link": f"{base}/blob/{t['ref']}/{name}"}


def _inline(s: str) -> str:
    s = re.sub(r"`([^`]+)`", r"<code>\1</code>", s)
    s = re.sub(r"!\[([^\]]*)\]\(([^)\s]+)[^)]*\)", r'<a href="\2">[图片] \1</a>', s)
    s = re.sub(r"\[([^\]]+)\]\(([^)\s]+)[^)]*\)", r'<a href="\2">\1</a>', s)
    s = re.sub(r"\*\*([^*]+)\*\*", r"<b>\1</b>", s)
    s = re.sub(r"(?<!\*)\*([^*]+)\*(?!\*)", r"<i>\1</i>", s)
    return s


def markdown_to_html(md: str) -> str:
    """够用就好的 Markdown 渲染：标题、代码块、列表、引用、段落、链接。"""
    md = md.replace("\r\n", "\n")
    blocks: list[str] = []

    def stash(m):
        blocks.append("<pre><code>" + html.escape(m.group(1)) + "</code></pre>")
        return f"\x00B{len(blocks) - 1}\x00"

    md = re.sub(r"```[^\n]*\n(.*?)```", stash, md, flags=re.S)
    md = re.sub(r"~~~[^\n]*\n(.*?)~~~", stash, md, flags=re.S)

    out: list[str] = []
    para: list[str] = []
    list_open = False

    def flush_para():
        if para:
            out.append("<p>" + _inline(" ".join(para)) + "</p>")
            para.clear()

    def close_list():
        nonlocal list_open
        if list_open:
            out.append("</ul>")
            list_open = False

    def start_block():
        flush_para()
        close_list()

    for line in md.split("\n"):
        s = line.rstrip()
        if re.fullmatch(r"\x00B\d+\x00", s):
            start_block()
            out.append(s)
            continue
        m = re.match(r"^(#{1,6})\s+(.*)$", s)
        if m:
            start_block()
            lvl = min(len(m.group(1)), 4)
            out.append(f"<h{lvl}>{_inline(m.group(2))}</h{lvl}>")
            continue
        if re.match(r"^\s*([-*_])\s*\1\s*\1[\s\-*_]*$", s):
            start_block()
            out.append("<hr>")
            continue
        if s.startswith(">"):
            start_block()
            out.append("<blockquote>" + _inline(s.lstrip("> ")) + "</blockquote>")
            continue
        m = re.match(r"^\s*(?:[-*+]|\d+\.)\s+(.*)$", s)
        if m:
            flush_para()
            if not list_open:
                out.append("<ul>")
                list_open = True
            out.append("<li>" + _inline(m.group(1)) + "</li>")
            continue
        if not s.strip():
            flush_para()
            close_list()
            continue
        if list_open:
            out.append("<li>" + _inline(s) + "</li>")
        else:
            para.append(s)
    flush_para()
    close_list()
    text = "\n".join(out)
    for i, b in enumerate(blocks):
        text = text.replace(f"\x00B{i}\x00", b)
    return text


def _rewrite_links(page: str, base: str) -> str:
    def fix(m):
        href = m.group(1)
        if href.startswith(("http://", "https://")):
            if "github.com" in href:
                return f'href="/read?url={urllib.parse.quote(href, safe="")}"'
            return m.group(0)
        if href.startswith("#"):
            return m.group(0)
        target = urllib.parse.urljoin(base + "/", href)
        if "github.com" not in target:
            return m.group(0)
        return f'href="/read?url={urllib.parse.quote(target, safe="")}"'

    return re.sub(r'href="([^"]+)"', fix, page)


def page(url: str) -> str:
    d = load(url)
    name, text, base, t = d["name"], d["text"], d["base"], d["target"]
    if name.lower().endswith((".md", ".markdown")):
        body = markdown_to_html(text)
    else:
        body = "<pre><code>" + html.escape(text[:400000]) + "</code></pre>"
    body = _rewrite_links(body, base)
    title = f"{t['owner']}/{t['repo']} · {name}"
    raw = _raw_url(t, d["file_path"])
    dl_link = "/dl?url=" + urllib.parse.quote(raw, safe="")
    home = "/read?url=" + urllib.parse.quote(base, safe="")
    return f"""<!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>{html.escape(title)}</title>
<style>
:root{{--bg:#0d1117;--card:#161b22;--line:#30363d;--fg:#e6edf3;--dim:#8b949e;--acc:#58a6ff}}
*{{box-sizing:border-box}}
body{{margin:0;padding:12px;background:var(--bg);color:var(--fg);
font:15px/1.7 -apple-system,system-ui,"PingFang SC",sans-serif}}
.top{{position:sticky;top:0;background:var(--bg);padding:6px 0 8px;border-bottom:1px solid var(--line);margin-bottom:12px}}
.top b{{font-size:14px}}
.top a{{color:var(--acc);font-size:13px;margin-right:12px;text-decoration:none;white-space:nowrap}}
.card{{background:var(--card);border:1px solid var(--line);border-radius:10px;padding:12px 14px}}
h1,h2,h3,h4{{line-height:1.35;margin:18px 0 8px}}
h1{{font-size:22px}}h2{{font-size:19px}}h3{{font-size:17px}}h4{{font-size:15px}}
a{{color:var(--acc);word-break:break-word}}
pre{{background:#0d1117;border:1px solid var(--line);border-radius:8px;padding:10px;
overflow-x:auto;font-size:13px;line-height:1.45}}
code{{background:#0d1117;border:1px solid var(--line);border-radius:4px;padding:1px 4px;font-size:13px}}
pre code{{border:0;padding:0}}
blockquote{{margin:10px 0;padding:2px 12px;border-left:3px solid var(--line);color:var(--dim)}}
li{{margin:4px 0}}hr{{border:0;border-top:1px solid var(--line);margin:16px 0}}
img{{max-width:100%}}
</style></head><body>
<div class="top"><b>{html.escape(title)}</b><br>
<a href="{home}">仓库首页</a>
<a href="{html.escape(d['link'])}">原始页面</a>
<a href="{dl_link}">下载文件</a>
<span style="color:var(--dim);font-size:12px">只读 · 服务端加速</span></div>
<div class="card">{body}</div>
</body></html>"""
