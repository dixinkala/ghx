"""命令行入口：ghx <命令>。"""
from __future__ import annotations

import argparse
import os
import socket
import sys

from . import net
from .util import Color, ago, cache_path, human_size, load_json, table

VERSION = "1.0.0"


def _probe_direct(host: str) -> dict:
    ips = net.system_ips(host)
    if not ips:
        return {"host": host, "ok": False, "error": "DNS 解析失败"}
    r = net.probe_https(ips[0], host, path="/", timeout=6.0, sample_bytes=64 * 1024)
    r["host"] = host
    r["ip"] = ips[0]
    return r


def cmd_doctor(args) -> int:
    print(Color.bold("\nGitHub 加速器 · 体检"))
    print(Color.dim("  环境: proot Ubuntu / 手机网络\n"))

    print("1) 直连测试")
    for host in ("github.com", "api.github.com", "codeload.github.com"):
        r = _probe_direct(host)
        if r["ok"]:
            speed = (f"{r['kbps']:6.0f} KiB/s" if r["bytes"] > 32 * 1024
                     else "响应偏小   ")
            print(f"   {Color.green('✓')} {host:22s} {r['ip']:15s} "
                  f"{speed}  首字节 {r['ttfb_ms']:.0f}ms")
        else:
            print(f"   {Color.red('×')} {host:22s} {r['ip']:15s} {r['error'][:46]}")

    print("\n2) SNI 阻断检测（hosts 救不了的域名）")
    for host in ("raw.githubusercontent.com", "gist.github.com"):
        blocked, err = net.is_sni_blocked(host)
        if blocked:
            print(f"   {Color.yellow('!')} {host:26s} 被阻断 → 只能用镜像")
        else:
            print(f"   {Color.green('✓')} {host:26s} 可直连")

    print("\n3) 镜像节点")
    from . import mirrors

    rows = mirrors.rank(force=args.refresh)
    if rows:
        cols = [[r["name"], ("可用" if r["ok"] else "不可用"),
                 (f"{r['kbps']:.0f} KiB/s" if r["ok"] else r["error"][:28])] for r in rows]
        print("\n".join("   " + line for line in table(cols).splitlines()))
        print(Color.dim(f"   缓存: {ago(load_json(cache_path('mirrors.json'), {}).get('ts', 0))}"
                        "   （ghx mirror rank 可强制重测）"))

    print("\n4) hosts 加速")
    from . import ipopt

    block = ipopt.current_block()
    if block:
        v = ipopt.verify()
        flag = Color.green("生效") if v["ok"] else Color.yellow("未生效")
        print(f"   已写入 {len(block)} 条，{flag}")
        for line in block:
            print(f"   {Color.dim(line)}")
    else:
        print("   未启用（ghx ip --apply 一键优选并写入）")

    print("\n5) git 配置")
    from . import gitcfg

    cfg = gitcfg.status()
    if cfg:
        for k, v in cfg:
            print(f"   {k} = {v}")
    else:
        print("   未做加速配置（ghx git tune / ghx git apply）")

    print("\n6) 网页门户")
    try:
        s = socket.create_connection(("127.0.0.1", 8788), timeout=1.5)
        s.close()
        print(f"   {Color.green('✓')} http://127.0.0.1:8788 正在运行")
    except OSError:
        print("   未运行（ghx serve 启动，手机浏览器可用）")

    print("\n建议：")
    print("   · 下载/克隆慢 → ghx dl <链接> / ghx git clone owner/repo")
    print("   · 网页与 git 慢 → ghx ip --apply（写入 hosts 优选 IP）")
    print("   · 手机浏览器 → ghx serve，然后打开 http://127.0.0.1:8788")
    return 0


def cmd_ip(args) -> int:
    from . import ipopt

    print(Color.bold("\n优选 GitHub 入口 IP") + Color.dim("（真实 TLS + 下载测速）\n"))
    rows = ipopt.optimize(verbose=True)
    ok = [r for r in rows if r.get("ok")]
    if not ok:
        print(Color.red("\n没有可用结果，稍后重试"))
        return 1
    print()
    print(ipopt.render(rows))
    if args.apply:
        ipopt.apply(rows)
        v = ipopt.verify()
        print(Color.green("  ✓ 验证通过：系统已解析到优选 IP") if v["ok"]
              else Color.yellow("  ! 写入后验证未通过，可 ghx hosts restore 恢复"))
    else:
        print(Color.dim("\n加 --apply 写入 /etc/hosts（会先备份原文件）"))
    return 0


def cmd_hosts(args) -> int:
    from . import ipopt

    if args.action == "restore":
        ipopt.restore()
        return 0
    block = ipopt.current_block()
    if not block:
        print("hosts 加速未启用")
        return 0
    v = ipopt.verify()
    print("当前加速条目：")
    for line in block:
        print("  " + line)
    print(Color.green("解析验证通过") if v["ok"] else Color.yellow("解析验证未通过"))
    return 0


def cmd_dl(args) -> int:
    from . import dl

    dl.download(args.url, out=args.out, jobs=args.jobs)
    return 0


def cmd_read(args) -> int:
    from . import readpage

    d = readpage.load(args.url)
    t = d["target"]
    print(Color.bold(f"\n{t['owner']}/{t['repo']} · {d['name']}\n"))
    print(d["text"])
    return 0


def cmd_git(args) -> int:
    from . import gitcfg

    if args.action == "clone":
        gitcfg.clone(args.repo, dest=args.dest, depth=args.depth, branch=args.branch)
    elif args.action == "apply":
        gitcfg.apply_read()
    elif args.action == "undo":
        gitcfg.undo()
    elif args.action == "tune":
        gitcfg.tune()
    elif args.action == "status":
        rows = gitcfg.status()
        print("\n".join(f"  {k} = {v}" for k, v in rows) if rows else "  未配置")
    return 0


def cmd_mirror(args) -> int:
    from . import mirrors
    from .util import table as tbl

    if args.action == "add":
        mirrors.add(args.value)
        return 0
    if args.action == "rm":
        mirrors.remove(args.value)
        return 0
    if args.action == "rank":
        rows = mirrors.rank(force=True)
    elif args.action == "ls":
        rows = [{"name": m.name, "base": m.base, "kind": m.kind, "ok": True,
                 "kbps": 0, "error": ""} for m in mirrors.candidates()]
    else:
        rows = mirrors.rank(force=False)
    data = [[r["name"], r["base"] if args.action == "ls" else ("可用" if r["ok"] else "×"),
             f"{r['kbps']:.0f} KiB/s" if r["ok"] else r.get("error", "")[:30]] for r in rows]
    print(tbl(data, ["节点", "状态", "速度"]))
    return 0


def cmd_serve(args) -> int:
    from . import portal

    host = "0.0.0.0" if args.lan else "127.0.0.1"
    if args.lan:
        print(Color.yellow("  ! 已监听所有网卡，同一 Wi-Fi 的其他设备可访问此门户"))
    portal.serve(host=host, port=args.port)
    return 0


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(prog="ghx", description="GitHub 加速器（国内网络）")
    p.add_argument("--version", action="version", version=f"ghx {VERSION}")
    sub = p.add_subparsers(dest="cmd", required=True)

    d = sub.add_parser("doctor", help="体检：直连、阻断、镜像、hosts、git")
    d.add_argument("--refresh", action="store_true", help="强制重新测速镜像")
    d.set_defaults(func=cmd_doctor)

    i = sub.add_parser("ip", help="优选 GitHub IP 并可选写入 hosts")
    i.add_argument("--apply", action="store_true", help="写入 /etc/hosts")
    i.set_defaults(func=cmd_ip)

    h = sub.add_parser("hosts", help="查看或恢复 hosts 加速块")
    h.add_argument("action", nargs="?", default="show", choices=["show", "restore"])
    h.set_defaults(func=cmd_hosts)

    d2 = sub.add_parser("dl", help="多镜像加速下载")
    d2.add_argument("url")
    d2.add_argument("-o", "--out")
    d2.add_argument("-j", "--jobs", type=int, default=6)
    d2.set_defaults(func=cmd_dl)

    g = sub.add_parser("git", help="git 加速")
    gsub = g.add_subparsers(dest="action", required=True)
    gc = gsub.add_parser("clone")
    gc.add_argument("repo")
    gc.add_argument("-d", "--dest")
    gc.add_argument("--depth", type=int)
    gc.add_argument("-b", "--branch")
    gsub.add_parser("apply")
    gsub.add_parser("undo")
    gsub.add_parser("tune")
    gsub.add_parser("status")
    g.set_defaults(func=cmd_git)

    rd = sub.add_parser("read", help="终端里只读查看仓库 README / 源码")
    rd.add_argument("url")
    rd.set_defaults(func=cmd_read)

    m = sub.add_parser("mirror", help="镜像节点管理")
    msub = m.add_subparsers(dest="action", required=True)
    msub.add_parser("ls")
    msub.add_parser("rank")
    ma = msub.add_parser("add")
    ma.add_argument("value")
    mr = msub.add_parser("rm")
    mr.add_argument("value")
    m.set_defaults(func=cmd_mirror)

    s = sub.add_parser("serve", help="启动本机网页门户")
    s.add_argument("-p", "--port", type=int, default=8788)
    s.add_argument("--lan", action="store_true", help="允许局域网访问")
    s.set_defaults(func=cmd_serve)
    return p


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        return args.func(args)
    except KeyboardInterrupt:
        print("\n已中断")
        return 130
    except SystemExit as e:
        if e.code not in (0, None):
            print(Color.red(f"\n{e}"), file=sys.stderr)
        return int(e.code or 0)


if __name__ == "__main__":
    sys.exit(main())
