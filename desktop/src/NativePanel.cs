using System;
using System.Collections.Generic;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Globalization;
using System.Text;
using System.Windows.Forms;

namespace DshRelay
{
    /// <summary>配色与字体：与网页控制台（panel.html）保持同一套视觉语言。</summary>
    internal static class Theme
    {
        public static readonly Color Bg = Color.FromArgb(11, 13, 16);
        public static readonly Color Surface = Color.FromArgb(20, 24, 30);
        public static readonly Color Surface2 = Color.FromArgb(25, 30, 37);
        public static readonly Color Inset = Color.FromArgb(16, 19, 24);
        public static readonly Color Line = Color.FromArgb(40, 45, 53);
        public static readonly Color Fg = Color.FromArgb(233, 237, 242);
        public static readonly Color FgSoft = Color.FromArgb(182, 192, 204);
        public static readonly Color Muted = Color.FromArgb(125, 136, 149);
        public static readonly Color Faint = Color.FromArgb(92, 102, 111);
        public static readonly Color Accent = Color.FromArgb(240, 182, 75);
        public static readonly Color Ok = Color.FromArgb(76, 195, 138);
        public static readonly Color Bad = Color.FromArgb(242, 119, 122);

        public static Font Ui(float size, FontStyle style)
        {
            return new Font("Microsoft YaHei UI", size, style, GraphicsUnit.Point);
        }

        public static Font Code(float size, FontStyle style)
        {
            return new Font("Consolas", size, style, GraphicsUnit.Point);
        }

        /// <summary>生成圆角矩形路径；半径按 DPI 换算，缩放屏上不会变成尖角。</summary>
        public static GraphicsPath Round(RectangleF rect, float radius)
        {
            var path = new GraphicsPath();
            float diameter = Math.Max(1f, radius * 2f);
            if (diameter > rect.Width) diameter = rect.Width;
            if (diameter > rect.Height) diameter = rect.Height;
            if (diameter <= 1f)
            {
                path.AddRectangle(rect);
                path.CloseFigure();
                return path;
            }
            path.AddArc(rect.X, rect.Y, diameter, diameter, 180f, 90f);
            path.AddArc(rect.Right - diameter, rect.Y, diameter, diameter, 270f, 90f);
            path.AddArc(rect.Right - diameter, rect.Bottom - diameter, diameter, diameter, 0f, 90f);
            path.AddArc(rect.X, rect.Bottom - diameter, diameter, diameter, 90f, 90f);
            path.CloseFigure();
            return path;
        }

        public static float Scale(Graphics g)
        {
            return g.DpiX <= 0f ? 1f : g.DpiX / 96f;
        }

        public static Color Blend(Color from, Color to, float amount)
        {
            if (amount <= 0f) return from;
            if (amount >= 1f) return to;
            int r = (int)(from.R + (to.R - from.R) * amount);
            int g = (int)(from.G + (to.G - from.G) * amount);
            int b = (int)(from.B + (to.B - from.B) * amount);
            return Color.FromArgb(from.A, r, g, b);
        }
    }

    /// <summary>
    /// 圆角卡片。
    ///
    /// 刻意从 TableLayoutPanel 派生：卡片要「宽度撑满父容器、高度随内容」，
    /// 这在 WinForms 里只有 TableLayoutPanel 的 AutoSize 能稳当地做到。
    /// 早期用 Panel + Dock 时，卡片被 AutoSize 压成了内容的固有宽度。
    /// </summary>
    public class Card : TableLayoutPanel
    {
        public Card()
        {
            SetStyle(ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer
                | ControlStyles.UserPaint | ControlStyles.ResizeRedraw, true);
            BackColor = Theme.Surface;
            ColumnCount = 1;
            ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100f));
            AutoSize = true;
            AutoSizeMode = AutoSizeMode.GrowAndShrink;
            Dock = DockStyle.Top;
            Padding = new Padding(20, 16, 20, 18);
            Margin = new Padding(0, 0, 0, 14);
        }

        protected override void OnPaintBackground(PaintEventArgs e)
        {
            Graphics g = e.Graphics;
            Color outer = Parent == null ? Theme.Bg : Parent.BackColor;
            g.Clear(outer);
            g.SmoothingMode = SmoothingMode.AntiAlias;

            float scale = Theme.Scale(g);
            var rect = new RectangleF(0.5f, 0.5f, Width - 1.5f, Height - 1.5f);
            using (GraphicsPath path = Theme.Round(rect, 12f * scale))
            using (var brush = new SolidBrush(BackColor))
            using (var pen = new Pen(Theme.Line, Math.Max(1f, scale)))
            {
                g.FillPath(brush, path);
                g.DrawPath(pen, path);
            }
        }
    }

    /// <summary>
    /// 扁平按钮：WinForms 自带的 Button 带 3D 边框与系统底色，在深色界面上很跳。
    /// 这里自绘圆角背景与文字，悬停/按下给一层轻微提亮，符合现代桌面的手感。
    /// </summary>
    public class FlatButton : Button
    {
        private bool hover;
        private bool pressed;

        public Color Face { get; set; }
        public Color Ink { get; set; }
        public Color Rim { get; set; }
        public bool Primary { get; set; }

        public FlatButton()
        {
            SetStyle(ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer
                | ControlStyles.UserPaint | ControlStyles.ResizeRedraw, true);
            FlatStyle = FlatStyle.Flat;
            FlatAppearance.BorderSize = 0;
            UseVisualStyleBackColor = false;
            Face = Theme.Surface2;
            Ink = Theme.Fg;
            Rim = Theme.Line;
            Font = Theme.Ui(9.5f, FontStyle.Regular);
            Height = 36;
            Width = 120;
            Cursor = Cursors.Hand;
            TabStop = false;
        }

        protected override void OnMouseEnter(EventArgs e)
        {
            hover = true;
            Invalidate();
            base.OnMouseEnter(e);
        }

        protected override void OnMouseLeave(EventArgs e)
        {
            hover = false;
            pressed = false;
            Invalidate();
            base.OnMouseLeave(e);
        }

        protected override void OnMouseDown(MouseEventArgs e)
        {
            pressed = true;
            Invalidate();
            base.OnMouseDown(e);
        }

        protected override void OnMouseUp(MouseEventArgs e)
        {
            pressed = false;
            Invalidate();
            base.OnMouseUp(e);
        }

        protected override void OnPaint(PaintEventArgs e)
        {
            Graphics g = e.Graphics;
            Color outer = Parent == null ? Theme.Bg : Parent.BackColor;
            g.Clear(outer);
            g.SmoothingMode = SmoothingMode.AntiAlias;

            float scale = Theme.Scale(g);
            Color face = Face;
            if (!Enabled) face = Theme.Blend(Face, outer, 0.55f);
            else if (pressed) face = Theme.Blend(Face, Color.Black, 0.16f);
            else if (hover) face = Primary ? Theme.Blend(Face, Color.White, 0.12f) : Theme.Blend(Face, Color.White, 0.07f);

            var rect = new RectangleF(0.5f, 0.5f, Width - 1.5f, Height - 1.5f);
            using (GraphicsPath path = Theme.Round(rect, 8f * scale))
            using (var brush = new SolidBrush(face))
            using (var pen = new Pen(Primary ? Theme.Blend(Face, Color.White, 0.22f)
                                             : (hover ? Theme.Blend(Rim, Color.White, 0.10f) : Rim), Math.Max(1f, scale)))
            {
                g.FillPath(brush, path);
                if (!Primary) g.DrawPath(pen, path);
            }

            Color ink = Enabled ? Ink : Theme.Blend(Ink, face, 0.45f);
            TextRenderer.DrawText(g, Text, Font, ClientRectangle, ink,
                TextFormatFlags.HorizontalCenter | TextFormatFlags.VerticalCenter | TextFormatFlags.EndEllipsis);
        }
    }

    /// <summary>
    /// 原生界面：把配对码、地址与运行状态直接画在窗口上。
    ///
    /// 为什么保留它：内嵌浏览器依赖 WebView2 运行时，精简版 Windows 上可能没有；
    /// 而中继本身不需要浏览器就能工作。有了这层，程序在**任何 Windows 10/11**
    /// 上都能打开并显示连接信息，最坏情况只是少了个更好看的页面。
    ///
    /// 排版全用 TableLayoutPanel，不引入任何第三方控件。
    /// </summary>
    public class NativePanel : UserControl
    {
        private Label statusLabel;
        private Panel statusDot;
        private readonly Label noteLabel;
        private Label codeLabel;
        private TableLayoutPanel addressList;
        private Label metaLabel;
        private TextBox details;
        private FlatButton rotateButton;
        private FlatButton tunnelButton;
        private FlatButton copyButton;
        private FlatButton detailsToggle;
        private readonly Timer timer;
        private readonly TextBox hiddenDetails;
        private readonly TableLayoutPanel root;

        private readonly Action<string> log;
        private PanelHost host;
        private bool detailsOpen;

        public NativePanel()
        {
            log = StartupLog.Write;
            BackColor = Theme.Bg;
            AutoScroll = true;

            root = new TableLayoutPanel();
            root.ColumnCount = 1;
            root.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100f));
            root.AutoSize = true;
            root.AutoSizeMode = AutoSizeMode.GrowAndShrink;
            root.Dock = DockStyle.Top;
            root.BackColor = Theme.Bg;
            root.Padding = new Padding(24, 22, 24, 22);
            root.Margin = new Padding(0);
            Controls.Add(root);

            root.Controls.Add(BuildHeader());
            root.Controls.Add(BuildCodeCard());
            root.Controls.Add(BuildAddressCard());
            root.Controls.Add(BuildDetailsCard());

            noteLabel = new Label();
            noteLabel.Text = "";
            noteLabel.Font = Theme.Ui(8.5f, FontStyle.Regular);
            noteLabel.ForeColor = Theme.Muted;
            noteLabel.AutoSize = true;
            noteLabel.MaximumSize = new Size(880, 0);
            noteLabel.Margin = new Padding(4, 2, 4, 0);
            noteLabel.Visible = false;
            root.Controls.Add(noteLabel);

            // 明细收起时仍要把文本算好：「复制全部信息」读的是这份，而不是界面上可见的那份。
            hiddenDetails = new TextBox();
            hiddenDetails.Multiline = true;
            hiddenDetails.Visible = false;
            hiddenDetails.ReadOnly = true;
            Controls.Add(hiddenDetails);

            timer = new Timer();
            timer.Interval = 2000;
            timer.Tick += delegate { Refresh_(); };
        }

        // ---------------------------------------------------------------- 页头

        private Control BuildHeader()
        {
            var header = new TableLayoutPanel();
            header.ColumnCount = 2;
            header.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100f));
            header.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
            header.AutoSize = true;
            header.AutoSizeMode = AutoSizeMode.GrowAndShrink;
            header.Dock = DockStyle.Top;
            header.BackColor = Theme.Bg;
            header.Margin = new Padding(0, 0, 0, 18);
            header.Padding = new Padding(0);

            var titleBox = new TableLayoutPanel();
            titleBox.ColumnCount = 1;
            titleBox.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100f));
            titleBox.AutoSize = true;
            titleBox.AutoSizeMode = AutoSizeMode.GrowAndShrink;
            titleBox.BackColor = Theme.Bg;
            titleBox.Margin = new Padding(0);
            titleBox.Padding = new Padding(0);
            header.Controls.Add(titleBox);

            var titleLabel = new Label();
            titleLabel.Text = "DSH 中继";
            titleLabel.Font = Theme.Ui(15f, FontStyle.Bold);
            titleLabel.ForeColor = Theme.Fg;
            titleLabel.AutoSize = true;
            titleLabel.Margin = new Padding(0);
            titleBox.Controls.Add(titleLabel);

            statusLabel = new Label();
            statusLabel.Text = "正在启动中继…";
            statusLabel.Font = Theme.Ui(9f, FontStyle.Regular);
            statusLabel.ForeColor = Theme.Muted;
            statusLabel.AutoSize = true;
            statusLabel.Margin = new Padding(0, 3, 0, 0);
            titleBox.Controls.Add(statusLabel);

            var badge = new FlowLayoutPanel();
            badge.FlowDirection = FlowDirection.LeftToRight;
            badge.WrapContents = false;
            badge.AutoSize = true;
            badge.AutoSizeMode = AutoSizeMode.GrowAndShrink;
            badge.BackColor = Theme.Bg;
            badge.Margin = new Padding(12, 8, 0, 0);
            header.Controls.Add(badge);

            // 状态灯：自绘圆点，比字符「●」在缩放屏上干净。
            statusDot = new Panel();
            statusDot.Size = new Size(10, 10);
            statusDot.Margin = new Padding(0, 5, 8, 0);
            statusDot.BackColor = Theme.Bg;
            statusDot.Tag = Theme.Muted;
            statusDot.Paint += PaintStatusDot;
            badge.Controls.Add(statusDot);

            var statusText = new Label();
            statusText.Text = "状态";
            statusText.Font = Theme.Ui(9f, FontStyle.Regular);
            statusText.ForeColor = Theme.Faint;
            statusText.AutoSize = true;
            statusText.Margin = new Padding(0, 1, 0, 0);
            badge.Controls.Add(statusText);

            return header;
        }

        // ---------------------------------------------------------------- 配对码

        private Control BuildCodeCard()
        {
            var card = new Card();
            card.Controls.Add(SectionLabel("配对码"));

            codeLabel = new Label();
            codeLabel.Text = "••••••";
            codeLabel.Font = Theme.Code(30f, FontStyle.Bold);
            codeLabel.ForeColor = Theme.Accent;
            codeLabel.AutoSize = true;
            codeLabel.Margin = new Padding(0, 8, 0, 6);
            card.Controls.Add(codeLabel);

            var hint = new Label();
            hint.Text = "手表「设置」里填这个码与下面的地址；配对成功后不会自动更换";
            hint.Font = Theme.Ui(9f, FontStyle.Regular);
            hint.ForeColor = Theme.Muted;
            hint.AutoSize = true;
            hint.MaximumSize = new Size(880, 0);
            hint.Margin = new Padding(0);
            card.Controls.Add(hint);
            return card;
        }

        // ---------------------------------------------------------------- 地址

        private Control BuildAddressCard()
        {
            var card = new Card();
            card.Controls.Add(SectionLabel("连接地址"));

            addressList = new TableLayoutPanel();
            addressList.ColumnCount = 1;
            addressList.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100f));
            addressList.AutoSize = true;
            addressList.AutoSizeMode = AutoSizeMode.GrowAndShrink;
            addressList.Dock = DockStyle.Top;
            addressList.BackColor = Theme.Surface;
            addressList.Margin = new Padding(0, 8, 0, 0);
            card.Controls.Add(addressList);

            var actions = new FlowLayoutPanel();
            actions.FlowDirection = FlowDirection.LeftToRight;
            actions.WrapContents = true;
            actions.AutoSize = true;
            actions.AutoSizeMode = AutoSizeMode.GrowAndShrink;
            actions.Dock = DockStyle.Top;
            actions.BackColor = Theme.Surface;
            actions.Margin = new Padding(0, 14, 0, 0);
            card.Controls.Add(actions);

            copyButton = MakeButton("复制全部信息", true, 136);
            copyButton.Click += delegate { CopyAll(); };
            actions.Controls.Add(copyButton);

            tunnelButton = MakeButton("开启公网访问", false, 128);
            tunnelButton.Click += delegate { ToggleTunnel(); };
            actions.Controls.Add(tunnelButton);

            rotateButton = MakeButton("换一个配对码", false, 124);
            rotateButton.Click += delegate { Rotate(); };
            actions.Controls.Add(rotateButton);
            return card;
        }

        // ---------------------------------------------------------------- 明细

        private Control BuildDetailsCard()
        {
            var card = new Card();
            card.Margin = new Padding(0, 0, 0, 0);

            var head = new TableLayoutPanel();
            head.ColumnCount = 2;
            head.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100f));
            head.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
            head.ColumnCount = 2;
            head.AutoSize = true;
            head.AutoSizeMode = AutoSizeMode.GrowAndShrink;
            head.Dock = DockStyle.Top;
            head.BackColor = Theme.Surface;
            head.Margin = new Padding(0);
            card.Controls.Add(head);

            metaLabel = new Label();
            metaLabel.Text = "连接信息";
            metaLabel.Font = Theme.Ui(9f, FontStyle.Regular);
            metaLabel.ForeColor = Theme.Muted;
            metaLabel.AutoSize = true;
            metaLabel.Margin = new Padding(0, 8, 0, 0);
            head.Controls.Add(metaLabel);

            detailsToggle = MakeButton("展开", false, 72);
            detailsToggle.Height = 30;
            detailsToggle.Font = Theme.Ui(8.5f, FontStyle.Regular);
            detailsToggle.Margin = new Padding(0, 0, 0, 0);
            detailsToggle.Click += delegate { SetDetailsOpen(!detailsOpen); };
            head.Controls.Add(detailsToggle);

            details = new TextBox();
            details.Multiline = true;
            details.ReadOnly = true;
            details.ScrollBars = ScrollBars.Vertical;
            details.Dock = DockStyle.Top;
            details.Height = 150;
            details.BackColor = Theme.Inset;
            details.ForeColor = Theme.FgSoft;
            details.BorderStyle = BorderStyle.None;
            details.Font = Theme.Code(9f, FontStyle.Regular);
            details.Margin = new Padding(0, 10, 0, 0);
            details.Visible = false;
            card.Controls.Add(details);
            return card;
        }

        private static Label SectionLabel(string text)
        {
            var label = new Label();
            label.Text = text;
            label.Font = Theme.Ui(8.5f, FontStyle.Bold);
            label.ForeColor = Theme.Faint;
            label.AutoSize = true;
            label.Margin = new Padding(0);
            return label;
        }

        private static FlatButton MakeButton(string text, bool primary, int width)
        {
            var button = new FlatButton();
            button.Text = text;
            button.Primary = primary;
            button.Face = primary ? Theme.Accent : Theme.Surface2;
            button.Ink = primary ? Color.FromArgb(26, 19, 5) : Theme.Fg;
            button.Rim = primary ? Theme.Accent : Theme.Line;
            button.Width = width;
            button.Margin = new Padding(0, 0, 8, 0);
            return button;
        }

        private void PaintStatusDot(object sender, PaintEventArgs e)
        {
            Graphics g = e.Graphics;
            g.SmoothingMode = SmoothingMode.AntiAlias;
            Color color = statusDot.Tag is Color ? (Color)statusDot.Tag : Theme.Muted;
            using (var halo = new SolidBrush(Color.FromArgb(46, color)))
            using (var core = new SolidBrush(color))
            {
                g.FillEllipse(halo, 0, 0, statusDot.Width - 1, statusDot.Height - 1);
                g.FillEllipse(core, 3f, 3f, statusDot.Width - 7f, statusDot.Height - 7f);
            }
        }

        /// <summary>持有宿主，用于触发操作。</summary>
        public void Attach(PanelHost panelHost)
        {
            host = panelHost;
            StartPolling();
        }

        public void SetNote(string note)
        {
            noteLabel.Text = note;
            noteLabel.Visible = !string.IsNullOrEmpty(note);
        }

        public void SetError(string message)
        {
            statusLabel.Text = "启动未成功";
            statusLabel.ForeColor = Theme.Bad;
            SetStatusDot(Theme.Bad);
            hiddenDetails.Text = message + Environment.NewLine + Environment.NewLine
                + "详细信息见程序目录下的 startup.log";
            details.Text = hiddenDetails.Text;
            SetDetailsOpen(true);
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

        private void SetStatusDot(Color color)
        {
            statusDot.Tag = color;
            statusDot.Invalidate();
        }

        private void SetDetailsOpen(bool open)
        {
            detailsOpen = open;
            details.Visible = open;
            detailsToggle.Text = open ? "收起" : "展开";
        }

        /// <summary>刷新一次界面。</summary>
        private void Refresh_()
        {
            try
            {
                PanelState state = host == null ? null : host.GetState();
                if (state == null) return;

                codeLabel.Text = string.IsNullOrEmpty(state.PairingCode) ? "••••••" : Spaced(state.PairingCode);

                if (state.Running)
                {
                    statusLabel.ForeColor = Theme.Muted;
                    SetStatusDot(Theme.Ok);
                    statusLabel.Text = string.Format(CultureInfo.InvariantCulture,
                        "运行中 · 端口 {0} · 已配对 {1} 台 · 已运行 {2}",
                        state.Port, state.DeviceCount, Duration(state.UptimeMs));
                }
                else
                {
                    statusLabel.ForeColor = Theme.Bad;
                    SetStatusDot(Theme.Bad);
                    statusLabel.Text = "中继未在运行";
                }

                RebuildAddresses(state);
                UpdateMeta(state);

                tunnelButton.Text = string.IsNullOrEmpty(state.PublicUrl)
                    ? (host.TunnelBusy ? "正在开启…" : "开启公网访问")
                    : "关闭公网访问";
                tunnelButton.Enabled = !host.TunnelBusy;
            }
            catch (Exception error)
            {
                log("刷新界面失败：" + error.Message);
            }
        }

        private void RebuildAddresses(PanelState state)
        {
            var lines = new List<string>();
            addressList.SuspendLayout();
            while (addressList.Controls.Count > 0)
            {
                Control child = addressList.Controls[0];
                addressList.Controls.RemoveAt(0);
                child.Dispose();
            }

            for (int i = 0; i < state.LanAddresses.Count; i++)
            {
                string url = "http://" + state.LanAddresses[i] + ":" + state.Port;
                lines.Add(url);
                addressList.Controls.Add(AddressRow(i == 0 ? "局域网" : "备用 " + (i + 1),
                    i == 0 ? "手表与电脑连同一个 WiFi 时填这条" : "上面的地址不通时可试这条", url));
            }
            if (!string.IsNullOrEmpty(state.PublicUrl))
            {
                lines.Add(state.PublicUrl);
                addressList.Controls.Add(AddressRow("公网", "不在同一网络时用这条（走 Cloudflare 中转）", state.PublicUrl));
            }
            if (lines.Count == 0)
            {
                addressList.Controls.Add(AddressRow("地址", "尚未取得", "请确认电脑已连上网络"));
            }
            addressList.ResumeLayout();

            var builder = new StringBuilder();
            builder.AppendLine("DSH 手表连接信息");
            builder.AppendLine("配对码：" + state.PairingCode);
            foreach (string line in lines) builder.AppendLine(line);
            builder.AppendLine();
            builder.AppendLine("DSH 端口：" + state.DshPort);
            builder.AppendLine("中继端口：" + state.Port);
            builder.AppendLine("已配对设备：" + state.DeviceCount + " 台");
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

            string text = builder.ToString();
            if (hiddenDetails.Text != text) hiddenDetails.Text = text;
            if (detailsOpen && details.Text != text) details.Text = text;
        }

        private void UpdateMeta(PanelState state)
        {
            string text = "DSH 127.0.0.1:" + state.DshPort
                + " · 中继端口 " + state.Port
                + " · 令牌 " + state.Tokens.Count + " 个";
            if (metaLabel.Text != text) metaLabel.Text = text;
        }

        /// <summary>把配对码分成两组显示，便于核对。</summary>
        private static string Spaced(string code)
        {
            if (code.Length != 6) return code;
            return code.Substring(0, 3) + " " + code.Substring(3);
        }

        private Control AddressRow(string tag, string note, string url)
        {
            var row = new TableLayoutPanel();
            row.ColumnCount = 2;
            row.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100f));
            row.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
            row.AutoSize = true;
            row.AutoSizeMode = AutoSizeMode.GrowAndShrink;
            row.Dock = DockStyle.Top;
            row.BackColor = Theme.Inset;
            row.Padding = new Padding(13, 9, 10, 10);
            row.Margin = new Padding(0, 0, 0, 8);

            var stack = new TableLayoutPanel();
            stack.ColumnCount = 1;
            stack.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100f));
            stack.AutoSize = true;
            stack.AutoSizeMode = AutoSizeMode.GrowAndShrink;
            stack.Dock = DockStyle.Top;
            stack.BackColor = Theme.Inset;
            stack.Margin = new Padding(0);
            row.Controls.Add(stack);

            var head = new FlowLayoutPanel();
            head.FlowDirection = FlowDirection.LeftToRight;
            head.WrapContents = false;
            head.AutoSize = true;
            head.AutoSizeMode = AutoSizeMode.GrowAndShrink;
            head.BackColor = Theme.Inset;
            head.Margin = new Padding(0);
            stack.Controls.Add(head);

            bool isPublic = tag == "公网";
            var tagLabel = new Label();
            tagLabel.Text = tag;
            tagLabel.AutoSize = true;
            tagLabel.Font = Theme.Ui(8.5f, FontStyle.Regular);
            tagLabel.ForeColor = isPublic ? Theme.Accent : Theme.Faint;
            tagLabel.Margin = new Padding(0, 5, 9, 0);
            head.Controls.Add(tagLabel);

            var urlLabel = new Label();
            urlLabel.Text = url;
            urlLabel.AutoSize = true;
            urlLabel.Font = Theme.Code(10.5f, FontStyle.Regular);
            urlLabel.ForeColor = Theme.Fg;
            urlLabel.Margin = new Padding(0, 1, 0, 0);
            urlLabel.Cursor = Cursors.Hand;
            urlLabel.Click += delegate { Copy(url); };
            head.Controls.Add(urlLabel);

            var noteLabelRow = new Label();
            noteLabelRow.Text = note;
            noteLabelRow.AutoSize = true;
            noteLabelRow.Font = Theme.Ui(8.5f, FontStyle.Regular);
            noteLabelRow.ForeColor = Theme.Faint;
            noteLabelRow.Margin = new Padding(0, 1, 0, 0);
            stack.Controls.Add(noteLabelRow);

            var copy = MakeButton("复制", false, 62);
            copy.Height = 30;
            copy.Font = Theme.Ui(8.5f, FontStyle.Regular);
            copy.Margin = new Padding(10, 0, 0, 0);
            copy.Anchor = AnchorStyles.Top | AnchorStyles.Right;
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
            try { Clipboard.SetText(hiddenDetails.Text); } catch { }
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
