using System;
using System.Collections.Generic;
using System.IO;
using System.Text;
using System.Web.Script.Serialization;

namespace DshRelay
{
    /// <summary>
    /// 桌面程序需要跨次启动保留的东西。
    ///
    /// 为什么必须有它：以前每次重启中继，配对码都是新的、已配对过的设备全部失效，
    /// 手表得重新配一遍。用户明确要求「默认沿用同一端口与密钥，除非主动要求更改」，
    /// 因此把配对码与已发放的令牌都存下来。
    ///
    /// 存放位置与内容：
    /// - 文件在程序目录（与 startup.log 同处），随程序一起拷走也就跟着走；
    /// - 只存配对码、令牌、端口与它们的时间戳，**不存 DSH 的任何凭据**。
    /// </summary>
    public class RelaySettings
    {
        /// <summary>中继监听端口（用户可改，改了就记住）。</summary>
        public int Port = RelayServer.DefaultPort;

        /// <summary>固定配对码；空表示还没有（首次启动会生成并存入）。</summary>
        public string PairingCode = "";

        /// <summary>已发放的令牌：令牌 → 发放时间（Unix 毫秒）。</summary>
        public Dictionary<string, long> Tokens = new Dictionary<string, long>();

        /// <summary>
        /// 已验证过的「常用设备」：设备标识 → 设备信息。
        ///
        /// 手表第一次一键配对时可以选择「以后自动同意」。被记住的设备再来请求时
        /// 直接放行，不再弹窗——但**仍受过期与撤销约束**（见 TrustedDevice）。
        /// 识别用设备自己生成的随机 ID，不用 IP：换 WiFi 后 IP 会变，
        /// 而按 IP 记忆等于把「记住这台设备」变成了「记住这个网络」。
        /// </summary>
        public Dictionary<string, TrustedDevice> TrustedDevices = new Dictionary<string, TrustedDevice>();

        /// <summary>是否随 Windows 登录自动启动。</summary>
        public bool StartWithWindows;

        /// <summary>是否跟随 DSH 的启停自动开始/暂停对外服务。</summary>
        public bool FollowDsh;

        /// <summary>是否在启动时自动开启公网访问（需求方要求默认开启）。</summary>
        public bool AutoTunnel = true;

        /// <summary>控制台与窗口的界面主题：dark / light。默认深色。</summary>
        public string Theme = "dark";

        /// <summary>一条被记住的设备。</summary>
        public class TrustedDevice
        {
            public string DeviceId = "";
            /// <summary>手表自报的型号，用于在界面上说明「谁被记住了」。</summary>
            public string Name = "";
            /// <summary>最近一次成功配对的 IP，仅供展示与排查。</summary>
            public string LastAddress = "";
            /// <summary>记住的时间（Unix 毫秒）。</summary>
            public long Since;
            /// <summary>最近一次自动同意的时间（Unix 毫秒）。</summary>
            public long LastSeen;
        }

        private static readonly JavaScriptSerializer Json = new JavaScriptSerializer();

        /// <summary>
        /// 设置文件路径。
        ///
        /// 关键要求：**程序升级/重建时不能丢**，否则「密钥不变」就落空了。
        /// 因此按可靠性依次尝试：
        /// 1. 用户数据目录 `%LOCALAPPDATA%\dsh-relay\`——正常安装时最合适，且不受程序目录更新影响；
        /// 2. 程序目录下的 `data\` 子目录——用户目录不可写时（受控环境、便携使用）的后路。
        ///    构建脚本会把 `data\` 排除在清理之外，所以升级也不会丢。
        /// </summary>
        public static string PathFor(string directory)
        {
            // 显式覆盖优先：调试第二份程序时（原版仍在跑、正在用 %LOCALAPPDATA% 那份存档），
            // 用一个环境变量就能让它们各用各的配对码与令牌，不会互相覆盖。
            try
            {
                string overrideDirectory = Environment.GetEnvironmentVariable("DSH_RELAY_STATE_DIR");
                if (!string.IsNullOrEmpty(overrideDirectory))
                {
                    Directory.CreateDirectory(overrideDirectory);
                    return Path.Combine(overrideDirectory, "relay-settings.json");
                }
            }
            catch { }

            try
            {
                string root = Path.Combine(
                    Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "dsh-relay");
                Directory.CreateDirectory(root);
                // 能建目录不代表能写文件，真写一下确认。
                string probe = Path.Combine(root, ".write-test");
                File.WriteAllText(probe, "ok");
                File.Delete(probe);
                return Path.Combine(root, "relay-settings.json");
            }
            catch { }

            try
            {
                string root = Path.Combine(directory ?? ".", "data");
                Directory.CreateDirectory(root);
                return Path.Combine(root, "relay-settings.json");
            }
            catch { }

            return Path.Combine(directory ?? ".", "relay-settings.json");
        }

        /// <summary>设置文件的显示路径（用于界面告知用户）。</summary>
        public static string DescribeLocation(string directory)
        {
            return PathFor(directory);
        }

        /// <summary>读取设置；文件不存在或损坏时返回一份全新的默认值。</summary>
        ///
        /// 不抛异常：设置读不出来最多是「配对码换新、手表要重配一次」，
        /// 不该让程序起不来。
        public static RelaySettings Load(string directory)
        {
            string file = PathFor(directory);
            MigrateFromProgramDirectory(directory, file);
            try
            {
                if (File.Exists(file))
                {
                    string text = File.ReadAllText(file, Encoding.UTF8);
                    var data = Json.DeserializeObject(text) as Dictionary<string, object>;
                    if (data != null)
                    {
                        var settings = new RelaySettings();
                        object port;
                        if (data.TryGetValue("port", out port) && port != null)
                        {
                            int parsed;
                            if (int.TryParse(Convert.ToString(port), out parsed) && parsed > 0 && parsed <= 65535)
                            {
                                settings.Port = parsed;
                            }
                        }
                        object code;
                        if (data.TryGetValue("pairingCode", out code) && code != null)
                        {
                            string value = Convert.ToString(code).Trim();
                            if (value.Length > 0) settings.PairingCode = value;
                        }
                        object tokens;
                        if (data.TryGetValue("tokens", out tokens))
                        {
                            var map = tokens as Dictionary<string, object>;
                            if (map != null)
                            {
                                foreach (var pair in map)
                                {
                                    long issued;
                                    if (long.TryParse(Convert.ToString(pair.Value), out issued))
                                    {
                                        settings.Tokens[pair.Key] = issued;
                                    }
                                }
                            }
                        }
                        object trusted;
                        if (data.TryGetValue("trustedDevices", out trusted))
                        {
                            var map = trusted as Dictionary<string, object>;
                            if (map != null)
                            {
                                foreach (var pair in map)
                                {
                                    var item = pair.Value as Dictionary<string, object>;
                                    if (item == null) continue;
                                    var device = new TrustedDevice();
                                    device.DeviceId = pair.Key;
                                    device.Name = ReadString(item, "name");
                                    device.LastAddress = ReadString(item, "lastAddress");
                                    device.Since = ReadLong(item, "since");
                                    device.LastSeen = ReadLong(item, "lastSeen");
                                    if (device.DeviceId.Length > 0) settings.TrustedDevices[device.DeviceId] = device;
                                }
                            }
                        }
                        settings.StartWithWindows = ReadBool(data, "startWithWindows");
                        settings.FollowDsh = ReadBool(data, "followDsh");
                        // 缺省视为开启：需求方要求「默认打开公网模式」。
                        settings.AutoTunnel = !data.ContainsKey("autoTunnel") || ReadBool(data, "autoTunnel");
                        string theme = ReadString(data, "theme");
                        settings.Theme = theme == "light" ? "light" : "dark";
                        return settings;
                    }
                }
            }
            catch (Exception error)
            {
                StartupLog.Write("读取设置失败，按默认值启动：" + error.Message);
            }
            return new RelaySettings();
        }

        /// <summary>
        /// 把老位置（程序目录根、或用户目录）的设置搬到现在该在的位置。
        ///
        /// 早先设置直接瘫在程序目录根，构建时会被清掉；现在优先用户目录、
        /// 其次程序目录的 data\。老用户的文件必须搬过来，否则升级一次丢一次配对。
        /// </summary>
        private static void MigrateFromProgramDirectory(string programDirectory, string newPath)
        {
            try
            {
                if (File.Exists(newPath)) return;
                if (string.IsNullOrEmpty(programDirectory)) return;

                var candidates = new List<string>();
                candidates.Add(Path.Combine(programDirectory, "relay-settings.json"));
                try
                {
                    candidates.Add(Path.Combine(
                        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                        "dsh-relay", "relay-settings.json"));
                }
                catch { }

                foreach (string legacy in candidates)
                {
                    if (!File.Exists(legacy)) continue;
                    if (string.Equals(legacy, newPath, StringComparison.OrdinalIgnoreCase)) continue;
                    try
                    {
                        Directory.CreateDirectory(Path.GetDirectoryName(newPath));
                    }
                    catch { }
                    File.Copy(legacy, newPath, true);
                    StartupLog.Write("已迁移设置文件：" + legacy + " → " + newPath);
                    return;
                }
            }
            catch (Exception error)
            {
                StartupLog.Write("迁移设置文件失败：" + error.Message);
            }
        }

        /// <summary>写回设置。失败只记日志，不影响运行。</summary>
        public void Save(string directory)
        {
            try
            {
                var data = new Dictionary<string, object>();
                data["port"] = Port;
                data["pairingCode"] = PairingCode;
                var tokens = new Dictionary<string, object>();
                foreach (var pair in Tokens) tokens[pair.Key] = pair.Value;
                data["tokens"] = tokens;

                var trusted = new Dictionary<string, object>();
                foreach (var pair in TrustedDevices)
                {
                    var item = new Dictionary<string, object>();
                    item["name"] = pair.Value.Name ?? "";
                    item["lastAddress"] = pair.Value.LastAddress ?? "";
                    item["since"] = pair.Value.Since;
                    item["lastSeen"] = pair.Value.LastSeen;
                    trusted[pair.Key] = item;
                }
                data["trustedDevices"] = trusted;
                data["startWithWindows"] = StartWithWindows;
                data["followDsh"] = FollowDsh;
                data["autoTunnel"] = AutoTunnel;
                data["theme"] = Theme;

                File.WriteAllText(PathFor(directory), Json.Serialize(data), Encoding.UTF8);
            }
            catch (Exception error)
            {
                StartupLog.Write("保存设置失败：" + error.Message);
            }
        }

        private static string ReadString(Dictionary<string, object> data, string key)
        {
            object value;
            if (!data.TryGetValue(key, out value) || value == null) return "";
            return Convert.ToString(value) ?? "";
        }

        private static long ReadLong(Dictionary<string, object> data, string key)
        {
            object value;
            if (!data.TryGetValue(key, out value) || value == null) return 0L;
            long parsed;
            return long.TryParse(Convert.ToString(value), out parsed) ? parsed : 0L;
        }

        private static bool ReadBool(Dictionary<string, object> data, string key)
        {
            object value;
            if (!data.TryGetValue(key, out value) || value == null) return false;
            bool parsed;
            return bool.TryParse(Convert.ToString(value), out parsed) && parsed;
        }
    }
}
