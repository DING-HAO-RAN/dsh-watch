using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Net;
using System.Security.Cryptography;
using System.Text;
using System.Web.Script.Serialization;

namespace DshRelay
{
    /// <summary>
    /// DSH 协议客户端：负责浏览器会话 cookie 的签名与一元 RPC 调用。
    ///
    /// DSH 的 /api 只接受绑定到自身 authority 的签名 cookie：
    ///   名字 = dsh-auth-&lt;base64url(sha256(authority))&gt;
    ///   值   = v1.&lt;base64url(payload)&gt;.&lt;base64url(hmac-sha256(secret, body))&gt;
    /// 密钥来自 .credentials.yaml。按下同一算法自签一张票，中继就能以合法客户端
    /// 身份调用全部 API，无需改动 DSH 的任何配置。
    ///
    /// 有效期不能超过 DSH 的 cookieMaxAgeDays（默认 30 天），因此这里取 29 天。
    /// </summary>
    public class DshClient
    {
        private const long TokenTtlMs = 29L * 24 * 60 * 60 * 1000;
        private const int TimeoutMs = 30000;

        private readonly object gate = new object();
        private readonly JavaScriptSerializer json = new JavaScriptSerializer();

        public string Home { get; private set; }
        public int Port { get; private set; }
        /// <summary>签名密钥的原始写法；变了说明 DSH 重装过，需要重新读取。</summary>
        public string SecretRaw { get; private set; }

        private byte[] secret;
        private string cookie;
        private string authority;

        public DshClient(string home, int port, byte[] secret, string secretRaw)
        {
            this.Home = home;
            this.Port = port;
            this.secret = secret;
            this.SecretRaw = secretRaw;
            this.authority = "127.0.0.1:" + port.ToString(CultureInfo.InvariantCulture);
            // 大整数用 double 会丢精度，DSH 的 token 计数都在安全范围内，这里放宽长度上限。
            json.MaxJsonLength = 64 * 1024 * 1024;
            json.RecursionLimit = 200;
        }

        public JavaScriptSerializer Serializer { get { return json; } }

        /// <summary>凭据文件被替换时重新读取密钥（DSH 重启会换）。</summary>
        public bool RefreshSecretIfChanged()
        {
            byte[] fresh;
            string raw;
            if (!DshLocator.TryReadSecret(Path.Combine(Home, ".credentials.yaml"), out fresh, out raw)) return false;
            if (raw == SecretRaw) return false;
            lock (gate)
            {
                secret = fresh;
                SecretRaw = raw;
                cookie = null;
            }
            return true;
        }

        /// <summary>当前 authority 对应的会话 cookie。</summary>
        private string CookieHeader()
        {
            lock (gate)
            {
                if (cookie != null) return cookie;
                long issuedAt = NowMs();
                var payload = new Dictionary<string, object>();
                payload["version"] = 1;
                payload["authority"] = authority;
                payload["issuedAt"] = issuedAt;
                payload["expiresAt"] = issuedAt + TokenTtlMs;
                string body = DshLocator.Base64UrlEncode(Encoding.UTF8.GetBytes(json.Serialize(payload)));
                using (var hmac = new HMACSHA256(secret))
                {
                    string signature = DshLocator.Base64UrlEncode(hmac.ComputeHash(Encoding.UTF8.GetBytes(body)));
                    string name = "dsh-auth-" + DshLocator.Base64UrlEncode(SHA256.Create().ComputeHash(Encoding.UTF8.GetBytes(authority)));
                    cookie = name + "=v1." + body + "." + signature;
                }
                return cookie;
            }
        }

        private static long NowMs()
        {
            return (long)(DateTime.UtcNow - new DateTime(1970, 1, 1, 0, 0, 0, DateTimeKind.Utc)).TotalMilliseconds;
        }

        /// <summary>
        /// 调用一个一元 RPC 端点。
        /// </summary>
        /// <param name="method">形如 session/list（斜杠分隔）。</param>
        /// <param name="args">顶层参数；字段必须与 descriptor 精确匹配。</param>
        /// <returns>成功的业务返回值（Dictionary / List / 标量）。</returns>
        /// <exception cref="DshException">传输失败或 DSH 返回错误信封时抛出。</exception>
        public object Rpc(string method, Dictionary<string, object> args)
        {
            string rpcId = Guid.NewGuid().ToString();
            var envelope = new Dictionary<string, object>();
            envelope["type"] = "client-request";
            envelope["rpcId"] = rpcId;
            envelope["method"] = method;
            var payload = new Dictionary<string, object>();
            payload["args"] = args ?? new Dictionary<string, object>();
            envelope["payload"] = payload;

            string url = "http://127.0.0.1:" + Port.ToString(CultureInfo.InvariantCulture) + "/api/" + method;
            string responseText = Post(url, json.Serialize(envelope));

            object parsed;
            try { parsed = json.DeserializeObject(responseText); }
            catch (Exception error) { throw new DshException("protocol", method + " 的响应不是 JSON：" + error.Message); }

            var top = parsed as Dictionary<string, object>;
            if (top == null || !top.ContainsKey("result")) throw new DshException("protocol", method + " 的响应缺少 result");

            var result = top["result"] as Dictionary<string, object>;
            if (result == null) throw new DshException("protocol", method + " 的 result 不是对象");

            object ok;
            result.TryGetValue("ok", out ok);
            if (!(ok is bool) || !((bool)ok))
            {
                string code = "remote-error";
                string message = "未知错误";
                var error = result.ContainsKey("error") ? result["error"] as Dictionary<string, object> : null;
                if (error != null)
                {
                    if (error.ContainsKey("code")) code = Convert.ToString(error["code"]);
                    if (error.ContainsKey("message")) message = Convert.ToString(error["message"]);
                }
                throw new DshException(code, method + "：" + message);
            }

            return result.ContainsKey("value") ? result["value"] : null;
        }

        /// <summary>与 Rpc 相同，但失败返回 null（用于非关键路径）。</summary>
        public object RpcSafe(string method, Dictionary<string, object> args)
        {
            try { return Rpc(method, args); }
            catch { return null; }
        }

        /// <summary>发一次 POST 并读回文本。401 时重读密钥并重试一次。</summary>
        private string Post(string url, string body)
        {
            for (int attempt = 0; attempt < 2; attempt++)
            {
                HttpWebRequest request = (HttpWebRequest)WebRequest.Create(url);
                request.Method = "POST";
                request.ContentType = "application/json";
                request.Timeout = TimeoutMs;
                request.ReadWriteTimeout = TimeoutMs;
                request.Headers["Cookie"] = CookieHeader();
                request.Proxy = null;
                // 让 Host 头与 authority 完全一致，DSH 的信任栅栏按它校验。
                request.Host = authority;

                byte[] bytes = Encoding.UTF8.GetBytes(body);
                request.ContentLength = bytes.Length;
                using (Stream stream = request.GetRequestStream())
                {
                    stream.Write(bytes, 0, bytes.Length);
                }

                try
                {
                    using (HttpWebResponse response = (HttpWebResponse)request.GetResponse())
                    using (var reader = new StreamReader(response.GetResponseStream(), Encoding.UTF8))
                    {
                        return reader.ReadToEnd();
                    }
                }
                catch (WebException error)
                {
                    var http = error.Response as HttpWebResponse;
                    if (http != null && (int)http.StatusCode == 401 && attempt == 0)
                    {
                        // 密钥可能在 DSH 重启后换了：重读一次再试。
                        lock (gate) { cookie = null; }
                        RefreshSecretIfChanged();
                        http.Close();
                        continue;
                    }
                    if (http != null) http.Close();
                    throw new DshException("transport", "调用失败：" + error.Message);
                }
            }
            throw new DshException("unauthorized", "鉴权失败：签名密钥可能已失效，请重启应用重新读取。");
        }
    }

    /// <summary>DSH 调用失败，携带稳定 code 供上层区分处理。</summary>
    public class DshException : Exception
    {
        public string Code { get; private set; }
        public DshException(string code, string message) : base(message) { this.Code = code; }
    }
}
