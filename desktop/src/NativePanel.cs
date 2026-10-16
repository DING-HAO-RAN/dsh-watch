using System;
using System.Collections.Generic;
using System.Drawing;
using System.Globalization;
using System.Text;
using System.Windows.Forms;

namespace DshRelay
{
    /// <summary>
    /// 原生界面：把配对码、地址与运行状态直接画在窗口上。
    ///
    /// 为什么保留它：内嵌浏览器依赖 WebView2 运行时，精简版 Windows 上可能没有；
    /// 而中继本身不需要浏览器就能工作。有了这层，程序在**任何 Windows 10/11**
    /// 上都能打开并显示连接信息，最坏情况只是少了个更好看的页面。
    ///
    /// 用 TableLayoutPanel 手工排版，不引入任何第三方控件。
    /// </summary>
    public class NativePanel : UserControl
    {
        private readonly Label titleLabel;
        private readonly Label noteLabel;
        private readonly Label codeLabel;
        private readonly Label codeHint;
        private readonly FlowLayoutPanel addressList;
        private readonly Label statusLine;
        private readonly TextBox details;
        private readonly Button rotateButton;
        private readonly Button tunnelButton;
        private readonly Button copyButton;
        private readonly Timer timer;

        private readonly Action<string> log;
        private PanelHost host;

        public NativePanel()
        {
            log = StartupLog.Write;
            BackColor = Color.FromArgb(13, 17, 23);
            Padding = new Padding(24, 20, 24, 20);

            var root = new TableLayoutPanel();
            root.Dock = DockStyle.Fill;
            root.ColumnCount = 1;
            root.BackColor = Color.FromArgb(13, 17, 23);
            root.RowCount = 8;
            root.RowStyles.Add(new RowStyle(SizeType.AutoSize));  // 标题
            root.RowStyles.Add(new RowStyle(SizeType.AutoSize));  // 状态行
            root.RowStyles.Add(new RowStyle(SizeType.AutoSize));  // 提示
            root.RowStyles.Add(new RowStyle(SizeType.AutoSize));  // 配对码标题
            root.RowStyles.Add(new RowStyle(SizeType.AutoSize));  // 配对码
            root.RowStyles.Add(new RowStyle(SizeType.AutoSize));  // 操作按钮
            root.RowStyles.Add(new RowStyle(SizeType.AutoSize));  // 地址
            root.RowStyles.Add(new RowStyle(SizeType.Percent, 100f)); // 明细
            Controls.Add(root);

            titleLabel = new Label();
            titleLabel.Text = "DSH 中继";
            titleLabel.Font = new Font("Microsoft YaHei UI", 18f, FontStyle.Bold);
            titleLabel.ForeColor = Color.FromArgb(230, 237, 243);
            titleLabel.AutoSize = true;
            root.Controls.Add(titleLabel);

            statusLine = new Label();
            statusLine.Text = "正在启动中继…";
            statusLine.Font = new Font("Microsoft YaHei UI", 9.5f);
            statusLine.ForeColor = Color.FromArgb(139, 152, 169);
            statusLine.AutoSize = true;
            statusLine.Margin = new Padding(0, 2, 0, 12);
            root.Controls.Add(statusLine);

            noteLabel = new Label();
            noteLabel.Text = "";
            noteLabel.Font = new Font("Microsoft YaHei UI", 9f);
            noteLabel.ForeColor = Color.FromArgb(210, 153, 34);
            noteLabel.AutoSize = true;
            noteLabel.MaximumSize = new Size(840, 0);
            root.Controls.Add(noteLabel);

            Label codeTitle = new Label();
            codeTitle.Text = "配对码";
            codeTitle.Font = new Font("Microsoft YaHei UI", 9f);
            codeTitle.ForeColor = Color.FromArgb(139, 152, 169);
            codeTitle.AutoSize = true;
            codeTitle.Margin = new Padding(0, 14, 0, 0);
            root.Controls.Add(codeTitle);

            codeLabel = new Label();
            codeLabel.Text = "——————";
            codeLabel.Font = new Font("Consolas", 34f, FontStyle.Bold);
            codeLabel.ForeColor = Color.FromArgb(210, 153, 34);
            codeLabel.AutoSize = true;
            codeLabel.Margin = new Padding(0, 2, 0, 2);
            root.Controls.Add(codeLabel);

            codeHint = new Label();
            codeHint.Text = "在手表的「DSH 手表」→ 左滑 → 设置 里填入下方地址与本配对码";
            codeHint.Font = new Font("Microsoft YaHei UI", 9f);
            codeHint.ForeColor = Color.FromArgb(139, 152, 169);
            codeHint.AutoSize = true;
            root.Controls.Add(codeHint);

            var actions = new FlowLayoutPanel();
            actions.AutoSize = true;
            actions.FlowDirection = FlowDirection.LeftToRight;
            actions.Margin = new Padding(0, 12, 0, 6);
            root.Controls.Add(actions);

            copyButton = MakeButton("复制全部信息", true);
            copyButton.Click += delegate { CopyAll(); };
            actions.Controls.Add(copyButton);

            tunnelButton = MakeButton("开启公网访问", false);
            tunnelButton.Click += delegate { ToggleTunnel(); };
            actions.Controls.Add(tunnelButton);

            rotateButton = MakeButton("换一个配对码", false);
            rotateButton.Click += delegate { Rotate(); };
            actions.Controls.Add(rotateButton);

            addressList = new FlowLayoutPanel();
            addressList.AutoSize = true;
            addressList.FlowDirection = FlowDirection.TopDown;
            addressList.WrapContents = false;
            addressList.Margin = new Padding(0, 4, 0, 8);
            root.Controls.Add(addressList);

            details = new TextBox();
            details.Multiline = true;
            details.ReadOnly = true;
            details.ScrollBars = ScrollBars.Vertical;
            details.Dock = DockStyle.Fill;
            details.BackColor = Color.FromArgb(22, 27, 34);
            details.ForeColor = Color.FromArgb(139, 152, 169);
            details.BorderStyle = BorderStyle.FixedSingle;
            details.Font = new Font("Consolas", 9f);
            details.Margin = new Padding(0);
            details.Text = "启动过程中…";
            root.Controls.Add(details);

            timer = new Timer();
            timer.Interval = 2000;
            timer.Tick += delegate { Refresh_(); };
        }

        /// <summary>持有宿主，用于触发操作。</summary>
        public void Attach(PanelHost panelHost)
        {
            host = panelHost;
            StartPolling();
        }

        private static Button MakeButton(string text, bool primary)
        {
            var button = new Button();
            button.Text = text;
            button.AutoSize = true;
            button.Padding = new Padding(12, 5, 12, 5);
            button.FlatStyle = FlatStyle.Flat;
            button.FlatAppearance.BorderSize = 1;
            button.BackColor = primary ? Color.FromArgb(31, 111, 235) : Color.FromArgb(33, 38, 45);
            button.ForeColor = Color.FromArgb(230, 237, 243);
            button.FlatAppearance.BorderColor = primary ? Color.FromArgb(31, 111, 235) : Color.FromArgb(38, 45, 56);
            button.Margin = new Padding(0, 0, 8, 0);
            return button;
        }

        public void SetNote(string note)
        {
            noteLabel.Text = note;
        }

        public void SetError(string message)
        {
            statusLine.Text = "启动未成功";
            statusLine.ForeColor = Color.FromArgb(248, 81, 73);
            details.Text = message + Environment.NewLine + Environment.NewLine
                + "详细信息见程序目录下的 startup.log";
        }

        public void StartPolling()
        {
            timer.Start();
            Refresh_();
        }

        public void StopPolling()
        {
            timer.Stop();
        }

        /// <summary>刷新一次界面。</summary>
        private void Refresh_()
        {
            try
            {
                PanelState state = host == null ? null : host.GetState();
                if (state == null) return;

                codeLabel.Text = string.IsNullOrEmpty(state.PairingCode) ? "——————" : Spaced(state.PairingCode);
                statusLine.ForeColor = Color.FromArgb(139, 152, 169);
                statusLine.Text = state.Running
                    ? string.Format(CultureInfo.InvariantCulture,
                        "中继运行中 · 端口 {0} · 已配对 {1} 台设备 · 已运行 {2}",
                        state.Port, state.DeviceCount, Duration(state.UptimeMs))
                    : "中继未在运行";
                if (!state.Running)
                {
                    statusLine.ForeColor = Color.FromArgb(248, 81, 73);
                }

                addressList.Controls.Clear();
                var lines = new List<string>();
                for (int i = 0; i < state.LanAddresses.Count; i++)
                {
                    string url = "http://" + state.LanAddresses[i] + ":" + state.Port;
                    lines.Add(url);
                    addressList.Controls.Add(AddressRow(i == 0 ? "局域网" : "备用", url));
                }
                if (!string.IsNullOrEmpty(state.PublicUrl))
                {
                    lines.Add(state.PublicUrl);
                    addressList.Controls.Add(AddressRow("公网", state.PublicUrl));
                }
                if (lines.Count == 0)
                {
                    addressList.Controls.Add(AddressRow("地址", "尚未取得（请确认电脑已连上网络）"));
                }

                tunnelButton.Text = string.IsNullOrEmpty(state.PublicUrl)
                    ? (host.TunnelBusy ? "正在开启…" : "开启公网访问")
                    : "关闭公网访问";
                tunnelButton.Enabled = !host.TunnelBusy;

                var builder = new StringBuilder();
                builder.AppendLine("DSH 手表连接信息");
                builder.AppendLine("配对码：" + state.PairingCode);
                foreach (string line in lines) builder.AppendLine(line);
                builder.AppendLine();
                builder.AppendLine("DSH 端口：" + state.DshPort);
                builder.AppendLine("中继端口：" + state.Port);
                if (state.Tokens.Count > 0)
                {
                    builder.AppendLine();
                    builder.AppendLine("已发放的令牌（可直接用于其它客户端）：");
                    foreach (string token in state.Tokens) builder.AppendLine(token);
                }
                else
                {
                    builder.AppendLine();
                    builder.AppendLine("令牌：手表配对成功后在这里显示");
                }
                details.Text = builder.ToString();
            }
            catch (Exception error)
            {
                log("刷新界面失败：" + error.Message);
            }
        }

        /// <summary>把配对码分成两组显示，便于核对。</summary>
        private static string Spaced(string code)
        {
            if (code.Length != 6) return code;
            return code.Substring(0, 3) + " " + code.Substring(3);
        }

        private Control AddressRow(string tag, string url)
        {
            var row = new FlowLayoutPanel();
            row.AutoSize = true;
            row.FlowDirection = FlowDirection.LeftToRight;
            row.Margin = new Padding(0, 0, 0, 3);

            var tagLabel = new Label();
            tagLabel.Text = tag;
            tagLabel.AutoSize = true;
            tagLabel.ForeColor = Color.FromArgb(139, 152, 169);
            tagLabel.Font = new Font("Microsoft YaHei UI", 8.5f);
            tagLabel.Margin = new Padding(0, 4, 8, 0);
            row.Controls.Add(tagLabel);

            var urlLabel = new Label();
            urlLabel.Text = url;
            urlLabel.AutoSize = true;
            urlLabel.ForeColor = Color.FromArgb(88, 166, 255);
            urlLabel.Font = new Font("Consolas", 10f);
            urlLabel.Margin = new Padding(0, 0, 8, 0);
            urlLabel.Cursor = Cursors.Hand;
            urlLabel.Click += delegate { Copy(url); };
            row.Controls.Add(urlLabel);

            var copy = MakeButton("复制", false);
            copy.AutoSize = true;
            copy.Padding = new Padding(8, 1, 8, 1);
            copy.Font = new Font("Microsoft YaHei UI", 8.5f);
            copy.Click += delegate { Copy(url); };
            row.Controls.Add(copy);
            return row;
        }

        private void Copy(string text)
        {
            try { Clipboard.SetText(text); } catch { }
        }

        private void CopyAll()
        {
            try { Clipboard.SetText(details.Text); } catch { }
            copyButton.Text = "已复制";
            var restore = new Timer();
            restore.Interval = 1400;
            restore.Tick += delegate
            {
                restore.Stop();
                restore.Dispose();
                copyButton.Text = "复制全部信息";
            };
            restore.Start();
        }

        private void Rotate()
        {
            host.RotatePairingCode();
            Refresh_();
        }

        private void ToggleTunnel()
        {
            tunnelButton.Enabled = false;
            tunnelButton.Text = "处理中…";
            string result = host.ToggleTunnel();
            if (!string.IsNullOrEmpty(result))
            {
                MessageBox.Show(result, "DSH 中继", MessageBoxButtons.OK, MessageBoxIcon.Information);
            }
            tunnelButton.Enabled = true;
            Refresh_();
        }

        private static string Duration(long ms)
        {
            if (ms <= 0) return "—";
            long seconds = ms / 1000;
            if (seconds < 60) return seconds + " 秒";
            if (seconds < 3600) return (seconds / 60) + " 分 " + (seconds % 60) + " 秒";
            return (seconds / 3600) + " 小时 " + ((seconds % 3600) / 60) + " 分";
        }
    }

    /// <summary>界面要的一份状态快照。</summary>
    public class PanelState
    {
        public bool Running;
        public int Port;
        public string PairingCode = "";
        public List<string> LanAddresses = new List<string>();
        public string PublicUrl = "";
        public int DeviceCount;
        public long UptimeMs;
        public int DshPort;
        /// <summary>已发放且仍有效的配对令牌。</summary>
        public List<string> Tokens = new List<string>();
    }
}
