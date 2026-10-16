# 手表 DSH

在 OPPO Watch（Android 11，466×466 圆屏）上打开一个窗口，连接局域网或公网上的电脑，直接看电脑上 DSH 的对话、发消息、回答问题。

## 它由两部分组成

| 部分 | 位置 | 作用 |
|---|---|---|
| 电脑端中继 | [relay/](relay/) | 连本机 DSH，做鉴权、裁剪数据、保护入口，给手表提供简单接口 |
| 手表端应用 | [app/](<app/>) | 圆屏界面：项目分组、看对话与工具步骤、发消息、回答问题、切换模型、看用量 |

之所以需要一个中继，是因为 DSH 的接口只接受绑定到它自身地址的签名 cookie，而且一次会话消息就有几百 KB；手表既无法直接鉴权，也没必要收下这些数据。

中继**不修改 DSH 的任何配置**，也不依赖任何 DSH 插件。

## 使用

### 1. 电脑上启动中继

三种方式任选：

| 方式 | 适合 |
|---|---|
| 双击 [启动中继.cmd](<relay/start-relay.cmd>) | 本机开发，需要装了 Node.js 22 以上 |
| 双击分发目录里的 `DSH中继.exe` | **拷到别的电脑用，目标机不需要装任何东西** |
| `node relay/src/server.mjs` | 命令行，可加参数 |

启动后会弹出一个控制面板（浏览器打开 `http://127.0.0.1:19390/`），显示局域网地址、配对码与连接状态。

需要公网地址时加 `--tunnel`：

```powershell
node relay/src/server.mjs --tunnel
```

它会拉起 cloudflared 生成一条临时公网地址（形如 `https://xxx.trycloudflare.com`），不在同一局域网也能连。退出即失效。

### 2. 手表上配对

安装 [DSH手表.apk](<dist/DSH手表.apk>)（安装方式见下），打开应用 → 左滑打开菜单 →「设置」：

1. 地址填控制面板上显示的**局域网地址**或**公网地址**（也可点「自动查找局域网」自动填）；
2. 填配对码；
3. 点「配对并连接」。

配对成功后手表会记住，之后打开直接就是会话列表；中继重启也不会掉。

### 3. 日常使用

**手势**：右滑返回上一级，左滑打开操作菜单。

| 想做 | 操作 |
|---|---|
| 看有哪些项目 | 打开应用即是项目列表；点项目看它的会话 |
| 看对话 | 点会话卡片；进入时自动定位到最新内容 |
| 看每一步操作 | 工具调用默认折叠成一行（工具名 + 状态 + 参数），点一下展开看结果 |
| 只想要纯对话 | 菜单 →「只看对话」 |
| 发消息 | 会话页点「发消息」→ 输入 → 「发送」 |
| 回答问题 | 有提问时会显示「AI 在等你回答」，点进去选选项（支持多选与打字） |
| 换模型 | 菜单 →「模型」→ 选供应商 → 选模型 → 选推理强度 |
| 看用量 | 菜单 →「用量」：token 消耗、上下文占用、耗时统计 |
| 停止生成 | 会话页点「停止」 |
| 往前翻历史 | 菜单 →「看更早的内容」 |
| 换电脑/改地址 | 菜单 →「设置」 |

## 分发到别的电脑

`scripts/pack-relay.ps1` 会把中继打包成一个**独立 exe**（内含 Node 运行时，目标机无需安装 Node.js）：

```powershell
pwsh -File scripts/pack-relay.ps1 -WorkRoot <本项目目录>
```

产出 `dist/DSH中继/` 目录，整个拷到目标电脑即可：

| 文件 | 说明 |
|---|---|
| `DSH中继.exe` | 中继本体（约 88 MB） |
| `bin/cloudflared.exe` | 公网中转用（可选，随包附带） |
| `public/index.html` | 控制面板页面 |
| `start-relay.cmd` | 双击启动并打开面板 |

目标电脑需要已装 DSH 桌面版并处于运行状态（中继要读它的登录凭据）。

## 安装到手表

安装包是 [DSH手表.apk](<dist/DSH手表.apk>)，应用名「DSH 手表」，包名 `cn.dsh.watch`。

1. 手表开启开发者选项与 USB 调试（通常在「关于手表」里连点版本号）；
2. 用数据线连接电脑，在手表上允许调试授权；
3. 执行 `adb devices`，确认手表是 `device` 而不是 `unauthorized`；
4. 安装：

```powershell
adb install -r "dist/DSH手表.apk"
```

也可以用启动参数预填设置页，省去在手表上输入：

```powershell
adb shell am start -n cn.dsh.watch/.MainActivity --es host 192.168.1.121 --ei port 19390 --es code 882688
```

## 从源码构建

需要 JDK 17 以上、Android SDK platform-35 与 build-tools 35.x。构建产物与密钥放在工作树之外。

```powershell
$env:JAVA_HOME = "D:/工具/jdk"
$env:ANDROID_HOME = "D:/工具/android-sdk"
$env:WATCH_BUILD_ROOT = "D:/构建缓存/watch-dsh"
./scripts/build.ps1 -WorkRoot <本项目目录>
```

密钥保存在 `WATCH_BUILD_ROOT/signing/dsh-watch-dev.jks`，默认口令 `android-dev`。请保留同一密钥以便覆盖安装；正式使用请换成自己的密钥。

## 测试

```powershell
node tests/check-protocol.mjs               # 逐个端点的参数形态（只读）
node tests/check-features.mjs               # 项目分组、步骤详情、模型目录、用量（只读）
node tests/e2e.mjs --token <令牌>            # 端到端：列表、读消息、发消息
node tests/answer-once.mjs                  # 回答当前待答问题
node tests/watch-events.mjs --seconds 15    # 观察事件流（排查提问没有出现时用）
node tests/probe-shapes.mjs                 # 侦察 DSH 实际返回的字段形状
```

`tests/e2e.mjs` 会真的往会话里发一条消息（内容是带时间戳的标记），再确认它进入 DSH。

## 实现要点

- **鉴权**：DSH 的 `/api` 只认它自己签发的 `dsh-auth-<hash>` cookie。中继从 `~/.dsh/.credentials.yaml` 读取签名密钥，按同一算法为 `127.0.0.1:<port>` 签一张票，因此无需改动 DSH 即可合法调用。有效期不能超过 DSH 的 `cookieMaxAgeDays`（默认 30 天）。
- **提问的两条通道**：仍在等待（open）的问题只经 WebSocket `$events` 流下发，且**不带 callId**，必须用 `$events/result` 按 `eventId` 回填；已超时挂起（continued）的问题才用 `userQuestions/answer` RPC。中继按情况自动选路。
- **补齐遗漏的提问**：DSH 会在新客户端接入事件流时重放所有待处理事件，所以中继发现「有会话在运行但看不到任何提问」时会重连一次事件流来补齐。
- **数据裁剪**：DSH 的会话事件里包含大量流式分片与工具 diff，中继只保留真人消息、助手正文与工具步骤（工具名、参数摘要、改动文件、结果），并把长文本截断后再给手表。
- **圆屏适配**：基础安全区按圆的内接矩形留边；此外每个内容块还会按自己的纵向位置再次收紧左右边距，保证四角不被圆弧切掉。
- **局域网发现**：中继每秒发一次 UDP 广播（端口 19391），手表收到即可自动填地址；广播不可靠时仍可手动输入。

## 已知边界

- 中继以明文 HTTP 提供局域网服务（公网走 HTTPS），并靠 6 位配对码保护。它只应在你信任的网络里运行。
- 手表上一次最多显示 40 条消息 / 60 个会话，更早的历史要点「看更早的内容」逐批加载。
- 中继需要电脑上的 DSH 桌面版处于运行状态。
- 公网地址是 cloudflared 的临时隧道，每次启动都会变；需要固定地址得自备域名或反向代理。
