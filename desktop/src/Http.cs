using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Net;
using System.Text;
using System.Web.Script.Serialization;

namespace DshRelay
{
    /// <summary>
    /// 极简 HTTP：只实现手表端中继需要的那部分。
    ///
    /// 不引入 HttpListener 的理由：它在本机需要 URL 预留（管理员权限），
    /// 而中继必须能以普通用户身份跑起来。直接读写 socket 反而更可控。
    /// </summary>
    public static class Http
    {
        public class Request
        {
            public string Method = "GET";
            public string Path = "/";
            public Dictionary<string, string> Query = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
            public Dictionary<string, string> Headers = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
            public string BodyText = "";
            /// <summary>发起方 IP（由服务端接受连接时填入，用于配对确认时说明来源）。</summary>
            public string ClientAddress = "";
        }

        public class Response
        {
            public int Status = 200;
            public string ContentType = "application/json; charset=utf-8";
            public byte[] Body = new byte[0];
        }

        private static readonly JavaScriptSerializer JsonSerializer = new JavaScriptSerializer();

        public static Response Json(int status, object payload)
        {
            if (JsonSerializer.MaxJsonLength < 64 * 1024 * 1024) JsonSerializer.MaxJsonLength = 64 * 1024 * 1024;
            var response = new Response();
            response.Status = status;
            response.Body = Encoding.UTF8.GetBytes(JsonSerializer.Serialize(payload));
            return response;
        }

        public static Response Text(int status, string contentType, string text)
        {
            var response = new Response();
            response.Status = status;
            response.ContentType = contentType;
            response.Body = Encoding.UTF8.GetBytes(text);
            return response;
        }

        /// <summary>读一条 HTTP 请求；连接提前断开时返回 null。</summary>
        public static Request ReadRequest(Stream stream)
        {
            var request = new Request();

            string requestLine = ReadLine(stream);
            if (string.IsNullOrEmpty(requestLine)) return null;
            string[] parts = requestLine.Split(' ');
            if (parts.Length < 2) return null;
            request.Method = parts[0].ToUpperInvariant();
            string target = parts[1];

            int question = target.IndexOf('?');
            if (question >= 0)
            {
                string query = target.Substring(question + 1);
                target = target.Substring(0, question);
                foreach (string pair in query.Split('&'))
                {
                    if (pair.Length == 0) continue;
                    int equals = pair.IndexOf('=');
                    if (equals < 0) request.Query[Uri.UnescapeDataString(pair)] = "";
                    else request.Query[Uri.UnescapeDataString(pair.Substring(0, equals))] =
                        Uri.UnescapeDataString(pair.Substring(equals + 1).Replace("+", " "));
                }
            }
            request.Path = target;

            string line;
            int contentLength = 0;
            while (!string.IsNullOrEmpty(line = ReadLine(stream)))
            {
                int colon = line.IndexOf(':');
                if (colon <= 0) continue;
                string name = line.Substring(0, colon).Trim();
                string value = line.Substring(colon + 1).Trim();
                request.Headers[name] = value;
                if (string.Equals(name, "content-length", StringComparison.OrdinalIgnoreCase))
                {
                    int.TryParse(value, out contentLength);
                }
            }

            if (contentLength > 0)
            {
                var buffer = new byte[Math.Min(contentLength, 8 * 1024 * 1024)];
                int read = 0;
                while (read < buffer.Length)
                {
                    int got = stream.Read(buffer, read, buffer.Length - read);
                    if (got <= 0) break;
                    read += got;
                }
                request.BodyText = Encoding.UTF8.GetString(buffer, 0, read);
            }
            return request;
        }

        private static string ReadLine(Stream stream)
        {
            var builder = new StringBuilder(128);
            while (true)
            {
                int next = stream.ReadByte();
                if (next < 0) return builder.Length == 0 ? null : builder.ToString();
                if (next == '\n') return builder.ToString().TrimEnd('\r');
                builder.Append((char)next);
                // 防御畸形请求：单行超过 8KB 直接放弃。
                if (builder.Length > 8192) return builder.ToString();
            }
        }

        private static readonly Dictionary<int, string> Reasons = new Dictionary<int, string>
        {
            { 200, "OK" }, { 400, "Bad Request" }, { 401, "Unauthorized" },
            { 403, "Forbidden" }, { 404, "Not Found" }, { 500, "Internal Server Error" },
            { 502, "Bad Gateway" },
        };

        public static void WriteResponse(Stream stream, Response response)
        {
            string reason;
            if (!Reasons.TryGetValue(response.Status, out reason)) reason = "OK";
            var header = new StringBuilder();
            header.Append("HTTP/1.1 ").Append(response.Status.ToString(CultureInfo.InvariantCulture))
                  .Append(' ').Append(reason).Append("\r\n");
            header.Append("Content-Type: ").Append(response.ContentType).Append("\r\n");
            header.Append("Content-Length: ").Append(response.Body.Length.ToString(CultureInfo.InvariantCulture)).Append("\r\n");
            header.Append("Cache-Control: no-store\r\n");
            header.Append("Connection: close\r\n\r\n");
            byte[] headerBytes = Encoding.ASCII.GetBytes(header.ToString());
            stream.Write(headerBytes, 0, headerBytes.Length);
            if (response.Body.Length > 0) stream.Write(response.Body, 0, response.Body.Length);
            stream.Flush();
        }
    }
}
