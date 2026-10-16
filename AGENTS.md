# 项目约束

## 构成

- 三个部件：[桌面程序](<desktop/>)（C#，装在电脑上，日常用这个）、[手表端应用](<app/>)（原生 Java）、[命令行中继](<relay/>)（Node，无界面，供脚本或服务器环境使用）。中继负责 DSH 鉴权与数据裁剪，手表只调用中继自己的简单接口。
- **桌面程序是主推形态**：一个 exe 拷到任何 Windows 10/11 上双击即用，自动找到本机 DSH 并显示配对码与地址。
- 中继**不修改 DSH 配置、不依赖任何 DSH 插件**，这是需求方明确要求的前提，改动方案时不得破坏。
- 地址统一存在 `Store.base` 字段（`host:port` 或 `https://域名`），由 `Store.parse` 解析。**不要再引入第二套地址存取逻辑**。

## 桌面程序（C#）

- 构建：[scripts/build-app.ps1](<scripts/build-app.ps1>) 用**系统自带的 .NET Framework 4.8 编译器**（`%WINDIR%\Microsoft.NET\Framework64\v4.0.30319\csc.exe`）。这是刻意的选择：产出的 exe 在 Win10 1903+/Win11 上开箱即用，**目标机不需要装 .NET SDK、不需要装 Node.js**。
- **该编译器只支持 C# 5**：不能用 `$"..."` 字符串插值、`nameof`、表达式体成员。集合初始化器、`var`、lambda、匿名方法都可以。违规时报 `error CS1644: 功能"..."在 C# 5 中不可用`。
- 界面有两层，**不要把它们合并**：
  1. **WebView2 网页界面**（[panel.html](<desktop/src/panel.html>) + [MainForm.cs](<desktop/src/MainForm.cs>)）：好看、信息密度高，但依赖 WebView2 运行时；
  2. **原生界面**（[NativePanel.cs](<desktop/src/NativePanel.cs>)）：纯 WinForms，零依赖，**永远可用**，同时是内嵌浏览器失败时的自动降级目标。
  这样分层的理由：中继本身不需要浏览器就能工作，精简版 Windows 上可能没有 WebView2；有了第二层，「任何电脑都能打开并显示配对码」才有保证。
- **依赖全部嵌进 exe**，分发只有一个文件。挂载方式（[Bootstrap.cs](<desktop/src/Bootstrap.cs>)）分两条路，两者都必须保留：
  - 托管程序集用 `Assembly.Load(byte[])` **从内存装入**（在 `AssemblyResolve` 里实现）；
  - 原生加载器 `WebView2Loader.dll` **必须落盘**（Win32 的 `LoadLibrary` 只接受路径），优先写程序自身目录，失败再退到临时目录。
- **绝不要把依赖做成"解压到用户配置目录"**：受控环境（企业策略、沙箱）会拒绝对 `%LOCALAPPDATA%` 的写入，表现为 `Directory.CreateDirectory` 抛"访问被拒绝"，随后 WebView2 报"未能加载程序集"。优先写程序目录就是这个原因。
- `Bootstrap.Init()` 必须在**任何 WebView2 类型被 JIT 之前**调用，否则类型解析会先失败。
- 程序目录下的 `startup.log` 记录启动全过程（DSH 定位、端口、依赖释放、异常堆栈）。**GUI 程序闪退时这是唯一的诊断线索**，排查先看它。
- 判断程序是否真的在运行**不要用 `Get-Process -Name 中文名`**（对中文进程名不可靠，会假报"未找到"）。可靠做法：`tasklist /FO CSV` 过滤，或 `Get-CimInstance Win32_Process -Filter "Name='DSH中继.exe'"`。
- 从命令行启动 GUI 程序时 `Start-Process`/`& exe` 会阻塞到程序退出，**这不代表失败**。要立即返回并拿到 PID，用 `Invoke-CimMethod -ClassName Win32_Process -MethodName Create`。

## 中继打包（命令行版，SEA）

- 打包链路：`scripts/pack-relay.ps1` → `scripts/bundle-relay.mjs` → Node SEA → postject 注入。
- `bundle-relay.mjs` 的四条硬规则，改动时不要违反（每条都对应一个真实故障）：
  1. **保留内置模块导入**并提到文件顶部统一声明，否则运行时报 `join is not defined`；
  2. **`import path from 'node:path'` 这类默认导入要整体引入**（`const path = require(...)`），拆成 `default: path` 解构会得到 undefined，随后 `path.join` 报错；
  3. **不要给模块套块级作用域**，否则跨模块使用的函数（如 `moduleDirectory`）不可见；
  4. **CJS 不允许顶层 await**，入口整体包一层 `async main()`。
- `import.meta` 在 CJS 里是**语法错误**（不是运行时未定义），所以取目录的逻辑集中在 [paths.mjs](<relay/src/paths.mjs>)，由打包脚本把 `fileURLToPath(import.meta.url)` 改写成 `__filename`；**不要在别处再写 import.meta**。
- postject 只在打包时需要，本机缓存在 `%LOCALAPPDATA%\dsh-relay-pack`；`npx postject` 在本机 shell 下解析不到包名，必须直接调用它的 `dist/cli.js`。
- 控制面板只在 loopback 上提供（`/` 与 `/local/state`），面板文件随分发包放在 `public/`，**开发时与打包后的相对位置不同**，`PANEL_DIR` 会依次探测两处。

## 公网中转

- 桌面程序与命令行版都支持公网中转，拉起 cloudflared 生成一条临时 `*.trycloudflare.com` 地址。查找顺序：程序目录 → `relay/bin/` → DSH 插件目录 → PATH。
- **Cloudflare 对免注册隧道有频率限制**，短时间内反复开关会返回 HTTP 429。桌面程序为此做了 4 轮递增退避重试，且看到 429 立即收工而不是等满超时——**不要把这个重试去掉**，去掉后用户点一次就会拿到失败弹窗。局域网功能不受影响。
- 手表端走 HTTPS 时放行全部证书（`Api.insecureFactory`）——手表上没有便捷的证书安装入口，访问控制由配对令牌承担。

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

- 圆屏安全区由 [WatchUi](<app/src/main/java/cn/dsh/watch/WatchUi.java>) 的 `page()` 一处负责：按圆的内接矩形留边（左右 15%、上下 18%）。四角到圆心约 0.474 < 半径 0.5（余量约 12px），**整个滚动区都在圆内，可见内容天然安全**，页面不要自己再加边距。
- **绝不要按控件自身的屏幕位置去动态调边距**。曾经这样做过（`applyRoundInsets`）：它形成布局反馈环——算边距 → 控件变窄 → 文字折行 → 位置变化 → 再算出不同边距，最终把同排按钮压成宽窄不一的窄条且无法拉伸。基础安全区已经足够。
- 按钮用 `TextView` + 自绘背景实现（`WatchUi.button`），**不用 `android.widget.Button`**：后者带 9-patch 背景与固有最小宽度，在窄圆屏上会被顶住而撑不满整行。按钮必须单行显示，宽度交给父容器（`LayoutParams(-1, -2)`）。
- 同排两个按钮用 `weighted()`（`weight=1` 平分）；**之后不要再改它们的 layoutParams 或加 margin**，那会破坏 weight 分配。
- 可点区域不低于 48dp；**操作入口尽量收进左滑菜单**，页面上只留最常用的两个按钮，把高度让给内容。
- 标题必须单行（`WatchUi.headingView` 已设 `setSingleLine`）。圆屏可视高度只有三百多像素，标题一旦折成两三行，按钮和内容区就被挤出屏幕、整页也滚不动。截断用 `clipLine`（只加省略号），**不要用 `cut`**（它会插入换行，等于把标题变成两行）。
- **绝不能让「渲染 → 发请求 → 回调再渲染」形成环**。列表页曾经如此：`showSessionList()` 里发请求，回调又调 `showSessionList()`，屏幕每轮都重建、持续上下抽动。现在的分工是：`showSessionList()` 只在进入页面时调用一次，回调一律走 `renderSessionListPage()`（只重绘、不发请求）。
- 界面用「重建整屏」的方式刷新，因此**滚动位置由 `render()` 统一保留**，换页时才调 `resetScroll()` 回顶部。会话页（4 秒）与会话列表（10 秒）都会定时刷新，不做这件事用户就翻不动列表和长对话。
- 刷新前先比对**内容指纹**，没变化就直接返回。定时刷新还额外受两道闸约束：用户刚触摸过（2.5 秒静默期）、或正在编辑时跳过。
- **需要用户停留的页面**（发消息、打字回答、选项答题）必须先置 `editing = true` 暂停自动刷新，否则刚打开的界面会被立刻换掉。
- 服务端 JSON 的文案字段可能显式为 `null`；`org.json` 的 `optString(key, default)` 遇 null 会返回 null，必须经 `Api.text(...)` 兜底，否则界面会崩。

## 手势与操作菜单

- 横向手势在 `MainActivity.dispatchTouchEvent` 里判定，**不能**挂在页面根容器上——子层 `ScrollView` 会先消费触摸，左滑会被当成纵向滚动。
- 判定要用**整个手势期间的最大位移**，不能只看抬手那一帧：注入的滑动常被拆成很多小步，逐帧看会漏判。门槛为横向 ≥44dp 且大于纵向的 1.2 倍。
- 约定：**右滑返回上一级，左滑打开操作菜单**。方向必须一致——左滑是「把右侧的内容拉出来」，因此菜单面板**贴右边缘、从右滑入**（180ms 位移 + 淡入）；面板放左边会让人觉得手势反了。关闭用 150ms 反向动画，动画结束才移除视图并允许下一次打开。
- 正文与工具结果都经 [Markdown](<app/src/main/java/cn/dsh/watch/Markdown.java>) 渲染：标题放大加粗、列表带圆点、行内代码等宽加底色、引用变灰、表格压成一行、代码块整体等宽。工具读出的 .md 文本因此能直接可读。
- 工具调用按**正文分界**分组，不是按「相邻的工具步骤」：中继为每次工具调用插一条空文本助手步骤（只承载工具清单），相邻工具步骤几乎从不相邻，按相邻关系分组会让每次调用各自成组（实测 49 个会话里几乎全是单条组）。折叠分两级——点卡片列出这次连续调用的清单，点清单里某一条才看它的参数与结果；收组时连带清掉内部明细，下次打开是干净的清单。
- **展开状态必须记在 `openGroups` / `openRows` 里**（键为 `groupKey` 拼组内下标）。界面是重建整屏刷新，展开状态若只存在于视图上，会话页每 4 秒一次的刷新就会把用户刚点开的详情缩回去——这正是「点了展开却看不到内容」。还原走 `restoreCollapsed` 直接设可见性、不走动画，否则整屏重建时每张卡片都闪一遍。
- 收组监听器只挂标题、摘要与「展开/收起」提示三处，**不要挂整张卡片**：清单区域必须留给单条明细，否则「点这一条」被卡片抢走，变成把整组收起来。清单行整行可点（`rowBox`，`minHeight 48dp`），只挂标题那一小块时点行内空白同样会落到卡片上。
- 模型选择页三级（供应商 → 模型 → 推理强度）共用 `currentChoice()`：按「会话用量 → 会话摘要 → 目录 default」依次取值，都不存在时不臆造模型名。`usage` 为空（新建会话还没产生回复）时前两者都没有，靠 `Api.parseModelDefault` 兜底。
- 长路径会占满圆屏，展示前一律经 `shortenPaths` / `shortPath` 压成末两段。

## 测试

- 三个只读/半只读自检脚本：`tests/check-protocol.mjs`（端点参数形态）、`tests/check-features.mjs`（项目分组、步骤详情、模型目录、用量、翻页）、`tests/e2e.mjs`（配对、列表、读消息、发消息）。
- **`e2e.mjs` 挑样本会话有两条硬要求**，否则会误报失败：
  1. 必须**有正文**——列表首项常是刚建的空会话；
  2. 必须**空闲**（`running !== true`）——DSH 对运行中的会话会把新消息放进队列而不立即写入历史，此时查历史必然找不到。发送用例因此单独建一个空白会话。
- 涉及界面行为的改动，用 `adb` 在真机上从实际入口走一遍（启动 → 点项目 → 点会话 → 发消息 / 答题）。
- 手表上验证输入框内容要看截图：`uiautomator dump` 对第三方 `EditText` 常读到空值。
- 验证「界面是否还在闪」不要靠肉眼看截图：间隔几秒连拍数张，比对文件哈希；哈希一致即画面稳定。
