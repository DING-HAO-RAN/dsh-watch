using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.Globalization;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Reflection;
using System.Text;
using System.Threading;
using System.Windows.Forms;

namespace DshRelay
{
    /// <summary>
    /// 主窗口。
    ///
    /// 界面有两套，优先用内嵌浏览器显示控制台页面；如果 WebView2 不可用
    /// （精简版 Windows 没装运行时、或环境被占用），自动降级为原生界面。
    /// 中继本身不依赖任何界面组件，因此**核心功能在任何机器上都能用** ——
    /// 这是「拷到别的电脑就能打开」的底线。
    /// </summary>
    public class MainForm : Form
    {
        private readonly PanelHost host;
        private Microsoft.Web.WebView2.WinForms.WebView2 webView;
        private NativePanel fallback;
        private string panelUrl;
        private bool browserFailed;
        private NotifyIcon tray;
        private bool reallyClosing;

        /// <summary>以 --background 启动（开机自启）时，窗口不显示，只驻留托盘。</summary>
        private readonly bool startHidden;

        public MainForm(bool background)
        {
            startHidden = background;
            Text = "DSH 中继";
            AutoScaleMode = AutoScaleMode.Dpi;
            StartPosition = FormStartPosition.CenterScreen;
            // 先按存档里的主题定下整组颜色，再创建控件，
            // 否则会先画一屏深色再跳成浅色（就是「闪一下旧界面」）。
            Theme.Apply(RelaySettings.Load(AppDomain.CurrentDomain.BaseDirectory).Theme);
            BackColor = Theme.Bg;
            Font = new Font("Microsoft YaHei UI", 9f);
            ApplyDisplaySize();

            // 原生面板**不预先摆出来**。
            //
            // 以前它一创建就填满窗口，等 WebView2 就绪再盖上去，于是每次启动都会
            // 先看到一屏旧的 WinForms 界面、随后闪一下换成新界面。那既是「显示旧界面」
            // 的 bug，也是所谓的切换过程。现在只在浏览器真的起不来时才创建它。
            loading = new Label();
            loading.Text = "正在启动中继…";
            loading.Dock = DockStyle.Fill;
            loading.TextAlign = ContentAlignment.MiddleCenter;
            loading.ForeColor = Theme.Muted;
            loading.Font = new Font("Microsoft YaHei UI", 11f);
            loading.BackColor = Theme.Bg;
            Controls.Add(loading);

            BuildTray();

            host = new PanelHost(StartupLog.Write, AppDomain.CurrentDomain.BaseDirectory);
            host.AttachForm(this);
            host.UrlReady += OnPanelReady;
            host.Failed += OnFailed;
            host.ThemeChanged += ApplyWindowTheme;
            host.Start();
        }

        /// <summary>浏览器就绪前的占位文字（不是旧界面，只是「正在启动」）。</summary>
        private Label loading;

        /// <summary>按主题给自己的底色与占位文字换色，避免切主题时窗口边框仍是旧色。</summary>
        private void ApplyWindowTheme(bool light)
        {
            Theme.Apply(light ? "light" : "dark");
            BackColor = Theme.Bg;
            if (loading != null)
            {
                loading.BackColor = Theme.Bg;
                loading.ForeColor = Theme.Muted;
            }
            // 原生降级界面用的是 Theme 里的静态色，重建一次才能全部换过来。
            if (fallback != null)
            {
                Controls.Remove(fallback);
                fallback.Dispose();
                fallback = null;
                EnsureFallback();
            }
        }

        /// <summary>
        /// 托盘图标。
        ///
        /// 「开机自启」与「随 DSH 启动」都要让程序在后台活着，那就必须有一个
        /// 用户能看见、能唤回窗口、能真正退出的入口——否则用户只能去任务管理器
        /// 里结束进程。关闭按钮也改成「收进托盘」，退出走托盘菜单。
        /// </summary>
        private void BuildTray()
        {
            tray = new NotifyIcon();
            tray.Text = "DSH 中继";
            tray.Icon = MakeTrayIcon();

            var menu = new ContextMenuStrip();
            menu.Items.Add("打开窗口", null, delegate { RestoreWindow(); });
            menu.Items.Add(new ToolStripSeparator());
            menu.Items.Add("退出", null, delegate
            {
                reallyClosing = true;
                Close();
            });
            tray.ContextMenuStrip = menu;
            // 单击与双击都恢复窗口：Win11 把新图标收进溢出区后，单击那一层的
            // 行为并不稳定，只绑 DoubleClick 会让用户「点了没反应」。
            tray.MouseClick += delegate (object sender, MouseEventArgs e)
            {
                if (e.Button == MouseButtons.Left) RestoreWindow();
            };
            tray.DoubleClick += delegate { RestoreWindow(); };

            // 先把图标与菜单都挂好，最后再置 Visible：
            // 反过来做的话，NotifyIcon 会在图标还没设置好时就向系统注册，
            // 注册出来的条目可能是个空图标，用户根本认不出、也点不到。
            tray.Visible = true;
            StartupLog.Write("托盘图标已创建（可见=" + tray.Visible + "）");

            // 首次运行时提示图标被折叠进了溢出区是常见困惑：Windows 11 默认把
            // 新图标放进「^」里。这里不做弹窗打扰，只在日志里留一条线索。
            try
            {
                StartupLog.Write("若任务栏看不到图标，请点任务栏的「^」展开隐藏的图标区，"
                    + "并把 DSH 中继拖到任务栏上固定。");
            }
            catch { }
        }

        /// <summary>
        /// 画一个简单的托盘图标，避免依赖外部 .ico 文件。
        ///
        /// 必须返回**拥有自己数据**的图标：`Icon.FromHandle(bitmap.GetHicon())`
        /// 拿到的是「借用」句柄的包装，位图一释放图标内容就失效，托盘里会变成
        /// 一片空白或干脆不显示——这正是「程序在跑但托盘里找不到」的原因。
        /// 这里用 Clone 复制成独立图标，再释放原始句柄。
        /// </summary>
        private static Icon MakeTrayIcon()
        {
            try
            {
                using (var bitmap = new Bitmap(32, 32))
                {
                    using (var g = Graphics.FromImage(bitmap))
                    {
                        g.SmoothingMode = System.Drawing.Drawing2D.SmoothingMode.AntiAlias;
                        g.Clear(Color.Transparent);
                        using (var ring = new Pen(Color.FromArgb(240, 182, 75), 3.2f))
                        {
                            g.DrawEllipse(ring, 4f, 4f, 24f, 24f);
                        }
                        using (var hand = new Pen(Color.FromArgb(240, 182, 75), 3.2f))
                        {
                            g.DrawLine(hand, 16f, 16f, 16f, 9f);
                            g.DrawLine(hand, 16f, 16f, 21.5f, 19f);
                        }
                    }
                    IntPtr handle = bitmap.GetHicon();
                    try
                    {
                        using (var borrowed = Icon.FromHandle(handle))
                        {
                            // Clone 之后 Icon 自带数据副本，不再依赖位图与原始句柄。
                            return (Icon)borrowed.Clone();
                        }
                    }
                    finally
                    {
                        DestroyIcon(handle);
                    }
                }
            }
            catch (Exception error)
            {
                StartupLog.Write("生成托盘图标失败，退回系统图标：" + error.Message);
                // 画不出来也不能让托盘缺位，退回系统默认图标。
                return (Icon)SystemIcons.Application.Clone();
            }
        }

        [System.Runtime.InteropServices.DllImport("user32.dll", SetLastError = true)]
        private static extern bool DestroyIcon(IntPtr handle);

        private void RestoreWindow()
        {
            try
            {
                // 顺序要紧：先把状态设回 Normal 再显示。窗口若停在 Minimized，
                // 单靠 Show() 只能得到一个最小化窗口，用户看到的仍是「没打开」。
                if (WindowState == FormWindowState.Minimized) WindowState = FormWindowState.Normal;
                Show();
                WindowState = FormWindowState.Normal;
                ShowInTaskbar = true;
                Activate();
                BringToFront();
                Focus();
                StartupLog.Write("用户从托盘打开了窗口");
            }
            catch (Exception error)
            {
                StartupLog.Write("从托盘恢复窗口失败：" + error.Message);
            }
        }

        protected override void OnShown(EventArgs e)
        {
            base.OnShown(e);
            if (!startHidden) return;
            // 托盘没建起来就绝不隐藏窗口——否则用户既看不到窗口、也没有托盘可点，
            // 只能去任务管理器结束进程。这是「打开了却什么都没有」的兜底。
            if (tray == null || !tray.Visible)
            {
                StartupLog.Write("托盘不可用，保持窗口可见（否则用户将无法操作）");
                return;
            }
            // 开机自启不该在登录后弹窗，这里隐藏到托盘。
            StartupLog.Write("以 --background 启动，窗口收进托盘");
            Hide();
            // 开机自启时用户看不到任何窗口，给一次气泡提示说明「我在托盘里」，
            // 否则会以为程序没启动。只提示一次，不反复打扰。
            try
            {
                if (tray != null)
                {
                    tray.BalloonTipTitle = "DSH 中继已在后台运行";
                    tray.BalloonTipText = "手表可以正常连接。单击托盘图标可打开窗口，右键可退出。";
                    tray.BalloonTipIcon = ToolTipIcon.Info;
                    tray.ShowBalloonTip(5000);
                }
            }
            catch { }
        }

        protected override void OnResize(EventArgs e)
        {
            base.OnResize(e);
            if (WindowState == FormWindowState.Minimized && tray != null)
            {
                Hide();
            }
        }

        /// <summary>
        /// 按当前显示器的可用区域决定窗口大小。
        ///
        /// 不能写死像素：在 150%/200% 缩放的屏幕上，同一个 920×720 会显得又小又挤，
        /// 而在 1366×768 的小屏上又会超出屏幕。这里按工作区比例取值并夹在合理区间内，
        /// 同时把初值取在「本窗口所在屏幕」的 DPI 上，WebView2 里的 CSS 像素才是等比的。
        /// </summary>
        private void ApplyDisplaySize()
        {
            Rectangle area = Screen.PrimaryScreen == null
                ? new Rectangle(0, 0, 1920, 1080)
                : Screen.PrimaryScreen.WorkingArea;

            int width = Clamp((int)(area.Width * 0.72), 940, 1480);
            int height = Clamp((int)(area.Height * 0.84), 660, 1040);

            ClientSize = new Size(width, height);
            MinimumSize = new Size(660, 560);
            MaximumSize = new Size(area.Width, area.Height);
        }

        private static int Clamp(int value, int min, int max)
        {
            if (value < min) return min;
            if (value > max) return max;
            return value;
        }

        private void OnPanelReady(string url)
        {
            panelUrl = url;
            BeginInvoke(new Action(delegate
            {
                TryBrowser();
            }));
        }

        /// <summary>
        /// 取出（必要时创建）原生降级面板。
        ///
        /// 只在浏览器确实不可用时调用——这样正常启动的路径上完全不会出现旧界面，
        /// 「先闪旧界面再换新」的问题也就没有发生的机会。
        /// </summary>
        private NativePanel EnsureFallback()
        {
            if (fallback != null) return fallback;
            if (loading != null)
            {
                Controls.Remove(loading);
                loading.Dispose();
                loading = null;
            }
            fallback = new NativePanel();
            fallback.Dock = DockStyle.Fill;
            Controls.Add(fallback);
            fallback.Attach(host);
            fallback.BringToFront();
            return fallback;
        }

        /// <summary>
        /// 挑一个可写的浏览器数据目录。
        ///
        /// 必须**先探测再交给 WebView2**：如果指定了一个不可写的目录，
        /// WebView2 初始化会直接抛 E_UNEXPECTED（灾难性故障），而不是换个位置重试。
        /// 探测顺序按「不污染分发目录」与「大概率可写」折中：
        /// 1. 程序目录下的 webview-data —— 最常见的情况都可写，且随程序一起走；
        /// 2. 用户缓存目录 —— 程序装在 Program Files 时的退路；
        /// 3. 系统临时目录 —— 最后的退路。
        /// 都不可写时返回 null，交给 WebView2 用默认位置（会失败，随后降级到原生界面）。
        /// </summary>
        private static string PickWritableDirectory()
        {
            var candidates = new System.Collections.Generic.List<string>();
            try { candidates.Add(Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "webview-data")); }
            catch { }
            try
            {
                candidates.Add(Path.Combine(
                    Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "dsh-relay", "webview2"));
            }
            catch { }
            try { candidates.Add(Path.Combine(Path.GetTempPath(), "dsh-relay", "webview2")); }
            catch { }

            foreach (string candidate in candidates)
            {
                try
                {
                    Directory.CreateDirectory(candidate);
                    // 真正写一个文件才算数：目录能建不代表能写（权限可能是只读的）。
                    string probe = Path.Combine(candidate, ".write-test");
                    File.WriteAllText(probe, "ok");
                    File.Delete(probe);
                    return candidate;
                }
                catch { }
            }
            return null;
        }

        /// <summary>尝试用 WebView2 显示控制台；失败则保持原生界面。</summary>
        private void TryBrowser()
        {
            if (browserFailed || panelUrl == null) return;
            try
            {
                if (webView == null)
                {
                    webView = new Microsoft.Web.WebView2.WinForms.WebView2();
                    webView.Dock = DockStyle.Fill;
                    // 只在探测到确实可写时才指定数据目录；否则交给 WebView2 自己决定，
                    // 指定一个不可写的路径会让它直接初始化失败。
                    string dataDirectory = PickWritableDirectory();
                    if (dataDirectory != null)
                    {
                        var creation = new Microsoft.Web.WebView2.WinForms.CoreWebView2CreationProperties();
                        creation.UserDataFolder = dataDirectory;
                        webView.CreationProperties = creation;
                        StartupLog.Write("浏览器数据目录：" + dataDirectory);
                    }
                    webView.CoreWebView2InitializationCompleted += delegate (object sender, Microsoft.Web.WebView2.Core.CoreWebView2InitializationCompletedEventArgs args)
                    {
                        if (!args.IsSuccess)
                        {
                            string reason = args.InitializationException == null ? "未知原因" : args.InitializationException.Message;
                            StartupLog.Write("内嵌浏览器不可用，改用原生界面：" + reason);
                            UseFallback("内嵌浏览器不可用，已切换为原生界面");
                            return;
                        }
                        try { webView.CoreWebView2.Navigate(panelUrl); }
                        catch (Exception error) { StartupLog.Error(error); }
                    };
                    Controls.Add(webView);
                    webView.BringToFront();
                    webView.EnsureCoreWebView2Async(null);
                    return;
                }
                if (!browserFailed) webView.Source = new Uri(panelUrl);
            }
            catch (Exception error)
            {
                StartupLog.Write("内嵌浏览器初始化异常，改用原生界面：" + error.Message);
                UseFallback("内嵌浏览器不可用，已切换为原生界面");
            }
        }

        /// <summary>降级到原生界面：移除浏览器控件，让原生面板接管。</summary>
        private void UseFallback(string note)
        {
            browserFailed = true;
            try
            {
                BeginInvoke(new Action(delegate
                {
                    if (webView != null)
                    {
                        Controls.Remove(webView);
                        webView.Dispose();
                        webView = null;
                    }
                    NativePanel panel = EnsureFallback();
                    panel.SetNote(note);
                    panel.StartPolling();
                }));
            }
            catch { }
        }

        private void OnFailed(string message)
        {
            StartupLog.Write("启动未成功：" + message);
            try
            {
                BeginInvoke(new Action(delegate
                {
                    if (webView != null) { Controls.Remove(webView); webView.Dispose(); webView = null; }
                    browserFailed = true;
                    NativePanel panel = EnsureFallback();
                    panel.SetError(message);
                }));
            }
            catch { }
        }

        protected override void OnFormClosing(FormClosingEventArgs e)
        {
            // 点右上角关闭只收进托盘：程序在后台替手表提供服务，
            // 关掉窗口就等于手表断开，这与「随 DSH 启动」的预期相反。
            // 真正退出走托盘菜单，那里会置 reallyClosing。
            if (!reallyClosing)
            {
                e.Cancel = true;
                Hide();
                return;
            }
            if (tray != null)
            {
                tray.Visible = false;
                tray.Dispose();
                tray = null;
            }
            host.Stop();
            if (fallback != null) fallback.StopPolling();
            base.OnFormClosing(e);
        }
    }

    /// <summary>
    /// 控制台宿主：一个只绑 127.0.0.1 的极简 HTTP 服务，向界面提供状态并接受操作。
    ///
    /// 端口取 0 让系统分配，避免与用户其它程序冲突；地址通过事件回传。
    /// </summary>
    public class PanelHost
    {
        private readonly TcpListener listener;
        private Thread thread;
        private volatile bool stopping;

        private DshClient dsh;
        private RelayServer relay;
        private PublicTunnel tunnel;
        private readonly Action<string> log;
        private readonly string appDirectory;
        /// <summary>主窗口，用于弹「是否允许配对」的对话框（null 表示还没有窗口）。</summary>
        private Form uiForm;

        /// <summary>绑定主窗口，配对确认框需要它作为父窗口。</summary>
        public void AttachForm(Form form)
        {
            uiForm = form;
        }

        public event Action<string> UrlReady;
        public event Action<string> Failed;
        /// <summary>主题变了：参数为 true 表示切到了浅色。</summary>
        public event Action<bool> ThemeChanged;

        public bool TunnelBusy { get; private set; }

        public PanelHost()
            : this(delegate { }, AppDomain.CurrentDomain.BaseDirectory)
        {
        }

        /// <summary>取一份状态快照（原生界面与网页界面共用同一数据源）。</summary>
        public PanelState GetState()
        {
            var state = new PanelState();
            state.Running = relay != null && relay.Running;
            state.Port = relay == null ? 0 : relay.Port;
            state.PairingCode = relay == null ? "" : relay.PairingCode;
            if (relay != null) state.LanAddresses = relay.LanAddresses();
            state.PublicUrl = relay == null ? "" : (relay.PublicUrl ?? "");
            state.DeviceCount = relay == null ? 0 : relay.PairedDevices;
            state.UptimeMs = relay == null ? 0L : (long)(DateTime.Now - relay.StartedAt).TotalMilliseconds;
            state.DshPort = dsh == null ? 0 : dsh.Port;
            if (relay != null)
            {
                foreach (TokenInfo info in relay.IssuedTokens()) state.Tokens.Add(info.Token);
            }
            return state;
        }

        /// <summary>更换配对码（原生界面按钮用）。</summary>
        public void RotatePairingCode()
        {
            if (relay == null) return;
            relay.PairingCode = RelayServer.NewPairingCode();
            log("配对码已更换：" + relay.PairingCode);
        }

        /// <summary>切换一项设置并落盘（原生界面复选框用）。</summary>
        public void SetOptions(bool startWithWindows, bool followDsh, bool autoTunnel, string theme)
        {
            if (relay == null) return;
            relay.SetOptions(startWithWindows, followDsh, autoTunnel, theme);
            ApplyAutostart();
        }

        /// <summary>
        /// 开/关公网访问（原生界面按钮用）。
        /// @returns 需要提示给用户的文本；正常时返回空串。
        /// </summary>
        public string ToggleTunnel()
        {
            if (relay == null) return "中继尚未启动";
            try
            {
                if (!string.IsNullOrEmpty(relay.PublicUrl))
                {
                    if (tunnel != null) tunnel.Stop();
                    relay.PublicUrl = null;
                    log("已关闭公网访问");
                    return "";
                }
                return StartTunnel("");
            }
            finally
            {
                TunnelBusy = false;
            }
        }

        /// <summary>开启公网访问；失败时返回可展示的原因（成功返回空串）。</summary>
        private string StartTunnel(string unused)
        {
            if (relay == null) return "中继尚未启动";
            TunnelBusy = true;
            try
            {
                string binary = PublicTunnel.FindBinary(null, appDirectory);
                if (tunnel == null) tunnel = new PublicTunnel(binary, relay.Port, log);
                if (!tunnel.Available())
                {
                    return "找不到 cloudflared，无法开启公网访问。\n"
                        + "请把 cloudflared.exe 放到本程序同一目录后重试。";
                }
                string url = tunnel.Start(60000);
                relay.PublicUrl = url;
                log("公网访问已开启：" + url + "（手表连上后会自动换成这个地址）");
                return "";
            }
            catch (Exception error)
            {
                string detail = tunnel == null ? "" : tunnel.RecentOutput();
                return "开启公网访问失败：" + error.Message
                    + (detail.Length > 0 ? "\n\n" + detail : "");
            }
            finally
            {
                TunnelBusy = false;
            }
        }

        /// <summary>最近一次成功连接 DSH 时用的端口（供监视线程复用）。</summary>
        private int dshPortHint;

        public PanelHost(Action<string> log, string appDirectory)
        {
            this.log = log ?? delegate { };
            this.appDirectory = appDirectory;
            listener = new TcpListener(IPAddress.Loopback, 0);
        }

        public void Start()
        {
            var worker = new Thread(Run);
            worker.IsBackground = true;
            worker.Name = "relay-bootstrap";
            worker.Start();
        }

        public void Stop()
        {
            stopping = true;
            try { listener.Stop(); } catch { }
            try { if (tunnel != null) tunnel.Stop(); } catch { }
            try { if (relay != null) relay.Stop(); } catch { }
        }

        /// <summary>启动顺序：找 DSH → 建中继 → 起控制台。</summary>
        private void Run()
        {
            try
            {
                log("正在查找本机的 DSH…");
                DshInstance instance = DshLocator.Locate(null, log);
                if (instance == null)
                {
                    RaiseFailed("没有找到 DSH。请先启动 DSH 桌面版，然后重新打开本应用。");
                    return;
                }
                if (instance.Port <= 0)
                {
                    RaiseFailed("找到 DSH 配置（" + instance.Home + "），但它没有在运行。\n请先启动 DSH 桌面版。");
                    return;
                }

                log("已连接 DSH（127.0.0.1:" + instance.Port + "），配置目录：" + instance.Home);
                dshPortHint = instance.Port;
                dsh = new DshClient(instance.Home, instance.Port, instance.Secret, instance.SecretRaw);

                // 先验证一次鉴权是否可用，避免界面显示「已运行」但实际连不上。
                try
                {
                    var probe = new Dictionary<string, object>();
                    probe["_request"] = new Dictionary<string, object>();
                    dsh.Rpc("session/list", probe);
                }
                catch (DshException error)
                {
                    RaiseFailed("连接 DSH 失败：" + error.Message);
                    return;
                }

                // 状态目录随程序走：配对码与令牌存在程序目录的 relay-settings.json 里，
                // 重启后手表不用重新配对（用户明确要求「密钥不变，除非主动更改」）。
                // 端口可由环境变量覆盖：调试第二份程序时原版仍占着 19390，
                // 不换端口就只能看到「套接字地址只允许使用一次」。
                int listenPort = RelayServer.DefaultPort;
                try
                {
                    string overridePort = Environment.GetEnvironmentVariable("DSH_RELAY_PORT");
                    int parsedPort;
                    if (!string.IsNullOrEmpty(overridePort)
                        && int.TryParse(overridePort, out parsedPort)
                        && parsedPort > 0 && parsedPort <= 65535)
                    {
                        listenPort = parsedPort;
                        log("使用环境变量指定的端口：" + listenPort);
                    }
                }
                catch { }
                relay = new RelayServer(dsh, listenPort, log, appDirectory);
                // 一键配对：手表发起后由界面弹窗让用户确认（决定权在电脑端）。
                relay.PairingRequested = OnPairingRequested;

                listener.Start();
                int port = ((IPEndPoint)listener.LocalEndpoint).Port;
                // 登记自己的控制台端口：DSH 探测必须跳过它，否则会把自家页面
                // 当成 DSH API（本次故障就是这么来的）。
                DshLocator.MarkOwnPort(port);
                string url = "http://127.0.0.1:" + port.ToString(CultureInfo.InvariantCulture) + "/";
                log("控制台：" + url);
                RaiseReady(url);

                StartServing();
                StartWatchdog();
                ApplyAutostart();
                AcceptLoop();
            }
            catch (Exception error)
            {
                RaiseFailed("启动失败：" + error.Message);
            }
        }

        /// <summary>开始对外提供服务（中继 + 默认的公网通道）。</summary>
        private void StartServing()
        {
            if (relay == null || relay.Running) return;
            if (followingPaused)
            {
                log("跟随 DSH 模式：DSH 未运行，暂不对外服务");
                return;
            }

            // 恢复服务前必须重新定位 DSH。
            //
            // DSH 桌面版的端口是每次启动随机分配的。旧实现只在最初定位一次，
            // 暂停后直接复用那个 DshClient——DSH 重启换了端口，客户端仍然对着老端口发请求，
            // 于是手表端全部返回「无法连接到远程服务器」。实测日志里那串
            // 「127.0.0.1:53540 积极拒绝」就是这么来的。
            if (!RelocateDsh())
            {
                log("跟随 DSH 模式：暂时找不到 DSH，保持待命");
                followingPaused = true;
                return;
            }

            relay.Start();
            log("中继已启动，局域网端口：" + relay.Port);
            if (relay.AutoTunnel)
            {
                StartTunnelInBackground();
            }
        }

        /// <summary>
        /// 重新定位 DSH 并把中继切到新的客户端上。
        ///
        /// @returns 是否成功找到一个可用的 DSH。
        /// </summary>
        private bool RelocateDsh()
        {
            try
            {
                DshInstance instance = DshLocator.Locate(null, log);
                if (instance == null || instance.Port <= 0) return false;
                if (dsh != null && instance.Port == dsh.Port && instance.Home == dsh.Home)
                {
                    // 端口没变，沿用现有客户端即可。
                    return true;
                }

                var fresh = new DshClient(instance.Home, instance.Port, instance.Secret, instance.SecretRaw);
                var probe = new Dictionary<string, object>();
                probe["_request"] = new Dictionary<string, object>();
                fresh.Rpc("session/list", probe);

                log("DSH 端口已更新：" + instance.Port);
                dsh = fresh;
                dshPortHint = instance.Port;
                if (relay != null) relay.RebindDsh(fresh);
                return true;
            }
            catch (Exception error)
            {
                log("重新定位 DSH 失败：" + error.Message);
                return false;
            }
        }

        /// <summary>暂停对外服务（DSH 退出时）。</summary>
        private void StopServing()
        {
            if (relay == null || !relay.Running) return;
            try { if (tunnel != null) tunnel.Stop(); } catch { }
            if (relay != null) relay.PublicUrl = null;
            relay.Stop();
            log("跟随 DSH 模式：DSH 已退出，暂停对外服务（重新启动 DSH 会自动恢复）");
        }

        /// <summary>
        /// 公网通道在后台线程开：cloudflared 首次连接要几秒，偶遇限流还要退避重试，
        /// 不能卡住启动流程——局域网功能不该等它。
        /// </summary>
        private void StartTunnelInBackground()
        {
            if (TunnelBusy) return;
            var worker = new Thread(delegate ()
            {
                try
                {
                    string result = StartTunnel("");
                    if (!string.IsNullOrEmpty(result)) log("公网访问未开启：" + result);
                }
                catch (Exception error)
                {
                    log("开启公网访问失败：" + error.Message);
                }
            });
            worker.IsBackground = true;
            worker.Name = "relay-tunnel";
            worker.Start();
        }

        /// <summary>
        /// 监视 DSH 是否还在。
        ///
        /// 只在「跟随 DSH」模式下生效：DSH 退出就暂停对外服务，DSH 回来后自动恢复。
        /// 这样开机自启 + 随 DSH 启动同时勾选时，程序始终只有一个进程，
        /// 由这里负责在两种状态间切换，不会出现两个实例抢端口。
        /// </summary>
        private void StartWatchdog()
        {
            var timer = new System.Threading.Timer(delegate (object unused)
            {
                try
                {
                    if (stopping || relay == null || !relay.FollowDsh) return;
                    bool alive = DshLocator.IsRunning(dshPortHint);
                    if (alive && !relay.Running)
                    {
                        followingPaused = false;
                        StartServing();
                    }
                    else if (!alive && relay.Running)
                    {
                        followingPaused = true;
                        StopServing();
                    }
                }
                catch { }
            }, null, 5000, 5000);
            watchdog = timer;
        }

        private System.Threading.Timer watchdog;
        private volatile bool followingPaused;

        /// <summary>按设置写入/移除开机自启项。</summary>
        private void ApplyAutostart()
        {
            try
            {
                bool want = relay != null && relay.StartWithWindows;
                bool changed = Autostart.SetEnabled(want, Application.ExecutablePath);
                if (changed)
                {
                    log(want ? "已设置开机自启" : "已取消开机自启");
                }
            }
            catch (Exception error)
            {
                log("设置开机自启失败：" + error.Message);
            }
        }

        private void RaiseReady(string url)
        {
            var handler = UrlReady;
            if (handler != null) handler(url);
        }

        /// <summary>
        /// 手表请求一键配对时调用（可能来自网络线程）。
        ///
        /// 这里必须切回界面线程弹窗：WinForms 的 MessageBox 只能在 UI 线程用。
        /// 用户点「同意」后才发放一次性票据，手表凭它换令牌——决定权在电脑端。
        /// </summary>
        private void OnPairingRequested(RelayServer.PairRequest request)
        {
            try
            {
                var form = uiForm;
                if (form == null || form.IsDisposed)
                {
                    log("没有可用窗口确认配对，已忽略（请用配对码方式）");
                    return;
                }
                form.BeginInvoke(new Action(delegate
                {
                    try
                    {
                        string who = string.IsNullOrEmpty(request.DeviceName)
                            ? request.From
                            : (request.DeviceName + "（" + request.From + "）");
                        // 三选一：只同意一次 / 同意并记住 / 拒绝。
                        // 「记住」让这台手表以后再来配对时不再弹窗，所以它必须是
                        // 用户主动选的，不能做成默认。
                        string message = "有一台设备请求连接本机的中继：\n\n"
                            + who + "\n\n"
                            + "同意后它会自动获得局域网与公网的连接地址、以及访问令牌。\n\n"
                            + "选择「是并记住」会把这台设备保存为常用设备，"
                            + "以后它再配对时自动同意、不再弹窗。\n\n"
                            + "只在你确实正在手表上操作时同意。";
                        using (var dialog = new PairingDialog(who, message))
                        {
                            dialog.ShowDialog(form);
                            if (dialog.Choice == PairingDialog.Answer.Approve
                                || dialog.Choice == PairingDialog.Answer.Remember)
                            {
                                relay.ApprovePairing(request.Id);
                                if (dialog.Choice == PairingDialog.Answer.Remember)
                                {
                                    relay.TrustDevice(request.DeviceId, request.DeviceName, request.From);
                                }
                            }
                            else
                            {
                                relay.DenyPairing(request.Id);
                            }
                        }
                    }
                    catch (Exception error)
                    {
                        log("确认配对时出错：" + error.Message);
                    }
                }));
            }
            catch (Exception error)
            {
                log("无法弹出配对确认：" + error.Message);
            }
        }

        private void RaiseFailed(string message)
        {
            var handler = Failed;
            if (handler != null) handler(message);
        }

        private void AcceptLoop()
        {
            while (!stopping)
            {
                TcpClient client = null;
                try { client = listener.AcceptTcpClient(); }
                catch { if (stopping) return; continue; }

                TcpClient captured = client;
                var worker = new Thread(delegate () { Handle(captured); });
                worker.IsBackground = true;
                worker.Start();
            }
        }

        private void Handle(TcpClient client)
        {
            try
            {
                client.ReceiveTimeout = 30000;
                client.SendTimeout = 30000;
                using (var stream = client.GetStream())
                {
                    Http.Request request = Http.ReadRequest(stream);
                    if (request == null) return;
                    Http.Response response = Route(request);
                    Http.WriteResponse(stream, response);
                }
            }
            catch { }
            finally
            {
                try { client.Close(); } catch { }
            }
        }

        private Http.Response Route(Http.Request request)
        {
            string path = request.Path.TrimStart('/');

            if (path.Length == 0) return Panel();
            if (path == "state") return State();
            if (path == "rotate-code" && request.Method == "POST") return RotateCode();
            if (path == "settings")
            {
                // 控制台只绑 127.0.0.1，外部设备碰不到这两个端点。
                if (request.Method == "POST" || request.Method == "GET") return Settings(request);
            }
            if (path == "trusted")
            {
                if (request.Method == "GET" || request.Method == "POST") return TrustedDevices(request);
            }
            if (path == "tunnel")
            {
                if (request.Method == "POST") return StartTunnel();
                if (request.Method == "DELETE") return StopTunnel();
            }
            return Http.Json(404, new Dictionary<string, object> { { "ok", false }, { "error", "未知接口" } });
        }

        /// <summary>返回内嵌的控制台页面。</summary>
        private Http.Response Panel()
        {
            try
            {
                string text = Embedded.ReadText("panel.html");
                return Http.Text(200, "text/html; charset=utf-8", text);
            }
            catch (Exception error)
            {
                return Http.Text(500, "text/plain; charset=utf-8", "控制台页面读取失败：" + error.Message);
            }
        }

        private Http.Response State()
        {
            var state = new Dictionary<string, object>();
            state["running"] = relay != null && relay.Running;
            state["port"] = relay == null ? 0 : relay.Port;
            state["pairingCode"] = relay == null ? "" : relay.PairingCode;
            state["lanAddresses"] = relay == null ? new List<object>() : ToObjects(relay.LanAddresses());
            state["publicUrl"] = relay == null ? "" : (relay.PublicUrl ?? "");
            state["deviceCount"] = relay == null ? 0 : relay.PairedDevices;
            state["uptimeMs"] = relay == null ? 0L : (long)(DateTime.Now - relay.StartedAt).TotalMilliseconds;
            state["dshPort"] = dsh == null ? 0 : dsh.Port;
            state["tunnelBusy"] = TunnelBusy;
            // 令牌一并给出：用户可以直接用 curl 等客户端连中继，不必依赖手表。
            var tokens = new List<object>();
            if (relay != null)
            {
                foreach (TokenInfo info in relay.IssuedTokens())
                {
                    var entry = new Dictionary<string, object>();
                    entry["token"] = info.Token;
                    entry["issuedAt"] = info.IssuedAt;
                    tokens.Add(entry);
                }
            }
            state["tokens"] = tokens;
            state["startWithWindows"] = relay != null && relay.StartWithWindows;
            state["followDsh"] = relay != null && relay.FollowDsh;
            state["autoTunnel"] = relay == null || relay.AutoTunnel;
            state["trustedCount"] = relay == null ? 0 : relay.TrustedCount;
            state["paused"] = followingPaused;
            state["theme"] = relay == null ? "dark" : relay.Theme;
            var trustedList = new List<object>();
            if (relay != null)
            {
                foreach (RelaySettings.TrustedDevice device in relay.TrustedDevices())
                {
                    var entry = new Dictionary<string, object>();
                    entry["deviceId"] = device.DeviceId;
                    entry["name"] = device.Name ?? "";
                    entry["lastAddress"] = device.LastAddress ?? "";
                    entry["since"] = device.Since;
                    entry["lastSeen"] = device.LastSeen;
                    trustedList.Add(entry);
                }
            }
            state["trustedDevices"] = trustedList;
            return Http.Json(200, state);
        }

        private static List<object> ToObjects(List<string> values)
        {
            var list = new List<object>();
            foreach (string value in values) list.Add(value);
            return list;
        }

        private Http.Response RotateCode()
        {
            if (relay != null)
            {
                relay.PairingCode = RelayServer.NewPairingCode();
                log("配对码已更换：" + relay.PairingCode);
            }
            var result = new Dictionary<string, object>();
            result["ok"] = true;
            result["pairingCode"] = relay == null ? "" : relay.PairingCode;
            return Http.Json(200, result);
        }

        private Http.Response StartTunnel()
        {
            if (relay == null) return Fail("中继尚未启动");
            if (!string.IsNullOrEmpty(relay.PublicUrl)) return Fail("公网访问已经开启");

            TunnelBusy = true;
            try
            {
                string binary = PublicTunnel.FindBinary(null, appDirectory);
                if (tunnel == null) tunnel = new PublicTunnel(binary, relay.Port, log);
                if (!tunnel.Available())
                {
                    return Fail("找不到 cloudflared，无法开启公网访问。\n"
                        + "请把 cloudflared.exe 放在程序目录下，或安装后重试。\n"
                        + "（开发时可直接用 relay/ --tunnel）");
                }
                string url = tunnel.Start(60000);
                relay.PublicUrl = url;
                var result = new Dictionary<string, object>();
                result["ok"] = true;
                result["url"] = url;
                return Http.Json(200, result);
            }
            catch (Exception error)
            {
                string detail = tunnel != null ? tunnel.RecentOutput() : "";
                return Fail("开启公网访问失败：" + error.Message + (detail.Length > 0 ? "\n" + detail : ""));
            }
            finally
            {
                TunnelBusy = false;
            }
        }

        private Http.Response StopTunnel()
        {
            if (tunnel != null) tunnel.Stop();
            if (relay != null) relay.PublicUrl = null;
            var result = new Dictionary<string, object>();
            result["ok"] = true;
            return Http.Json(200, result);
        }

        /// <summary>读取/写入设置（开机自启、跟随 DSH、自动开公网）。</summary>
        private Http.Response Settings(Http.Request request)
        {
            if (relay == null) return Fail("中继尚未启动");

            if (request.Method == "POST")
            {
                string body = request.BodyText ?? "";
                bool startWithWindows = relay.StartWithWindows;
                bool followDsh = relay.FollowDsh;
                bool autoTunnel = relay.AutoTunnel;
                ReadFlag(body, "startWithWindows", ref startWithWindows);
                ReadFlag(body, "followDsh", ref followDsh);
                ReadFlag(body, "autoTunnel", ref autoTunnel);

                string theme = ParseString(ParseJsonSafe(body), "theme");
                relay.SetOptions(startWithWindows, followDsh, autoTunnel, theme);
                ApplyAutostart();
                // 网页自己会切，但窗口边框与降级界面也要跟着换，否则浅色页面
                // 被一块深色边框框住，看起来像没换成功。
                var handler = ThemeChanged;
                if (handler != null) handler(relay.Theme == "light");
                log("设置已更新：开机自启=" + (startWithWindows ? "开" : "关")
                    + "，随 DSH 启动=" + (followDsh ? "开" : "关")
                    + "，自动开公网=" + (autoTunnel ? "开" : "关"));
            }

            var result = new Dictionary<string, object>();
            result["ok"] = true;
            result["startWithWindows"] = relay.StartWithWindows;
            result["followDsh"] = relay.FollowDsh;
            result["autoTunnel"] = relay.AutoTunnel;
            result["theme"] = relay.Theme;
            result["autostartRegistered"] = Autostart.IsEnabled();
            return Http.Json(200, result);
        }

        /// <summary>从 JSON 里读一个布尔字段；没给就保持原值。</summary>
        private static void ReadFlag(string body, string name, ref bool target)
        {
            if (string.IsNullOrEmpty(body)) return;
            var data = ParseJsonSafe(body);
            if (data == null) return;
            object value;
            if (!data.TryGetValue(name, out value) || value == null) return;
            bool parsed;
            if (bool.TryParse(Convert.ToString(value), out parsed)) target = parsed;
        }

        private static Dictionary<string, object> ParseJsonSafe(string body)
        {
            try
            {
                var serializer = new System.Web.Script.Serialization.JavaScriptSerializer();
                return serializer.DeserializeObject(body) as Dictionary<string, object>;
            }
            catch
            {
                return null;
            }
        }

        /// <summary>从解析好的 JSON 里读一个字符串字段；缺失返回空串。</summary>
        private static string ParseString(Dictionary<string, object> data, string name)
        {
            if (data == null) return "";
            object value;
            if (!data.TryGetValue(name, out value) || value == null) return "";
            string text = Convert.ToString(value);
            return text == null ? "" : text;
        }

        /// <summary>常用设备：列表与撤销（忘记后该设备下次配对会重新弹窗）。</summary>
        private Http.Response TrustedDevices(Http.Request request)
        {
            if (relay == null) return Fail("中继尚未启动");

            if (request.Method == "POST")
            {
                string body = request.BodyText ?? "";
                var data = ParseJsonSafe(body);
                string id = data != null && data.ContainsKey("deviceId") ? Convert.ToString(data["deviceId"]) : "";
                if (string.IsNullOrEmpty(id)) return Fail("缺少设备标识");
                relay.ForgetDevice(id);
            }

            var items = new List<object>();
            foreach (RelaySettings.TrustedDevice device in relay.TrustedDevices())
            {
                var entry = new Dictionary<string, object>();
                entry["deviceId"] = device.DeviceId;
                entry["name"] = device.Name ?? "";
                entry["lastAddress"] = device.LastAddress ?? "";
                entry["since"] = device.Since;
                entry["lastSeen"] = device.LastSeen;
                items.Add(entry);
            }
            var result = new Dictionary<string, object>();
            result["ok"] = true;
            result["items"] = items;
            return Http.Json(200, result);
        }

        private static Http.Response Fail(string message)
        {
            var result = new Dictionary<string, object>();
            result["ok"] = false;
            result["error"] = message;
            return Http.Json(200, result);
        }
    }

    /// <summary>
    /// 读取随程序嵌入的资源。
    ///
    /// 界面文件打进 exe 里，避免分发时丢文件；开发时也能直接从磁盘读，便于改界面。
    /// </summary>
    public static class Embedded
    {
        public static string ReadText(string name)
        {
            // 开发时优先读磁盘上的同名文件（改动立即生效）。
            try
            {
                string path = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, name);
                if (File.Exists(path)) return File.ReadAllText(path, Encoding.UTF8);
            }
            catch { }

            Assembly assembly = Assembly.GetExecutingAssembly();
            foreach (string resource in assembly.GetManifestResourceNames())
            {
                if (resource.EndsWith("." + name, StringComparison.OrdinalIgnoreCase)
                    || resource.Equals(name, StringComparison.OrdinalIgnoreCase))
                {
                    using (Stream stream = assembly.GetManifestResourceStream(resource))
                    {
                        if (stream == null) continue;
                        using (var reader = new StreamReader(stream, Encoding.UTF8))
                        {
                            return reader.ReadToEnd();
                        }
                    }
                }
            }
            throw new FileNotFoundException("找不到内嵌资源：" + name);
        }
    }

    internal static class Program
    {
        [STAThread]
        private static void Main(string[] args)
        {
            // 这一步必须在最前面：StartupLog 本身不依赖任何东西，
            // 而它之后的每一步都可能因为依赖没释放好而失败。
            StartupLog.StartNew();
            StartupLog.Write("准备释放内嵌依赖…");

            // 必须最先执行：把内嵌的 WebView2 依赖释放到磁盘并注册解析路径。
            // 晚于任何 WebView2 类型的 JIT 就会加载失败（找不到 DLL）。
            try
            {
                Bootstrap.Init();
                StartupLog.Write("内嵌依赖就绪。");
            }
            catch (Exception error)
            {
                StartupLog.Error(error);
            }

            // 任何未捕获异常都记到 startup.log：GUI 程序闪退时用户拿不到别的东西。
            AppDomain.CurrentDomain.UnhandledException += delegate (object sender, UnhandledExceptionEventArgs e)
            {
                StartupLog.Write("未捕获异常（程序即将退出）：");
                StartupLog.Error(e.ExceptionObject as Exception);
            };
            Application.ThreadException += delegate (object sender, System.Threading.ThreadExceptionEventArgs e)
            {
                StartupLog.Error(e.Exception);
            };

            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);

            try
            {
                // --background 由开机自启项传入：启动后只驻留托盘，不弹窗。
                Application.Run(new MainForm(Autostart.LaunchedInBackground(args)));
                StartupLog.Write("窗口已关闭，程序退出。");
            }
            catch (Exception error)
            {
                StartupLog.Error(error);
                MessageBox.Show("DSH 中继启动失败：\n" + error.Message
                    + "\n\n详细信息见程序目录下的 startup.log", "DSH 中继",
                    MessageBoxButtons.OK, MessageBoxIcon.Error);
            }
        }
    }
}
