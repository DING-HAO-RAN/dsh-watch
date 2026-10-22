using System;
using System.Drawing;
using System.Windows.Forms;

namespace DshRelay
{
    /// <summary>
    /// 手表请求配对时的确认框。
    ///
    /// 为什么不用 MessageBox：它只能给出「是 / 否」，而这里还要能选择
    /// 「把这台设备记成常用设备、以后自动同意」。用复选框而不是第三个按钮，
    /// 是为了让「记住」始终是一个**需要主动勾选**的动作——默认不勾，
    /// 放行范围就不会在用户顺手点「确定」时被悄悄放大。
    /// </summary>
    public class PairingDialog : Form
    {
        public enum Answer
        {
            Deny,
            Approve,
            Remember
        }

        public Answer Choice { get; private set; }

        public PairingDialog(string who, string message)
        {
            Choice = Answer.Deny;

            Text = "配对请求";
            FormBorderStyle = FormBorderStyle.FixedDialog;
            StartPosition = FormStartPosition.CenterParent;
            MaximizeBox = false;
            MinimizeBox = false;
            ShowInTaskbar = false;
            BackColor = Theme.Bg;
            AutoScaleMode = AutoScaleMode.Dpi;
            ClientSize = new Size(520, 320);
            Padding = new Padding(0);

            var root = new TableLayoutPanel();
            root.Dock = DockStyle.Fill;
            root.ColumnCount = 1;
            root.BackColor = Theme.Bg;
            root.Padding = new Padding(24, 20, 24, 18);
            root.RowCount = 4;
            root.RowStyles.Add(new RowStyle(SizeType.AutoSize));
            root.RowStyles.Add(new RowStyle(SizeType.Percent, 100f));
            root.RowStyles.Add(new RowStyle(SizeType.AutoSize));
            root.RowStyles.Add(new RowStyle(SizeType.AutoSize));
            Controls.Add(root);

            var title = new Label();
            title.Text = "手表请求配对";
            title.Font = Theme.Ui(13.5f, FontStyle.Bold);
            title.ForeColor = Theme.Fg;
            title.AutoSize = true;
            title.Margin = new Padding(0, 0, 0, 10);
            root.Controls.Add(title);

            var body = new TextBox();
            body.Multiline = true;
            body.ReadOnly = true;
            body.BorderStyle = BorderStyle.None;
            body.BackColor = Theme.Surface;
            body.ForeColor = Theme.FgSoft;
            body.Font = Theme.Ui(9.5f, FontStyle.Regular);
            body.Dock = DockStyle.Fill;
            body.Margin = new Padding(0, 0, 0, 12);
            body.Text = string.IsNullOrEmpty(who) ? message : message;
            // 只读文本框里选中文字会被用户误当成可编辑，这里禁掉焦点。
            body.TabStop = false;
            root.Controls.Add(body);

            var remember = new CheckBox();
            remember.Text = "把这台设备保存为常用设备（以后自动同意，不再弹窗）";
            remember.Font = Theme.Ui(9f, FontStyle.Regular);
            remember.ForeColor = Theme.Muted;
            remember.AutoSize = true;
            remember.Margin = new Padding(2, 0, 0, 12);
            root.Controls.Add(remember);

            var actions = new FlowLayoutPanel();
            actions.FlowDirection = FlowDirection.RightToLeft;
            actions.Dock = DockStyle.Fill;
            actions.AutoSize = true;
            actions.BackColor = Theme.Bg;
            actions.Margin = new Padding(0);
            root.Controls.Add(actions);

            var deny = MakeButton("拒绝", false, 84);
            deny.Click += delegate { Choice = Answer.Deny; Close(); };
            actions.Controls.Add(deny);

            var approve = MakeButton("同意这次", true, 96);
            approve.Click += delegate
            {
                Choice = remember.Checked ? Answer.Remember : Answer.Approve;
                Close();
            };
            actions.Controls.Add(approve);

            // Esc / 关闭窗口都按「拒绝」处理：配对是放行动作，默认必须是否。
            CancelButton = deny;
            AcceptButton = approve;
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
            button.Height = 34;
            button.Margin = new Padding(8, 0, 0, 0);
            return button;
        }
    }
}
