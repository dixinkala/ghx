# GitHub 加速器（ghx）

给国内网络的 GitHub 加速工具，跑在这台手机的 Ubuntu（proot）里。
解决三件事：**下载慢 / 克隆超时 / 网页打不开**，不装 VPN、不改手机系统。

```
git clone 直连 60 秒没动静  →  走镜像 5 秒完成
release 下载 174 KiB/s      →  镜像分片 337 KiB/s（再多节点可到 700+）
github.com 页面 49 KiB/s    →  优选 IP 后 188 KiB/s（实测 6~9 倍）
```

## 安卓 App（原生，免 root）

已经做成可以直接安装的 APK：`ghx.apk`（37 KB，包名 `com.ghx.accel`）。
不依赖这个 Ubuntu 环境，装上就能下载、只读浏览、优选 IP，并用本地 DNS 加速浏览器。
详见 [README-Android.md](README-Android.md)。

## 快速开始

```bash
cd "/root/workspace/GitHub Accelerator"
./install.sh          # 装到 /usr/local/bin，之后直接敲 ghx

ghx doctor            # 体检：直连速度、阻断情况、镜像排行、hosts 状态
ghx ip --apply        # 优选 IP 写进 hosts（改前自动备份）
ghx git clone jqlang/jq    # 走镜像克隆，自动配好 origin/github 两个远端
ghx serve             # 启动本机网页门户 → 手机浏览器打开 127.0.0.1:8788
```

## 命令一览

| 命令 | 作用 |
|---|---|
| `ghx doctor` | 体检：直连测试、SNI 阻断检测、镜像测速、hosts/git/门户状态 |
| `ghx ip [--apply]` | 给 github.com / api / codeload 优选最快真实 IP；不加参数只预览 |
| `ghx hosts [show\|restore]` | 查看或移除 hosts 加速块 |
| `ghx dl <链接> [-o 文件] [-j 分片数]` | 多镜像并行下载（release / raw / 仓库压缩包都行） |
| `ghx git clone <owner/repo>` | 镜像克隆，origin 指镜像（拉取快），另加 github 远端（推送用） |
| `ghx git apply` / `undo` | 全局让 git 走镜像（clone/fetch 加速，push 会失败）；`undo` 还原 |
| `ghx git tune` | 大包缓冲、低速超时、HTTP/1.1 等传输参数 |
| `ghx mirror ls\|rank\|add\|rm` | 镜像节点管理，`rank` 强制重新测速 |
| `ghx read <链接>` | 终端里只读查看仓库 README / 源码 |
| `ghx serve [--lan]` | 本机网页门户（默认只监听 127.0.0.1） |

## 它怎么加速

**1. hosts 优选 IP（治网页和 git 慢）**
国内 DNS 经常把 github 域名解析到很远的节点。程序用国内 DoH 拿到真实 IP，
再对每个 IP 做真实 TLS 握手 + 下载测速（SNI 用域名，所以污染地址会因证书失败被淘汰），
挑最快的写进 `/etc/hosts`。改前备份到 `/etc/hosts.ghx.bak`，`ghx hosts restore` 可还原。

**2. 公开反代镜像（治下载和克隆慢/断）**
内置 gh-proxy.com、ghfast.top 等一批反代，每次按实测速度排序；
下载时分 4~8 片并行拉，某片失败自动换节点重试，文件大小校验后再落盘。
镜像会失效，所以不写死一个地址，`ghx mirror rank` 随时重测。

**3. 本机门户（治手机浏览器进不去）**
`ghx serve` 在 127.0.0.1:8788 起一个页面（proot 和 Android 共用 loopback，手机浏览器能直接开）：
- 粘链接 → 生成本机加速下载按钮（服务端走镜像，支持断点续传）
- 「只读浏览」：服务端抓 README / 源码渲染成纯文字页面，几十 KB，比打开 GitHub 快得多
- 状态面板：一键优选 IP、重新测速镜像

## 已知限制（重要）

- **hosts 只影响这个 Ubuntu 环境**（git / curl / pip / node），不影响手机系统浏览器。
  手机浏览器要真正打开 github.com 网页，需要 root 改系统 hosts 或用系统级代理，本工具做不到。
  浏览器里能用的是门户的下载和只读浏览。
- **SNI 阻断的域名**（raw.githubusercontent.com、gist.github.com 在部分线路）换任何 IP 都没用，
  hosts 救不了，只能走镜像。
- **镜像都是第三方服务**：只用来下载公开内容，别把私有仓库、token、私有 release 放上去。
  重要文件建议比对官方 sha256；`ghx dl` 会校验大小，但不校验哈希。
- **`ghx git apply` 会让 push 失败**（镜像只读），推送前 `ghx git undo`，或直接用
  `git push github 分支`（`ghx git clone` 配好的官方远端）。
- 本 App 启动时可能重写 `/etc/hosts`，加速块会消失，重跑 `ghx ip --apply` 即可。
- 公开镜像随时可能挂；`ghx doctor` 里显示「不可用」是正常的，速度排行会自动跳过。

## 目录结构

```
ghx                 启动器（bash，定位 ghxkit 包）
install.sh          链接到 /usr/local/bin/ghx
ghxkit/net.py       底层网络：TLS 探测、重定向 HTTP、DoH
ghxkit/mirrors.py   镜像候选、实测排序（6 小时缓存）
ghxkit/ipopt.py     IP 优选、hosts 读写与校验
ghxkit/dl.py        多镜像并行下载、断点续传
ghxkit/gitcfg.py    git 克隆加速与全局配置
ghxkit/readpage.py  只读网页视图（Markdown 渲染）
ghxkit/portal.py    本机网页门户
ghxkit/cli.py       命令行入口
```

## 常见问题

**门户打不开？** 先用 `ghx serve` 确认在跑；仍不行就 `ghx serve --lan`，用手机浏览器访问
`http://<手机局域网IP>:8788`（需同一 Wi-Fi）。

**下载到一半失败？** 重跑同样的命令会续传；镜像全挂时 `ghx mirror add https://你的反代/` 加一个。

**克隆后 push 报错？** origin 指向镜像，只读。用 `git push github main`，
或 `ghx git undo` 后改回官方地址。

**想彻底卸载？** `ghx hosts restore`，删掉 `/usr/local/bin/ghx` 软链和本项目目录即可。
