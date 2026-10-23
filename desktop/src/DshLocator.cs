using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Net;
using System.Text;
using System.Text.RegularExpressions;

namespace DshRelay
{
    /// <summary>
    /// 一个已识别的 DSH 实例。
    /// </summary>
    public class DshInstance
    {
        /// <summary>DSH 主目录（含 .credentials.yaml）。</summary>
        public string Home;
        /// <summary>web 端口。</summary>
        public int Port;
        /// <summary>凭据文件路径。</summary>
        public string CredentialsFile;
        /// <summary>识别来源，用于在界面上说明为什么选它。</summary>
        public string Source;
        /// <summary>签名密钥（32 字节）。</summary>
        public byte[] Secret;
        /// <summary>签名密钥的原始 base64url 写法（用于显示与热更新比较）。</summary>
        public string SecretRaw;

        /// <summary>显示用的简称。</summary>
        public string Label
        {
            get { return "127.0.0.1:" + Port + "（" + Source + "）"; }
        }
    }

    /// <summary>
    /// 在本机寻找正在运行的 DSH 实例。
    ///
    /// 为什么要自动发现：用户装 DSH 的位置各不相同（可能在 %LOCALAPPDATA%、
    /// 也可能在 D 盘的任意目录），而中继需要两样东西才能工作——DSH 的 web 端口
    /// 与它签发的 browser-session 密钥。两者都可以从运行中的进程与磁盘状态推断出来，
    /// 因此不必让用户手填路径。
    ///
    /// 探测顺序按「可信度」排列，命中第一个可用实例即返回：
    /// 1. 已知安装位置下的 .dsh 目录；
    /// 2. 正在运行的 DSH 进程所在目录；
    /// 3. 磁盘上任意位置的 .dsh 目录（限定几个常见根目录，避免全盘扫描）。
    /// </summary>
    public static class DshLocator
    {
        /// <summary>DSH desktop 众所周知的默认端口。</summary>
        public const int DefaultPort = 19387;

        /// <summary>额外要试的端口（web profile 与用户自定义）。</summary>
        private static readonly int[] CandidatePorts = new int[] { 19387, 3080, 19388, 19389 };

        /// <summary>
        /// 找出一个可用的 DSH 实例；找不到返回 null。
        /// </summary>
        /// <param name="manualHome">用户手动指定的 .dsh 目录（可为空）。</param>
        /// <param name="log">进度回调。</param>
        public static DshInstance Locate(string manualHome, Action<string> log)
        {
            var homes = new List<KeyValuePair<string, string>>();

            if (!string.IsNullOrEmpty(manualHome) && Directory.Exists(manualHome))
            {
                homes.Add(new KeyValuePair<string, string>(manualHome, "手动指定"));
            }

            // 1) 运行中的 DSH 进程：从它的可执行文件位置反推 .dsh 目录。
            foreach (var home in HomesFromRunningDsh(log))
            {
                homes.Add(home);
            }

            // 2) 用户主目录下的 .dsh（最常见）。
            string userHome = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), ".dsh");
            if (Directory.Exists(userHome))
            {
                homes.Add(new KeyValuePair<string, string>(userHome, "用户目录"));
            }

            // 3) 常见安装位置旁边的 .dsh（用户把 DSH 装在 D 盘等情况）。
            foreach (var home in HomesFromCommonRoots())
            {
                homes.Add(home);
            }

            // 去重后逐个验证：必须同时有凭据文件与一个能连通的端口。
            var seen = new Dictionary<string, bool>(StringComparer.OrdinalIgnoreCase);
            DshInstance fallback = null;
            foreach (var pair in homes)
            {
                string home = pair.Key;
                if (seen.ContainsKey(home)) continue;
                seen[home] = true;

                DshInstance instance = Probe(home, pair.Value, log);
                if (instance == null) continue;

                // 端口连得上就用它；连不上则先留着，继续找更好的。
                if (instance.Port > 0) return instance;
                if (fallback == null) fallback = instance;
            }

            if (fallback != null)
            {
                log("找到 DSH 配置，但端口连不上——请确认 DSH 桌面版已启动。");
                if (!string.IsNullOrEmpty(LastPortDiagnostic))
                {
                    log("端口探测详情：" + LastPortDiagnostic);
                }
                return fallback;
            }
            return null;
        }

        /// <summary>从运行中的 DSH 进程推测 .dsh 目录。</summary>
        private static IEnumerable<KeyValuePair<string, string>> HomesFromRunningDsh(Action<string> log)
        {
            var list = new List<KeyValuePair<string, string>>();
            Process[] processes;
            try
            {
                processes = Process.GetProcessesByName("DeepSeek Harness");
            }
            catch
            {
                return list;
            }

            foreach (var process in processes)
            {
                try
                {
                    string exe = process.MainModule == null ? null : process.MainModule.FileName;
                    if (string.IsNullOrEmpty(exe)) continue;
                    log("发现运行中的 DSH：" + exe);

                    // 进程自己会带着 DSH_HOME，但拿不到环境变量；
                    // 因此按「exe 同级或上级存在 .dsh」来推断。
                    string dir = Path.GetDirectoryName(exe);
                    for (int depth = 0; depth < 4 && !string.IsNullOrEmpty(dir); depth++)
                    {
                        string candidate = Path.Combine(dir, ".dsh");
                        if (File.Exists(Path.Combine(candidate, ".credentials.yaml")))
                        {
                            list.Add(new KeyValuePair<string, string>(candidate, "运行中的进程"));
                            break;
                        }
                        dir = Path.GetDirectoryName(dir);
                    }
                }
                catch
                {
                    // 某些进程读不到 MainModule（权限不足），跳过。
                }
                finally
                {
                    process.Dispose();
                }
            }
            return list;
        }

        /// <summary>扫描几个常见根目录下的 .dsh。</summary>
        private static IEnumerable<KeyValuePair<string, string>> HomesFromCommonRoots()
        {
            var list = new List<KeyValuePair<string, string>>();
            var roots = new List<string>();
            try
            {
                string local = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
                roots.Add(Path.Combine(local, "Programs"));
                roots.Add(local);
                roots.Add(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles));
                roots.Add(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFilesX86));
            }
            catch { }

            // 其它盘符的根目录（用户常把应用装在 D 盘）。
            try
            {
                foreach (var drive in DriveInfo.GetDrives())
                {
                    if (drive.DriveType != DriveType.Fixed || !drive.IsReady) continue;
                    string letter = drive.RootDirectory.FullName;
                    if (letter.StartsWith("C:", StringComparison.OrdinalIgnoreCase)) continue;
                    roots.Add(letter);
                }
            }
            catch { }

            foreach (var root in roots)
            {
                if (string.IsNullOrEmpty(root) || !Directory.Exists(root)) continue;
                try
                {
                    foreach (var dir in Directory.GetDirectories(root))
                    {
                        string candidate = Path.Combine(dir, ".dsh");
                        if (File.Exists(Path.Combine(candidate, ".credentials.yaml")))
                        {
                            list.Add(new KeyValuePair<string, string>(candidate, Path.GetFileName(dir)));
                        }
                    }
                }
                catch
                {
                    // 权限不足时跳过这个根目录。
                }
            }
            return list;
        }

        /// <summary>检验一个目录是否是有效 DSH 主目录，并找到它能连通的端口。</summary>
        private static DshInstance Probe(string home, string source, Action<string> log)
        {
            string credentials = Path.Combine(home, ".credentials.yaml");
            if (!File.Exists(credentials)) return null;

            var instance = new DshInstance();
            instance.Home = home;
            instance.CredentialsFile = credentials;
            instance.Source = source;

            if (!TryReadSecret(credentials, out instance.Secret, out instance.SecretRaw))
            {
                log("在 " + home + " 找到 DSH 配置，但读不出签名密钥。");
                return null;
            }

            instance.Port = FindListeningPort();
            return instance;
        }

        /// <summary>
        /// 从 .credentials.yaml 里取 browser-session 的签名密钥。
        ///
        /// 不引入 YAML 解析库：文件结构固定，只需定位记录名后取 secret 字段。
        /// </summary>
        public static bool TryReadSecret(string credentialsFile, out byte[] secret, out string raw)
        {
            secret = null;
            raw = null;
            try
            {
                string text = File.ReadAllText(credentialsFile, Encoding.UTF8);
                const string marker = "client-connection/browser-session:";
                int at = text.IndexOf(marker, StringComparison.Ordinal);
                if (at < 0) return false;

                string tail = text.Substring(at);
                Match match = Regex.Match(tail, @"secret:\s*([A-Za-z0-9_\-]+)");
                if (!match.Success) return false;

                byte[] decoded = Base64UrlDecode(match.Groups[1].Value);
                if (decoded == null || decoded.Length != 32) return false;

                raw = match.Groups[1].Value;
                secret = decoded;
                return true;
            }
            catch
            {
                return false;
            }
        }

        /// <summary>
        /// 找一个 DSH 的 HTTP 端口。
        ///
        /// 顺序是「先问系统、再猜常量」：
        /// 1. **看 DSH 进程自己在监听哪些端口**——DSH 桌面版的端口是运行时随机分配的
        ///    （实测见过 24107、3080、19387 等各种值），只靠写死的列表会完全找不到；
        /// 2. 再退回已知的常用端口。
        ///
        /// 第 1 步是必须的：早期版本只试写死的 19387/3080/19388/19389，
        /// 碰上随机端口的 DSH 就报「没有找到 DSH」。
        /// </summary>
        private static int FindListeningPort()
        {
            int port = FindPortFromProcesses();
            if (port > 0) return port;

            foreach (int candidate in CandidatePorts)
            {
                if (IsListening(candidate)) return candidate;
            }
            return 0;
        }

        /// <summary>最近一次端口探测的诊断信息（失败时写进日志，便于排查）。</summary>
        public static string LastPortDiagnostic = "";

        /// <summary>
        /// 本进程（中继自己）正在监听的端口。
        ///
        /// **必须排除**：中继的控制台也在 loopback 上提供 HTTP 页面，而那个页面的
        /// 标题就写着「DSH 中继」——正文判据（含 dsh / deepseek）会把它当成 DSH API。
        /// 实际踩到过：探测选中了自己的控制台端口，随后所有 RPC 都 404，
        /// 界面上表现为「连不上 DSH」，而 DSH 明明开着。
        /// </summary>
        private static readonly List<int> OwnPorts = new List<int>();

        /// <summary>登记一个属于本进程的端口，端口探测会跳过它。</summary>
        public static void MarkOwnPort(int port)
        {
            if (port <= 0) return;
            lock (OwnPorts)
            {
                if (!OwnPorts.Contains(port)) OwnPorts.Add(port);
            }
        }

        private static bool IsOwnPort(int port)
        {
            lock (OwnPorts) { return OwnPorts.Contains(port); }
        }

        /// <summary>
        /// 从正在运行的 DSH 进程所监听的 TCP 端口里挑一个能当 HTTP 服务用的。
        ///
        /// 通过 IP Helper API 读系统连接表（无需管理员权限，比 netstat 解析可靠），
        /// 拿到属于 DSH 进程的监听端口，再逐个探活确认确实是 HTTP 服务。
        /// </summary>
        private static int FindPortFromProcesses()
        {
            var processIds = new List<int>();
            int totalProcesses = 0;
            try
            {
                foreach (var process in System.Diagnostics.Process.GetProcesses())
                {
                    totalProcesses++;
                    try
                    {
                        string name = process.ProcessName ?? "";
                        // DSH 桌面版的进程名形如 "DeepSeek Harness"；
                        // 也兼容命令行版（node/dsh）的情况。
                        if (name.IndexOf("DeepSeek Harness", StringComparison.OrdinalIgnoreCase) >= 0
                            || name.IndexOf("DeepSeekHarness", StringComparison.OrdinalIgnoreCase) >= 0)
                        {
                            processIds.Add(process.Id);
                        }
                    }
                    catch { }
                    finally { try { process.Dispose(); } catch { } }
                }
            }
            catch { }

            var candidates = new List<int>();
            foreach (int ownerPid in processIds)
            {
                foreach (int port in ListeningPortsOf(ownerPid))
                {
                    if (!candidates.Contains(port)) candidates.Add(port);
                }
            }
            candidates.Sort();
            if (candidates.Count > 0)
            {
                var probeLog = new StringBuilder();
                foreach (int port in candidates)
                {
                    if (IsOwnPort(port)) continue;
                    if (IsDshHttp(port, probeLog))
                    {
                        LastPortDiagnostic = "按进程定位：进程 " + processIds.Count + " 个，候选端口 "
                            + JoinInts(candidates) + "，命中 " + port;
                        return port;
                    }
                }
                LastPortDiagnostic = "按进程定位失败（候选 " + JoinInts(candidates) + "）：" + probeLog;
            }

            // 兜底：直接扫本机所有监听端口去找 DSH 的 HTTP 服务。
            //
            // 为什么需要这一步：按进程名匹配依赖读取别的进程信息，在某些受限环境
            // （不同会话、提权进程、受控沙箱）下会读到空列表——实测就遇到过
            // 「诊断程序能看到 11 个进程，中继只看得到 2 个」。
            // 扫描监听端口不依赖读进程，稳得多；代价只是多探几个端口。
            int scanned = ScanListeningForDsh();
            if (scanned > 0)
            {
                LastPortDiagnostic = "按端口扫描命中 " + scanned
                    + "（进程定位未成功：进程 " + processIds.Count + "/" + totalProcesses + "）";
                return scanned;
            }

            LastPortDiagnostic = "进程 " + processIds.Count + "/" + totalProcesses
                + "，候选端口 " + (candidates.Count == 0 ? "(空)" : JoinInts(candidates))
                + "，端口扫描也未命中";
            return 0;
        }

        /// <summary>
        /// 扫描本机的监听端口，找出 DSH 的 HTTP 服务。
        ///
        /// 只看「本地回环或任意地址上正在监听」的端口，逐个探活。
        /// 端口数量通常只有几十个，且 DSH 的端口往往在低位或常用段，很快就命中。
        /// </summary>
        private static int ScanListeningForDsh()
        {
            var ports = new List<int>();
            int size = 0;
            const int AF_INET = 2;
            const int TCP_TABLE_OWNER_PID_LISTENER = 3;

            GetExtendedTcpTable(IntPtr.Zero, ref size, false, AF_INET, TCP_TABLE_OWNER_PID_LISTENER, 0);
            if (size <= 0) return 0;

            IntPtr buffer = IntPtr.Zero;
            try
            {
                buffer = System.Runtime.InteropServices.Marshal.AllocHGlobal(size);
                if (GetExtendedTcpTable(buffer, ref size, false, AF_INET, TCP_TABLE_OWNER_PID_LISTENER, 0) != 0)
                {
                    return 0;
                }
                int rows = System.Runtime.InteropServices.Marshal.ReadInt32(buffer);
                IntPtr row = new IntPtr(buffer.ToInt64() + 4);
                const int RowSize = 24;
                for (int i = 0; i < rows; i++)
                {
                    int raw = System.Runtime.InteropServices.Marshal.ReadInt32(row, 8);
                    int port = ((raw & 0xFF) << 8) | ((raw >> 8) & 0xFF);
                    // 跳过系统保留段与明显不是 HTTP 的端口，减少探测次数。
                    if (port > 1024 && !ports.Contains(port)) ports.Add(port);
                    row = new IntPtr(row.ToInt64() + RowSize);
                }
            }
            catch { return 0; }
            finally
            {
                if (buffer != IntPtr.Zero)
                {
                    try { System.Runtime.InteropServices.Marshal.FreeHGlobal(buffer); } catch { }
                }
            }

            ports.Sort();
            var probeLog = new StringBuilder();
            foreach (int port in ports)
            {
                if (IsOwnPort(port)) continue;
                if (IsDshHttp(port, probeLog)) return port;
            }
            return 0;
        }

        private static string JoinInts(List<int> values)
        {
            var parts = new List<string>();
            foreach (int value in values) parts.Add(value.ToString());
            return string.Join(",", parts.ToArray());
        }

        /// <summary>
        /// 确认某个端口上跑的确实是 DSH 的 HTTP 服务。
        ///
        /// 判定依据是**响应体位**：DSH 未授权时返回 401，且正文里带 dsh/deepseek 字样；
        /// 而本机其它随机端口（Chromium 的调试端口等）也会回 401，但正文完全不同。
        /// 早期只看状态码，会把 Chromium 的调试端口误判成 DSH。
        /// </summary>
        private static bool IsDshHttp(int port)
        {
            return IsDshHttp(port, null);
        }

        private static bool IsDshHttp(int port, StringBuilder probeLog)
        {
            try
            {
                var request = (System.Net.HttpWebRequest)System.Net.WebRequest.Create(
                    "http://127.0.0.1:" + port + "/");
                request.Proxy = null;
                request.Timeout = 2000;
                request.ReadWriteTimeout = 2000;
                request.UserAgent = "dsh-relay/1";
                request.AllowAutoRedirect = false;

                System.Net.HttpWebResponse response;
                try
                {
                    response = (System.Net.HttpWebResponse)request.GetResponse();
                }
                catch (System.Net.WebException error)
                {
                    // 401/403 会走异常分支，但那正是我们要找的东西，继续读正文。
                    response = error.Response as System.Net.HttpWebResponse;
                    if (response == null) return false;
                }

                using (response)
                {
                    int status = (int)response.StatusCode;
                    if (status != 401 && status != 200)
                    {
                        if (probeLog != null) probeLog.Append(" " + port + "=" + status + ";");
                        return false;
                    }

                    string body = "";
                    try
                    {
                        using (var stream = response.GetResponseStream())
                        {
                            if (stream != null)
                            {
                                using (var reader = new StreamReader(stream, Encoding.UTF8))
                                {
                                    // 只需看开头一小段就能判断。
                                    char[] buffer = new char[2048];
                                    int read = reader.Read(buffer, 0, buffer.Length);
                                    body = new string(buffer, 0, Math.Max(0, read));
                                }
                            }
                        }
                    }
                    catch { }

                    // 正面特征：DSH 的页面里带这些标识。
                    bool looksDsh = body.IndexOf("dsh", StringComparison.OrdinalIgnoreCase) >= 0
                        || body.IndexOf("deepseek", StringComparison.OrdinalIgnoreCase) >= 0
                        || body.IndexOf("harness", StringComparison.OrdinalIgnoreCase) >= 0;
                    // 反面特征：中继自己的控制台页面也含「DSH」，靠这两个标记排除。
                    // 只靠正文判据会把自家控制台认成 DSH（实测踩过）。
                    bool looksRelayPanel = body.IndexOf("dsh-relay", StringComparison.OrdinalIgnoreCase) >= 0
                        || body.IndexOf("配对码", StringComparison.Ordinal) >= 0
                        || body.IndexOf("control-panel", StringComparison.OrdinalIgnoreCase) >= 0;
                    if (looksDsh && !looksRelayPanel) return true;
                    if (probeLog != null)
                    {
                        string head = body.Length > 40 ? body.Substring(0, 40).Replace("\n", " ") : body;
                        probeLog.Append(" " + port + "=" + status + "/未命中(" + head.Length + "字);");
                    }
                    return false;
                }
            }
            catch
            {
                return false;
            }
        }

        /// <summary>查某个进程正在监听的 TCP 端口（用 IP Helper API，无需解析 netstat 输出）。</summary>
        private static List<int> ListeningPortsOf(int ownerPid)
        {
            var ports = new List<int>();
            int size = 0;
            const int AF_INET = 2;
            const int TCP_TABLE_OWNER_PID_LISTENER = 3;

            // 第一次调用只为拿所需缓冲区大小。
            GetExtendedTcpTable(IntPtr.Zero, ref size, false, AF_INET, TCP_TABLE_OWNER_PID_LISTENER, 0);
            if (size <= 0) return ports;

            IntPtr buffer = IntPtr.Zero;
            try
            {
                buffer = System.Runtime.InteropServices.Marshal.AllocHGlobal(size);
                int result = GetExtendedTcpTable(buffer, ref size, false, AF_INET, TCP_TABLE_OWNER_PID_LISTENER, 0);
                if (result != 0) return ports;

                int rows = System.Runtime.InteropServices.Marshal.ReadInt32(buffer);
                IntPtr row = new IntPtr(buffer.ToInt64() + 4);
                // MIB_TCPROW_OWNER_PID 结构固定 24 字节。
                const int RowSize = 24;
                for (int i = 0; i < rows; i++)
                {
                    int pid = System.Runtime.InteropServices.Marshal.ReadInt32(row, 20);
                    if (pid == ownerPid)
                    {
                        // 本地端口在偏移 8，网络字节序（高字节在前）。
                        int raw = System.Runtime.InteropServices.Marshal.ReadInt32(row, 8);
                        int port = ((raw & 0xFF) << 8) | ((raw >> 8) & 0xFF);
                        if (port > 0 && !ports.Contains(port)) ports.Add(port);
                    }
                    row = new IntPtr(row.ToInt64() + RowSize);
                }
            }
            catch { }
            finally
            {
                if (buffer != IntPtr.Zero)
                {
                    try { System.Runtime.InteropServices.Marshal.FreeHGlobal(buffer); } catch { }
                }
            }
            return ports;
        }

        [System.Runtime.InteropServices.DllImport("iphlpapi.dll", SetLastError = true)]
        private static extern int GetExtendedTcpTable(
            IntPtr pTcpTable, ref int pdwSize, bool bOrder, int ulAf, int tableClass, uint reserved);

        /// <summary>
        /// DSH 是否还在运行（供「随 DSH 启停」的监视线程使用）。
        ///
        /// 先看已知端口是否仍在监听——DSH 重启后端口可能变，端口不通时再做一次
        /// 完整定位（较慢，所以只在需要时做）。找不到配置也当作「没在运行」。
        /// </summary>
        public static bool IsRunning(int knownPort)
        {
            try
            {
                if (knownPort > 0 && IsListening(knownPort)) return true;
                DshInstance instance = Locate(null, delegate { });
                return instance != null && instance.Port > 0;
            }
            catch
            {
                return false;
            }
        }

        /// <summary>端口是否在本机监听。</summary>
        public static bool IsListening(int port)
        {
            try
            {
                var client = new System.Net.Sockets.TcpClient();
                IAsyncResult result = client.BeginConnect(IPAddress.Loopback, port, null, null);
                bool ok = result.AsyncWaitHandle.WaitOne(600);
                if (ok) client.EndConnect(result);
                client.Close();
                return ok;
            }
            catch
            {
                return false;
            }
        }

        /// <summary>base64url 解码（DSH 用它编码二进制）。</summary>
        public static byte[] Base64UrlDecode(string value)
        {
            if (string.IsNullOrEmpty(value)) return null;
            string text = value.Replace('-', '+').Replace('_', '/');
            switch (text.Length % 4)
            {
                case 2: text += "=="; break;
                case 3: text += "="; break;
                case 1: return null;
            }
            try { return Convert.FromBase64String(text); }
            catch { return null; }
        }

        /// <summary>base64url 编码。</summary>
        public static string Base64UrlEncode(byte[] value)
        {
            return Convert.ToBase64String(value)
                .Replace('+', '-').Replace('/', '_').TrimEnd('=');
        }
    }
}
