using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;
using System.Threading;
using System.Web.Script.Serialization;

namespace DshRelay
{
    /// <summary>一个已发放的配对令牌（供控制台展示）。</summary>
    public class TokenInfo
    {
        public string Token;
        /// <summary>发放时间（Unix 毫秒）。</summary>
        public long IssuedAt;
    }

    /// <summary>
    /// 面向手表的 HTTP 服务：把 DSH 的复杂协议翻译成手表能用的简单接口，
    /// 并做数据裁剪与配对保护。
    ///
    /// 设计取舍与手表版中继一致：
    /// - 手表不说 DSH 协议（Typert 信封、authority 绑定的 cookie、数百 KB 的原始响应
    ///   都不适合手表），差异在这里吸收；
    /// - 监听 0.0.0.0 必须配口令，否则同网段任何设备都能拿到 DSH 的完全控制权；
    /// - 用 UDP 广播让手表自动发现，不需要手输 IP。
    /// </summary>
    public class RelayServer : IDisposable
    {
        public const int DefaultPort = 19390;
        private const int DiscoveryPort = 19391;
        private const string ServiceTag = "dsh-watch-relay/1";
        private const long TokenTtlMs = 30L * 24 * 60 * 60 * 1000;
        // 会话数与每页消息数。
        //
        // 用户要求「能显示 DSH 里的全部项目和会话以及每个会话的全部内容」，
        // 所以这两个上限放得很宽：会话列表一次给 500 条（足够覆盖实际规模），
        // 单页消息 120 条；更早的历史由手表的「看更早的内容」继续往前翻。
        private const int MaxSessions = 500;
        private const int MaxMessages = 120;

        /// <summary>
        /// DSH 客户端。**不能是 readonly**：DSH 重启会换随机端口，
        /// 跟随模式恢复时要换成新客户端（见 RebindDsh），否则请求会打到旧端口。
        /// </summary>
        private DshClient dsh;
        private readonly JavaScriptSerializer json;
        private readonly object gate = new object();
        private readonly Dictionary<string, long> tokens = new Dictionary<string, long>();
        private readonly Action<string> log;

        private TcpListener listener;
        private Thread acceptThread;
        /// <summary>接收手表查询的 socket（绑定 19391）。</summary>
        private UdpClient beacon;
        /// <summary>专门用来发广播与单播回复的 socket（不绑定固定端口）。</summary>
        private UdpClient sender;
        private Timer beaconTimer;
        private volatile bool stopping;

        /// <summary>
        /// 6 位配对码。
        ///
        /// **默认保持不变**：从设置文件读出来，只有用户主动点「换一个配对码」才变。
        /// 以前每次启动都随机生成，导致手表每次都要重新配一遍。
        /// </summary>
        public string PairingCode { get; set; }
        public int Port { get; private set; }
        public bool Running { get; private set; }
        /// <summary>公网地址（隧道就绪后由界面层注入）。</summary>
        public string PublicUrl { get; set; }
        public DateTime StartedAt { get; private set; }
        /// <summary>已配对设备数。</summary>
        public int PairedDevices { get { lock (gate) { return tokens.Count; } } }

        /// <summary>设置文件的存放目录（空表示不持久化）。</summary>
        private readonly string stateDirectory;

        public RelayServer(DshClient dsh, int port, Action<string> log)
            : this(dsh, port, log, null)
        {
        }

        /// <summary>
        /// @param stateDirectory 设置文件目录；给出时会读取/保存配对码与令牌，
        ///        使重启后手表不必重新配对。传 null 则不落盘（测试用）。
        /// </summary>
        public RelayServer(DshClient dsh, int port, Action<string> log, string stateDirectory)
        {
            this.dsh = dsh;
            this.Port = port;
            this.log = log ?? delegate { };
            this.json = new JavaScriptSerializer();
            this.json.MaxJsonLength = 64 * 1024 * 1024;
            this.stateDirectory = stateDirectory;

            RelaySettings settings = stateDirectory == null
                ? new RelaySettings()
                : RelaySettings.Load(stateDirectory);

            // 配对码优先沿用存档；没有才生成一个并存下来。
            this.PairingCode = string.IsNullOrEmpty(settings.PairingCode)
                ? NewPairingCode()
                : settings.PairingCode;

            // 恢复此前发过且未过期的令牌，手表就能继续用原令牌。
            long now = NowMs();
            foreach (var pair in settings.Tokens)
            {
                if (now - pair.Value <= TokenTtlMs) tokens[pair.Key] = pair.Value;
            }

            this.trusted = settings.TrustedDevices;
            this.StartWithWindows = settings.StartWithWindows;
            this.FollowDsh = settings.FollowDsh;
            this.AutoTunnel = settings.AutoTunnel;
            this.Theme = settings.Theme;

            Persist();
            if (tokens.Count > 0)
            {
                log("已恢复 " + tokens.Count + " 个配对令牌，配对码沿用 " + this.PairingCode);
            }
            if (trusted.Count > 0)
            {
                log("已记住 " + trusted.Count + " 台常用设备，它们再来配对会自动同意");
            }
        }

        /// <summary>已记住的常用设备（按设备标识索引）。</summary>
        private readonly Dictionary<string, RelaySettings.TrustedDevice> trusted;
        private readonly object trustedGate = new object();

        /// <summary>是否随 Windows 登录自启（从设置读出，供界面显示）。</summary>
        public bool StartWithWindows { get; private set; }
        /// <summary>是否跟随 DSH 启停（从设置读出，供界面显示）。</summary>
        public bool FollowDsh { get; private set; }
        /// <summary>是否自动开启公网访问。</summary>
        public bool AutoTunnel { get; private set; }
        /// <summary>界面主题：dark / light。</summary>
        public string Theme { get; private set; }

        /// <summary>当前记住的设备数。</summary>
        public int TrustedCount { get { lock (trustedGate) { return trusted.Count; } } }

        /// <summary>列出已记住的设备（供控制台展示与撤销）。</summary>
        public List<RelaySettings.TrustedDevice> TrustedDevices()
        {
            lock (trustedGate)
            {
                return new List<RelaySettings.TrustedDevice>(trusted.Values);
            }
        }

        /// <summary>是否已经记住这台设备。</summary>
        public bool IsTrusted(string deviceId)
        {
            if (string.IsNullOrEmpty(deviceId)) return false;
            lock (trustedGate) { return trusted.ContainsKey(deviceId); }
        }

        /// <summary>记住一台设备，之后它的一键配对会自动同意。</summary>
        public void TrustDevice(string deviceId, string name, string address)
        {
            if (string.IsNullOrEmpty(deviceId)) return;
            lock (trustedGate)
            {
                RelaySettings.TrustedDevice device;
                if (!trusted.TryGetValue(deviceId, out device))
                {
                    device = new RelaySettings.TrustedDevice();
                    device.DeviceId = deviceId;
                    device.Since = NowMs();
                    trusted[deviceId] = device;
                }
                if (!string.IsNullOrEmpty(name)) device.Name = name;
                if (!string.IsNullOrEmpty(address)) device.LastAddress = address;
                device.LastSeen = NowMs();
            }
            log("已记住设备" + (string.IsNullOrEmpty(name) ? "" : "「" + name + "」") + "，以后它配对会自动同意");
            Persist();
        }

        /// <summary>忘掉一台设备：它下次配对会重新弹窗确认。</summary>
        public void ForgetDevice(string deviceId)
        {
            if (string.IsNullOrEmpty(deviceId)) return;
            bool removed;
            lock (trustedGate) { removed = trusted.Remove(deviceId); }
            if (!removed) return;
            log("已忘掉一台常用设备，它下次配对需要重新确认");
            Persist();
        }

        /// <summary>改一项设置并立刻落盘。</summary>
        public void SetOptions(bool startWithWindows, bool followDsh, bool autoTunnel, string theme)
        {
            StartWithWindows = startWithWindows;
            FollowDsh = followDsh;
            AutoTunnel = autoTunnel;
            if (!string.IsNullOrEmpty(theme)) Theme = theme == "light" ? "light" : "dark";
            Persist();
        }

        /// <summary>
        /// 一次通过一次性票据换令牌的配对请求。
        ///
        /// 流程：手表在电脑上点了「同意」之后才拿到 grant，用它来换令牌。
        /// 这样「一键配对」的**决定权在电脑端**——手表不能自己把自己配上。
        /// </summary>
        private readonly Dictionary<string, long> grants = new Dictionary<string, long>();

        /// <summary>一次性票据有效期（用户点了同意但手表没及时来换的情况）。</summary>
        private const long GrantTtlMs = 120L * 1000;

        /// <summary>发起一键配对的回调：由界面层弹出确认框，用户同意后调用 ApprovePairing。</summary>
        public Action<PairRequest> PairingRequested;

        /// <summary>一张待用户确认的配对请求。</summary>
        public class PairRequest
        {
            public string Id = "";
            /// <summary>发起方（手表的）IP，用于在弹窗里说明「谁在请求」。</summary>
            public string From = "";
            /// <summary>手表自报的名字（可能为空）。</summary>
            public string DeviceName = "";
            /// <summary>手表自己生成的设备标识（可为空：老版本手表不带这个字段）。</summary>
            public string DeviceId = "";
            /// <summary>这台设备是否已被记住（记住时会自动同意，不需要弹窗）。</summary>
            public bool Trusted;
            public long CreatedAt;
        }

        /// <summary>
        /// 手表通过 HTTP 请求一键配对：记下请求并通知界面弹窗。
        ///
        /// 返回 requestId 给手表去轮询结果；用户同意后手表的轮询会拿到 grant。
        ///
        /// **这是无需令牌的端点**（手表还没配对），因此必须自己做防护：
        /// - 同一 IP 冷却期内只受理一次，防止有人写脚本反复触发把弹窗刷屏；
        /// - 待确认请求总量设上限，防止内存被灌满；
        /// - 真正的放行仍由用户在电脑上点「是」决定，这里只防骚扰。
        /// </summary>
        private Http.Response RequestPairing(Http.Request request)
        {
            string from = request.ClientAddress ?? "";
            long now = NowMs();

            lock (gate)
            {
                long last;
                if (pairingCooldown.TryGetValue(from, out last) && now - last < PairCooldownMs)
                {
                    log("忽略来自 " + from + " 的重复配对请求（冷却中）");
                    var busy = new Dictionary<string, object>();
                    busy["ok"] = false;
                    busy["error"] = "刚刚已请求过，请稍等片刻";
                    return Http.Json(429, busy);
                }
                if (pairingRequests.Count >= MaxPendingPairs)
                {
                    var full = new Dictionary<string, object>();
                    full["ok"] = false;
                    full["error"] = "待确认的配对请求太多，请在电脑上处理后再试";
                    return Http.Json(429, full);
                }
                pairingCooldown[from] = now;
                // 顺手清掉冷却表里的老记录，避免长期运行越积越多。
                if (pairingCooldown.Count > 200)
                {
                    var stale = new List<string>();
                    foreach (var pair in pairingCooldown)
                    {
                        if (now - pair.Value > PairCooldownMs * 10) stale.Add(pair.Key);
                    }
                    foreach (string key in stale) pairingCooldown.Remove(key);
                }
            }
            // 每次请求顺带清一次过期票据：这是配对功能里唯一稳定的调用点。
            PruneGrants();

            var body = ParseJson(request.BodyText) as Dictionary<string, object>;
            var entry = new PairRequest();
            entry.Id = Guid.NewGuid().ToString("N");
            entry.From = from;
            entry.DeviceName = body != null && body.ContainsKey("name") ? Convert.ToString(body["name"]) : "";
            entry.DeviceId = body != null && body.ContainsKey("deviceId") ? Convert.ToString(body["deviceId"]) : "";
            entry.CreatedAt = now;
            entry.Trusted = IsTrusted(entry.DeviceId);

            if (entry.Trusted)
            {
                // 常用设备：直接放行，不弹窗。仍然生成独立票据、仍然只能换一次令牌，
                // 所以「记住」只是省掉确认这一步，不改变配对本身的安全模型。
                var remembered = new PairRequest();
                remembered.Id = entry.Id;
                remembered.From = entry.From;
                remembered.DeviceName = entry.DeviceName;
                remembered.DeviceId = entry.DeviceId;
                remembered.Trusted = true;
                remembered.CreatedAt = now;
                ApprovePairing(entry.Id);
                TouchTrusted(entry.DeviceId, entry.From);
                log("常用设备「" + (string.IsNullOrEmpty(entry.DeviceName) ? entry.From : entry.DeviceName)
                    + "」请求配对，已自动同意");

                var auto = new Dictionary<string, object>();
                auto["ok"] = true;
                auto["requestId"] = entry.Id;
                auto["trusted"] = true;
                return Http.Json(200, auto);
            }

            lock (gate) { pairingRequests[entry.Id] = entry; }
            log("收到手表的一键配对请求（来自 " + entry.From + "），等待在电脑上确认");

            var handler = PairingRequested;
            if (handler != null)
            {
                try { handler(entry); }
                catch (Exception error) { log("弹出配对确认失败：" + error.Message); }
            }
            else
            {
                log("没有界面可以确认配对请求，请用配对码方式配对");
            }

            var result = new Dictionary<string, object>();
            result["ok"] = true;
            result["requestId"] = entry.Id;
            result["trusted"] = false;
            return Http.Json(200, result);
        }

        /// <summary>更新常用设备的最近出现时间与地址。</summary>
        private void TouchTrusted(string deviceId, string address)
        {
            if (string.IsNullOrEmpty(deviceId)) return;
            lock (trustedGate)
            {
                RelaySettings.TrustedDevice device;
                if (!trusted.TryGetValue(deviceId, out device)) return;
                device.LastSeen = NowMs();
                if (!string.IsNullOrEmpty(address)) device.LastAddress = address;
            }
        }

        /// <summary>手表轮询配对结果：等待 / 已同意（返回 grant）/ 被拒绝。</summary>
        private Http.Response PairingResult(Http.Request request)
        {
            string id = request.Query.ContainsKey("id") ? request.Query["id"] : "";
            string grant;
            string state;
            lock (gate)
            {
                if (pairingDenied.ContainsKey(id))
                {
                    state = "denied";
                    pairingDenied.Remove(id);
                    grant = "";
                }
                else if (approvedRequests.ContainsKey(id))
                {
                    // grant 是独立生成的随机值，**不能**直接用 requestId：
                    // requestId 是明文发给请求方的，同网段的人拿到它就能换令牌，
                    // 等于绕过用户在电脑上的确认。
                    state = "approved";
                    approvedRequests.TryGetValue(id, out grant);
                }
                else if (NowMs() - GrantTtlMs > RequestAge(id))
                {
                    state = "expired";
                    grant = "";
                }
                else
                {
                    state = "waiting";
                    grant = "";
                }
            }

            var result = new Dictionary<string, object>();
            result["ok"] = true;
            result["state"] = state;
            if (grant.Length > 0) result["grant"] = grant;
            return Http.Json(200, result);
        }

        private long RequestAge(string id)
        {
            lock (gate)
            {
                PairRequest entry;
                return pairingRequests.TryGetValue(id, out entry) ? entry.CreatedAt : 0L;
            }
        }

        private readonly Dictionary<string, PairRequest> pairingRequests = new Dictionary<string, PairRequest>();
        private readonly Dictionary<string, bool> pairingDenied = new Dictionary<string, bool>();
        /// <summary>已同意但还没被换走的票据：requestId → grant（随机值）。</summary>
        private readonly Dictionary<string, string> approvedRequests = new Dictionary<string, string>();
        /// <summary>每个来源 IP 上次请求配对的时间，用于冷却。</summary>
        private readonly Dictionary<string, long> pairingCooldown = new Dictionary<string, long>();
        /// <summary>同一 IP 的配对请求冷却时间。</summary>
        private const long PairCooldownMs = 15L * 1000;
        /// <summary>同时待确认的配对请求上限。</summary>
        private const int MaxPendingPairs = 3;

        /// <summary>用户在电脑上点了「同意」：发放一次性票据并与请求 Id 关联。</summary>
        public void ApprovePairing(string requestId)
        {
            if (string.IsNullOrEmpty(requestId)) return;
            // 票据必须是独立随机值：requestId 会明文回给请求方，
            // 拿它当票据等于把「用户确认」这个环节架空了。
            string ticket = NewGrant();
            lock (gate)
            {
                approvedRequests[requestId] = ticket;
                grants[ticket] = NowMs();
                pairingRequests.Remove(requestId);
            }
            log("已同意配对请求 " + requestId);
        }

        /// <summary>生成一张不可猜测的一次性票据。</summary>
        private static string NewGrant()
        {
            var bytes = new byte[24];
            using (var rng = new RNGCryptoServiceProvider()) { rng.GetBytes(bytes); }
            return DshLocator.Base64UrlEncode(bytes);
        }

        /// <summary>用户在电脑上点了「拒绝」。</summary>
        public void DenyPairing(string requestId)
        {
            if (string.IsNullOrEmpty(requestId)) return;
            lock (gate)
            {
                pairingDenied[requestId] = true;
                pairingRequests.Remove(requestId);
            }
            log("已拒绝配对请求 " + requestId);
        }

        /// <summary>取走并作废一张票据（一次性）。</summary>
        private bool ConsumeGrant(string grant)
        {
            lock (gate)
            {
                if (!grants.ContainsKey(grant)) return false;
                grants.Remove(grant);
                // 对应的 requestId → grant 映射也一并清掉，避免内存里留下永久有效的记录。
                var dead = new List<string>();
                foreach (var pair in approvedRequests)
                {
                    if (pair.Value == grant) dead.Add(pair.Key);
                }
                foreach (string key in dead) approvedRequests.Remove(key);
                return true;
            }
        }

        /// <summary>
        /// 清掉过期的一次性票据。
        ///
        /// 用户点了「同意」但手表没来换（关掉了、断网了）时，票据会一直留着。
        /// 不清理的话：内存缓慢增长，且这些旧票据**永久有效**——
        /// 谁拿到就能换令牌，绕过时间限制。
        /// </summary>
        private void PruneGrants()
        {
            long now = NowMs();
            lock (gate)
            {
                var stale = new List<string>();
                foreach (var pair in grants)
                {
                    if (now - pair.Value > GrantTtlMs) stale.Add(pair.Key);
                }
                foreach (string key in stale) grants.Remove(key);
                if (stale.Count > 0)
                {
                    var deadRequests = new List<string>();
                    foreach (var pair in approvedRequests)
                    {
                        if (!grants.ContainsKey(pair.Value)) deadRequests.Add(pair.Key);
                    }
                    foreach (string key in deadRequests) approvedRequests.Remove(key);
                }

                // 被拒绝的记录同样会残留，一并按时间清掉。
                if (pairingDenied.Count > 100)
                {
                    pairingDenied.Clear();
                }
            }
        }

        /// <summary>当前是否有待确认的配对请求（界面据此决定要不要弹窗）。</summary>
        public List<PairRequest> PendingPairing()
        {
            lock (gate) { return new List<PairRequest>(pairingRequests.Values); }
        }

        /// <summary>把配对码与令牌写回设置文件。</summary>
        private void Persist()
        {
            if (stateDirectory == null) return;
            var settings = new RelaySettings();
            settings.Port = Port;
            settings.PairingCode = PairingCode;
            lock (gate)
            {
                foreach (var pair in tokens) settings.Tokens[pair.Key] = pair.Value;
            }
            lock (trustedGate)
            {
                foreach (var pair in trusted) settings.TrustedDevices[pair.Key] = pair.Value;
            }
            settings.StartWithWindows = StartWithWindows;
            settings.FollowDsh = FollowDsh;
            settings.AutoTunnel = AutoTunnel;
            settings.Theme = Theme;
            settings.Save(stateDirectory);
        }

        /// <summary>生成易读的 6 位配对码（避开容易看错的 0/O/1/I）。</summary>
        public static string NewPairingCode()
        {
            const string alphabet = "23456789";
            var random = new byte[6];
            using (var rng = new RNGCryptoServiceProvider()) { rng.GetBytes(random); }
            var builder = new StringBuilder(6);
            for (int i = 0; i < 6; i++) builder.Append(alphabet[random[i] % alphabet.Length]);
            return builder.ToString();
        }

        public void Start()
        {
            // 先确认端口没被上一次的残留占用。
            //
            // 中继拉起的 cloudflared 会继承监听 socket 的句柄：中继被强杀后端口
            // 仍显示 LISTENING，但那个 PID 已经不存在，而且不会随时间释放（实测 90 秒仍在）。
            // 这时新实例只会拿到「每个套接字地址只允许使用一次」。这里先尝试清掉
            // 指向该端口的 cloudflared，再绑定。
            ReleaseOrphanedPort();

            listener = new TcpListener(IPAddress.Any, Port);
            listener.Start();
            Running = true;
            StartedAt = DateTime.Now;
            stopping = false;

            acceptThread = new Thread(AcceptLoop);
            acceptThread.IsBackground = true;
            acceptThread.Name = "relay-accept";
            acceptThread.Start();

            StartBeacon();
        }

        public void Stop()
        {
            stopping = true;
            Running = false;
            try { if (listener != null) listener.Stop(); } catch { }
            try { if (beaconTimer != null) beaconTimer.Dispose(); } catch { }
            try { if (beacon != null) beacon.Close(); } catch { }
            try { if (sender != null) sender.Close(); } catch { }
        }

        public void Dispose() { Stop(); }

        private void AcceptLoop()
        {
            while (!stopping)
            {
                TcpClient client = null;
                try { client = listener.AcceptTcpClient(); }
                catch { if (stopping) return; continue; }

                TcpClient captured = client;
                var worker = new Thread(delegate () { HandleClient(captured); });
                worker.IsBackground = true;
                worker.Start();
            }
        }

        /// <summary>
        /// 局域网发现：既周期广播自己的存在，也响应手表的主动查询。
        ///
        /// 两个方向都要有——只广播的话，手表必须恰好在那 2 秒窗口里监听才能发现；
        /// 而手表点「寻找电脑」时不希望靠运气，所以它也会发一条查询广播，
        /// 中继收到后立刻单播回复。
        /// </summary>
        private void StartBeacon()
        {
            try
            {
                // 收与发用**两个独立的 socket**：
                // 一个 bind 在 19391 上收（手表发来的查询），另一个专门用来发广播。
                // 共用一个 socket 时，bind 到固定端口后再 Send 在部分 Windows 配置下会失败，
                // 表现就是「广播一次都发不出去」。
                beacon = new UdpClient();
                beacon.Client.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
                beacon.Client.Bind(new IPEndPoint(IPAddress.Any, DiscoveryPort));

                sender = new UdpClient();
                sender.EnableBroadcast = true;

                // 单播回复也要能发：从一个临时端口发，避免和监听端口抢。
                beaconTimer = new Timer(delegate (object unused)
                {
                    try
                    {
                        byte[] payload = BeaconPayload(false);
                        sender.Send(payload, payload.Length, new IPEndPoint(IPAddress.Broadcast, DiscoveryPort));
                        // 再往各网段的定向广播发一次：部分路由器会丢弃 255.255.255.255。
                        foreach (string target in SubnetBroadcasts())
                        {
                            try
                            {
                                sender.Send(payload, payload.Length,
                                    new IPEndPoint(IPAddress.Parse(target), DiscoveryPort));
                            }
                            catch { }
                        }
                    }
                    catch { }
                }, null, 0, 2000);

                // 监听手表的查询：收到就单播回一份带端口的身份信息。
                var receiveThread = new Thread(ListenForQueries);
                receiveThread.IsBackground = true;
                receiveThread.Name = "relay-discovery";
                receiveThread.Start();
            }
            catch (Exception error)
            {
                log("广播启动失败（不影响局域网直连）：" + error.Message);
            }
        }

        /// <summary>本机各网段的定向广播地址（如 192.168.1.255），用于提高发现成功率。</summary>
        private List<string> SubnetBroadcasts()
        {
            var list = new List<string>();
            try
            {
                foreach (var nic in System.Net.NetworkInformation.NetworkInterface.GetAllNetworkInterfaces())
                {
                    if (nic.OperationalStatus != System.Net.NetworkInformation.OperationalStatus.Up) continue;
                    if (nic.NetworkInterfaceType == System.Net.NetworkInformation.NetworkInterfaceType.Loopback) continue;
                    foreach (var info in nic.GetIPProperties().UnicastAddresses)
                    {
                        if (info.Address.AddressFamily != AddressFamily.InterNetwork) continue;
                        byte[] bytes = info.Address.GetAddressBytes();
                        byte[] mask = info.PrefixLength <= 0 ? null : PrefixToMask(info.PrefixLength);
                        if (bytes.Length != 4 || mask == null) continue;
                        var broadcast = new byte[4];
                        for (int i = 0; i < 4; i++)
                        {
                            broadcast[i] = (byte)(bytes[i] | (byte)~mask[i]);
                        }
                        list.Add(new IPAddress(broadcast).ToString());
                    }
                }
            }
            catch { }
            return list;
        }

        private static byte[] PrefixToMask(int prefix)
        {
            if (prefix < 0 || prefix > 32) return null;
            var mask = new byte[4];
            for (int i = 0; i < 4; i++)
            {
                int bits = prefix - i * 8;
                if (bits >= 8) mask[i] = 0xFF;
                else if (bits <= 0) mask[i] = 0x00;
                else mask[i] = (byte)(0xFF << (8 - bits));
            }
            return mask;
        }

        /// <summary>广播/回复用的身份信息。</summary>
        private byte[] BeaconPayload(bool reply)
        {
            var map = new Dictionary<string, object>();
            map["service"] = ServiceTag;
            map["port"] = Port;
            map["name"] = Environment.MachineName;
            map["pairing"] = true;
            if (reply) map["reply"] = true;
            return Encoding.UTF8.GetBytes(json.Serialize(map));
        }

        /// <summary>回应手表发来的查询广播。</summary>
        private void ListenForQueries()
        {
            while (!stopping)
            {
                try
                {
                    IPEndPoint remote = new IPEndPoint(IPAddress.Any, 0);
                    byte[] data = beacon.Receive(ref remote);
                    string text = Encoding.UTF8.GetString(data);
                    if (text.IndexOf("dsh-watch-query", StringComparison.Ordinal) < 0) continue;

                    byte[] reply = BeaconPayload(true);
                    sender.Send(reply, reply.Length, remote);
                    log("手表在寻找中继（来自 " + remote.Address + "），已回复");
                }
                catch
                {
                    // 停止或套接字关闭时会抛异常，直接退出循环。
                    if (stopping) return;
                }
            }
        }

        /// <summary>
        /// 某个会话当前是否在生成回复。
        ///
        /// 用 session/list 里的 running 字段判断：这是 DSH 自己维护的权威状态。
        /// 查列表比查单个会话便宜（一次调用覆盖所有会话），因此还顺带缓存 2 秒，
        /// 避免手表 4 秒一次的刷新把 DSH 打爆。
        /// </summary>
        private bool IsRunning(string sessionId)
        {
            lock (runningGate)
            {
                if (NowMs() - runningCheckedAt < 2000)
                {
                    bool cached;
                    if (runningCache.TryGetValue(sessionId, out cached)) return cached;
                    return false;
                }
            }

            var args = new Dictionary<string, object>();
            args["_request"] = new Dictionary<string, object>();
            var value = dsh.RpcSafe("session/list", args) as Dictionary<string, object>;
            var items = ListField(value, "items");

            var fresh = new Dictionary<string, bool>();
            if (items != null)
            {
                foreach (var raw in items)
                {
                    var item = raw as Dictionary<string, object>;
                    if (item == null) continue;
                    string id = GetString(item, "sessionId");
                    if (id.Length == 0) continue;
                    fresh[id] = GetBool(item, "running");
                }
            }

            lock (runningGate)
            {
                runningCache = fresh;
                runningCheckedAt = NowMs();
                bool cached;
                return runningCache.TryGetValue(sessionId, out cached) && cached;
            }
        }

        /// <summary>
        /// 释放端口上的残留占用者。
        ///
        /// 情形：上一个中继实例被强杀，但它的 cloudflared 子进程继承了监听 socket
        /// 句柄，端口因此一直 LISTENING 在一个已不存在的 PID 上。此时新实例绑不上。
        /// 处理方式：找出命令行里带 "--url http://127.0.0.1:&lt;端口&gt;" 的 cloudflared
        /// 并结束它（它本来就是上一个实例的遗留），句柄随之关闭。
        ///
        /// 只动和我们端口匹配的 cloudflared，绝不碰别人的进程。
        /// </summary>
        private void ReleaseOrphanedPort()
        {
            if (!IsPortBusy(Port)) return;
            log("端口 " + Port + " 仍被占用，正在清理上一次遗留的公网中转进程…");
            try
            {
                foreach (var process in System.Diagnostics.Process.GetProcessesByName("cloudflared"))
                {
                    try
                    {
                        string args = CommandLineOf(process.Id);
                        if (args == null) continue;
                        if (args.IndexOf("127.0.0.1:" + Port, StringComparison.Ordinal) < 0) continue;
                        log("结束遗留的 cloudflared（PID " + process.Id + "）");
                        process.Kill();
                        process.WaitForExit(3000);
                    }
                    catch { }
                    finally { try { process.Dispose(); } catch { } }
                }
            }
            catch (Exception error)
            {
                log("清理遗留进程失败：" + error.Message);
            }
            // 给系统一点时间真正关闭句柄。
            for (int i = 0; i < 20 && IsPortBusy(Port); i++)
            {
                System.Threading.Thread.Sleep(150);
            }
        }

        private static bool IsPortBusy(int port)
        {
            try
            {
                var probe = new TcpListener(IPAddress.Any, port);
                probe.Start();
                probe.Stop();
                return false;
            }
            catch
            {
                return true;
            }
        }

        /// <summary>取某个进程的命令行（失败返回 null）。</summary>
        private static string CommandLineOf(int processId)
        {
            try
            {
                using (var searcher = new System.Management.ManagementObjectSearcher(
                    "SELECT CommandLine FROM Win32_Process WHERE ProcessId = " + processId))
                {
                    foreach (System.Management.ManagementObject item in searcher.Get())
                    {
                        object value = item["CommandLine"];
                        if (value != null) return Convert.ToString(value);
                    }
                }
            }
            catch { }
            return null;
        }

        /// <summary>
        /// 换用一个新的 DSH 客户端（DSH 重启、端口变化后调用）。
        ///
        /// 只换引用、不动配对码与令牌：手表那边完全无感，重连即刻可用。
        /// </summary>
        public void RebindDsh(DshClient fresh)
        {
            if (fresh == null) return;
            dsh = fresh;
            // 运行状态缓存是按旧端口算的，必须清掉。
            lock (runningGate)
            {
                runningCache = new Dictionary<string, bool>();
                runningCheckedAt = 0;
            }
            log("中继已切换到新的 DSH 连接（端口 " + fresh.Port + "）");
        }
        /// <summary>正在重新定位 DSH（防止并发请求同时重连）。</summary>
        private int relocating;

        /// <summary>
        /// DSH 连不上时重新定位一次并换客户端。
        ///
        /// 用 Interlocked 做互斥：一次断线会有多个手表请求同时失败，
        /// 不能让它们各自定位一遍、互相把客户端改来改去。
        /// </summary>
        private bool TryRelocate()
        {
            if (System.Threading.Interlocked.CompareExchange(ref relocating, 1, 0) != 0) return false;
            try
            {
                DshInstance instance = DshLocator.Locate(null, log);
                if (instance == null || instance.Port <= 0) return false;
                if (dsh != null && instance.Port == dsh.Port && instance.Home == dsh.Home) return false;

                var fresh = new DshClient(instance.Home, instance.Port, instance.Secret, instance.SecretRaw);
                var probe = new Dictionary<string, object>();
                probe["_request"] = new Dictionary<string, object>();
                fresh.Rpc("session/list", probe);

                log("DSH 端口变化，已自动重连：" + instance.Port);
                RebindDsh(fresh);
                return true;
            }
            catch (Exception error)
            {
                log("自动重连 DSH 失败：" + error.Message);
                return false;
            }
            finally
            {
                System.Threading.Interlocked.Exchange(ref relocating, 0);
            }
        }
        private readonly object runningGate = new object();
        private Dictionary<string, bool> runningCache = new Dictionary<string, bool>();
        private long runningCheckedAt;

        /// <summary>中继的身份信息（无需令牌）：手表用它确认连的是哪台电脑。</summary>
        private Http.Response Identity()
        {
            var result = new Dictionary<string, object>();
            result["ok"] = true;
            result["service"] = ServiceTag;
            result["name"] = Environment.MachineName;
            result["port"] = Port;
            result["lanAddresses"] = ToObjectList(LanAddresses());
            result["paired"] = PairedDevices > 0;
            // 公网地址随每次启动变化，手表连上后据此更新自己记的那一条，
            // 这样「一次配对」在公网与内网都能长期使用，不必重配。
            string publicUrl = NormalizePublicUrl(PublicUrl);
            result["publicUrl"] = publicUrl;
            return Http.Json(200, result);
        }

        /// <summary>
        /// 本次请求实际到达的局域网基地址（如 http://192.168.1.7:19390）。
        ///
        /// 用请求来源地址而不是遍历网卡：多网卡时只有这一条**确实能通到手表**，
        /// 把「虚拟网卡」「公网出口」一并交给手表只会让它试错。
        /// 来源是 loopback 时（本机测试）退回第一条局域网地址。
        /// </summary>
        private string PublicBaseFor(Http.Request request)
        {
            string from = request == null ? "" : (request.ClientAddress ?? "");
            if (from.Length > 0 && !from.StartsWith("127.") && from != "::1")
            {
                return "http://" + from + ":" + Port.ToString(CultureInfo.InvariantCulture);
            }
            List<string> lan = LanAddresses();
            if (lan.Count > 0) return "http://" + lan[0] + ":" + Port.ToString(CultureInfo.InvariantCulture);
            return "";
        }

        /// <summary>规范化公网地址：去掉末尾斜杠，空值返回空串。</summary>
        public static string NormalizePublicUrl(string value)
        {
            if (string.IsNullOrEmpty(value)) return "";
            string text = value.Trim();
            while (text.EndsWith("/")) text = text.Substring(0, text.Length - 1);
            return text;
        }

        private static List<object> ToObjectList(List<string> values)
        {
            var list = new List<object>();
            foreach (string value in values) list.Add(value);
            return list;
        }

        /// <summary>本机局域网 IPv4 地址，用于界面显示。</summary>
        public List<string> LanAddresses()
        {
            var list = new List<string>();
            try
            {
                foreach (var nic in System.Net.NetworkInformation.NetworkInterface.GetAllNetworkInterfaces())
                {
                    if (nic.OperationalStatus != System.Net.NetworkInformation.OperationalStatus.Up) continue;
                    if (nic.NetworkInterfaceType == System.Net.NetworkInformation.NetworkInterfaceType.Loopback) continue;
                    foreach (var address in nic.GetIPProperties().UnicastAddresses)
                    {
                        if (address.Address.AddressFamily != AddressFamily.InterNetwork) continue;
                        string text = address.Address.ToString();
                        if (text.StartsWith("169.254.")) continue;
                        list.Add(text);
                    }
                }
            }
            catch { }
            return list;
        }

        private void HandleClient(TcpClient client)
        {
            try
            {
                client.ReceiveTimeout = 30000;
                client.SendTimeout = 30000;
                string clientAddress = "";
                try
                {
                    IPEndPoint remote = client.Client.RemoteEndPoint as IPEndPoint;
                    if (remote != null) clientAddress = remote.Address.ToString();
                }
                catch { }
                using (var stream = client.GetStream())
                {
                    // 只处理一条请求——手表每次调用都是独立的短连接，避免长连管理。
                    var request = Http.ReadRequest(stream);
                    if (request == null) return;
                    request.ClientAddress = clientAddress;
                    var response = Route(request);
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
            return Route(request, false);
        }

        /// <summary>@param retried 已经重连重试过一次，避免无限递归。</summary>
        private Http.Response Route(Http.Request request, bool retried)
        {
            string path = request.Path;

            // 控制面板由界面层提供；这里只暴露中继接口。
            if (path == "/api/status" && request.Method == "GET")
            {
                return Http.Json(200, StatusObject());
            }

            if (path == "/api/pair" && request.Method == "POST")
            {
                var body = ParseJson(request.BodyText) as Dictionary<string, object>;
                string code = body != null && body.ContainsKey("code") ? Convert.ToString(body["code"]) : "";
                // 一键配对：手表扫到中继后在电脑上点了「同意」，凭一次性票据直接换令牌。
                string grant = body != null && body.ContainsKey("grant") ? Convert.ToString(body["grant"]) : "";
                if (!string.IsNullOrEmpty(grant) && !ConsumeGrant(grant))
                {
                    return Http.Json(403, Simple("配对请求已失效，请在电脑上重新同意"));
                }
                if (string.IsNullOrEmpty(grant) && (code == null || code.Trim() != PairingCode))
                {
                    return Http.Json(403, Simple("配对码不正确"));
                }

                string token = NewToken();
                // 配对码**保持不变**（用户要求「密钥不变，除非主动更改」），
                // 只把令牌存下来，重启后手表不用重配。
                Persist();

                // 手表用同一个令牌既能走局域网也能走公网，因此一次配对就把两种入口
                // 都交出去：局域网地址是本次请求实际到达的那条，公网地址来自隧道。
                // 公网地址每次重启都会变，手表连上后会通过 /api/identity 再同步一次。
                string deviceId = body != null && body.ContainsKey("deviceId") ? Convert.ToString(body["deviceId"]) : "";
                if (!string.IsNullOrEmpty(deviceId))
                {
                    // 已记住的设备在这一步刷新「最近见过」，供控制台显示。
                    TouchTrusted(deviceId, request.ClientAddress);
                }

                log("新设备已配对（当前共 " + PairedDevices + " 台），配对码仍是 " + PairingCode);
                var ok = new Dictionary<string, object>();
                ok["ok"] = true;
                ok["token"] = token;
                ok["expiresInMs"] = TokenTtlMs;
                ok["lanBase"] = PublicBaseFor(request);
                string publicUrl = NormalizePublicUrl(PublicUrl);
                if (publicUrl.Length > 0) ok["publicUrl"] = publicUrl;
                return Http.Json(200, ok);
            }

            // 一键配对的两个端点**必须在鉴权之前**：手表这时还没拿到令牌。
            // 安全边界靠「电脑端弹窗确认」而不是令牌——用户不点同意，什么都不会发生。
            if (path == "/api/pair/request" && request.Method == "POST") return RequestPairing(request);
            if (path == "/api/pair/result" && request.Method == "GET") return PairingResult(request);
            // 中继身份：手表用来在设置页里确认「连的是哪台电脑」。
            if (path == "/api/identity" && request.Method == "GET") return Identity();

            if (!IsAuthorized(request))
            {
                return Http.Json(401, Simple("未配对或令牌已失效"));
            }

            try
            {
                if (path == "/api/sessions" && request.Method == "GET") return Sessions();
                if (path == "/api/subagents" && request.Method == "GET") return Subagents(request);
                if (path == "/api/session" && request.Method == "GET") return Session(request);
                if (path == "/api/file" && request.Method == "GET") return FileView(request);
                if (path == "/api/send" && request.Method == "POST") return Send(request);
                if (path == "/api/cancel" && request.Method == "POST") return Cancel(request);
                if (path == "/api/create" && request.Method == "POST") return Create();
                if (path == "/api/models" && request.Method == "GET") return Models();
                if (path == "/api/model" && request.Method == "POST") return SelectModel(request);
                if (path == "/api/answer" && request.Method == "POST") return Answer(request);
            }
            catch (DshException error)
            {
                // DSH 可能刚重启、端口变了。这里立刻重新定位并重试一次，
                // 而不是把错误抛给手表——手表只会显示「无法连接到远程服务器」，
                // 用户完全不知道该怎么办。重试成功后这次请求就是正常的。
                if (TryRelocate() && retried == false)
                {
                    retried = true;
                    return Route(request);
                }
                return Http.Json(502, Simple(error.Message));
            }
            catch (Exception error)
            {
                // 用 ToString()：StackTrace 在部分异常上为 null，而 ToString() 总是带类型与消息。
                StartupLog.Write("处理 " + path + " 时异常：" + error.ToString());
                return Http.Json(500, Simple(error.Message));
            }

            return Http.Json(404, Simple("未知接口"));
        }

        private Dictionary<string, object> StatusObject()        {
            var result = new Dictionary<string, object>();
            result["service"] = ServiceTag;
            result["name"] = "DSH 手表中继";
            result["version"] = 2;
            result["needsPairing"] = true;
            result["uptimeMs"] = (long)(DateTime.Now - StartedAt).TotalMilliseconds;
            string publicUrl = NormalizePublicUrl(PublicUrl);
            if (publicUrl.Length > 0) result["publicUrl"] = publicUrl;
            return result;
        }

        // ---------------------------------------------------------------- 鉴权

        private string NewToken()
        {
            var bytes = new byte[24];
            using (var rng = new RNGCryptoServiceProvider()) { rng.GetBytes(bytes); }
            string token = DshLocator.Base64UrlEncode(bytes);
            lock (gate) { tokens[token] = NowMs(); }
            return token;
        }

        private bool IsAuthorized(Http.Request request)
        {
            string header = request.Headers.ContainsKey("authorization") ? request.Headers["authorization"] : null;
            if (string.IsNullOrEmpty(header) || !header.StartsWith("Bearer ")) return false;
            string token = header.Substring(7).Trim();
            lock (gate)
            {
                long issued;
                if (!tokens.TryGetValue(token, out issued)) return false;
                if (NowMs() - issued > TokenTtlMs) { tokens.Remove(token); return false; }
                return true;
            }
        }

        private static long NowMs()
        {
            return (long)(DateTime.UtcNow - new DateTime(1970, 1, 1, 0, 0, 0, DateTimeKind.Utc)).TotalMilliseconds;
        }

        /// <summary>
        /// 已发放且仍有效的配对令牌，按发放时间倒序。
        ///
        /// 供本机控制台显示：用户拿到链接与令牌后，可以用 curl 或别的客户端
        /// 直接连中继，不必依赖手表。只在 loopback 上暴露。
        /// </summary>
        public List<TokenInfo> IssuedTokens()
        {
            var list = new List<TokenInfo>();
            lock (gate)
            {
                long now = NowMs();
                var stale = new List<string>();
                foreach (var pair in tokens)
                {
                    if (now - pair.Value > TokenTtlMs) { stale.Add(pair.Key); continue; }
                    var info = new TokenInfo();
                    info.Token = pair.Key;
                    info.IssuedAt = pair.Value;
                    list.Add(info);
                }
                // 顺手清掉过期令牌，避免越积越多。
                foreach (string key in stale) tokens.Remove(key);
            }
            list.Sort(delegate (TokenInfo a, TokenInfo b) { return b.IssuedAt.CompareTo(a.IssuedAt); });
            return list;
        }

        // ---------------------------------------------------------------- 接口实现

        private Http.Response Sessions()
        {
            var args = new Dictionary<string, object>();
            args["_request"] = new Dictionary<string, object>();
            object rawValue = dsh.Rpc("session/list", args);
            var value = rawValue as Dictionary<string, object>;

            // 取不到会话时把实际拿到的形状记下来：DSH 正常应返回 items 数组，
            // 返回空或类型不对说明参数/鉴权有问题，只看最终的空列表是查不出来的。
            if (value == null)
            {
                StartupLog.Write("session/list 返回了非对象：" + (rawValue == null ? "null" : rawValue.GetType().Name));
            }
            else if (!value.ContainsKey("items"))
            {
                StartupLog.Write("session/list 返回的字段：" + string.Join(", ", new List<string>(value.Keys).ToArray()));
            }

            var items = ListField(value, "items");

            // 先把**全部**条目（含子代理）归一化：子代理条目本身就带着父会话 id、
            // 状态与耗时，统计与 /api/subagents 都从这份全量里取，不必再发 RPC。
            var all = new List<object>();
            if (items != null)
            {
                foreach (var raw in items)
                {
                    var item = raw as Dictionary<string, object>;
                    if (item == null) continue;
                    all.Add(NormalizeSession(item));
                }
            }

            // 统计每个会话派生了几个子代理：键是父会话 id，值固定为 [总数, 运行中数]。
            var counts = new Dictionary<string, int[]>();
            foreach (var raw in all)
            {
                var child = raw as Dictionary<string, object>;
                if (child == null) continue;
                if (!Convert.ToBoolean(child["subagent"])) continue;
                string parentId = Convert.ToString(child["parentId"]);
                if (parentId.Length == 0) continue;
                int[] entry;
                if (!counts.TryGetValue(parentId, out entry))
                {
                    entry = new int[2];
                    counts[parentId] = entry;
                }
                entry[0] = entry[0] + 1;
                if (Convert.ToBoolean(child["running"])) entry[1] = entry[1] + 1;
            }

            var sessions = new List<object>();
            var projects = new Dictionary<string, Dictionary<string, object>>();

            foreach (var raw in all)
            {
                var session = raw as Dictionary<string, object>;
                if (session == null) continue;
                // 子代理不进列表，只作为父会话的计数来源。
                if (Convert.ToBoolean(session["subagent"])) continue;
                // 上限只是兜底（DSH 实际规模远小于此）。**不能在加进项目分组之前 break**，
                // 否则最后一个项目会被截成半截，手表上点进去少会话而没有任何提示。
                if (sessions.Count >= MaxSessions) break;

                string sessionId = Convert.ToString(session["id"]);
                int[] entry;
                if (sessionId.Length > 0 && counts.TryGetValue(sessionId, out entry))
                {
                    session["subagentCount"] = entry[0];
                    session["subagentRunning"] = entry[1];
                }

                sessions.Add(session);

                string project = Convert.ToString(session["project"]);
                if (!projects.ContainsKey(project))
                {
                    var group = new Dictionary<string, object>();
                    group["project"] = project;
                    group["path"] = session["projectPath"];
                    group["sessions"] = new List<object>();
                    group["running"] = 0;
                    group["waiting"] = 0;
                    group["updatedAt"] = 0L;
                    projects[project] = group;
                }
                ((List<object>)projects[project]["sessions"]).Add(session);
                if (Convert.ToBoolean(session["running"])) projects[project]["running"] = Convert.ToInt32(projects[project]["running"]) + 1;
                if (Convert.ToBoolean(session["waiting"])) projects[project]["waiting"] = Convert.ToInt32(projects[project]["waiting"]) + 1;
                long updated = Convert.ToInt64(session["updatedAt"]);
                if (updated > Convert.ToInt64(projects[project]["updatedAt"])) projects[project]["updatedAt"] = updated;
            }

            var projectList = new List<object>(projects.Values);
            projectList.Sort(delegate (object a, object b)
            {
                return Convert.ToInt64(((Dictionary<string, object>)b)["updatedAt"])
                    .CompareTo(Convert.ToInt64(((Dictionary<string, object>)a)["updatedAt"]));
            });

            var top = new Dictionary<string, object>();
            top["ok"] = true;
            top["sessions"] = sessions;
            top["projects"] = projectList;
            top["pending"] = PendingQuestions(sessions);
            return Http.Json(200, top);
        }

        /// <summary>
        /// 列出某个会话派生的全部子代理。
        ///
        /// 数据全部来自一次 `session/list`（每个条目自带 projections.values），
        /// 不逐个拉投影：子代理动辄数百条，逐个发 RPC 会慢到不可用。
        /// </summary>
        private Http.Response Subagents(Http.Request request)
        {
            string id = request.Query.ContainsKey("id") ? request.Query["id"] : "";
            if (string.IsNullOrEmpty(id)) return Http.Json(400, Simple("缺少 id 参数"));

            var args = new Dictionary<string, object>();
            args["_request"] = new Dictionary<string, object>();
            var value = dsh.Rpc("session/list", args) as Dictionary<string, object>;

            var list = new List<object>();
            var items = ListField(value, "items");
            if (items != null)
            {
                foreach (var raw in items)
                {
                    var item = raw as Dictionary<string, object>;
                    if (item == null) continue;
                    if (GetString(item, "parentSessionId") != id) continue;

                    var values = Dig(item, "projections", "values");
                    var identity = Dig(values, "subagent");
                    var timing = Dig(values, "subagentTiming");

                    string title = GetString(values, "title");
                    // 子代理不一定有 label（one-shot 模式），退回会话标题。
                    string label = GetString(identity, "label");
                    if (label.Length == 0) label = title;
                    if (label.Length == 0) label = "子任务";

                    bool running = GetBool(item, "running");
                    bool completed = GetBool(timing, "lastTurnCompleted");
                    bool agentAvailable = GetBool(item, "agentAvailable");

                    // 顺序不能变：运行中优先，其次已结束，再看是否可继续对话。
                    string state;
                    if (running) state = "running";
                    else if (completed) state = "done";
                    else if (agentAvailable) state = "idle";
                    else state = "done";

                    var entry = new Dictionary<string, object>();
                    entry["id"] = GetString(item, "sessionId");
                    entry["label"] = label;
                    entry["title"] = title;
                    entry["mode"] = GetString(identity, "mode");
                    entry["state"] = state;
                    entry["running"] = running;
                    entry["settledMs"] = GetLong(timing, "settledMs");
                    entry["active"] = timing != null && timing.ContainsKey("active") && timing["active"] != null;
                    entry["completed"] = completed;
                    entry["updatedAt"] = GetLong(item, "updatedAt");
                    entry["turns"] = GetLong(Dig(values, "sessionStats"), "turns");
                    entry["steps"] = GetLong(Dig(values, "sessionStats"), "steps");
                    list.Add(entry);
                }
            }

            list.Sort(delegate (object a, object b)
            {
                var left = (Dictionary<string, object>)a;
                var right = (Dictionary<string, object>)b;
                bool leftRunning = Convert.ToBoolean(left["running"]);
                bool rightRunning = Convert.ToBoolean(right["running"]);
                if (leftRunning != rightRunning) return leftRunning ? -1 : 1;
                return Convert.ToInt64(right["updatedAt"]).CompareTo(Convert.ToInt64(left["updatedAt"]));
            });

            var result = new Dictionary<string, object>();
            result["ok"] = true;
            result["parentId"] = id;
            result["subagents"] = list;
            return Http.Json(200, result);
        }

        private Dictionary<string, object> NormalizeSession(Dictionary<string, object> item)
        {
            var values = Dig(item, "projections", "values");
            string title = GetString(values, "title");
            string cwd = GetString(item, "cwd");
            if (title == null || title.Trim().Length == 0) title = cwd.Length == 0 ? "未命名会话" : Path.GetFileName(cwd.TrimEnd('\\', '/'));

            var model = values != null && values.ContainsKey("modelSelection")
                ? values["modelSelection"] as Dictionary<string, object> : null;
            var next = model != null && model.ContainsKey("next") ? model["next"] as Dictionary<string, object> : null;

            string origin = GetString(item, "origin");
            // subagent 与 subagentTiming 都是投影里的可选块：不存在时 Dig 返回 null，
            // 下面的取值helper 会退化成空串/0，不会抛异常。
            var identity = Dig(values, "subagent");
            var timing = Dig(values, "subagentTiming");

            var session = new Dictionary<string, object>();
            session["id"] = GetString(item, "sessionId");
            session["title"] = title;
            session["running"] = GetBool(item, "running");
            session["waiting"] = HasActiveQuestion(values);
            session["updatedAt"] = GetLong(item, "updatedAt");
            session["project"] = ProjectName(cwd);
            session["projectPath"] = cwd;
            session["provider"] = GetString(next, "provider");
            session["model"] = GetString(next, "model");
            session["effort"] = GetString(next, "reasoningEffort");
            session["subagent"] = origin == "subagent";
            // 子代理会话靠 parentSessionId 挂回父会话；普通会话为空串。
            session["parentId"] = GetString(item, "parentSessionId");
            session["subagentLabel"] = GetString(identity, "label");
            session["subagentMode"] = GetString(identity, "mode");
            session["agentAvailable"] = GetBool(item, "agentAvailable");
            // settledMs 是已跑时长；active 存在（哪怕值为 false）就表示这一轮仍在跑。
            session["settledMs"] = GetLong(timing, "settledMs");
            session["active"] = timing != null && timing.ContainsKey("active") && timing["active"] != null;
            session["completed"] = GetBool(timing, "lastTurnCompleted");
            // 派生的子代理数量由 Sessions() 汇总后回填。
            session["subagentCount"] = 0;
            session["subagentRunning"] = 0;
            // 子任务列表要显示工作量：轮次与步数来自会话统计投影。
            session["turns"] = GetLong(Dig(values, "sessionStats"), "turns");
            session["steps"] = GetLong(Dig(values, "sessionStats"), "steps");
            return session;
        }

        /// <summary>取工作目录的最后一段作为项目名；根目录退回完整路径。</summary>
        public static string ProjectName(string cwd)
        {
            if (string.IsNullOrEmpty(cwd) || cwd.Trim().Length == 0) return "未分组";
            string[] parts = cwd.Split(new char[] { '\\', '/' }, StringSplitOptions.RemoveEmptyEntries);
            if (parts.Length == 0) return cwd;
            string last = parts[parts.Length - 1];
            if (last.EndsWith(":")) return cwd;
            return last;
        }

        private Http.Response Session(Http.Request request)
        {
            string id = request.Query.ContainsKey("id") ? request.Query["id"] : "";
            if (string.IsNullOrEmpty(id)) return Http.Json(400, Simple("缺少 id 参数"));

            var projectionArgs = new Dictionary<string, object>();
            var projectionRequest = new Dictionary<string, object>();
            projectionRequest["sessionId"] = id;
            projectionArgs["request"] = projectionRequest;
            var projection = dsh.Rpc("session/projections", projectionArgs) as Dictionary<string, object>;

            long asOfSeq = projection != null ? GetLong(projection, "asOfSeq") : 0;
            long through = asOfSeq;
            if (request.Query.ContainsKey("through"))
            {
                long parsed;
                if (long.TryParse(request.Query["through"], out parsed) && parsed > 0) through = Math.Min(parsed, asOfSeq);
            }

            var pageArgs = new Dictionary<string, object>();
            var pageRequest = new Dictionary<string, object>();
            var address = SessionAddress(id);
            pageRequest["address"] = address;
            pageRequest["throughSeq"] = through;
            pageRequest["maxMessages"] = MaxMessages;
            pageArgs["request"] = pageRequest;
            var page = dsh.Rpc("session/page", pageArgs) as Dictionary<string, object>;

            List<object> records = ListField(page, "records");
            var values = projection != null && projection.ContainsKey("values") ? projection["values"] as Dictionary<string, object> : null;

            var result = new Dictionary<string, object>();
            result["ok"] = true;
            result["sessionId"] = id;
            result["title"] = GetString(values, "title");
            // running 必须反映真实状态：手表靠它决定显示「停止」还是「刷新」，
            // 写死 false 会让「停止生成」按钮永远不出现。
            result["running"] = IsRunning(id);
            result["hasMore"] = page != null && GetBool(page, "hasMore");
            result["asOfSeq"] = asOfSeq;
            result["beforeSeq"] = EarliestSeq(records);
            result["messages"] = Steps.NormalizeMessages(records);
            result["steps"] = Steps.NormalizeSteps(records);
            result["usage"] = Usage.Normalize(values);
            result["plan"] = NormalizePlan(values);

            var question = ActiveQuestion(values);
            if (question != null) result["question"] = question;
            result["files"] = StepFiles(records);
            return Http.Json(200, result);
        }

        /// <summary>
        /// 拼出读历史用的会话地址。
        ///
        /// 子代理会话**必须**用「父地址 + 子地址」，否则 DSH 直接拒绝：
        /// `session/page: subagent Sessions require their durable parent address`。
        /// 父会话 id 与子代理模式只能从 `session/list` 里取（投影里没有父 id），
        /// 因此这里多一次 list 调用；查不到该条目时按普通会话处理，不报错。
        /// </summary>
        private Dictionary<string, object> SessionAddress(string id)
        {
            var address = new Dictionary<string, object>();

            string parentId = "";
            string mode = "";
            var args = new Dictionary<string, object>();
            args["_request"] = new Dictionary<string, object>();
            var listValue = dsh.RpcSafe("session/list", args) as Dictionary<string, object>;
            var items = ListField(listValue, "items");
            if (items != null)
            {
                foreach (var raw in items)
                {
                    var item = raw as Dictionary<string, object>;
                    if (item == null) continue;
                    if (GetString(item, "sessionId") != id) continue;
                    parentId = GetString(item, "parentSessionId");
                    mode = GetString(Dig(Dig(item, "projections", "values"), "subagent"), "mode");
                    break;
                }
            }

            if (parentId.Length > 0)
            {
                address["kind"] = "subagent";
                address["parentSessionId"] = parentId;
                address["childSessionId"] = id;
                // one-shot 的子代理没有 subagent 块；continuable 是默认模式。
                address["mode"] = mode.Length == 0 ? "continuable" : mode;
            }
            else
            {
                address["kind"] = "session";
                address["sessionId"] = id;
            }
            return address;
        }

        /// <summary>
        /// 把一个会话的「计划与任务」压成手表要的结构。
        ///
        /// 四块内容来源不同、含义也不同，不要混为一谈：
        /// - todos：todo_write 产生的待办清单，是真正会逐项勾选的东西；
        /// - plan：只是「计划模式」的开关状态（active/pending 两个布尔），没有正文；
        /// - team：Agent Teams 的成员与任务板（谁负责、什么状态、被谁阻塞）；
        /// - goal：长目标（objective + phase），只在有目标时存在。
        /// </summary>
        private static Dictionary<string, object> NormalizePlan(Dictionary<string, object> values)
        {
            var todos = new List<object>();
            var rawTodos = ListField(values, "todos");
            if (rawTodos != null)
            {
                foreach (var raw in rawTodos)
                {
                    var todo = raw as Dictionary<string, object>;
                    if (todo == null) continue;
                    string content = GetString(todo, "content");
                    if (content.Length == 0) continue;
                    var entry = new Dictionary<string, object>();
                    entry["content"] = content;
                    string status = GetString(todo, "status");
                    entry["status"] = status.Length == 0 ? "pending" : status;
                    todos.Add(entry);
                }
            }

            var mode = new Dictionary<string, object>();
            var planMode = Dig(values, "plan");
            mode["active"] = GetBool(planMode, "active");
            mode["pending"] = GetBool(planMode, "pending");

            var team = Dig(values, "agentTeam");
            var members = new List<object>();
            var rawMembers = ListField(team, "members");
            if (rawMembers != null)
            {
                foreach (var raw in rawMembers)
                {
                    var member = raw as Dictionary<string, object>;
                    if (member == null) continue;
                    string name = GetString(member, "name");
                    if (name.Length == 0) continue;
                    var entry = new Dictionary<string, object>();
                    entry["name"] = name;
                    string role = GetString(member, "role");
                    entry["role"] = role.Length == 0 ? "teammate" : role;
                    string phase = GetString(member, "phase");
                    entry["phase"] = phase.Length == 0 ? "active" : phase;
                    entry["error"] = GetString(member, "error");
                    members.Add(entry);
                }
            }

            var tasks = new List<object>();
            var rawTasks = ListField(team, "tasks");
            if (rawTasks != null)
            {
                foreach (var raw in rawTasks)
                {
                    var task = raw as Dictionary<string, object>;
                    if (task == null) continue;
                    string status = GetString(task, "status");
                    if (status == "deleted") continue;
                    string subject = GetString(task, "subject");
                    if (subject.Length == 0) continue;

                    var entry = new Dictionary<string, object>();
                    entry["id"] = GetString(task, "id");
                    entry["subject"] = subject;
                    entry["status"] = status.Length == 0 ? "pending" : status;
                    entry["owner"] = GetString(task, "ownerName");
                    entry["ready"] = GetBool(task, "ready");
                    // 被谁阻塞只给名字，手表上没有空间展开 id。
                    var blockedBy = new List<object>();
                    var rawBlocked = ListField(task, "blockedBy");
                    if (rawBlocked != null)
                    {
                        foreach (var rawId in rawBlocked) blockedBy.Add(Convert.ToString(rawId));
                    }
                    entry["blockedBy"] = blockedBy;
                    tasks.Add(entry);
                }
            }

            var teamResult = new Dictionary<string, object>();
            teamResult["members"] = members;
            teamResult["tasks"] = tasks;

            var result = new Dictionary<string, object>();
            result["todos"] = todos;
            result["mode"] = mode;
            result["team"] = teamResult;

            // goal 与 subagent 是「有才出现」的键：没有时整个键都不下发，
            // 手表据此区分「没有目标」和「目标字段为空」。
            var goalOuter = Dig(values, "goal");
            if (goalOuter != null && goalOuter.ContainsKey("goal") && goalOuter["goal"] != null)
            {
                var goal = goalOuter["goal"] as Dictionary<string, object>;
                var entry = new Dictionary<string, object>();
                entry["objective"] = GetString(goal, "objective");
                string phase = GetString(goal, "phase");
                entry["phase"] = phase.Length == 0 ? "active" : phase;
                entry["rounds"] = GetLong(goalOuter, "roundsStarted");
                entry["maxRounds"] = GetLong(goal, "maxGoalRounds");
                entry["blockedReason"] = GetString(goal, "blockedReason");
                result["goal"] = entry;
            }

            if (values != null && values.ContainsKey("subagent") && values["subagent"] != null)
            {
                var identity = values["subagent"] as Dictionary<string, object>;
                var entry = new Dictionary<string, object>();
                entry["label"] = GetString(identity, "label");
                entry["mode"] = GetString(identity, "mode");
                result["subagent"] = entry;
            }
            return result;
        }

        /// <summary>
        /// 从本次取到的步骤里收集「碰到的文件」，给手表的文件列表用。
        ///
        /// 只认这次返回的步骤，不额外扫描磁盘：手表要看的正是对话里出现的那些
        /// md / txt / 代码文件，范围与白名单也就此确定（见 FileView）。
        /// </summary>
        private List<object> StepFiles(List<object> records)
        {
            var seen = new Dictionary<string, bool>(StringComparer.OrdinalIgnoreCase);
            var items = new List<object>();
            List<object> steps = Steps.NormalizeSteps(records);
            if (steps == null) return items;
            foreach (var raw in steps)
            {
                var step = raw as Dictionary<string, object>;
                if (step == null) continue;
                var files = ListField(step, "files");
                if (files == null) continue;
                foreach (var entry in files)
                {
                    string file = Convert.ToString(entry);
                    if (string.IsNullOrEmpty(file) || seen.ContainsKey(file)) continue;
                    seen[file] = true;
                    var item = new Dictionary<string, object>();
                    item["path"] = file;
                    item["name"] = FileName(file);
                    item["language"] = LanguageOf(file);
                    item["kind"] = DocumentKind(file);
                    item["size"] = SafeSize(file);
                    items.Add(item);
                }
            }
            return items;
        }

        private static string FileName(string path)
        {
            if (string.IsNullOrEmpty(path)) return "";
            int cut = Math.Max(path.LastIndexOf('/'), path.LastIndexOf('\\'));
            return cut >= 0 ? path.Substring(cut + 1) : path;
        }

        private static long SafeSize(string path)
        {
            try
            {
                var info = new FileInfo(path);
                return info.Exists ? info.Length : 0L;
            }
            catch
            {
                return 0L;
            }
        }

        /// <summary>文件类型：markdown / text / code / other。</summary>
        private static string DocumentKind(string path)
        {
            string ext = (Path.GetExtension(path) ?? "").ToLowerInvariant();
            if (ext == ".md" || ext == ".markdown" || ext == ".mdown") return "markdown";
            if (ext == ".txt" || ext == ".log" || ext == ".csv" || ext == ".tsv"
                || ext == ".ini" || ext == ".cfg" || ext == ".conf" || ext == ".env") return "text";
            if (LanguageOf(path).Length > 0) return "code";
            return "other";
        }

        /// <summary>代码语言标识（由扩展名推断，用于手表上的高亮）。</summary>
        private static string LanguageOf(string path)
        {
            switch ((Path.GetExtension(path) ?? "").ToLowerInvariant())
            {
                case ".cs": return "csharp";
                case ".java": return "java";
                case ".kt": return "kotlin";
                case ".js": case ".mjs": case ".cjs": return "javascript";
                case ".ts": case ".mts": case ".cts": return "typescript";
                case ".tsx": case ".jsx": return "tsx";
                case ".py": return "python";
                case ".go": return "go";
                case ".rs": return "rust";
                case ".c": case ".h": return "c";
                case ".cpp": case ".cc": case ".hpp": case ".cxx": return "cpp";
                case ".html": case ".htm": return "html";
                case ".css": case ".scss": case ".less": return "css";
                case ".json": return "json";
                case ".yml": case ".yaml": return "yaml";
                case ".xml": return "xml";
                case ".sh": case ".bash": return "bash";
                case ".ps1": return "powershell";
                case ".sql": return "sql";
                case ".lua": return "lua";
                case ".php": return "php";
                case ".rb": return "ruby";
                case ".swift": return "swift";
                case ".dart": return "dart";
                default: return "";
            }
        }

        /// <summary>单个文件允许读取的上限：手表屏幕小，再长也读不完。</summary>
        private const int MaxFileBytes = 256 * 1024;

        /// <summary>
        /// 读取一个文件给手表看。
        ///
        /// 安全边界：**只允许读这次会话里出现过的文件**。白名单来自 session/page
        /// 里工具步骤携带的 files，而不是让调用方任意指定路径——否则任何拿到
        /// 令牌的设备都能读遍这台电脑。
        /// </summary>
        private Http.Response FileView(Http.Request request)
        {
            string sessionId = request.Query.ContainsKey("session") ? request.Query["session"] : "";
            string path = request.Query.ContainsKey("path") ? request.Query["path"] : "";
            if (string.IsNullOrEmpty(path)) return Http.Json(400, Simple("缺少 path 参数"));
            if (string.IsNullOrEmpty(sessionId)) return Http.Json(400, Simple("缺少 session 参数"));

            string full = "";
            try { full = Path.GetFullPath(path); }
            catch { return Http.Json(400, Simple("路径不合法")); }

            if (!SessionTouches(sessionId, full) && !SessionTouches(sessionId, path))
            {
                return Http.Json(403, Simple("只看这次会话里出现过的文件"));
            }

            try
            {
                var info = new FileInfo(full);
                if (!info.Exists) return Http.Json(404, Simple("文件不存在或已被移走"));

                var result = new Dictionary<string, object>();
                result["ok"] = true;
                result["path"] = full;
                result["name"] = FileName(full);
                result["kind"] = DocumentKind(full);
                result["language"] = LanguageOf(full);
                result["size"] = info.Length;
                result["truncated"] = info.Length > MaxFileBytes;

                if (info.Length > MaxFileBytes)
                {
                    // 只读前一段，避免把几 MB 的日志推给手表。
                    using (var stream = info.OpenRead())
                    {
                        var buffer = new byte[MaxFileBytes];
                        int read = stream.Read(buffer, 0, buffer.Length);
                        result["text"] = Encoding.UTF8.GetString(buffer, 0, read);
                    }
                }
                else
                {
                    result["text"] = File.ReadAllText(full, Encoding.UTF8);
                }
                return Http.Json(200, result);
            }
            catch (Exception error)
            {
                return Http.Json(500, Simple("读取失败：" + error.Message));
            }
        }

        /// <summary>这个文件是否在这次会话里被工具碰到过。</summary>
        private bool SessionTouches(string sessionId, string candidate)
        {
            if (string.IsNullOrEmpty(candidate)) return false;
            try
            {
                var projectionArgs = new Dictionary<string, object>();
                var projectionRequest = new Dictionary<string, object>();
                projectionRequest["sessionId"] = sessionId;
                projectionArgs["request"] = projectionRequest;
                var projection = dsh.Rpc("session/projections", projectionArgs) as Dictionary<string, object>;
                long asOfSeq = projection != null ? GetLong(projection, "asOfSeq") : 0;

                var pageArgs = new Dictionary<string, object>();
                var pageRequest = new Dictionary<string, object>();
                var address = new Dictionary<string, object>();
                address["kind"] = "session";
                address["sessionId"] = sessionId;
                pageRequest["address"] = address;
                pageRequest["throughSeq"] = asOfSeq;
                pageRequest["maxMessages"] = MaxMessages;
                pageArgs["request"] = pageRequest;
                var page = dsh.Rpc("session/page", pageArgs) as Dictionary<string, object>;

                foreach (var raw in Steps.NormalizeSteps(ListField(page, "records")))
                {
                    var step = raw as Dictionary<string, object>;
                    if (step == null) continue;
                    var files = ListField(step, "files");
                    if (files == null) continue;
                    foreach (var entry in files)
                    {
                        string file = Convert.ToString(entry);
                        if (string.IsNullOrEmpty(file)) continue;
                        if (string.Equals(file, candidate, StringComparison.OrdinalIgnoreCase)) return true;
                        try
                        {
                            if (string.Equals(Path.GetFullPath(file), candidate, StringComparison.OrdinalIgnoreCase)) return true;
                        }
                        catch { }
                    }
                }
            }
            catch { }
            return false;
        }

        private Http.Response Send(Http.Request request)
        {
            var body = ParseJson(request.BodyText) as Dictionary<string, object>;
            if (body == null) return Http.Json(400, Simple("请求体不是合法 JSON"));
            string sessionId = Convert.ToString(body.ContainsKey("sessionId") ? body["sessionId"] : "");
            string text = Convert.ToString(body.ContainsKey("text") ? body["text"] : "");
            if (string.IsNullOrEmpty(sessionId) || string.IsNullOrEmpty(text) || text.Trim().Length == 0)
            {
                return Http.Json(400, Simple("缺少 sessionId 或 text"));
            }

            var content = new Dictionary<string, object>();
            content["type"] = "text";
            content["text"] = text;

            var args = new Dictionary<string, object>();
            var payload = new Dictionary<string, object>();
            payload["requestId"] = Guid.NewGuid().ToString();
            payload["sessionId"] = sessionId;
            payload["mode"] = "queue";
            payload["content"] = new List<object> { content };
            payload["clientTimeZone"] = "Asia/Shanghai";
            args["request"] = payload;
            dsh.Rpc("session/prompt", args);
            // 注意别写成 Http.Json(200, Ok())：Ok() 已经是一个完整响应，
            // 再包一层会把它序列化成 {Status,ContentType,Body} 发给客户端。
            return Ok();
        }

        private Http.Response Cancel(Http.Request request)
        {
            var body = ParseJson(request.BodyText) as Dictionary<string, object>;
            string sessionId = body != null && body.ContainsKey("sessionId") ? Convert.ToString(body["sessionId"]) : "";
            var args = new Dictionary<string, object>();
            var payload = new Dictionary<string, object>();
            payload["sessionId"] = sessionId;
            args["request"] = payload;
            dsh.Rpc("session/cancel", args);
            return Ok();
        }

        private Http.Response Create()
        {
            var args = new Dictionary<string, object>();
            args["request"] = new Dictionary<string, object>();
            var value = dsh.Rpc("session/create", args) as Dictionary<string, object>;
            var result = new Dictionary<string, object>();
            result["ok"] = true;
            result["sessionId"] = GetString(value, "sessionId");
            return Http.Json(200, result);
        }

        private Http.Response Models()
        {
            var value = dsh.Rpc("session/modelCatalog", new Dictionary<string, object>()) as Dictionary<string, object>;
            var routable = new Dictionary<string, bool>();
            var routableList = ListField(value, "routableProviders");
            if (routableList != null)
            {
                foreach (var item in routableList) routable[Convert.ToString(item)] = true;
            }

            var groups = new List<object>();
            var rawGroups = ListField(value, "groups");
            if (rawGroups != null)
            {
                foreach (var raw in rawGroups)
                {
                    var group = raw as Dictionary<string, object>;
                    if (group == null) continue;
                    string provider = GetString(group, "id");
                    if (routable.Count > 0 && !routable.ContainsKey(provider)) continue;

                    var models = new List<object>();
                    var rawModels = ListField(group, "models");
                    if (rawModels != null)
                    {
                        foreach (var rawModel in rawModels)
                        {
                            var model = rawModel as Dictionary<string, object>;
                            if (model == null) continue;
                            var entry = new Dictionary<string, object>();
                            entry["id"] = GetString(model, "id");
                            string name = GetString(model, "name");
                            entry["name"] = string.IsNullOrEmpty(name) ? GetString(model, "id") : name;
                            entry["efforts"] = DigEfforts(model);
                            models.Add(entry);
                        }
                    }
                    if (models.Count == 0) continue;

                    var item = new Dictionary<string, object>();
                    item["provider"] = provider;
                    string groupName = GetString(group, "name");
                    item["name"] = string.IsNullOrEmpty(groupName) ? provider : groupName;
                    item["models"] = models;
                    groups.Add(item);
                }
            }

            var result = new Dictionary<string, object>();
            result["ok"] = true;
            result["groups"] = groups;
            var defaults = value != null && value.ContainsKey("default") ? value["default"] as Dictionary<string, object> : null;
            var defaultEntry = new Dictionary<string, object>();
            defaultEntry["provider"] = GetString(defaults, "provider");
            defaultEntry["model"] = GetString(defaults, "model");
            defaultEntry["effort"] = GetString(defaults, "reasoningEffort");
            result["default"] = defaultEntry;
            return Http.Json(200, result);
        }

        private List<object> DigEfforts(Dictionary<string, object> model)
        {
            var list = new List<object>();
            var reasoning = model.ContainsKey("reasoning") ? model["reasoning"] as Dictionary<string, object> : null;
            var efforts = ListField(reasoning, "efforts");
            if (efforts != null)
            {
                foreach (var raw in efforts)
                {
                    var effort = raw as Dictionary<string, object>;
                    if (effort == null) continue;
                    list.Add(GetString(effort, "id"));
                }
            }
            return list;
        }

        private Http.Response SelectModel(Http.Request request)
        {
            var body = ParseJson(request.BodyText) as Dictionary<string, object>;
            if (body == null) return Http.Json(400, Simple("请求体不是合法 JSON"));
            string sessionId = Convert.ToString(body.ContainsKey("sessionId") ? body["sessionId"] : "");
            string provider = Convert.ToString(body.ContainsKey("provider") ? body["provider"] : "");
            string model = Convert.ToString(body.ContainsKey("model") ? body["model"] : "");
            if (string.IsNullOrEmpty(sessionId) || string.IsNullOrEmpty(provider) || string.IsNullOrEmpty(model))
            {
                return Http.Json(400, Simple("缺少 sessionId、provider 或 model"));
            }

            var payload = new Dictionary<string, object>();
            payload["sessionId"] = sessionId;
            payload["provider"] = provider;
            payload["model"] = model;
            if (body.ContainsKey("effort"))
            {
                string effort = Convert.ToString(body["effort"]);
                if (!string.IsNullOrEmpty(effort)) payload["reasoningEffort"] = effort;
            }
            var args = new Dictionary<string, object>();
            args["request"] = payload;
            var value = dsh.Rpc("session/selectModel", args) as Dictionary<string, object>;
            var selected = value != null && value.ContainsKey("selected") ? value["selected"] as Dictionary<string, object> : null;

            var result = new Dictionary<string, object>();
            result["ok"] = true;
            result["provider"] = GetString(selected, "provider");
            result["model"] = GetString(selected, "model");
            result["effort"] = GetString(selected, "reasoningEffort");
            return Http.Json(200, result);
        }

        private Http.Response Answer(Http.Request request)
        {
            var body = ParseJson(request.BodyText) as Dictionary<string, object>;
            if (body == null) return Http.Json(400, Simple("请求体不是合法 JSON"));
            string sessionId = Convert.ToString(body.ContainsKey("sessionId") ? body["sessionId"] : "");
            string callId = Convert.ToString(body.ContainsKey("callId") ? body["callId"] : "");
            var answers = ListField(body, "answers");
            if (string.IsNullOrEmpty(sessionId) || string.IsNullOrEmpty(callId) || answers == null || answers.Count == 0)
            {
                return Http.Json(400, Simple("缺少 sessionId、callId 或 answers"));
            }

            var normalized = new List<object>();
            foreach (var raw in answers)
            {
                var item = raw as Dictionary<string, object>;
                if (item == null) continue;
                var entry = new Dictionary<string, object>();
                entry["id"] = Convert.ToString(item.ContainsKey("id") ? item["id"] : "");
                var selected = new List<object>();
                var rawSelected = ListField(item, "selected");
                if (rawSelected != null) foreach (var value in rawSelected) selected.Add(Convert.ToString(value));
                entry["selected"] = selected;
                if (item.ContainsKey("custom"))
                {
                    string custom = Convert.ToString(item["custom"]);
                    if (!string.IsNullOrEmpty(custom)) entry["custom"] = custom;
                }
                normalized.Add(entry);
            }

            var answer = new Dictionary<string, object>();
            answer["answers"] = normalized;
            var args = new Dictionary<string, object>();
            args["agentId"] = sessionId;
            args["callId"] = callId;
            args["answer"] = answer;
            object accepted = dsh.Rpc("userQuestions/answer", args);

            var result = new Dictionary<string, object>();
            result["ok"] = true;
            result["via"] = "rpc";
            result["accepted"] = accepted is bool && (bool)accepted;
            return Http.Json(200, result);
        }

        // ---------------------------------------------------------------- 提问

        /// <summary>
        /// 汇总待答问题。
        ///
        /// 只有投影里登记为 continued（超时挂起）的问题能在这里看到；
        /// 前台仍等待（open）的问题只经 WebSocket 事件流下发，桌面版应用不必处理它
        /// ——官方桌面客户端自己会应答那条通道，手表只需处理需要人工补答的情形。
        /// </summary>
        private List<object> PendingQuestions(List<object> sessions)
        {
            var pending = new List<object>();
            foreach (var raw in sessions)
            {
                var session = raw as Dictionary<string, object>;
                if (session == null || !Convert.ToBoolean(session["waiting"])) continue;

                var args = new Dictionary<string, object>();
                var payload = new Dictionary<string, object>();
                payload["sessionId"] = session["id"];
                args["request"] = payload;
                var projection = dsh.RpcSafe("session/projections", args) as Dictionary<string, object>;
                var values = projection != null && projection.ContainsKey("values") ? projection["values"] as Dictionary<string, object> : null;

                var active = DigQuestionList(values);
                if (active == null) continue;
                foreach (var item in active)
                {
                    var question = item as Dictionary<string, object>;
                    if (question == null) continue;
                    var entry = new Dictionary<string, object>();
                    entry["sessionId"] = session["id"];
                    entry["sessionTitle"] = session["title"];
                    entry["callId"] = GetString(question, "callId");
                    entry["state"] = GetString(question, "state");
                    entry["eventId"] = "";
                    entry["questions"] = CompactQuestions(ListField(question, "questions"));
                    pending.Add(entry);
                }
            }
            return pending;
        }

        private Dictionary<string, object> ActiveQuestion(Dictionary<string, object> values)
        {
            var active = DigQuestionList(values);
            if (active == null || active.Count == 0) return null;
            var first = active[0] as Dictionary<string, object>;
            if (first == null) return null;
            var entry = new Dictionary<string, object>();
            entry["callId"] = GetString(first, "callId");
            entry["state"] = GetString(first, "state");
            entry["eventId"] = "";
            entry["questions"] = CompactQuestions(ListField(first, "questions"));
            return entry;
        }

        private static List<object> DigQuestionList(Dictionary<string, object> values)
        {
            if (values == null || !values.ContainsKey("userQuestions")) return null;
            var questions = values["userQuestions"] as Dictionary<string, object>;
            if (questions == null) return null;
            return ListField(questions, "active");
        }

        private static bool HasActiveQuestion(Dictionary<string, object> values)
        {
            var active = DigQuestionList(values);
            if (active == null || active.Count == 0) return false;
            var first = active[0] as Dictionary<string, object>;
            if (first == null) return false;
            var questions = ListField(first, "questions");
            return questions != null && questions.Count > 0;
        }

        /// <summary>把提问压成手表要的结构。</summary>
        public static List<object> CompactQuestions(List<object> questions)
        {
            var list = new List<object>();
            if (questions == null) return list;
            foreach (var raw in questions)
            {
                var question = raw as Dictionary<string, object>;
                if (question == null) continue;
                var entry = new Dictionary<string, object>();
                entry["id"] = GetString(question, "id");
                entry["header"] = GetString(question, "header");
                entry["question"] = GetString(question, "question");
                entry["multiSelect"] = GetBool(question, "multiSelect");

                var options = new List<object>();
                var rawOptions = ListField(question, "options");
                if (rawOptions != null)
                {
                    foreach (var rawOption in rawOptions)
                    {
                        var option = rawOption as Dictionary<string, object>;
                        if (option == null) continue;
                        string label = GetString(option, "label");
                        if (string.IsNullOrEmpty(label)) continue;
                        var item = new Dictionary<string, object>();
                        item["label"] = label;
                        item["description"] = GetString(option, "description");
                        options.Add(item);
                    }
                }
                entry["options"] = options;
                list.Add(entry);
            }
            return list;
        }

        // ---------------------------------------------------------------- 小工具

        /// <summary>一个只含 ok:true 的成功响应。</summary>
        private static Http.Response Ok()
        {
            var result = new Dictionary<string, object>();
            result["ok"] = true;
            return Http.Json(200, result);
        }

        private static Dictionary<string, object> Simple(string message)
        {
            var result = new Dictionary<string, object>();
            result["ok"] = false;
            result["error"] = message;
            return result;
        }

        public object ParseJson(string text)
        {
            if (string.IsNullOrEmpty(text)) return null;
            try { return json.DeserializeObject(text); }
            catch { return null; }
        }

        private static long EarliestSeq(List<object> records)
        {
            long earliest = 0;
            if (records == null) return 0;
            foreach (var raw in records)
            {
                var record = raw as Dictionary<string, object>;
                if (record == null || !record.ContainsKey("event")) continue;
                var evt = record["event"] as Dictionary<string, object>;
                if (evt == null) continue;
                long seq = GetLong(evt, "seq");
                if (seq > 0 && (earliest == 0 || seq < earliest)) earliest = seq;
            }
            return earliest;
        }

        public static Dictionary<string, object> Dig(Dictionary<string, object> source, params string[] path)
        {
            object current = source;
            foreach (string key in path)
            {
                var map = current as Dictionary<string, object>;
                if (map == null || !map.ContainsKey(key)) return null;
                current = map[key];
            }
            return current as Dictionary<string, object>;
        }

        /// <summary>
        /// 把 JSON 数组字段转成 List&lt;object&gt;。
        ///
        /// **必须走这里，不能用 `as List&lt;object&gt;`**：JavaScriptSerializer 把 JSON 数组
        /// 反序列化成 `object[]`（数组），不是 `List&lt;object&gt;`，`as` 转换会静默得到 null，
        /// 表现为"接口正常但一条数据都没有"。这个坑在 18 处同时存在过，只在会话列表上
        /// 暴露出来（其余接口碰巧没被直接调用）。
        /// </summary>
        public static List<object> AsList(object value)
        {
            if (value == null) return null;
            var list = value as List<object>;
            if (list != null) return list;
            var array = value as object[];
            if (array != null) return new List<object>(array);
            // 其它可枚举类型（反序列化器换实现时可能出现）也一并接住。
            var enumerable = value as System.Collections.IEnumerable;
            if (enumerable == null || value is string) return null;
            var converted = new List<object>();
            foreach (object item in enumerable) converted.Add(item);
            return converted;
        }

        /// <summary>从字典里取一个数组字段（字段不存在或类型不对时返回 null）。</summary>
        public static List<object> ListField(Dictionary<string, object> source, string key)
        {
            if (source == null || !source.ContainsKey(key)) return null;
            return AsList(source[key]);
        }

        public static string GetString(Dictionary<string, object> source, string key)
        {
            if (source == null || !source.ContainsKey(key)) return "";
            object value = source[key];
            return value == null ? "" : Convert.ToString(value);
        }

        public static long GetLong(Dictionary<string, object> source, string key)
        {
            if (source == null || !source.ContainsKey(key)) return 0;
            object value = source[key];
            if (value == null) return 0;
            try { return Convert.ToInt64(value, CultureInfo.InvariantCulture); }
            catch { return 0; }
        }

        public static bool GetBool(Dictionary<string, object> source, string key)
        {
            if (source == null || !source.ContainsKey(key)) return false;
            object value = source[key];
            return value is bool && (bool)value;
        }
    }
}
