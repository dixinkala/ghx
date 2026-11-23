"""公共工具：路径、配置缓存、终端输出、表格。"""
from __future__ import annotations

import json
import os
import sys
import time

APP = "ghx"
CONFIG_DIR = os.path.expanduser("~/.config/ghx")
CACHE_DIR = os.path.expanduser("~/.cache/ghx")


def ensure_dirs() -> None:
    for d in (CONFIG_DIR, CACHE_DIR):
        os.makedirs(d, exist_ok=True)


def load_json(path: str, default):
    try:
        with open(path, "r", encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return default


def save_json(path: str, data) -> None:
    ensure_dirs()
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
    os.replace(tmp, path)


def cache_path(name: str) -> str:
    return os.path.join(CACHE_DIR, name)


def config_path(name: str) -> str:
    return os.path.join(CONFIG_DIR, name)


def human_size(n: float) -> str:
    for unit in ("B", "KiB", "MiB", "GiB"):
        if n < 1024 or unit == "GiB":
            return f"{n:.1f}{unit}" if unit != "B" else f"{int(n)}B"
        n /= 1024
    return f"{n:.1f}GiB"


class Color:
    on = sys.stdout.isatty() and os.environ.get("NO_COLOR") is None

    @classmethod
    def _wrap(cls, code: str, s: str) -> str:
        return f"\033[{code}m{s}\033[0m" if cls.on else s

    @classmethod
    def green(cls, s: str) -> str:
        return cls._wrap("32", s)

    @classmethod
    def yellow(cls, s: str) -> str:
        return cls._wrap("33", s)

    @classmethod
    def red(cls, s: str) -> str:
        return cls._wrap("31", s)

    @classmethod
    def cyan(cls, s: str) -> str:
        return cls._wrap("36", s)

    @classmethod
    def bold(cls, s: str) -> str:
        return cls._wrap("1", s)

    @classmethod
    def dim(cls, s: str) -> str:
        return cls._wrap("2", s)


def table(rows: list[list[str]], headers: list[str] | None = None) -> str:
    """按显示宽度对齐的简单表格，兼容中文宽字符。"""
    data = ([headers] if headers else []) + rows
    widths = []
    for col in range(len(data[0])):
        widths.append(max(_width(r[col]) for r in data))
    lines = []
    for i, row in enumerate(data):
        cells = [r + " " * (widths[j] - _width(r)) for j, r in enumerate(row)]
        lines.append("  ".join(cells).rstrip())
        if i == 0 and headers:
            lines.append("  ".join("-" * w for w in widths))
    return "\n".join(lines)


def _width(s: str) -> int:
    import unicodedata

    return sum(2 if unicodedata.east_asian_width(c) in "WF" else 1 for c in s)


def now_ts() -> float:
    return time.time()


def ago(ts: float) -> str:
    if not ts:
        return "从未"
    d = max(0, int(time.time() - ts))
    if d < 60:
        return f"{d} 秒前"
    if d < 3600:
        return f"{d // 60} 分钟前"
    if d < 86400:
        return f"{d // 3600} 小时前"
    return f"{d // 86400} 天前"
