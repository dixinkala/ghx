#!/usr/bin/env bash
# 下载构建所需的 Android 平台与 build-tools（只在缺工具时用）
set -euo pipefail
SDK="${ANDROID_SDK:-/opt/android}"
mkdir -p "$SDK"
cd "$SDK"
need_platform=1; need_bt=1
find "$SDK" -name android.jar | grep -q . && need_platform=0
[ -f "$SDK/sdk/android-14/lib/d8.jar" ] && need_bt=0
if [ "$need_platform" = 1 ]; then
  echo "下载 android.jar..."
  python3 -c "import urllib.request;urllib.request.urlretrieve('https://dl.google.com/android/repository/platform-30_r03.zip','platform-30.zip')"
  unzip -o -q platform-30.zip -d "$SDK/sdk"
fi
if [ "$need_bt" = 1 ]; then
  echo "下载 build-tools..."
  python3 -c "import urllib.request;urllib.request.urlretrieve('https://dl.google.com/android/repository/build-tools_r34-linux.zip','build-tools.zip')"
  unzip -o -q build-tools.zip -d "$SDK/sdk"
fi
echo "SDK 就绪: $SDK"
