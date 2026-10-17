using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Text;

namespace DshRelay
{
    /// <summary>
    /// 把 DSH 的原始事件流归一化成手表能直接渲染的紧凑结构。
    ///
    /// 为什么必须在中继侧做：DSH 的会话响应可达数百 KB（含大量流式分片与工具 diff），
    /// 手表的网络与内存都吃不下；而手表真正要看的只是「谁说了什么、调了哪个工具、
    /// 结果是什么」。
    /// </summary>
    public static class Steps
    {
        private const int MaxTextChars = 4000;
        private const int StepTextChars = 1500;
        private const int StepResultChars = 2000;
        /// <summary>思考内容的截断上限。用户要求能看到思考，但手表屏幕小，留一段足够判断思路。</summary>
        private const int StepThinkingChars = 1200;

        /// <summary>工具名到显示标签的映射（手表上是短标签，不需要原始英文名）。</summary>
        private static readonly Dictionary<string, string> ToolLabels = new Dictionary<string, string>(StringComparer.Ordinal)
        {
            { "pwsh", "命令" }, { "bash", "命令" }, { "run_code", "代码" },
            { "read", "读文件" }, { "edit", "改文件" }, { "write", "写文件" },
            { "grep", "搜索" }, { "glob", "找文件" },
            { "skill", "技能" }, { "subagent", "子任务" }, { "subagent_fork", "子任务" },
            { "web_search", "联网" }, { "web_search_engines", "联网" }, { "web_fetch", "联网" },
            { "web_browse", "浏览网页" }, { "read_page", "浏览网页" }, { "platform_search", "联网" },
            { "x_search", "联网" }, { "images-search", "找图" },
            { "generate_image", "画图" }, { "generate_images", "画图" }, { "edit_image", "改图" },
            { "ask_user_question", "提问" }, { "update_goal", "目标" },
            { "todo_write", "清单" }, { "present", "交付" },
            { "computer_use", "操作电脑" }, { "get_app_state", "操作电脑" },
            { "android_tap", "操作手机" }, { "android_screenshot", "手机截屏" },
            { "android_input_text", "手机输入" }, { "android_ui_dump", "手机界面" },
            { "mnemon_recall", "记忆" }, { "mnemon_runtime_memory", "记忆" },
            { "mnemon_document_search", "资料" }, { "mnemon_remember", "记忆" },
            { "excel_create", "表格" }, { "word_create", "文档" }, { "ppt_create", "演示" },
        };

        public static string ToolLabel(string name)
        {
            if (string.IsNullOrEmpty(name)) return "工具";
            string label;
            return ToolLabels.TryGetValue(name, out label) ? label : name;
        }

        /// <summary>把 session/page 的 records 归一化为消息列表。</summary>
        public static List<object> NormalizeMessages(List<object> records)
        {
            var messages = new List<object>();
            var texts = new List<Dictionary<string, object>>();
            var tools = new List<List<string>>();

            foreach (var raw in records ?? new List<object>())
            {
                var record = raw as Dictionary<string, object>;
                var evt = record != null && record.ContainsKey("event") ? record["event"] as Dictionary<string, object> : null;
                if (evt == null) continue;
                string type = RelayServer.GetString(evt, "type");
                var data = evt.ContainsKey("data") ? evt["data"] as Dictionary<string, object> : null;

                if (type == "user/message")
                {
                    if (!IsHumanMessage(data)) continue;
                    string text = TextOfContent(ContentOf(data));
                    if (text.Trim().Length == 0) continue;
                    var message = new Dictionary<string, object>();
                    message["seq"] = RelayServer.GetLong(evt, "seq");
                    message["role"] = "user";
                    message["text"] = Clip(text, MaxTextChars);
                    message["tools"] = new List<object>();
                    message["time"] = RelayServer.GetLong(evt, "time");
                    messages.Add(message);
                    continue;
                }

                if (type == "assistant/message")
                {
                    var content = ContentOf(data);
                    string text = TextOfContent(content);
                    string thinking = ThinkingOfContent(content);
                    var last = messages.Count > 0 ? messages[messages.Count - 1] as Dictionary<string, object> : null;
                    bool canAppend = last != null && Convert.ToString(last["role"]) == "assistant";
                    if (!canAppend)
                    {
                        last = new Dictionary<string, object>();
                        last["seq"] = RelayServer.GetLong(evt, "seq");
                        last["role"] = "assistant";
                        last["text"] = "";
                        last["thinking"] = "";
                        last["tools"] = new List<object>();
                        last["time"] = RelayServer.GetLong(evt, "time");
                        messages.Add(last);
                    }
                    if (text.Trim().Length > 0)
                    {
                        string existing = Convert.ToString(last["text"]);
                        last["text"] = existing.Length == 0 ? text : existing + "\n\n" + text;
                    }
                    if (thinking.Length > 0)
                    {
                        string existingThought = Convert.ToString(last["thinking"]);
                        last["thinking"] = existingThought.Length == 0
                            ? thinking
                            : existingThought + "\n\n" + thinking;
                    }
                    var calls = ToolsOfContent(content);
                    // tools 是本方法自己创建的 List<object>，不是 JSON 反序列化的结果；
                    // 但 canAppend 时上一条可能来自别处，取不到就补一个，避免空引用。
                    var list = last["tools"] as List<object>;
                    if (list == null)
                    {
                        list = new List<object>();
                        last["tools"] = list;
                    }
                    foreach (string tool in calls)
                    {
                        if (!list.Contains(tool)) list.Add(tool);
                    }
                }
            }

            foreach (var raw in messages)
            {
                var message = raw as Dictionary<string, object>;
                if (message == null) continue;
                message["text"] = Clip(Convert.ToString(message["text"]), MaxTextChars);
            }
            return messages;
        }

        /// <summary>
        /// 把会话事件压成「步骤」序列（消息 + 工具调用 + 结果）。
        ///
        /// 与消息列表的区别：这里保留每一次工具调用，并抽出可读的参数摘要
        /// （命令、文件路径）与结果开头，让手表能按步展示。
        /// </summary>
        public static List<object> NormalizeSteps(List<object> records)
        {
            var steps = new List<object>();
            var pendingTools = new Dictionary<string, Dictionary<string, object>>();
            Dictionary<string, object> pendingAssistant = null;

            foreach (var raw in records ?? new List<object>())
            {
                var record = raw as Dictionary<string, object>;
                var evt = record != null && record.ContainsKey("event") ? record["event"] as Dictionary<string, object> : null;
                if (evt == null) continue;
                string type = RelayServer.GetString(evt, "type");
                var data = evt.ContainsKey("data") ? evt["data"] as Dictionary<string, object> : null;

                if (type == "user/message")
                {
                    if (!IsHumanMessage(data)) continue;
                    string text = TextOfContent(ContentOf(data));
                    if (text.Trim().Length == 0) continue;
                    pendingAssistant = null;
                    steps.Add(MessageStep(evt, "user", text));
                    continue;
                }

                if (type == "assistant/message")
                {
                    var content = ContentOf(data);
                    string text = TextOfContent(content);
                    string thinking = ThinkingOfContent(content);
                    if (pendingAssistant == null) pendingAssistant = MessageStep(evt, "assistant", "");
                    if (text.Trim().Length > 0)
                    {
                        string existing = Convert.ToString(pendingAssistant["text"]);
                        pendingAssistant["text"] = existing.Length == 0 ? text : existing + "\n\n" + text;
                    }
                    if (thinking.Length > 0)
                    {
                        string existingThought = Convert.ToString(pendingAssistant["thinking"]);
                        pendingAssistant["thinking"] = existingThought.Length == 0
                            ? thinking
                            : existingThought + "\n\n" + thinking;
                    }
                    pendingAssistant["seq"] = RelayServer.GetLong(evt, "seq");
                    pendingAssistant["time"] = RelayServer.GetLong(evt, "time");

                    var calls = CallBlocks(content);
                    if (calls.Count == 0) continue;

                    // 先把助手正文收成一条，再把这次的所有工具调用各排一条。
                    //
                    // 早先的写法是在工具循环里取一次 pendingAssistant 就置空，循环到第二个
                    // 工具时拿到的已是 null，随即 NullReferenceException —— 于是「一条助手
                    // 消息里调了多个工具」的会话全都读不出来。这里改成循环外取一次。
                    var assistant = pendingAssistant;
                    pendingAssistant = null;
                    var labels = assistant["tools"] as List<object>;
                    if (labels == null)
                    {
                        labels = new List<object>();
                        assistant["tools"] = labels;
                    }
                    foreach (var call in calls)
                    {
                        string label = ToolLabel(Convert.ToString(call["name"]));
                        if (!labels.Contains(label)) labels.Add(label);
                    }
                    steps.Add(assistant);

                    foreach (var call in calls)
                    {
                        string label = ToolLabel(Convert.ToString(call["name"]));

                        var tool = new Dictionary<string, object>();
                        tool["kind"] = "tool";
                        tool["seq"] = RelayServer.GetLong(evt, "seq");
                        tool["time"] = RelayServer.GetLong(evt, "time");
                        tool["name"] = Convert.ToString(call["name"]);
                        tool["label"] = label;
                        tool["detail"] = DescribeToolCall(Convert.ToString(call["name"]), Convert.ToString(call["arguments"]));
                        tool["status"] = "running";
                        tool["result"] = "";
                        tool["files"] = new List<object>();
                        tool["isError"] = false;
                        steps.Add(tool);

                        string callId = Convert.ToString(call["id"]);
                        if (!string.IsNullOrEmpty(callId)) pendingTools[callId] = tool;
                    }
                    continue;
                }

                if (type == "tool/result")
                {
                    pendingAssistant = null;
                    var message = data != null && data.ContainsKey("message") ? data["message"] as Dictionary<string, object> : null;
                    string callId = RelayServer.GetString(message, "toolCallId");
                    string text = TextOfContent(ContentOf(message));
                    bool isError = RelayServer.GetBool(message, "isError");
                    var files = DiffPaths(data);

                    Dictionary<string, object> tool = null;
                    if (!string.IsNullOrEmpty(callId)) pendingTools.TryGetValue(callId, out tool);

                    if (tool == null)
                    {
                        // 没有对应调用（例如从中间翻页）：单独成一条，信息不丢。
                        var orphan = new Dictionary<string, object>();
                        orphan["kind"] = "tool";
                        orphan["seq"] = RelayServer.GetLong(evt, "seq");
                        orphan["time"] = RelayServer.GetLong(evt, "time");
                        orphan["name"] = "";
                        orphan["label"] = "工具结果";
                        orphan["detail"] = "";
                        orphan["status"] = isError ? "error" : "done";
                        orphan["result"] = Clip(text, StepResultChars);
                        orphan["files"] = files;
                        orphan["isError"] = isError;
                        steps.Add(orphan);
                        continue;
                    }

                    tool["status"] = isError ? "error" : "done";
                    tool["result"] = Clip(text, StepResultChars);
                    tool["isError"] = isError;
                    tool["files"] = files;
                }
            }

            // 收尾：正文与思考都可能超过上限，统一截断。
            // 思考给更小的上限：它通常很长，而手表屏幕只有几百像素。
            foreach (var raw in steps)
            {
                var step = raw as Dictionary<string, object>;
                if (step == null) continue;
                if (Convert.ToString(step["kind"]) == "message")
                {
                    step["text"] = Clip(Convert.ToString(step["text"]), StepTextChars);
                    if (step.ContainsKey("thinking"))
                    {
                        step["thinking"] = Clip(Convert.ToString(step["thinking"]), StepThinkingChars);
                    }
                }
            }
            return steps;
        }

        private static Dictionary<string, object> MessageStep(Dictionary<string, object> evt, string role, string text)
        {
            var step = new Dictionary<string, object>();
            step["kind"] = "message";
            step["seq"] = RelayServer.GetLong(evt, "seq");
            step["time"] = RelayServer.GetLong(evt, "time");
            step["role"] = role;
            step["text"] = text;
            // 思考内容与正文分开存：手表端默认折叠、弱化显示，不影响正文可读性。
            step["thinking"] = "";
            step["tools"] = new List<object>();
            return step;
        }

        /// <summary>取出 message.content 数组。</summary>
        private static List<object> ContentOf(Dictionary<string, object> data)
        {
            if (data == null) return null;
            var message = data.ContainsKey("message") ? data["message"] as Dictionary<string, object> : null;
            if (message == null) message = data;
            // 走 AsList：JSON 数组到这里是 object[]，直接 as List<object> 会得到 null。
            return RelayServer.ListField(message, "content");
        }

        /// <summary>
        /// 判断一条 user/message 是否来自真人。
        ///
        /// DSH 会用 user/message 承载多种系统注入（时间上下文、记忆快照、全局指令），
        /// 只有 source.kind 为 user / user-question-reply 的才是对话内容。
        /// </summary>
        private static bool IsHumanMessage(Dictionary<string, object> data)
        {
            if (data == null) return false;
            var source = data.ContainsKey("source") ? data["source"] as Dictionary<string, object> : null;
            if (source == null)
            {
                // 老格式没有 source：退回按 role 判断，避免漏掉真实消息。
                var message = data.ContainsKey("message") ? data["message"] as Dictionary<string, object> : null;
                if (message != null) return RelayServer.GetString(message, "role") == "user";
                return RelayServer.GetString(data, "role") == "user";
            }
            string kind = RelayServer.GetString(source, "kind");
            return kind == "user" || kind == "user-question-reply";
        }

        /// <summary>抽取 content 里的正文（只取 type=text，思考走 ThinkingOfContent）。</summary>
        private static string TextOfContent(List<object> content)
        {
            return JoinBlocks(content, "text");
        }

        /// <summary>
        /// 抽取 content 里的思考内容（type=reasoning）。
        ///
        /// 用户要求对话里能看到 AI 的思考过程。实测 DSH 的 content 块里
        /// reasoning 是数量最多的一类（一条会话里 35 个 reasoning / 59 个 tool-call
        /// / 5 个 text），字段与 text 块相同（type + text）。
        /// 这里单独取出来，交给手表端用弱化样式折叠显示。
        /// </summary>
        private static string ThinkingOfContent(List<object> content)
        {
            return JoinBlocks(content, "reasoning");
        }

        private static string JoinBlocks(List<object> content, string blockType)
        {
            if (content == null) return "";
            var parts = new List<string>();
            foreach (var raw in content)
            {
                var block = raw as Dictionary<string, object>;
                if (block == null) continue;
                if (RelayServer.GetString(block, "type") != blockType) continue;
                string text = RelayServer.GetString(block, "text");
                if (text.Trim().Length > 0) parts.Add(text);
            }
            return string.Join("\n", parts.ToArray()).Trim();
        }

        private static List<string> ToolsOfContent(List<object> content)
        {
            var list = new List<string>();
            foreach (var call in CallBlocks(content))
            {
                string label = ToolLabel(Convert.ToString(call["name"]));
                if (!list.Contains(label)) list.Add(label);
            }
            return list;
        }

        private static List<Dictionary<string, object>> CallBlocks(List<object> content)
        {
            var list = new List<Dictionary<string, object>>();
            if (content == null) return list;
            foreach (var raw in content)
            {
                var block = raw as Dictionary<string, object>;
                if (block == null) continue;
                if (RelayServer.GetString(block, "type") != "tool-call") continue;
                list.Add(block);
            }
            return list;
        }

        /// <summary>从工具结果里取出被改动/读取的文件路径。</summary>
        private static List<object> DiffPaths(Dictionary<string, object> data)
        {
            var list = new List<object>();
            var meta = data != null && data.ContainsKey("meta") ? data["meta"] as Dictionary<string, object> : null;
            var diffs = RelayServer.ListField(meta, "diffs");
            if (diffs == null) return list;
            foreach (var raw in diffs)
            {
                var diff = raw as Dictionary<string, object>;
                if (diff == null) continue;
                string path = RelayServer.GetString(diff, "path");
                if (!string.IsNullOrEmpty(path) && !list.Contains(path)) list.Add(path);
            }
            return list;
        }

        /// <summary>
        /// 从工具参数里抽出最有信息量的一句说明。
        ///
        /// 手表上不可能展示完整 JSON，因此按工具类型挑关键字段：
        /// 命令类给命令，文件类给路径。
        /// </summary>
        public static string DescribeToolCall(string name, string rawArguments)
        {
            if (string.IsNullOrEmpty(rawArguments)) return "";
            object parsed = null;
            try
            {
                var serializer = new System.Web.Script.Serialization.JavaScriptSerializer();
                parsed = serializer.DeserializeObject(rawArguments);
            }
            catch
            {
                return ClipOneLine(rawArguments, 120);
            }

            var map = parsed as Dictionary<string, object>;
            if (map == null) return ClipOneLine(rawArguments, 120);

            string[] order = new string[]
            {
                "description", "file_path", "path", "notebook_path", "command", "pattern",
                "query", "url", "prompt", "name", "subagent_type", "skill",
            };
            foreach (string key in order)
            {
                if (!map.ContainsKey(key)) continue;
                string value = Convert.ToString(map[key]);
                if (string.IsNullOrEmpty(value) || value.Trim().Length == 0) continue;
                return ClipOneLine(value, key == "command" ? 160 : 120);
            }
            return ClipOneLine(rawArguments, 100);
        }

        private static string ClipOneLine(string text, int limit)
        {
            if (text == null) return "";
            var builder = new StringBuilder();
            bool lastWasSpace = false;
            foreach (char ch in text)
            {
                bool isSpace = char.IsWhiteSpace(ch);
                if (isSpace)
                {
                    if (!lastWasSpace) builder.Append(' ');
                    lastWasSpace = true;
                }
                else
                {
                    builder.Append(ch);
                    lastWasSpace = false;
                }
            }
            string value = builder.ToString().Trim();
            return value.Length <= limit ? value : value.Substring(0, limit) + "…";
        }

        public static string Clip(string text, int limit)
        {
            if (string.IsNullOrEmpty(text)) return "";
            string value = text.Trim();
            return value.Length <= limit ? value : value.Substring(0, limit) + "…（已省略）";
        }
    }

    /// <summary>用量与运行统计。</summary>
    public static class Usage
    {
        public static Dictionary<string, object> Normalize(Dictionary<string, object> values)
        {
            var tokens = RelayServer.Dig(values, "tokenUsage");
            var pressure = RelayServer.Dig(values, "contextPressure");
            var breakdown = RelayServer.Dig(values, "contextBreakdown");
            var stats = RelayServer.Dig(values, "sessionStats");
            var model = RelayServer.Dig(values, "modelSelection", "next");

            long contextWindow = RelayServer.GetLong(pressure, "contextWindow");
            long pressureTokens = RelayServer.GetLong(pressure, "pressureTokens");

            var result = new Dictionary<string, object>();
            result["inputTokens"] = RelayServer.GetLong(tokens, "uncachedInputTokens");
            result["outputTokens"] = RelayServer.GetLong(tokens, "outputTokens");
            result["cacheReadTokens"] = RelayServer.GetLong(tokens, "cacheReadTokens");
            result["cacheWriteTokens"] = RelayServer.GetLong(tokens, "cacheWriteTokens");
            result["pressureTokens"] = pressureTokens;
            result["contextWindow"] = contextWindow;
            result["percent"] = contextWindow > 0
                ? Math.Round(pressureTokens * 1000.0 / contextWindow) / 10.0
                : 0.0;
            result["systemTokens"] = RelayServer.GetLong(breakdown, "systemTokens");
            result["toolsTokens"] = RelayServer.GetLong(breakdown, "toolsTokens");
            result["messageTokens"] = RelayServer.GetLong(breakdown, "messageTokens");
            result["turns"] = RelayServer.GetLong(stats, "turns");
            result["steps"] = RelayServer.GetLong(stats, "steps");
            result["llmMs"] = RelayServer.GetLong(stats, "llmMs");
            result["toolMs"] = RelayServer.GetLong(stats, "toolMs");
            result["ttftMs"] = RelayServer.GetLong(stats, "ttftMs");
            result["provider"] = RelayServer.GetString(model, "provider");
            result["model"] = RelayServer.GetString(model, "model");
            result["effort"] = RelayServer.GetString(model, "reasoningEffort");
            return result;
        }
    }
}
