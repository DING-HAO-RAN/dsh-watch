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
        private const int MaxSessions = 60;
        private const int MaxMessages = 40;

        private readonly DshClient dsh;
        private readonly JavaScriptSerializer json;
        private readonly object gate = new object();
        private readonly Dictionary<string, long> tokens = new Dictionary<string, long>();
        private readonly Action<string> log;

        private TcpListener listener;
        private Thread acceptThread;
        private UdpClient beacon;
        private Timer beaconTimer;
        private volatile bool stopping;

        /// <summary>6 位配对码；配对成功后更换，也可由界面手动更换。</summary>
        public string PairingCode { get; set; }
        public int Port { get; private set; }
        public bool Running { get; private set; }
        /// <summary>公网地址（隧道就绪后由界面层注入）。</summary>
        public string PublicUrl { get; set; }
        public DateTime StartedAt { get; private set; }
        /// <summary>已配对设备数。</summary>
        public int PairedDevices { get { lock (gate) { return tokens.Count; } } }

        public RelayServer(DshClient dsh, int port, Action<string> log)
        {
            this.dsh = dsh;
            this.Port = port;
            this.log = log ?? delegate { };
            this.json = new JavaScriptSerializer();
            this.json.MaxJsonLength = 64 * 1024 * 1024;
            this.PairingCode = NewPairingCode();
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

        /// <summary>周期性 UDP 广播，让手表无需手输 IP 即可发现。</summary>
        private void StartBeacon()
        {
            try
            {
                beacon = new UdpClient();
                beacon.EnableBroadcast = true;
                var payload = Encoding.UTF8.GetBytes(
                    "{\"service\":\"" + ServiceTag + "\",\"port\":" + Port.ToString(CultureInfo.InvariantCulture) + ",\"name\":\"DSH 中继\"}");
                var target = new IPEndPoint(IPAddress.Broadcast, DiscoveryPort);
                beaconTimer = new Timer(delegate (object unused)
                {
                    try { beacon.Send(payload, payload.Length, target); }
                    catch { }
                }, null, 0, 2000);
            }
            catch (Exception error)
            {
                log("广播启动失败（不影响局域网直连）：" + error.Message);
            }
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
                using (var stream = client.GetStream())
                {
                    // 只处理一条请求——手表每次调用都是独立的短连接，避免长连管理。
                    var request = Http.ReadRequest(stream);
                    if (request == null) return;
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
                if (code == null || code.Trim() != PairingCode)
                {
                    return Http.Json(403, Simple("配对码不正确"));
                }
                // 配对成功后换一个新口令，防止同一口令被重复使用。
                string token = NewToken();
                lock (gate) { PairingCode = NewPairingCode(); }
                log("手表已配对，新的配对码：" + PairingCode);
                var ok = new Dictionary<string, object>();
                ok["ok"] = true;
                ok["token"] = token;
                ok["expiresInMs"] = TokenTtlMs;
                return Http.Json(200, ok);
            }

            if (!IsAuthorized(request))
            {
                return Http.Json(401, Simple("未配对或令牌已失效"));
            }

            try
            {
                if (path == "/api/sessions" && request.Method == "GET") return Sessions();
                if (path == "/api/session" && request.Method == "GET") return Session(request);
                if (path == "/api/send" && request.Method == "POST") return Send(request);
                if (path == "/api/cancel" && request.Method == "POST") return Cancel(request);
                if (path == "/api/create" && request.Method == "POST") return Create();
                if (path == "/api/models" && request.Method == "GET") return Models();
                if (path == "/api/model" && request.Method == "POST") return SelectModel(request);
                if (path == "/api/answer" && request.Method == "POST") return Answer(request);
            }
            catch (DshException error)
            {
                return Http.Json(502, Simple(error.Message));
            }
            catch (Exception error)
            {
                return Http.Json(500, Simple(error.Message));
            }

            return Http.Json(404, Simple("未知接口"));
        }

        private Dictionary<string, object> StatusObject()
        {
            var result = new Dictionary<string, object>();
            result["service"] = ServiceTag;
            result["name"] = "DSH 手表中继";
            result["version"] = 2;
            result["needsPairing"] = true;
            result["uptimeMs"] = (long)(DateTime.Now - StartedAt).TotalMilliseconds;
            if (!string.IsNullOrEmpty(PublicUrl)) result["publicUrl"] = PublicUrl;
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

        // ---------------------------------------------------------------- 接口实现

        private Http.Response Sessions()
        {
            var args = new Dictionary<string, object>();
            args["_request"] = new Dictionary<string, object>();
            var value = dsh.Rpc("session/list", args) as Dictionary<string, object>;

            var sessions = new List<object>();
            var projects = new Dictionary<string, Dictionary<string, object>>();
            var items = value != null && value.ContainsKey("items") ? value["items"] as List<object> : null;

            if (items != null)
            {
                foreach (var raw in items)
                {
                    var item = raw as Dictionary<string, object>;
                    if (item == null) continue;
                    string origin = GetString(item, "origin");
                    if (origin == "subagent") continue;

                    var session = NormalizeSession(item);
                    sessions.Add(session);
                    if (sessions.Count >= MaxSessions) break;

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

        private Dictionary<string, object> NormalizeSession(Dictionary<string, object> item)
        {
            var values = Dig(item, "projections", "values");
            string title = GetString(values, "title");
            string cwd = GetString(item, "cwd");
            if (title == null || title.Trim().Length == 0) title = cwd.Length == 0 ? "未命名会话" : Path.GetFileName(cwd.TrimEnd('\\', '/'));

            var model = values != null && values.ContainsKey("modelSelection")
                ? values["modelSelection"] as Dictionary<string, object> : null;
            var next = model != null && model.ContainsKey("next") ? model["next"] as Dictionary<string, object> : null;

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
            session["subagent"] = false;
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
            var address = new Dictionary<string, object>();
            address["kind"] = "session";
            address["sessionId"] = id;
            pageRequest["address"] = address;
            pageRequest["throughSeq"] = through;
            pageRequest["maxMessages"] = MaxMessages;
            pageArgs["request"] = pageRequest;
            var page = dsh.Rpc("session/page", pageArgs) as Dictionary<string, object>;

            List<object> records = page != null && page.ContainsKey("records") ? page["records"] as List<object> : null;
            var values = projection != null && projection.ContainsKey("values") ? projection["values"] as Dictionary<string, object> : null;

            var result = new Dictionary<string, object>();
            result["ok"] = true;
            result["sessionId"] = id;
            result["title"] = GetString(values, "title");
            result["running"] = false;
            result["hasMore"] = page != null && GetBool(page, "hasMore");
            result["asOfSeq"] = asOfSeq;
            result["beforeSeq"] = EarliestSeq(records);
            result["messages"] = Steps.NormalizeMessages(records);
            result["steps"] = Steps.NormalizeSteps(records);
            result["usage"] = Usage.Normalize(values);

            var question = ActiveQuestion(values);
            if (question != null) result["question"] = question;
            return Http.Json(200, result);
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
            return Http.Json(200, Ok());
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
            return Http.Json(200, Ok());
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
            if (value != null && value.ContainsKey("routableProviders"))
            {
                var list = value["routableProviders"] as List<object>;
                if (list != null) foreach (var item in list) routable[Convert.ToString(item)] = true;
            }

            var groups = new List<object>();
            var rawGroups = value != null && value.ContainsKey("groups") ? value["groups"] as List<object> : null;
            if (rawGroups != null)
            {
                foreach (var raw in rawGroups)
                {
                    var group = raw as Dictionary<string, object>;
                    if (group == null) continue;
                    string provider = GetString(group, "id");
                    if (routable.Count > 0 && !routable.ContainsKey(provider)) continue;

                    var models = new List<object>();
                    var rawModels = group.ContainsKey("models") ? group["models"] as List<object> : null;
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
            var efforts = reasoning != null && reasoning.ContainsKey("efforts") ? reasoning["efforts"] as List<object> : null;
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
            var answers = body.ContainsKey("answers") ? body["answers"] as List<object> : null;
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
                var rawSelected = item.ContainsKey("selected") ? item["selected"] as List<object> : null;
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
                    entry["questions"] = CompactQuestions(question.ContainsKey("questions") ? question["questions"] as List<object> : null);
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
            entry["questions"] = CompactQuestions(first.ContainsKey("questions") ? first["questions"] as List<object> : null);
            return entry;
        }

        private static List<object> DigQuestionList(Dictionary<string, object> values)
        {
            if (values == null || !values.ContainsKey("userQuestions")) return null;
            var questions = values["userQuestions"] as Dictionary<string, object>;
            if (questions == null || !questions.ContainsKey("active")) return null;
            return questions["active"] as List<object>;
        }

        private static bool HasActiveQuestion(Dictionary<string, object> values)
        {
            var active = DigQuestionList(values);
            if (active == null || active.Count == 0) return false;
            var first = active[0] as Dictionary<string, object>;
            if (first == null) return false;
            var questions = first.ContainsKey("questions") ? first["questions"] as List<object> : null;
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
                var rawOptions = question.ContainsKey("options") ? question["options"] as List<object> : null;
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
