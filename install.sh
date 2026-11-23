#!/usr/bin/env bash
# 把 ghx 装到 /usr/local/bin，之后任意目录都能直接敲 ghx
set -euo pipefail
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
chmod +x "$DIR/ghx"
if [ ! -w /usr/local/bin ]; then
  echo "没有写 /usr/local/bin 的权限，手动加别名：alias ghx='$DIR/ghx'" >&2
  exit 1
fi
ln -sf "$DIR/ghx" /usr/local/bin/ghx
echo "已安装: /usr/local/bin/ghx -> $DIR/ghx"
echo "试试: ghx doctor"
