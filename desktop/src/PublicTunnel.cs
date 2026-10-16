using System;
using System.Diagnostics;
using System.IO;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;

namespace DshRelay
{
    /// <summary>
    /// 公网中转：拉起 cloudflared 生成一条临时公网地址。
    ///
    /// 为什么用 cloudflared：它把 https://&lt;随机名&gt;.trycloudflare.com 的流量转发到本机端口，
    /// 不需要域名、不需要在路由器上开放端口，退出即失效——对「临时给手表连一下」
    /// 这个场景最合适，也不必让用户准备服务器。
    /// </summary>
    public class PublicTunnel : IDisposable
    {
        private static readonly Regex PublicUrlPattern =
            new Regex(@"https://[a-z0-9\-]+\.trycloudflare\.com", RegexOptions.Compiled);

        private readonly string binary;
        private readonly int port;
        private readonly Action<string> log;
        private Process process;
        private readonly StringBuilder output = new StringBuilder();

        public string Url { get; private set; }
        public bool Running { get { return process != null && !process.HasExited; } }

        public PublicTunnel(string binary, int port, Action<string> log)
        {
            this.binary = binary;
            this.port = port;
            this.log = log ?? delegate { };
        }

        /// <summary>在几个常见位置找 cloudflared；都找不到时退回 PATH 上的名字。</summary>
        public static string FindBinary(string explicitPath, string appDirectory)
        {
            var candidates = new System.Collections.Generic.List<string>();
            if (!string.IsNullOrEmpty(explicitPath)) candidates.Add(explicitPath);
            if (!string.IsNullOrEmpty(appDirectory))
            {
                candidates.Add(Path.Combine(appDirectory, "cloudflared.exe"));
                candidates.Add(Path.Combine(appDirectory, "bin", "cloudflared.exe"));
            }
            try
            {
                string userProfile = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
                candidates.Add(Path.Combine(userProfile, ".dsh", "profiles", "desktop",
                    "node_modules", "cloudflared", "bin", "cloudflared.exe"));
            }
            catch { }

            foreach (string candidate in candidates)
            {
                if (!string.IsNullOrEmpty(candidate) && File.Exists(candidate)) return candidate;
            }
            return "cloudflared.exe";
        }

        public bool Available()
        {
            if (Path.IsPathRooted(binary)) return File.Exists(binary);
            // 只给了名字：去 PATH 里找一遍。
            string path = Environment.GetEnvironmentVariable("PATH") ?? "";
            foreach (string dir in path.Split(';'))
            {
                if (dir.Trim().Length == 0) continue;
                try
                {
                    if (File.Exists(Path.Combine(dir.Trim(), binary))) return true;
                }
                catch { }
            }
            return false;
        }

        /// <summary>
        /// 启动隧道并等待公网地址出现。
        ///
        /// 会重试：Cloudflare 对免注册的快速隧道有频率限制（HTTP 429），
        /// 短时间内反复开关就会遇到。这种限流通常几十秒后自动解除，
        /// 因此这里最多试 4 轮，避免用户点一次就得到一个失败弹窗。
        /// </summary>
        /// <param name="timeoutMs">每轮等待公网地址的时限。</param>
        /// <returns>公网地址；失败时抛出异常。</returns>
        public string Start(int timeoutMs)
        {
            if (!Available()) throw new InvalidOperationException("找不到 cloudflared：" + binary);

            const int attempts = 4;
            string lastReason = "未知原因";
            for (int round = 1; round <= attempts; round++)
            {
                if (round > 1)
                {
                    // 递增退避：限流越久等得越久。
                    int waitMs = 8000 * round;
                    log("公网服务暂时限流（" + lastReason + "），" + (waitMs / 1000) + " 秒后重试（第 " + round + "/" + attempts + " 次）…");
                    System.Threading.Thread.Sleep(waitMs);
                }
                try
                {
                    return StartOnce(timeoutMs);
                }
                catch (Exception error)
                {
                    lastReason = error.Message;
                    Stop();
                    // 只有限流才值得重试；其它错误（找不到程序、被拒绝）立刻上报。
                    if (!IsThrottled()) throw;
                }
            }
            throw new InvalidOperationException(
                "公网服务暂时限流（Cloudflare 对免注册隧道有频率限制），请等几分钟后再试。");
        }

        /// <summary>最近输出里是否出现过 429 限流。</summary>
        private bool IsThrottled()
        {
            string text = RecentOutput();
            return text.Contains("429") || text.Contains("provisioning failed");
        }

        /// <summary>启动一次隧道（不重试）。</summary>
        private string StartOnce(int timeoutMs)
        {
            if (Running) throw new InvalidOperationException("隧道已在运行");

            log("正在开启公网中转…");
            // 每轮都要清掉上一轮的输出，否则 IsThrottled 会读到历史记录。
            lock (output) { output.Length = 0; }

            var info = new ProcessStartInfo();
            info.FileName = binary;
            info.Arguments = "tunnel --url http://127.0.0.1:" + port + " --no-autoupdate";
            info.UseShellExecute = false;
            info.RedirectStandardOutput = true;
            info.RedirectStandardError = true;
            info.CreateNoWindow = true;

            process = new Process();
            process.StartInfo = info;
            process.EnableRaisingEvents = true;

            var ready = new ManualResetEvent(false);
            DataReceivedEventHandler onData = delegate (object sender, DataReceivedEventArgs args)
            {
                if (args.Data == null) return;
                lock (output)
                {
                    output.AppendLine(args.Data);
                    Match match = PublicUrlPattern.Match(args.Data);
                    if (match.Success && Url == null)
                    {
                        Url = match.Value;
                        ready.Set();
                    }
                    // 看到限流就立刻收工，不必等满超时。
                    if (args.Data.Contains("429") || args.Data.Contains("provisioning failed")) ready.Set();
                }
            };
            process.OutputDataReceived += onData;
            process.ErrorDataReceived += onData;
            process.Exited += delegate { ready.Set(); };

            process.Start();
            process.BeginOutputReadLine();
            process.BeginErrorReadLine();

            // cloudflared 把地址写在 stderr 上，两路都已监听。
            bool signalled = ready.WaitOne(timeoutMs);
            if (Url != null)
            {
                log("公网地址已就绪：" + Url);
                return Url;
            }
            if (IsThrottled()) throw new InvalidOperationException("公网服务限流（429）");
            if (!signalled) throw new TimeoutException("等待公网地址超时（" + (timeoutMs / 1000) + " 秒）");
            throw new InvalidOperationException("cloudflared 提前退出，未能取得公网地址");
        }

        /// <summary>最近若干行输出，失败时用于诊断。</summary>
        public string RecentOutput()
        {
            lock (output)
            {
                string[] lines = output.ToString().Split(new char[] { '\r', '\n' }, StringSplitOptions.RemoveEmptyEntries);
                int from = Math.Max(0, lines.Length - 8);
                var tail = new System.Collections.Generic.List<string>();
                for (int i = from; i < lines.Length; i++) tail.Add(lines[i]);
                return string.Join("\n", tail.ToArray());
            }
        }

        public void Stop()
        {
            if (process == null) return;
            try { if (!process.HasExited) process.Kill(); } catch { }
            try { process.Dispose(); } catch { }
            process = null;
            Url = null;
        }

        public void Dispose() { Stop(); }
    }
}
