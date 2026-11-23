"""本机网页门户：浏览器（含手机浏览器）打开 127.0.0.1:8788 就能用加速。

proot 与 Android 共用 loopback，所以手机浏览器可以直接访问这个地址。
服务端负责走镜像抓取，浏览器只管拿文件/看状态，不依赖浏览器自己的网络。
"""
from __future__ import annotations

import html
import http.client
import json
import os
import threading
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from . import dl, ipopt, mirrors, net, readpage
from .util import ago, cache_path, human_size, load_json

STATE_LOCK = threading.Lock()


def _mirror_state() -> dict:
    cached = load_json(cache_path("mirrors.json"), {})
    rows = cached.get("results") or []
    return {
        "ts": cached.get("ts", 0),
        "ago": ago(cached.get("ts", 0)),
        "rows": [{"name": r["name"], "ok": r["ok"], "kbps": r["kbps"]} for r in rows],
    }


def _hosts_state() -> dict:
    block = ipopt.current_block()
    return {"entries": block, "count": len(block)}


class Handler(BaseHTTPRequestHandler):
    server_version = "ghx/1.0"

    def log_message(self, fmt, *args):  # 静默访问日志，避免刷屏
        pass

    def _json(self, obj, code: int = 200):
        body = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def _html(self, text: str):
        body = text.encode()
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):  # noqa: N802
        u = urllib.parse.urlsplit(self.path)
        q = urllib.parse.parse_qs(u.query)
        try:
            if u.path in ("/", "/index.html"):
                return self._html(PAGE)
            if u.path == "/api/state":
                return self._json({"mirrors": _mirror_state(), "hosts": _hosts_state()})
            if u.path == "/api/resolve":
                target = (q.get("url") or [""])[0]
                if not target:
                    return self._json({"error": "缺少 url 参数"}, 400)
                return self._json(dl.plan(target))
            if u.path == "/read":
                target = (q.get("url") or [""])[0]
                if not target:
                    return self._json({"error": "缺少 url 参数"}, 400)
                try:
                    return self._html(readpage.page(target))
                except SystemExit as e:
                    msg = html.escape(str(e))
                    return self._html(
                        f'<!doctype html><meta charset="utf-8">'
                        f'<meta name="viewport" content="width=device-width,initial-scale=1">'
                        f'<body style="background:#0d1117;color:#e6edf3;font:15px system-ui;padding:24px">'
                        f'<p>读不出来：{msg}</p><p><a style="color:#58a6ff" href="/">返回首页</a></p>')
            if u.path == "/dl":
                target = (q.get("url") or [""])[0]
                if not target:
                    return self._json({"error": "缺少 url 参数"}, 400)
                return self._proxy_download(target)
            if u.path == "/health":
                return self._json({"ok": True})
        except SystemExit as e:
            return self._json({"error": str(e)}, 400)
        except Exception as e:  # noqa: BLE001
            return self._json({"error": f"{type(e).__name__}: {e}"}, 500)
        return self._json({"error": "not found"}, 404)

    def do_POST(self):  # noqa: N802
        u = urllib.parse.urlsplit(self.path)
        if u.path == "/api/ip/apply":
            with STATE_LOCK:
                rows = ipopt.optimize()
                ok = [r for r in rows if r.get("ok")]
                if not ok:
                    return self._json({"error": "没有找到可用 IP"}, 500)
                ipopt.apply(rows)
                return self._json({"ok": True, "hosts": ipopt.current_block()})
        if u.path == "/api/mirror/refresh":
            with STATE_LOCK:
                mirrors.rank(force=True)
            return self._json({"ok": True, "mirrors": _mirror_state()})
        return self._json({"error": "not found"}, 404)

    def _proxy_download(self, target: str):
        target = dl.normalize(target)
        cands = dl.candidate_urls(target)
        rng = self.headers.get("Range")
        last = ""
        for name, cu in cands:
            try:
                return self._stream(cu, name, rng)
            except (OSError, http.client.HTTPException) as e:
                last = f"{name}: {e}"
                continue
        return self._json({"error": f"所有节点都失败（{last}）"}, 502)

    def _stream(self, url: str, node: str, rng: str | None = None):
        u = urllib.parse.urlsplit(url)
        conn = http.client.HTTPSConnection(u.hostname, u.port or 443, timeout=30)
        path = urllib.parse.urlunsplit(("", "", u.path or "/", u.query, ""))
        hdrs = {"User-Agent": net.UA, "Accept-Encoding": "identity"}
        if rng:
            hdrs["Range"] = rng
        conn.request("GET", path, headers=hdrs)
        r = conn.getresponse()
        if r.status not in (200, 206):
            code = r.status
            conn.close()
            raise OSError(f"HTTP {code}")
        name = dl.guess_name(url, {k.lower(): v for k, v in r.getheaders()})
        self.send_response(r.status)
        for k in ("Content-Range", "Accept-Ranges"):
            if r.getheader(k):
                self.send_header(k, r.getheader(k))
        self.send_header("Content-Type", r.getheader("Content-Type") or "application/octet-stream")
        length = r.getheader("Content-Length")
        if length:
            self.send_header("Content-Length", length)
        self.send_header("Content-Disposition",
                         f"attachment; filename*=UTF-8''{urllib.parse.quote(name)}")
        self.send_header("X-Ghx-Node", node)
        self.end_headers()
        try:
            while True:
                b = r.read(131072)
                if not b:
                    break
                self.wfile.write(b)
        finally:
            conn.close()


PAGE = """<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>GitHub 加速器</title>
<style>
:root{--bg:#0d1117;--card:#161b22;--line:#30363d;--fg:#e6edf3;--dim:#8b949e;--acc:#2f81f7;--ok:#3fb950;--warn:#d29922}
*{box-sizing:border-box}
body{margin:0;padding:16px;background:var(--bg);color:var(--fg);font:15px/1.55 -apple-system,system-ui,"PingFang SC",sans-serif}
h1{font-size:19px;margin:0 0 2px}h1 small{color:var(--dim);font-weight:400;font-size:12px}
.card{background:var(--card);border:1px solid var(--line);border-radius:10px;padding:14px;margin:12px 0}
.card h2{font-size:14px;margin:0 0 10px;color:var(--dim);font-weight:500}
.row{display:flex;gap:8px}
input{flex:1;min-width:0;padding:10px;border-radius:8px;border:1px solid var(--line);background:#0d1117;color:var(--fg);font-size:14px}
button{padding:10px 14px;border-radius:8px;border:1px solid var(--line);background:#21262d;color:var(--fg);font-size:14px}
button.primary{background:var(--acc);border-color:var(--acc);color:#fff}
button:active{opacity:.8}
.pill{display:inline-block;padding:1px 8px;border-radius:20px;font-size:12px;border:1px solid var(--line)}
.ok{color:var(--ok);border-color:#238636}.bad{color:#f85149;border-color:#da3633}
table{width:100%;border-collapse:collapse;font-size:13px;table-layout:fixed}
td{padding:5px 0;border-bottom:1px solid #21262d;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
td:last-child{text-align:right;width:88px;color:var(--dim)}
a{color:var(--acc);text-decoration:none;word-break:break-all}
.res{margin-top:10px;font-size:13px;display:grid;gap:8px}
.res div{background:#0d1117;border:1px solid var(--line);border-radius:8px;padding:8px}
.mut{color:var(--dim);font-size:12px}
details{margin-top:8px}summary{cursor:pointer;color:var(--dim);font-size:13px}
code{background:#0d1117;border:1px solid var(--line);border-radius:4px;padding:1px 5px;font-size:12px;word-break:break-all}
</style></head><body>
<h1>GitHub 加速器 <small>本机 127.0.0.1</small></h1>
<div class="card"><h2>当前状态</h2><div id="state" class="mut">读取中…</div></div>
<div class="card"><h2>GitHub 链接 → 加速下载</h2>
<div class="row"><input id="u" placeholder="粘贴 github.com 链接，仓库 / release / raw 文件都行">
<button class="primary" id="go">解析</button></div>
<div id="out" class="res"></div>
<div class="mut" style="margin-top:8px">解析后可直接用手机浏览器下载，流量走本机镜像代理。</div>
</div>
<div class="card"><h2>只读浏览（手机看 README / 源码）</h2>
<div class="row"><input id="r" placeholder="粘贴仓库链接，如 github.com/jqlang/jq">
<button id="rg">打开</button></div>
<div class="mut" style="margin-top:8px">服务端抓取正文，页面只有文字，省流量也快。</div>
</div>
<div class="card"><h2>一键操作</h2>
<div class="row"><button id="ip">优选 IP 并写入 hosts</button><button id="mr">重新测速镜像</button></div>
<div id="act" class="mut" style="margin-top:8px"></div>
<details><summary>说明</summary><div class="mut">
· hosts 只影响这个 Ubuntu 环境里的 git / curl / pip，不影响手机系统浏览器。<br>
· 手机浏览器打开本页即可用加速下载，但 GitHub 网页本身仍需现场网络可达。<br>
· 推送代码请用 <code>git push github 分支</code>（origin 指向镜像，只适合拉取）。
</div></details>
</div>
<script>
const $=s=>document.querySelector(s);
async function api(p,opt){const r=await fetch(p,opt);return r.json()}
function esc(s){return String(s).replace(/[&<>"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]))}
async function state(){
  try{
    const d=await api('/api/state');
    const m=d.mirrors.rows.filter(r=>r.ok);
    let h='<div class="mut">镜像实测：'+d.mirrors.ago+'</div>';
    h+='<table>'+m.slice(0,5).map(r=>`<tr><td>${esc(r.name)}</td><td>${r.kbps.toFixed(0)} KiB/s</td></tr>`).join('')+'</table>';
    const n=d.hosts.count;
    h+=`<div style="margin-top:8px">hosts 加速：<span class="pill ${n?'ok':'bad'}">${n?'已启用 '+n+' 条':'未启用'}</span></div>`;
    if(n){h+='<div class="mut" style="margin-top:6px">'+d.hosts.entries.map(esc).join('<br>')+'</div>';}
    $('#state').innerHTML=h;
  }catch(e){$('#state').textContent='读取失败：'+e}
}
async function resolve(){
  const u=$('#u').value.trim(); if(!u)return;
  $('#out').innerHTML='<div class="mut">解析中…</div>';
  try{
    const d=await api('/api/resolve?url='+encodeURIComponent(u));
    if(d.error){$('#out').innerHTML='<div class="bad">'+esc(d.error)+'</div>';return}
    let h=`<div class="mut">原始地址<br>${esc(d.url)}</div>`;
    h+=`<div><b>本机加速下载</b>（推荐，服务端走镜像）<br><a href="/dl?url=${encodeURIComponent(d.url)}">点这里开始下载</a></div>`;
    h+=`<div><b>只读浏览</b>（看 README / 源码，不需要打开 GitHub）<br><a href="/read?url=${encodeURIComponent(d.url)}">用只读模式打开</a></div>`;
    h+='<div class="mut">镜像直链（复制到下载器）<br>'+d.links.slice(0,5).map(l=>`<span class="pill">${esc(l.node)}</span> <a href="${esc(l.url)}">${esc(l.url.slice(0,90))}</a>`).join('<br>')+'</div>';
    $('#out').innerHTML=h;
  }catch(e){$('#out').innerHTML='<div class="bad">'+esc(e)+'</div>'}
}
$('#go').onclick=resolve;
$('#rg').onclick=()=>{const u=$('#r').value.trim();if(u)location.href='/read?url='+encodeURIComponent(u)};
$('#r').addEventListener('keydown',e=>{if(e.key==='Enter'&&$('#r').value.trim())location.href='/read?url='+encodeURIComponent($('#r').value.trim())});
$('#u').addEventListener('keydown',e=>{if(e.key==='Enter')resolve()});
$('#ip').onclick=async()=>{$('#act').textContent='测速中，约 30 秒…';const d=await api('/api/ip/apply',{method:'POST'});$('#act').textContent=d.error?('失败：'+d.error):'已写入 hosts';state()};
$('#mr').onclick=async()=>{$('#act').textContent='测速中，约 30 秒…';const d=await api('/api/mirror/refresh',{method:'POST'});$('#act').textContent=d.error?('失败：'+d.error):'镜像已重新排序';state()};
state();
</script></body></html>
"""


def serve(host: str = "127.0.0.1", port: int = 8788) -> None:
    httpd = ThreadingHTTPServer((host, port), Handler)
    url = f"http://{host}:{port}"
    print(f"  ✓ 门户已启动: {url}")
    print("    手机浏览器直接打开上面地址；Ctrl+C 停止")
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\n  已停止")
    finally:
        httpd.server_close()
