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

        /// <summary>在候选端口里找一个真的在监听的。</summary>
        private static int FindListeningPort()
        {
            foreach (int port in CandidatePorts)
            {
                if (IsListening(port)) return port;
            }
            return 0;
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
