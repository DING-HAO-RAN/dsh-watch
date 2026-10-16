using System;
using System.IO;
using System.Text;
using System.Windows.Forms;

namespace DshRelay
{
    /// <summary>
    /// 启动诊断：把运行过程与异常写到应用目录下的 startup.log。
    ///
    /// 为什么需要：程序在别的电脑上闪退时，用户拿不到任何信息（GUI 程序没有控制台），
    /// 而这类问题几乎都出在启动阶段（找不到 DSH、端口被占、缺 DLL）。
    /// 日志只保留最近一次运行，位置就在 exe 旁边，便于让用户直接发过来。
    /// </summary>
    public static class StartupLog
    {
        private static readonly object Gate = new object();
        private static string path;

        /// <summary>日志文件路径（首次访问时确定）。</summary>
        public static string Path
        {
            get
            {
                if (path == null)
                {
                    string dir = AppDomain.CurrentDomain.BaseDirectory;
                    path = System.IO.Path.Combine(dir, "startup.log");
                }
                return path;
            }
        }

        /// <summary>写入一行（带时间戳）。写失败不影响程序运行。</summary>
        public static void Write(string message)
        {
            lock (Gate)
            {
                try
                {
                    string line = DateTime.Now.ToString("HH:mm:ss.fff") + "  " + message + Environment.NewLine;
                    File.AppendAllText(Path, line, Encoding.UTF8);
                }
                catch { }
            }
        }

        /// <summary>开始新一轮记录：清掉上次内容并写上环境信息。</summary>
        public static void StartNew()
        {
            lock (Gate)
            {
                try { File.WriteAllText(Path, "", Encoding.UTF8); } catch { }
            }
            Write("DSH 中继启动");
            Write("程序目录：" + AppDomain.CurrentDomain.BaseDirectory);
            Write("系统：" + Environment.OSVersion.VersionString
                + " / " + (Environment.Is64BitOperatingSystem ? "64 位" : "32 位")
                + " / CLR " + Environment.Version);
            Write("进程位数：" + (IntPtr.Size == 8 ? "64 位" : "32 位"));
        }

        /// <summary>记录异常（含堆栈）。</summary>
        public static void Error(Exception error)
        {
            Write("异常：" + error.GetType().Name + "：" + error.Message);
            if (error.StackTrace != null) Write(error.StackTrace);
            if (error.InnerException != null)
            {
                Write("  内部异常：" + error.InnerException.Message);
            }
        }
    }
}
