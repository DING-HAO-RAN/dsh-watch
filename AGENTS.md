# 项目约束

## 构成

- 三个部件：[桌面程序](<desktop/>)（C#，装在电脑上，日常用这个）、[手表端应用](<app/>)（原生 Java）、[命令行中继](<relay/>)（Node，无界面，供脚本或服务器环境使用）。中继负责 DSH 鉴权与数据裁剪，手表只调用中继自己的简单接口。
- **桌面程序是主推形态**：一个 exe 拷到任何 Windows 10/11 上双击即用，自动找到本机 DSH 并显示配对码与地址。
- 中继**不修改 DSH 配置、不依赖任何 DSH 插件**，这是需求方明确要求的前提，改动方案时不得破坏。
- 地址统一存在 `Store.base` 字段（`host:port` 或 `https://域名`），由 `Store.parse` 解析。**不要再引入第二套地址存取逻辑**。
- 需求方明确要求「**默认沿用同一端口与密钥，除非用户主动更改**」：配对码与令牌必须跨启动、跨升级保留，不要改回每次都随机生成。

## 配对与多设备

- 桌面程序的配对码与令牌存在 [RelaySettings](<desktop/src/RelaySettings.cs>)：优先 `%LOCALAPPDATA%\dsh-relay\relay-settings.json`，不可写时用程序目录的 `data\`。**`build-app.ps1` 会保留 `data\`**，否则每次重建就丢一次配对（实测过）。
- **配对码配对成功时不更换**：换了手表下次就得重输，与需求方要求相反。需要换码时由用户点界面上的按钮。
- 一键配对的链路：手表发 UDP 查询 → 中继单播回复 → 手表 `POST /api/pair/request` → 电脑弹窗 → 用户点「同意」→ 中继发放一次性票据 → 手表轮询 `GET /api/pair/result` 拿到 grant → `POST /api/pair {grant}` 换令牌。
- **`/api/pair/request` 与 `/api/pair/result` 必须先于鉴权判断**（手表这时还没有令牌）。安全边界靠电脑端弹窗，因此这三条防护不能删：
  1. 同 IP 15 秒冷却 + 待确认请求上限 3（防弹窗轰炸）；
  2. **grant 必须是独立随机值**，不能复用 `requestId`——requestId 明文回给请求方，复用它等于把用户确认环节架空；
  3. grant 2 分钟过期并定期清理（`PruneGrants`），否则旧票据永久有效。
- **常用设备自动同意**：手表首次配对生成随机 `deviceId` 永久保存（`Store.deviceId()`），请求把它放进 body；用户勾「保存为常用设备」后中继 `TrustDevice` 按这个 id 记住，下次 `RequestPairing` 看到 `IsTrusted` 就直接 `ApprovePairing` 并返回 `trusted:true`，不弹窗。**记住不等于放宽安全模型**——仍然发放独立一次性票据、仍然只能换一次令牌。用 `deviceId` 而不是 IP：换 WiFi 后 IP 会变，按 IP 记忆等于「记住这个网络」。撤销走控制台「常用设备」的删除按钮（`ForgetDevice`）。
- **一次配对交付两个入口**：`/api/pair` 的响应带 `lanBase`（本次请求实际到达的那条，来源是 loopback 时退回第一条局域网地址）与 `publicUrl`；手表把两条都存进同一台设备的 `base` 与 `alternateBase`，共用同一个令牌。**公网地址每次重启都会变**，所以手表每次进列表页都用无需鉴权的 `/api/identity`（已带 `publicUrl`）同步一次，不这么做中继一重启手表就再也连不上。
- 手表端 [Store](<app/src/main/java/cn/dsh/watch/Store.java>) 存的是**设备列表**（地址 + 令牌 + 备注 + 最后使用时间 + 备用地址 + 设备 id），`base()`/`token()` 语义不变仍指「当前设备」，因此三十余处调用点不用改。切换设备时必须清空会话、`steps`、`openGroups`、`openRows` 与 `lastListSignature`，否则上一台设备的状态会套到新界面上。`rememberDevice` 命中 `base` 或 `alternateBase` 都算同一台，否则一次配对会堆出两条并互相顶掉令牌。
- 局域网发现是**双向**的：中继既周期广播也响应手表的查询，且要按网段算定向广播地址（部分路由器丢弃 `255.255.255.255`）。**收发必须用两个独立 socket**——共用一个时，bind 到固定端口后再 Send 在部分 Windows 配置下会静默失败。

## 思考内容

- DSH 的 `assistant/message` 里 `content` 块按 `type` 分三类：`text`、`tool-call`、`reasoning`。实测一条会话里 reasoning 占 35 个、tool-call 59 个、text 仅 5 个——**思考才是占比最高的一类**，早期把它整类丢掉了。
- reasoning 块与 text 块字段相同（`type` + `text`），由 `Steps.ThinkingOfContent` 单独抽取，写进 `thinking` 字段。手表上做成默认折叠的「思考 ▸」块，展开状态记在 `openRows`（键 `think:<seq>`），否则 4 秒一次的刷新会把它收回去。

## 桌面程序（C#）

- 构建：[scripts/build-app.ps1](<scripts/build-app.ps1>) 用**系统自带的 .NET Framework 4.8 编译器**（`%WINDIR%\Microsoft.NET\Framework64\v4.0.30319\csc.exe`）。这是刻意的选择：产出的 exe 在 Win10 1903+/Win11 上开箱即用，**目标机不需要装 .NET SDK、不需要装 Node.js**。
- **该编译器只支持 C# 5**：不能用 `$"..."` 字符串插值、`nameof`、表达式体成员。集合初始化器、`var`、lambda、匿名方法都可以。违规时报 `error CS1644: 功能"..."在 C# 5 中不可用`。
- 界面有两层，**不要把它们合并**：
  1. **WebView2 网页界面**（[panel.html](<desktop/src/panel.html>) + [MainForm.cs](<desktop/src/MainForm.cs>)）：好看、信息密度高，但依赖 WebView2 运行时；
  2. **原生界面**（[NativePanel.cs](<desktop/src/NativePanel.cs>)）：纯 WinForms，零依赖，**永远可用**，同时是内嵌浏览器失败时的自动降级目标。
  这样分层的理由：中继本身不需要浏览器就能工作，精简版 Windows 上可能没有 WebView2；有了第二层，「任何电脑都能打开并显示配对码」才有保证。
- **原生界面绝不预先摆出来**。以前它一创建就填满窗口、等 WebView2 就绪再盖上去，于是每次启动都先闪一屏旧的 WinForms 界面（用户报的「显示旧的 UI 界面」就是这个）。现在窗口里只有一个「正在启动中继…」占位标签，`EnsureFallback()` 只在浏览器确实失败时才创建原生面板——正常启动路径上根本不出现旧界面，也就不存在切换过程。
- **关闭窗口＝收进托盘，不是退出**。`OnFormClosing` 在 `reallyClosing` 为 false 时取消关闭并 `Hide()`；真正的退出走托盘菜单「退出」。托盘图标是自绘的 `Icon`（`MakeTrayIcon`），不依赖外部 .ico。**不要改回「关闭即退出」**——「随 DSH 启动」要求程序在后台常驻。
- **`--background` 启动时窗口收进托盘**：`Autostart` 写进注册表的命令行带这个参数，`MainForm(startHidden)` 在 `OnShown` 里 `Hide()`。开机自启不能弹窗。
- **托盘图标必须用 `Icon.Clone()`，不能直接用 `Icon.FromHandle(bitmap.GetHicon())`**：后者是「借句柄」的包装，位图释放后图标内容失效，托盘里表现为空白或干脆不显示——这正是「开机自启后找不到程序、也没托盘」的真实原因。正确写法是 `GetHicon()` → `Icon.FromHandle` → `Clone()` → `DestroyIcon(原始句柄)`。**改图标逻辑后必须实测托盘里能看到**（用 UI Automation 打开溢出区确认存在「DSH 中继」按钮），只看 `tray.Visible == true` 不算数，它只表示调用成功。
- **托盘必须同时绑单击与双击**：只绑 `DoubleClick` 时，Win11 把新图标收进溢出区后单击那层行为不稳定，用户会「点了没反应」。`RestoreWindow()` 里也要**先把 `WindowState` 设回 `Normal` 再 `Show()`**，否则停在最小化的窗口显示出来仍是「没打开」。
- **托盘不可用时不隐藏窗口**：`OnShown` 里若 `tray == null || !tray.Visible` 就直接返回、保持窗口可见。否则用户既没窗口也没托盘，只能去任务管理器结束进程。
- Windows 11 会把新的托盘图标默认收进「^」溢出区（实测本次就是），托盘图标**存在但不在任务栏可见位置**。README/使用说明要写明这一点，引导用户展开溢出区或拖到任务栏固定。
- **开机自启写当前用户的 Run 键**（`HKCU\Software\Microsoft\Windows\CurrentVersion\Run`，值名 `DshRelay`）：不需要管理员权限，删掉即取消。刻意不用启动文件夹快捷方式（出错时用户看不到任何反馈）也不用计划任务（要提权，会被企业策略拦）。
- **「开机自启」与「随 DSH 启动」共用一个进程**：后者由 `PanelHost.StartWatchdog()` 每 5 秒问一次 `DshLocator.IsRunning(dshPortHint)`，DSH 没了就 `StopServing()`、回来就 `StartServing()`。两个都勾时先开机待命、DSH 一出现才开始服务，**绝不另起第二个实例**——两个实例会抢 19390 端口。
- **DSH 端口会变，恢复服务时必须重新定位**：`StartServing()` 里调用 `RelocateDsh()` 重新 `Locate`、重建 `DshClient` 并 `RelayServer.RebindDsh()`。**绝不能复用暂停前的客户端**——DSH 重启换了随机端口，旧客户端会一直打老端口，手表侧全部显示「无法连接到远程服务器」（实测日志里那串 `127.0.0.1:53540 积极拒绝` 就是这么来的）。因此 `RelayServer.dsh` **不是 readonly**。
- **`DshLocator.IsRunning` 不能只看端口**：旧端口不通**不代表 DSH 退出**（它每次启动都换端口）。正确顺序是「已知端口通 → true；否则看进程还在不在 → 在就重新定位、定位不到也**当作仍在运行**」。只按端口判断会让跟随模式反复误停服务。
- **DSH 请求失败要自动重连一次**：`RelayServer.Route` 捕获 `DshException` 后调 `TryRelocate()` 并用 `Route(request, true)` 重试。用 `Interlocked` 互斥，避免一次断线的多个并发请求各自定位、互相覆盖客户端。把错误直接抛给手表只会显示「无法连接到远程服务器」，用户无从处理。
- **公网默认开启**：`RelaySettings.AutoTunnel` 缺省为 `true`，`StartServing()` 起中继后在后台线程拉 cloudflared（不能阻塞启动，局域网不该等它）。老存档没有这个键时也按开启处理，否则升级上来的用户会以为功能丢了。
- **调试第二份实例不要打断正在运行的那份**：`RelaySettings.PathFor` 认 `DSH_RELAY_STATE_DIR` 覆盖状态目录，`PanelHost.Run` 认 `DSH_RELAY_PORT` 覆盖监听端口，`build-app.ps1` 有 `-OutputDir` / `-StateDir`。三者配合就能在原版仍占着 19390 的情况下跑新版验证。
- **更新程序时不要 `Stop-Process -Force` 强杀中继**：它拉起的 `cloudflared.exe` 子进程**继承了监听 socket 的句柄**，中继进程消失后端口仍显示 `LISTENING <已死PID>`，新实例绑不上、日志报「每个套接字地址只允许使用一次」，而且这种 orphaned socket 不会随时间释放（实测等了 90 秒仍在）。正确做法是**先杀该实例的 cloudflared、再杀中继**（或走托盘「退出」），端口立刻释放。排查手法：`netstat -ano | findstr :19390` 拿到的 PID 若已不存在，就去看 `cloudflared.exe` 的 `--url` 是否指向这个端口。
- **依赖全部嵌进 exe**，分发只有一个文件。挂载方式（[Bootstrap.cs](<desktop/src/Bootstrap.cs>)）分两条路，两者都必须保留：
  - 托管程序集用 `Assembly.Load(byte[])` **从内存装入**（在 `AssemblyResolve` 里实现）；
  - 原生加载器 `WebView2Loader.dll` **必须落盘**（Win32 的 `LoadLibrary` 只接受路径），优先写程序自身目录，失败再退到临时目录。
- **绝不要把依赖做成"解压到用户配置目录"**：受控环境（企业策略、沙箱）会拒绝对 `%LOCALAPPDATA%` 的写入，表现为 `Directory.CreateDirectory` 抛"访问被拒绝"，随后 WebView2 报"未能加载程序集"。优先写程序目录就是这个原因。
- `Bootstrap.Init()` 必须在**任何 WebView2 类型被 JIT 之前**调用，否则类型解析会先失败。
- 程序目录下的 `startup.log` 记录启动全过程（DSH 定位、端口、依赖释放、异常堆栈）。**GUI 程序闪退时这是唯一的诊断线索**，排查先看它。
- 判断程序是否真的在运行**不要用 `Get-Process -Name 中文名`**（对中文进程名不可靠，会假报"未找到"）。可靠做法：`tasklist /FO CSV` 过滤，或 `Get-CimInstance Win32_Process -Filter "Name='DSH中继.exe'"`。
- 从命令行启动 GUI 程序时 `Start-Process`/`& exe` 会阻塞到程序退出，**这不代表失败**。要立即返回并拿到 PID，用 `Invoke-CimMethod -ClassName Win32_Process -MethodName Create`。
- **DPI 感知靠 [app.manifest](<desktop/src/app.manifest>)，不要删**。缺它时进程在 125%/150%/200% 缩放下会被 DWM 按位图拉伸，整个窗口连内嵌网页一起发虚——用户会说「分辨率不够」。清单里 `dpiAware` 与 `dpiAwareness` 两条都要留，前者给 Win10 1703 以前的系统读。`build-app.ps1` 用 `/win32manifest:` 编译进去。
- 窗口尺寸由 `MainForm.ApplyDisplaySize()` 按**当前显示器工作区的比例**算，夹在 940×660 到 1480×1040 之间，**不要写死像素**：写死时小屏会超出屏幕、高缩放屏会显得又小又挤。
- 改 `panel.html` 后验证视觉：开发时 `Embedded.ReadText` 优先读程序目录下的同名文件，把 `panel.html` 拷进 `dist/DSH中继/` 再刷新即可，不必重编 exe。核对渲染用无头 Edge（`msedge --headless=new --remote-debugging-port`）截图，注意 **`fetch` 有缓存**时页面会显示旧结果。
- `NativePanel` 的卡片是**从 `TableLayoutPanel` 派生的**，不是 `Panel`：卡片要「宽度撑满、高度随内容」，只有 TableLayoutPanel 的 AutoSize 能稳妥做到，用 Panel + Dock 会被压成内容固有宽度。地址行同理改用 TableLayoutPanel，**不要用 `FlowLayoutPanel` + 手算像素宽度**，否则换窗口尺寸后行宽不跟随。

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
- **`pack-relay.ps1` 与 `build-app.ps1` 的输出目录必须分开**：前者出 `dist/中继命令行版/`，后者出 `dist/DSH中继/`。两个脚本启动时都会清空自己的输出目录，共用同一个目录时后跑的那个会把先跑的产物删掉。

## 发布

- 仓库：[DING-HAO-RAN/dsh-watch](https://github.com/DING-HAO-RAN/dsh-watch)（公开）。
- 一键发布：[scripts/release.ps1](<scripts/release.ps1>) 串起「建 release → 传两个资产 → 下载回来比对哈希」。事后要改说明用 [scripts/update-release-notes.mjs](<scripts/update-release-notes.mjs>)。
- **GitHub 会剥掉 Release 资产名里的非 ASCII 字符**（`DSH中继.exe` 会变成 `DSH.exe`，label 也会被丢掉），所以发布用的文件名固定为 `DSH-Relay.exe` 与 `DSH-Watch.apk`，README 与实际资产名必须一致。改完要下载回来比对哈希。
- 上传资产用**只带文件的 POST**，不要把 `--data-urlencode` 与 `--data-binary` 混在同一条 curl 里——两者会拼进同一个请求体，产出的资产比源文件大出一截且内容损坏。
- **发请求体必须显式转成 UTF-8 字节**。`Invoke-RestMethod` 直接接 `ConvertTo-Json` 的字符串时，中文会被替换成 `?`——实测整段发布说明 915 个字符全变问号，而接口返回 200、看不出异常。用 Node 的 `fetch`（字符串天然按 UTF-8 编码）或先 `[Text.UTF8Encoding]::GetBytes(...)` 都可以，**改完必须把内容拉回来校验是否含中文**，不能只看请求成功。
- **PATCH 更新 release 只能按 release id**，按 tag 打会 404。
- 凭据复用 `git push` 已存的那份（Windows 凭据管理器，无需额外配置）。取法：`git credential fill` 需要逐行协议文本 + 结尾空行——PowerShell 管道喂进去会报 `credential missing protocol field`，而 PowerShell 又不支持 `<` 重定向，所以走「临时文件 + cmd」或 Node 的 `execFileSync(..., { input })`。
- 命令行版中继（打包后约 88 MB）**不进 Releases**，公网访问用的 `cloudflared.exe`（约 53 MB）同样不随包提供，缺了它只影响公网功能。
- **发布产物必须从 `git archive HEAD` 导出的干净副本里构建**，不能直接用工作树：本机常有其它会话正在改这个项目，工作树里的代码未必是已提交的那份。

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

## 子智能体与计划任务

- **子代理会话的历史必须用「父地址 + 子地址」读**：对 `origin == "subagent"` 的会话用普通 `{kind:'session'}` 地址调 `session/page` 会直接报错 `subagent Sessions require their durable parent address`。正确形状是 `{kind:'subagent', parentSessionId, childSessionId, mode}`（`mode` 取 `values.subagent.mode`，缺省 `continuable`）。`session/projections` 不受影响，仍只用 `sessionId`。这是「查看子智能体对话」唯一的坑，两端中继都在 `Session()` / `readSession()` 里按 `parentSessionId` 是否为空选地址。
- **子代理与计划数据全在 `session/list` 的每个条目里**（`item.projections.values`），不需要额外 RPC：`subagent{mode,label}`、`subagentTiming{settledMs,active,lastTurnCompleted}`、`subagentCatalog[]`、`todos[{content,status}]`、`plan{active,pending}`、`agentTeam{members[{name,role,phase}],tasks[{subject,status,ownerName,ready,blockedBy}]}`、`goal{goal{objective,phase,maxGoalRounds},roundsStarted}`。**逐个会话拉投影来凑这些数据会慢到不可用**（实测 499 个子代理）。
- **列表截断必须发生在过滤之后**。子代理动辄数百条且 `updatedAt` 很新，先 `slice(0, 60)` 再筛父会话，父会话会被子代理整体挤出列表。`normalizeSessionList` 因此把 `limit` 交给调用方（`0` = 不截断），`/api/sessions` 先全量再过滤再截断，`/api/subagents` 完全不截断。
- 子代理状态四档按此顺序判定，不要重排：`running` → `running`；`lastTurnCompleted` → `done`；`agentAvailable` → `idle`；否则 `done`。
- 子任务列表的入口只在**会话页**（父会话左滑菜单「子任务」），不在项目/会话列表平铺——498 个子会话会把手表首屏淹掉。会话列表里用 `subagentCount`/`subagentRunning` 在副标题上提示「N 个子任务在跑」即可。
- 子任务对话**复用普通会话页**（子会话与普通会话同构），因此「返回」要按进入路径决定去向：`sessionReturnTo` 为 `subagents` 时回子任务列表，否则回项目列表。进过子任务对话后 `currentSessionId` 已指向子会话，回父会话时必须显式恢复 id，**且不要把子会话写进 `lastSession`**——否则下次启动会直接把用户丢进一个子代理的历史里。

## 构建

- 环境变量用 `JAVA_HOME` / `ANDROID_HOME` / `WATCH_BUILD_ROOT` 指定，**不要把本机路径写进仓库**。
- 构建走 [scripts/build.ps1](<scripts/build.ps1>)，直接调用 SDK 工具链。三个必须保留的写法（去掉就会踩坑）：
  1. 外部命令输出重定向到文件后判退出码——PowerShell 5.1 在 `ErrorActionPreference='Stop'` 下会把 javac 的过时警告当致命错误中断构建，从而**掩盖真正的编译错误**；
  2. 源码与 class 清单写文件后用 `@file` 传参——数组经 shell 展开会被拆坏，导致 d8 静默漏掉大部分类，手表上表现为 `NoClassDefFoundError`；
  3. 用 JDK 的 `jar` 工具把 dex 追加进包——受限语言模式下不可用 .NET 的 ZipFile。
- `build.ps1` 会逐个核对 `MainActivity` / `WatchUi` / `WatchUi$Page` / `Store` / `Api` / `Discovery` / `Theme` / `CodeView` 八个 class 是否存在，这是拦截「类漏编」的最小门禁。新增顶层类时要同步加进这份清单，否则新类漏编只会在手表上表现为 `NoClassDefFoundError`。
- 本机可用的 Android SDK 在 `D:\AndroidToolboxEnv\sdk`（`platforms` + `build-tools/35.0.0`），JDK 用 DSH 自带的 `jbr`；两者都用环境变量传入，别写进仓库。

## 界面

- 圆屏安全区由 [WatchUi](<app/src/main/java/cn/dsh/watch/WatchUi.java>) 的 `page()` 一处负责：按圆的内接矩形留边（左右 15%、上下 18%）。四角到圆心约 0.474 < 半径 0.5（余量约 12px），**整个滚动区都在圆内，可见内容天然安全**，页面不要自己再加边距。
- **绝不要按控件自身的屏幕位置去动态调边距**。曾经这样做过（`applyRoundInsets`）：它形成布局反馈环——算边距 → 控件变窄 → 文字折行 → 位置变化 → 再算出不同边距，最终把同排按钮压成宽窄不一的窄条且无法拉伸。基础安全区已经足够。
- 按钮用 `TextView` + 自绘背景实现（`WatchUi.button`），**不用 `android.widget.Button`**：后者带 9-patch 背景与固有最小宽度，在窄圆屏上会被顶住而撑不满整行。按钮必须单行显示，宽度交给父容器（`LayoutParams(-1, -2)`）。
- 同排两个按钮用 `weighted()`（`weight=1` 平分）；**之后不要再改它们的 layoutParams 或加 margin**，那会破坏 weight 分配。
- 可点区域不低于 48dp；**操作入口尽量收进左滑菜单**，页面上只留最常用的两个按钮，把高度让给内容。
- 标题必须单行（`WatchUi.headingView` 已设 `setSingleLine`）。圆屏可视高度只有三百多像素，标题一旦折成两三行，按钮和内容区就被挤出屏幕、整页也滚不动。截断用 `clipLine`（只加省略号），**不要用 `cut`**（它会插入换行，等于把标题变成两行）。
- **绝不能让「渲染 → 发请求 → 回调再渲染」形成环**。列表页曾经如此：`showSessionList()` 里发请求，回调又调 `showSessionList()`，屏幕每轮都重建、持续上下抽动。现在的分工是：`showSessionList()` 只在进入页面时调用一次，回调一律走 `renderSessionListPage()`（只重绘、不发请求）。
- 界面用「重建整屏」的方式刷新，因此**滚动位置由 `render()` 统一保留**，换页时才调 `resetScroll()` 回顶部。会话页（4 秒）与会话列表（10 秒）都会定时刷新，不做这件事用户就翻不动列表和长对话。
- **进入任何新页面都必须先 `resetScroll()`**：`render()` 默认沿用上一页的滚动位置，从长对话点进子任务页或计划页时，新页面会停在中间、连标题都被带出屏幕（实测）。子任务页、计划页在 `show*()` 里都显式调了它；只有同页重绘（定时刷新）才不调。
- **列表的定时刷新与响应回调一律用 `onListPage()` 判断，不要只判 `onSessionPage`**。文件页、子任务页、计划页都会把 `onSessionPage` 置 false，只判它的话列表刷新一到就会把这几页整屏覆盖掉——文件页长期存在这个问题，现在统一走 `onListPage()`（要求四个页面标记全为 false）。
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
- **主题靠「可变静态字段」实现，不是重建 View**：[WatchUi](<app/src/main/java/cn/dsh/watch/WatchUi.java>) 的 `BG`/`FG`/`ACCENT` 等都是 `static`（非 final），`applyTheme()` 一次性换掉整组；`Theme` 只负责给出深/浅两套语义色。全项目九十余处 `WatchUi.BG` 这类引用因此一行都不用改。**不要把这些字段改回 final**，也不要让界面代码自己去判断主题色——那会把配色散到几十个地方。切换后重绘整屏即可生效，`onCreate` 里必须在建页面**之前**应用。
- **圆屏优化是可关的开关**（`WatchUi.roundOptimized`，默认 true，与既有效果完全一致）：关闭时 `page()` 不再按内接矩形留 15%/18%，改用 `dp(8)` 常规边距，方屏设备能把整屏用满。判断条件同时要求「用户开着这个开关」和「系统报告是圆屏」，两者缺一都走方屏分支。
- **代码与 Markdown 必须用两个渲染器**：[Markdown](<app/src/main/java/cn/dsh/watch/Markdown.java>) 会把 `#`、`*`、`|`、`- ` 当结构语法吃掉，而这些字符在源码里有本义（注释、乘法、位运算），拿它渲染代码等于改坏代码。[CodeView](<app/src/main/java/cn/dsh/watch/CodeView.java>) 保留缩进与行号、每行独立上色、**不做跨行状态机**（状态机一旦出错整屏后续行都会被染成注释色）。代码视图必须 `setHorizontallyScrolling(true)`，自动折行会让缩进层级全乱。
- **文件查看只认会话里出现过的文件**：手表菜单「查看文件」的数据来自 `session` 响应里的 `files`（中继从工具步骤的 `files` 汇总，已带 `kind`/`language`/`size`）；`/api/file` 只接受这份白名单里的路径，任意路径一律 403。**不要把白名单放宽成「任意路径」**，那等于把整台电脑的文件系统交给任何拿到令牌的设备。

## 测试

- 三个只读/半只读自检脚本：`tests/check-protocol.mjs`（端点参数形态）、`tests/check-features.mjs`（项目分组、步骤详情、模型目录、用量、翻页）、`tests/e2e.mjs`（配对、列表、读消息、发消息）。
- **`check-features.mjs` / `answer-once.mjs` / `send.mjs` 不带 `--token` 时会去读 `~/.dsh-watch-relay.json`**，那是**命令行版中继**的存档；桌面程序的令牌只存在内存里，不落这个文件。因此对着桌面程序跑而不显式传 `--token` 时，会拿到旧令牌或空令牌，**表现为 8 条用例全红**（「列表失败」「缺少样本会话」），容易被误判成功能坏了。稳妥做法是另起一个带 `--state-file` 的命令行中继，配对取新令牌后再跑。
- **配对码现在不再「用一次就换」**（按需求方要求改为沿用），所以同一台中继的配对码在多次测试间是稳定的，可以从 `/local/state` 反复取用。要让它换新只有两条路：点界面上的「换一个配对码」，或删掉设置文件。
- **`e2e.mjs` 挑样本会话有两条硬要求**，否则会误报失败：
  1. 必须**有正文**——列表首项常是刚建的空会话；
  2. 必须**空闲**（`running !== true`）——DSH 对运行中的会话会把新消息放进队列而不立即写入历史，此时查历史必然找不到。发送用例因此单独建一个空白会话。
- 涉及界面行为的改动，用 `adb` 在真机上从实际入口走一遍（启动 → 点项目 → 点会话 → 发消息 / 答题）。
- 手表上验证输入框内容要看截图：`uiautomator dump` 对第三方 `EditText` 常读到空值。
- 验证「界面是否还在闪」不要靠肉眼看截图：间隔几秒连拍数张，比对文件哈希；哈希一致即画面稳定。

## 对外语言（需求方明确要求）

- **凡是仓库外的人能看见的文本一律写英文**：commit message、branch 名、PR/issue 标题与正文、Release 标题与说明、tag、仓库简介里英文那半。**代码、命令、变量名保持英文**这条本来就成立，不再赘述。
- **README 出中英两版**，中文是默认版：[README.md](<README.md>) 为中文（GitHub 首屏展示它），[README.en.md](<README.en.md>) 为英文。两版**首行都要有 `[中文](README.md) | [English](README.en.md)`**（英文版把顺序反过来写）。改内容时**两份一起改**，不要只更新一份——两份内容长期不同步会误导外部使用者。
- 仓库简介（GitHub 的 description）写成**英文 + 中文**，英文在前：外边检索的人看英文，中文给熟客。改简介用 Node 的 `fetch`，改完**必须拉回来核对含中文**——接口返回 200 不代表没被转义成 `?`（见「发布」一节同样的坑）。
- 仓库内部的文档（`AGENTS.md`、[B站投稿文案](<docs/B站投稿文案.md>)）是给需求方与后续会话看的，**保持中文**，不要跟着改成英文。
- 发出来的资产名沿用 `DSH-Relay.exe` / `DSH-Watch.apk`（英文），与上面的要求一致。

## 介绍片素材

- 成片与封面放在 `docs/`：`DSH手表-项目介绍.mp4`（1920×1080 / 60fps / 约 70 秒 / 11MB）、`DSH手表-视频封面-16x9.png`、`DSH手表-视频封面-4x3.png`。**都不进版本库**（`.gitignore` 已忽略），只在本机留档。
- 投稿文案在 `docs/B站投稿文案.md`（标题 / tag / 简介 / 置顶评论，**进版本库**）。简介正文 1725 字，距 B 站 2000 字上限留余量，再加内容先删同量的旧内容。
- 仓库用 **MIT 协议**（[LICENSE](<LICENSE/>)，版权署名用仓库标识 `DING-HAO-RAN`，不写个人姓名与邮箱）。改协议要同步三处：`LICENSE`、README 的许可段、`relay/package.json` 的 `license` 字段。
- 制作脚本在 `docs/介绍片制作/`（含字体，24MB，`.gitignore` 已忽略）：`player.html` 用一个纯函数 `render(t)` 描述每一帧，`render.mjs` 通过 CDP 驱动无头 Edge 按时间点逐帧截图，`tts.mjs` 调曼波 TTS 生成配音，`bgm.mjs` 用纯数学合成背景音乐，`audio.mjs` / `encode.mjs` 负责混音与编码。
- **画面不许用 CSS 动画**：逐帧截图时 CSS 动画不受控，同一 `t` 会截到不同画面。所有随时间变化的量都必须由 `t` 直接算出来。
- 只改某一镜时不要重渲全片（60fps 下 4201 帧约 20 分钟）：用 `node render.mjs 60 <目录> <起> <止>` 只渲染该区间，再按 `round(t*60)` 把帧写回 `frames/` 对应编号。
- **配音稿改了就全部重配**：`tts.mjs` 会重写 `timeline.json`，而画面里每一镜的节奏点都挂在 `S.start` 上，时间轴一动整片都要重渲。
- 旁白每行 ≤30 字、语速 `speed=-6`；字幕直接用旁白原句（静音也要能读懂），画面上的大字只写旁白里没有的事实，两者刻意不重复。
