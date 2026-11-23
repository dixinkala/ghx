"""git 加速：用镜像克隆、全局 insteadOf、连接参数调优。"""
from __future__ import annotations

import os
import re
import subprocess
import time

from . import mirrors
from .util import Color

GITHUB = "https://github.com"


def _git(*args: str, check: bool = True, timeout: float | None = None,
         cwd: str | None = None) -> subprocess.CompletedProcess:
    return subprocess.run(["git", *args], capture_output=True, text=True,
                          check=check, timeout=timeout, cwd=cwd)


def canon(repo: str) -> tuple[str, str]:
    """'owner/repo' 或任意 github URL → (https 克隆地址, 显示名)。"""
    repo = repo.strip()
    m = re.match(r"^[\w.-]+/[\w.-]+$", repo)
    if m:
        return f"{GITHUB}/{repo}.git", repo
    if repo.startswith("git@github.com:"):
        path = repo.split(":", 1)[1]
        path = path[:-4] if path.endswith(".git") else path
        return f"{GITHUB}/{path}.git", path
    if repo.startswith(("https://", "http://")):
        u = repo.rstrip("/")
        u = u[:-4] if u.endswith(".git") else u
        host = u.split("://", 1)[1].split("/", 1)[0].lower()
        if "github.com" not in host:
            raise SystemExit(f"不是 GitHub 仓库: {repo}")
        return u + ".git", u.split("github.com/", 1)[-1]
    raise SystemExit("仓库写法应为 owner/repo 或 https://github.com/owner/repo")


def git_mirrors() -> list[mirrors.Mirror]:
    """能加速 git 的节点：支持反代的优先，gitclone 兜底。"""
    out = list(mirrors.usable())
    for m in mirrors.candidates():
        if m.kind == "gitclone" and m not in out:
            out.append(m)
    return out


def clone(repo: str, dest: str | None = None, depth: int | None = None,
          branch: str | None = None) -> dict:
    url, name = canon(repo)
    dest = dest or name.split("/")[-1]
    if os.path.exists(dest) and os.listdir(dest):
        raise SystemExit(f"目录已存在且非空: {dest}")

    errors = []
    for m in git_mirrors():
        mu = m.build_git(url)
        if not mu:
            continue
        args = ["clone", "--progress"]
        if depth:
            args += ["--depth", str(depth)]
        if branch:
            args += ["--branch", branch]
        args += [mu, dest]
        print(Color.cyan(f"  尝试 {m.name} ..."), flush=True)
        t0 = time.monotonic()
        proc = subprocess.run(["git", *args], capture_output=True, text=True)
        dt = time.monotonic() - t0
        if proc.returncode == 0:
            _git("remote", "set-url", "origin", mu, cwd=dest)
            remotes = _git("remote", cwd=dest).stdout.split()
            if "github" in remotes:
                _git("remote", "set-url", "github", url, cwd=dest)
            else:
                _git("remote", "add", "github", url, cwd=dest)
            size = _repo_size(dest)
            print(Color.green(f"  ✓ 克隆完成：{dest}  {size}  {dt:.1f}s  节点 {m.name}"))
            print(f"    加速拉取: git pull            （origin = {m.name}）")
            print(f"    推送到官方: git push github <分支>")
            return {"ok": True, "dest": dest, "node": m.name, "seconds": round(dt, 2)}
        tail = (proc.stderr or proc.stdout).strip().splitlines()[-1:] or [""]
        errors.append(f"{m.name}: {tail[0][:90]}")
        print(Color.yellow(f"  × {m.name} 失败: {tail[0][:70]}"))
    raise SystemExit("所有节点克隆失败：\n  " + "\n  ".join(errors))


def _repo_size(path: str) -> str:
    total = 0
    for root, _dirs, files in os.walk(path):
        for f in files:
            try:
                total += os.path.getsize(os.path.join(root, f))
            except OSError:
                pass
    from .util import human_size

    return human_size(total)


def apply_read(mirror: mirrors.Mirror | None = None) -> None:
    """全局把 github.com 的读写都改到镜像（推送会失败，需要 --undo 恢复）。"""
    m = mirror or (git_mirrors()[0] if git_mirrors() else None)
    if not m:
        raise SystemExit("没有可用镜像")
    base = m.base.rstrip("/") + "/https://github.com/"
    _git("config", "--global", f"url.{base}.insteadOf", "https://github.com/")
    _git("config", "--global", "--add", f"url.{base}.insteadOf", "git@github.com:")
    print(Color.green(f"  ✓ 已让 git 走 {m.name}（clone/fetch 加速）"))
    print(Color.yellow("    注意：push 也会被改写而失败；需要推送时运行 ghx git undo"))


def undo() -> None:
    out = _git("config", "--global", "--get-regexp", r"^url\..*\.insteadof", check=False).stdout
    n = 0
    for line in out.splitlines():
        key = line.split(" ", 1)[0]
        _git("config", "--global", "--unset-all", key, check=False)
        n += 1
    print(f"已移除 {n} 条 insteadOf 规则" if n else "没有 insteadOf 规则需要移除")


def tune() -> None:
    _git("config", "--global", "http.postBuffer", "524288000")
    _git("config", "--global", "http.lowSpeedLimit", "1000")
    _git("config", "--global", "http.lowSpeedTime", "60")
    _git("config", "--global", "http.version", "HTTP/1.1")
    print("  ✓ 已设置 git 传输参数（大包缓冲、低速超时、HTTP/1.1）")


def status() -> list[tuple[str, str]]:
    out = _git("config", "--global", "--list", check=False).stdout
    rows = []
    for line in out.splitlines():
        if re.match(r"^(url\.|http\.|core\.compression)", line):
            k, _, v = line.partition("=")
            rows.append((k, v))
    return rows
