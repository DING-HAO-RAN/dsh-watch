[中文](README.md) | [English](README.en.md)

# 手表 DSH

在智能手表上查看和操作电脑上的 [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) 会话。

电脑上跑着 DSH，手表连上它，就能随时掏出手腕看 AI 干到哪一步了：

- 按**项目**浏览全部会话，点进去看对话正文
- 展开**工具调用**，看每一步读了什么文件、执行了什么命令、返回了什么结果
- **发消息**、停止生成、往前翻历史
- 回答 AI 的提问（单选、多选、打字都支持）
- 切换**模型**与推理强度，查看 **token 用量**与上下文占用

不需要公网服务器，不需要改动 DSH 的任何配置，也不依赖任何 DSH 插件。

> 开发环境为 OPPO Watch（Android 11，466×466 圆屏）。应用最低支持 Android 8.0，其它 Wear OS / Android 手表理论可用，但界面是按圆屏设计的。

## 工作原理

```
┌─────────────┐        HTTP/JSON         ┌──────────────┐       DSH 协议        ┌─────────┐
│  手表端应用  │ ───────────────────────▶ │   中继服务    │ ───────────────────▶ │   DSH   │
│ (Android)   │ ◀─────────────────────── │ (电脑上运行)  │ ◀─────────────────── │ 桌面版  │
└─────────────┘   只有 10 个简单接口     └──────────────┘   签名 cookie + 裁剪  └─────────┘
                          ▲                      │
                          └── UDP 广播自动发现 ───┘
```

**为什么不能直连 DSH？** 两个硬障碍：

1. DSH 的 `/api` 只接受它自己签发的、绑定到自身地址的签名 cookie，手表无法自行鉴权；
2. 一次会话的原始响应有几百 KB（含大量流式分片与工具 diff），手表既收不下也没必要收。

中继把这两件事都吸收掉：它从本机 DSH 的凭据里推导出一张合法票据完成鉴权，再把数据裁剪成手表真正要用的部分，对外只暴露一组简单的 REST 接口。**它只读凭据，不改配置、不装插件。**

## 下载与安装

### 1. 电脑端（二选一）

**方式 A：桌面程序（推荐）**

到 [Releases](https://github.com/DING-HAO-RAN/dsh-watch/releases/latest) 下载 `DSH-Relay.exe`，双击即可。也可以直接点：

```
https://github.com/DING-HAO-RAN/dsh-watch/releases/latest/download/DSH-Relay.exe
```

这是主推形态：一个文件，拷到任何 Windows 10/11 上都能跑，**目标电脑不需要装 .NET、Node.js 或任何其它东西**。程序会自动找到本机正在运行的 DSH，并在窗口里显示配对码和地址。

**方式 B：命令行中继（需装 Node.js）**

不需要界面、或要在服务器上跑时，直接用源码运行：

```powershell
node relay/src/server.mjs            # 只开局域网
node relay/src/server.mjs --tunnel   # 同时开一条公网地址
```

常用参数：`--port 19390`（手表接入端口）、`--dsh-port 19387`（本机 DSH 端口）、`--state-file <文件>`（配对令牌存档位置）。

也可以用 `scripts/pack-relay.ps1` 把它打包成单个 exe（内含 Node 运行时），见[从源码构建](#命令行中继)。

两种方式都要求电脑上**已安装并正在运行 DSH 桌面版**（中继需要读取它的登录凭据）。

> **公网访问是可选的附加功能**，需要 `cloudflared`。它不随 Releases 提供（体积约 50 MB），把 `cloudflared.exe` 放到程序（或中继）所在目录即可启用；缺了它只影响公网访问，局域网照常可用。

### 2. 手表端

到 [Releases](https://github.com/DING-HAO-RAN/dsh-watch/releases/latest) 下载 `DSH-Watch.apk`（直接下载地址：`https://github.com/DING-HAO-RAN/dsh-watch/releases/latest/download/DSH-Watch.apk`），然后：

1. 手表开启「开发者选项」与「USB 调试」（通常在「关于手表」里连点版本号）；
2. 用数据线连接电脑，在手表上允许调试授权；
3. 确认设备已就绪：

```powershell
adb devices    # 状态应为 device，而不是 unauthorized
```

4. 安装：

```powershell
adb install -r "DSH-Watch.apk"
```

也可以直接用启动参数预填地址与配对码，省去在手表上输入：

```powershell
adb shell am start -n cn.dsh.watch/.MainActivity `
  --es host 192.168.1.100 --ei port 19390 --es code 123456 --ez autopair true
```

`autopair` 会在填好之后自动发起配对，连点一次都不用在手表上做。

### 3. 配对

有两种方式，推荐第一种。

**一键配对（省事）**：手表上「设置」→ 点「一键配对」→ 手表自己找到同一 WiFi 下的电脑 → 电脑上弹出确认框，点「是」→ 地址与令牌自动导入。

**手动填**：电脑端窗口里会显示 **6 位配对码**和**若干条地址**，在手表「设置」里：

- **地址**：填窗口里显示的地址（同一个 WiFi 填「局域网」那条；也可以点「自动查找局域网」自动填入）
- **配对码**：填窗口里的 6 位数字
- 点「配对并连接」

配对成功后手表会记住，之后打开直接就是会话列表。**配对码与令牌都会保存在电脑上**，中继重启、甚至升级程序都不会变，不用重配。需要换码时点窗口里的「换一个配对码」。

### 4. 多台设备之间切换

手表会记住每一台连过的设备（显示电脑名与地址）。在「设置」→「已记住的设备」里点一条就能直接切过去，不用重新配对。出门用公网、回家用局域网、换另一台电脑，都在这个列表里。

## 日常使用

**手势**：右滑返回上一级，左滑打开操作菜单。

| 想做什么 | 怎么做 |
|---|---|
| 看有哪些项目 | 打开应用即是项目列表；点项目看它下面的会话 |
| 看对话 | 点会话卡片；进入时自动定位到最新内容 |
| 看 AI 的思考 | 每条回复上方有「思考 ▸」，点开就是他得出这句话之前的推理 |
| 看每一步操作 | 两段回复之间的工具调用会收成一组（显示「N 项」）；点一下列出清单，再点某一条看它的参数与结果 |
| 只想要纯对话 | 左滑 →「只看对话」 |
| 发消息 | 会话页点「发消息」→ 输入 →「发送」 |
| 停止生成 | 会话页点「停止」 |
| 回答问题 | 有待答提问时会显示「AI 在等你回答」，点进去选选项 |
| 换模型 | 左滑 →「模型」：先显示当前模型，再逐级选供应商 → 模型 → 推理强度 |
| 看用量 | 左滑 →「用量」：token 消耗、上下文占用、耗时统计 |
| 往前翻历史 | 左滑 →「看更早的内容」 |
| 换电脑 / 切设备 | 左滑 →「设置」：已记住的设备点一条就切过去 |
| 连一台新电脑 | 设置页点「一键配对」，电脑上点「是」即可 |

## 从源码构建

### 手表应用

需要 **JDK 17+**、**Android SDK platform-35** 与 **build-tools**。

```powershell
$env:JAVA_HOME            = "C:\path\to\jdk"        # JDK 17 或更高
$env:ANDROID_HOME         = "C:\path\to\android-sdk"
$env:WATCH_BUILD_ROOT     = "D:\build\watch-dsh"    # 必须在项目目录之外
pwsh -File scripts/build.ps1 -WorkRoot .
```

产出 `dist/DSH手表.apk`。构建过程是直接用 SDK 工具链（aapt2 → javac → d8 → zipalign → apksigner），不依赖 Gradle。

签名密钥首次构建时自动生成在 `$env:WATCH_BUILD_ROOT/signing/dsh-watch-dev.jks`（默认口令 `android-dev`）。**请保留同一个密钥**以便覆盖安装；正式分发请换成自己的密钥。

`WATCH_BUILD_ROOT` 不在项目目录内是硬性要求，脚本会拒绝在工作树里构建——避免把临时产物和私钥混进版本库。

### 桌面程序

只需要 **Windows 自带的 .NET Framework 4.8 编译器**，不必安装 .NET SDK：

```powershell
pwsh -File scripts/build-app.ps1 -WorkRoot .
```

产出 `dist/DSH中继/`。首次构建会从 NuGet 取一次 WebView2 的托管程序集并缓存，之后离线可用。

### 命令行中继

需要 **Node.js 22+**：

```powershell
pwsh -File scripts/pack-relay.ps1 -WorkRoot .
```

产出 `dist/中继命令行版/`。它用 Node 的 SEA（Single Executable Application）把中继和 Node 运行时打进单个 exe，拷到没装 Node 的机器上也能跑。首次打包会联网获取一次 `postject` 并缓存。

> 上述脚本用 PowerShell 7（`pwsh`）调用；Windows 自带的 Windows PowerShell 5.1 也能运行。

## 测试

中继提供了几个自检脚本，走的是手表实际调用的同一组接口，因此可以在电脑上替代大部分真机点击验证：

```powershell
node tests/check-protocol.mjs                 # 逐个核对 DSH 端点的参数形态（只读）
node tests/check-features.mjs                 # 项目分组、步骤详情、模型目录、用量、翻页（只读）
node tests/e2e.mjs --port 19390 --code 123456 # 端到端：配对、列表、读消息、发消息
node tests/answer-once.mjs --pick 米饭         # 回答当前待答问题，并确认它从待答列表消失
node tests/send.mjs --session <id> --text "内容"  # 从中继侧发一条消息
```

`e2e.mjs` 会真的往会话里发一条带时间戳的消息并确认它进入 DSH（发送用例会单独建一个空白会话，不污染现有对话）。

以下几个用于排查问题：

```powershell
node tests/watch-events.mjs --seconds 15      # 观察 DSH 事件流（提问没冒出来时用）
node tests/probe-shapes.mjs                   # 查看 DSH 实际返回的字段形状
```

涉及界面行为的改动，建议再用 `adb` 在真机上从实际入口走一遍（启动 → 点项目 → 点会话 → 发消息 / 答题）。

## 实现要点

- **鉴权**：DSH 的 `/api` 只认它自己签发的 `dsh-auth-<hash>` cookie。中继从 `~/.dsh/.credentials.yaml` 读取签名密钥，按同一算法为 `127.0.0.1:<port>` 签一张票，因此无需改动 DSH 即可合法调用。有效期不得超过 DSH 的 `cookieMaxAgeDays`（默认 30 天）。
- **自动找到 DSH**：桌面程序按「运行中的 DSH 进程位置 → 用户主目录 → 常见安装位置 → 其它盘符」的顺序查找 `.dsh` 配置目录，并探测几个常见端口，两样都对上才算可用。找不到时会明确说明原因（没装 / 没启动 / 读不出凭据）。
- **提问有两条通道**：仍在等待（`open`）的问题只经 WebSocket `$events` 流下发，且**不带 callId**，必须用 `$events/result` 按 `eventId` 回填；已超时挂起（`continued`）的问题才走 `userQuestions/answer` RPC。中继按情况自动选路。
- **补齐遗漏的提问**：DSH 会在新客户端接入事件流时重放所有待处理事件，中继据此在「有会话运行但看不到提问」时重连一次事件流来补齐。
- **数据裁剪**：中继只保留真人消息、助手正文与工具步骤，把长文本截断后再给手表。会话事件里混有大量系统注入的 `user/message`（时间上下文、记忆快照、指令），只有 `source.kind` 为 `user` / `user-question-reply` 的才是真人消息。
- **局域网发现**：中继每 2 秒向 UDP 19391 端口广播一条 JSON（`{service, port, name}`），手表收到即可自动填地址；广播不可靠时仍可手动输入。选择 UDP 广播而不是 mDNS，是为了让手表端不引入任何依赖。
- **单文件分发**：桌面程序把 WebView2 的托管程序集与原生加载器都嵌进 exe——托管程序集在启动时**从内存装入**，原生加载器释放到程序目录。这样分发只需一个文件，也不会往用户配置目录里堆东西。
- **圆屏适配**：按圆的内接矩形统一留边（左右 15%、上下 18%），四角到圆心约 0.474 < 半径 0.5，整个滚动区都在圆内，因此不需要为每个页面单独算边距。
- **界面永远可用**：桌面程序有两层界面——内嵌浏览器的网页控制台（好看、信息密度高）和纯 WinForms 的原生界面（零依赖）。前者依赖 WebView2 运行时，精简版 Windows 上可能没有，此时自动降级到后者，配对码与地址照常显示。
- **高分屏不模糊**：程序带 DPI 感知清单（PerMonitor V2），在 125%/150%/200% 缩放的屏幕上按真实分辨率渲染，文字与边框不发虚；窗口初始尺寸按当前显示器工作区的比例算出，小屏不超出、大屏不空旷。

## 项目结构

```
.
├── app/                       手表端应用（Android，原生 Java）
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/cn/dsh/watch/
│       │   ├── MainActivity.java    全部页面与交互逻辑
│       │   ├── WatchUi.java         圆屏控件与安全区
│       │   ├── Markdown.java        正文与工具结果的富文本渲染
│       │   ├── Api.java             HTTP 客户端
│       │   ├── Store.java           地址与令牌的存取
│       │   └── Discovery.java       UDP 自动发现
│       └── res/
├── desktop/                   电脑端桌面程序（C#，单文件 exe）
│   ├── src/
│   │   ├── MainForm.cs         窗口、内嵌浏览器与开窗尺寸
│   │   ├── NativePanel.cs      零依赖原生界面（降级目标）
│   │   ├── panel.html          网页控制台
│   │   ├── app.manifest        DPI 感知清单（高分屏不模糊）
│   │   ├── RelayServer.cs      中继服务
│   │   ├── DshClient.cs        DSH 鉴权与 RPC
│   │   ├── DshLocator.cs       查找本机 DSH 与凭据
│   │   ├── Steps.cs            会话数据裁剪
│   │   ├── PublicTunnel.cs     cloudflared 公网中转
│   │   ├── Bootstrap.cs        内嵌依赖的装载
│   │   └── StartupLog.cs       启动日志
│   └── 使用说明.txt            随分发包给使用者的说明
├── relay/                     命令行中继（Node，SEA 打包）
│   ├── src/
│   │   ├── server.mjs          入口与参数解析
│   │   ├── relay.mjs           面向手表的 REST 接口 + UDP 广播
│   │   ├── dsh.mjs             DSH 协议与鉴权
│   │   ├── state.mjs           DSH 数据的裁剪与归一化
│   │   ├── tunnel.mjs          公网中转
│   │   └── paths.mjs           取自身目录（打包兼容）
│   ├── public/index.html       控制面板
│   └── start-relay.cmd         双击启动
├── scripts/                   构建脚本
│   ├── build.ps1               构建手表 APK
│   ├── build-app.ps1           构建桌面程序
│   ├── pack-relay.ps1          打包命令行中继
│   └── bundle-relay.mjs        把中继源码合成单文件
└── tests/                     自检脚本
```

## 已知边界

- 中继以**明文 HTTP** 提供局域网服务（公网走 HTTPS），并靠 6 位配对码保护。它只应在你信任的网络里运行，用完关掉即可。
- 手表上一次最多显示 40 条消息 / 60 个会话；更早的历史要点「看更早的内容」逐批加载。
- 中继需要电脑上的 DSH 桌面版处于运行状态；**关掉中继窗口，手表就断开了**。
- 公网地址是 cloudflared 的临时隧道，每次启动都会变；需要固定地址得自备域名或反向代理。
- **Cloudflare 对免注册隧道有频率限制**，短时间内反复开关会提示限流。等几分钟再试即可，局域网功能不受影响。
- 手表走 HTTPS 时放行全部证书——手表上没有便捷的证书安装入口，访问控制由配对令牌承担。
- 桌面程序要求 Windows 10 1903 以上或 Windows 11；更早的系统自带 .NET Framework 低于 4.8，需要自行安装。

## 常见问题

**提示「没有找到 DSH」？**
先启动 DSH 桌面版，再重新打开中继。中继需要读取它的凭据，DSH 没运行就读不到。

**手表一直转圈连不上？**
确认手表和电脑在同一个 WiFi；地址填「局域网」那条（形如 `http://192.168.1.x:19390`），不要填 `127.0.0.1`——那指向手表自己。也检查电脑的防火墙是否拦了 19390 端口。

**配对码会变吗？**
不会。配对码与令牌都保存在电脑上，中继重启、升级程序都不会变，手表不用重配。只有你主动点「换一个配对码」才会换。

**一键配对找不到电脑？**
确认手表与电脑在同一个 WiFi。Windows 防火墙可能拦了 UDP 19391，允许它通过即可；也可以改用手动填地址与配对码，效果一样。

**中继会改动我的 DSH 配置吗？**
不会。它只读取 DSH 的登录凭据用于本机通信，不修改任何配置文件，也不安装任何插件。

**关掉窗口后手表还能连吗？**
不能。中继就运行在这个窗口里，关掉即停止。

**桌面程序打不开或闪退？**
程序目录下的 `startup.log` 记录了启动全过程（DSH 定位、端口、依赖释放、异常堆栈），这是排查的第一手线索。

## 许可

[MIT](LICENSE)。可以自由使用、修改、分发，包括商用；保留版权声明即可。
