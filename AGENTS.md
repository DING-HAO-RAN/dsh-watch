# 项目约束

## 构成

- 两个部件：[电脑端中继](<relay/>)（Node，无第三方依赖）与[手表端应用](<app/>)（原生 Java，无第三方运行库）。中继负责 DSH 鉴权与数据裁剪，手表只调用中继自己的简单接口。
- 中继**不修改 DSH 配置、不依赖任何 DSH 插件**，这是需求方明确要求的前提，改动方案时不得破坏。

## DSH 协议（实测结论，改动前先复核）

- 鉴权 cookie：名字 `dsh-auth-<b64url(sha256(authority))>`，值 `v1.<b64url(payload)>.<b64url(hmac-sha256(secret,body))>`；密钥在 `~/.dsh/.credentials.yaml` 的 `client-connection/browser-session.secret`。**有效期不得超过 DSH 的 cookieMaxAgeDays（默认 30 天）**，否则 401。
- 一元 RPC：`POST /api/<namespace>/<method>`（斜杠分隔），信封 `{type:"client-request",rpcId,method,payload:{args}}`，应答 `{type:"server-response",rpcId,result:{ok,value|error}}`。
- 参数名不统一，新增端点必须先核对 descriptor：`session/list` 用 `_request`；`session/page|prompt|create|cancel|projections` 用 `request`；`userQuestions/answer` 的 `agentId` 在 args 顶层。`tests/check-protocol.mjs` 覆盖了这些。
- 提问有两条通道：`open` 状态只经 WebSocket `$events` 流下发且**不带 callId**，必须用 `$events/result` 按 `clientId+eventId` 回填；`continued` 状态才用 `userQuestions/answer`。中继按情况自动选路，不要把两条合并成一条。
- DSH 在新客户端接入事件流时会重放所有待处理事件；中继据此在「有会话运行但看不到提问」时重连补齐。
- 会话事件里混有大量系统注入的 `user/message`（时间上下文、记忆快照、指令），只保留 `source.kind` 为 `user` / `user-question-reply` 的才是真人消息。

## 构建

- 环境变量用 `JAVA_HOME` / `ANDROID_HOME` / `WATCH_BUILD_ROOT` 指定，**不要把本机路径写进仓库**。
- 构建走 [scripts/build.ps1](<scripts/build.ps1>)，直接调用 SDK 工具链。三个必须保留的写法（去掉就会踩坑）：
  1. 外部命令输出重定向到文件后判退出码——PowerShell 5.1 在 `ErrorActionPreference='Stop'` 下会把 javac 的过时警告当致命错误中断构建，从而**掩盖真正的编译错误**；
  2. 源码与 class 清单写文件后用 `@file` 传参——数组经 shell 展开会被拆坏，导致 d8 静默漏掉大部分类，手表上表现为 `NoClassDefFoundError`；
  3. 用 JDK 的 `jar` 工具把 dex 追加进包——受限语言模式下不可用 .NET 的 ZipFile。
- `build.ps1` 会逐个核对 `MainActivity` / `WatchUi` / `WatchUi$Page` / `Store` / `Api` / `Discovery` 六个 class 是否存在，这是拦截「类漏编」的最小门禁。

## 界面

- 圆屏安全边距由 `WatchUi.page()` 统一处理（左右 13.5%、上下 16%），页面不要自己再加边距。
- 可点区域不低于 48dp；**操作入口尽量收进左滑菜单**，页面上只留最常用的两个按钮，把高度让给内容。
- 标题必须是单行（`WatchUi.headingView` 已设 `setSingleLine`）并预先截短。圆屏可视高度只有三百多像素，标题一旦折成两三行，按钮和内容区就被挤出屏幕、整页也滚不动。
- **绝不能让「渲染 → 发请求 → 回调再渲染」形成环**。列表页曾经如此：`showSessionList()` 里发请求，回调又调 `showSessionList()`，屏幕每轮都重建、持续上下抽动。现在的分工是：`showSessionList()` 只在进入页面时调用一次，回调一律走 `renderSessionListPage()`（只重绘、不发请求）。
- 界面用「重建整屏」的方式刷新，因此**滚动位置由 `render()` 统一保留**，换页时才调 `resetScroll()` 回顶部。会话页（4 秒）与会话列表（10 秒）都会定时刷新，不做这件事用户就翻不动列表和长对话。
- 刷新前先比对**内容指纹**，没变化就直接返回。定时刷新还额外受两道闸约束：用户刚触摸过（2.5 秒静默期）、或正在编辑时跳过。
- **需要用户停留的页面**（发消息、打字回答、选项答题）必须先置 `editing = true` 暂停自动刷新，否则刚打开的界面会被立刻换掉。
- 服务端 JSON 的文案字段可能显式为 `null`；`org.json` 的 `optString(key, default)` 遇 null 会返回 null，必须经 `Api.text(...)` 兜底，否则界面会崩。

## 手势与操作菜单

- 横向手势在 `MainActivity.dispatchTouchEvent` 里判定，**不能**挂在页面根容器上——子层 `ScrollView` 会先消费触摸，左滑会被当成纵向滚动。
- 判定门槛：横向位移 ≥60dp 且大于纵向的 1.5 倍；纵向超过 24dp 且比横向大就当作滚动放行。手表上上下翻页远比横滑频繁，误判代价很高。
- 约定：**右滑返回上一级，左滑打开操作菜单**。菜单从左侧滑入（180ms 位移 + 淡入），遮罩同步淡入；关闭用 150ms 反向动画，动画结束才移除视图并允许下一次打开。
- 正文与工具结果都经 [Markdown](<app/src/main/java/cn/dsh/watch/Markdown.java>) 渲染：标题放大加粗、列表带圆点、行内代码等宽加底色、引用变灰、表格压成一行、代码块整体等宽。工具读出的 .md 文本因此能直接可读。
- 工具调用默认折叠成一行（工具名 + 状态 + 参数摘要）；连续多个合并成一组，点卡片整组展开，展开/收起用 110–140ms 的 alpha 动画。

## 测试

- `tests/check-protocol.mjs` 只读自检端点参数形态；`tests/e2e.mjs` 会真的发一条带时间戳的消息进某个会话。
- 涉及界面行为的改动，用 `adb` 在真机上从实际入口走一遍（启动 → 点会话 → 发消息 / 答题）。
- 手表上验证输入框内容要看截图：`uiautomator dump` 对第三方 `EditText` 常读到空值。
