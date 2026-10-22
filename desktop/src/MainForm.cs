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

        public MainForm()
        {
            Text = "DSH 中继";
            AutoScaleMode = AutoScaleMode.Dpi;
            StartPosition = FormStartPosition.CenterScreen;
            BackColor = Color.FromArgb(11, 13, 16);
            Font = new Font("Microsoft YaHei UI", 9f);
            ApplyDisplaySize();

            // 先摆好原生界面：它同时也是内嵌浏览器失败时的后备。
            fallback = new NativePanel();
            fallback.Dock = DockStyle.Fill;
            Controls.Add(fallback);

            host = new PanelHost(StartupLog.Write, AppDomain.CurrentDomain.BaseDirectory);
            host.AttachForm(this);
            host.UrlReady += OnPanelReady;
            host.Failed += OnFailed;
            host.Start();
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
                // 原生界面从一开始就在跑，这里只让浏览器盖到它上面。
                fallback.Attach(host);
                TryBrowser();
            }));
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
                    fallback.SetNote(note);
                    fallback.BringToFront();
                    fallback.StartPolling();
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
                    fallback.Attach(host);
                    fallback.SetError(message);
                    fallback.BringToFront();
                }));
            }
            catch { }
        }

        protected override void OnFormClosing(FormClosingEventArgs e)
        {
            host.Stop();
            fallback.StopPolling();
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

                string binary = PublicTunnel.FindBinary(null, appDirectory);
                if (tunnel == null) tunnel = new PublicTunnel(binary, relay.Port, log);
                if (!tunnel.Available())
                {
                    return "找不到 cloudflared，无法开启公网访问。\n"
                        + "请把 cloudflared.exe 放到本程序同一目录后重试。";
                }
                TunnelBusy = true;
                string url = tunnel.Start(60000);
                relay.PublicUrl = url;
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
                relay = new RelayServer(dsh, RelayServer.DefaultPort, log, appDirectory);
                // 一键配对：手表发起后由界面弹窗让用户确认（决定权在电脑端）。
                relay.PairingRequested = OnPairingRequested;
                relay.Start();
                log("中继已启动，局域网端口：" + relay.Port);

                listener.Start();
                int port = ((IPEndPoint)listener.LocalEndpoint).Port;
                string url = "http://127.0.0.1:" + port.ToString(CultureInfo.InvariantCulture) + "/";
                log("控制台：" + url);
                RaiseReady(url);

                AcceptLoop();
            }
            catch (Exception error)
            {
                RaiseFailed("启动失败：" + error.Message);
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
                        string message = "有一台设备请求连接本机的中继：\n\n"
                            + who + "\n\n"
                            + "同意后它会自动获得连接地址与访问令牌，"
                            + "不必再输入配对码。\n\n"
                            + "只在你确实正在手表上操作时同意。";
                        DialogResult answer = MessageBox.Show(form, message, "配对请求",
                            MessageBoxButtons.YesNo, MessageBoxIcon.Question,
                            MessageBoxDefaultButton.Button2);
                        if (answer == DialogResult.Yes)
                        {
                            relay.ApprovePairing(request.Id);
                        }
                        else
                        {
                            relay.DenyPairing(request.Id);
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
                Application.Run(new MainForm());
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
