using System;
using System.IO;
using Microsoft.Win32;

namespace DshRelay
{
    /// <summary>
    /// 开机自启：写当前用户的 Run 键。
    ///
    /// 刻意不用「启动文件夹快捷方式」也不做计划任务：
    /// - 启动文件夹要给 .lnk 找图标与目标路径，出错时用户看不到任何反馈；
    /// - 计划任务需要管理员权限，还会被企业策略拦。
    /// 当前用户 Run 键不需要任何权限提升，且删掉就是取消，最容易解释也最容易撤销。
    /// </summary>
    public static class Autostart
    {
        private const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
        private const string ValueName = "DshRelay";

        /// <summary>
        /// 设置或取消开机自启。
        /// @returns 是否真的改动了注册表（已经是目标状态时返回 false）。
        /// </summary>
        public static bool SetEnabled(bool enabled, string executable)
        {
            try
            {
                using (RegistryKey key = Registry.CurrentUser.OpenSubKey(RunKey, true))
                {
                    if (key == null) return false;

                    object current = key.GetValue(ValueName);
                    string desired = enabled ? BuildCommand(executable) : null;

                    if (enabled)
                    {
                        if (current != null && string.Equals(Convert.ToString(current), desired, StringComparison.OrdinalIgnoreCase))
                        {
                            return false;
                        }
                        key.SetValue(ValueName, desired, RegistryValueKind.String);
                        return true;
                    }

                    if (current == null) return false;
                    key.DeleteValue(ValueName, false);
                    return true;
                }
            }
            catch (Exception error)
            {
                StartupLog.Write("写注册表自启项失败：" + error.Message);
                return false;
            }
        }

        /// <summary>当前是否已经登记了自启项。</summary>
        public static bool IsEnabled()
        {
            try
            {
                using (RegistryKey key = Registry.CurrentUser.OpenSubKey(RunKey, false))
                {
                    if (key == null) return false;
                    return key.GetValue(ValueName) != null;
                }
            }
            catch
            {
                return false;
            }
        }

        /// <summary>
        /// 命令行形态：路径加引号，再带一个 --background 让启动时直接进托盘。
        ///
        /// 开机自启不该在用户登录后突然弹一个窗口，但那正是「自启」最容易惹人烦的地方；
        /// 带这个参数后程序只驻留托盘，用户需要时再打开窗口。
        /// </summary>
        private static string BuildCommand(string executable)
        {
            string path = string.IsNullOrEmpty(executable) ? "" : executable;
            if (path.Length == 0)
            {
                try { path = System.Reflection.Assembly.GetExecutingAssembly().Location; }
                catch { }
            }
            if (path.Length == 0) return "";
            return "\"" + path + "\" --background";
        }

        /// <summary>是否以「后台启动」方式被拉起（用于开机自启时不弹窗）。</summary>
        public static bool LaunchedInBackground(string[] args)
        {
            if (args == null) return false;
            foreach (string arg in args)
            {
                if (string.Equals(arg, "--background", StringComparison.OrdinalIgnoreCase)) return true;
            }
            return false;
        }
    }
}
