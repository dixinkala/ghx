#!/usr/bin/env bash
# 一键回归：纯逻辑单测 + 真实网络 + APK 静态检查
set -uo pipefail
cd "$(dirname "$0")"

JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-arm64}"
JC="$JAVA_HOME/bin/javac"
JAVA="$JAVA_HOME/bin/java"
# 本机 JVM 信任库路径（Android 上用系统证书，不需要）
TLS=""
[ -f /etc/ssl/certs/java/cacerts ] && TLS="-Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts -Djavax.net.ssl.trustStorePassword=changeit"

pass=0; fail=0
section() { echo; echo "== $1 =="; }
ok()   { echo "PASS $1"; pass=$((pass+1)); }
bad()  { echo "FAIL $1"; fail=$((fail+1)); }

rm -rf build/test && mkdir -p build/test
section "编译"
if $JC -nowarn -d build/test src/com/ghx/accel/core/*.java test/*.java 2>build/javac.log; then
  ok "编译 core + 测试"
else
  bad "编译失败"; cat build/javac.log; exit 1
fi

run() { # name, main class, extra java opts
  local name="$1" cls="$2"; shift 2
  local log="build/$(echo "$name" | tr -c 'A-Za-z0-9._-' '_').log"
  if $JAVA $TLS "$@" -cp build/test "$cls" >"$log" 2>&1; then
    ok "$name"; sed 's/^/     /' "$log" | head -6
  else
    bad "$name"; sed 's/^/     /' "$log" | tail -8
  fi
}

section "纯逻辑"
run "DNS 报文与校验和"  com.ghx.accel.core.DnsTest
run "EDNS 查询端到端"   com.ghx.accel.core.PacketTest
run "DNS 路由与转发"     com.ghx.accel.core.RouterTest

FAST=0
for arg in "$@"; do [ "$arg" = "--fast" ] && FAST=1; done
if [ "$FAST" = 0 ]; then
  section "真实网络（约 40 秒）"
  run "镜像测速/下载/IP 优选" com.ghx.accel.core.CoreTest
  run "断点续传" com.ghx.accel.core.ResumeTest
fi

section "APK 静态检查"
APK="ghx.apk"
if $JAVA -jar "$(find /opt/android -name apksigner.jar | head -1)" verify "$APK" >/dev/null 2>&1; then
  ok "签名校验"
else
  bad "签名校验"
fi
VER=$(aapt dump badging "$APK" 2>/dev/null | head -1 | sed -E "s/.*versionName='([^']*)'.*/\1/")
[ -n "$VER" ] && ok "清单版本 $VER" || bad "读不到版本"
python3 - "$APK" <<'PY' && pass=$((pass+1)) || fail=$((fail+1))
import sys, zipfile
z = zipfile.ZipFile(sys.argv[1]); dex = z.read("classes.dex"); html = z.read("assets/index.html").decode()
need = [(b"MainActivity", "dex 含 MainActivity"), (b"DnsVpn", "dex 含 DnsVpn"),
        (b"DnsRouter", "dex 含 DnsRouter"), (b"selfCheck", "dex 含自检"),
        ("私人 DNS".encode(), "dex 含私人 DNS 检测")]
missing = [n for k, n in need if k not in dex]
if missing: print("FAIL 缺少:", missing); sys.exit(1)
if "先测一次" not in html: print("FAIL 页面缺提示"); sys.exit(1)
print("PASS APK 内容完整")
PY

echo
echo "结果：$pass 通过，$fail 失败"
[ "$fail" -eq 0 ]
