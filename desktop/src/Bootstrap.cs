using System;
using System.IO;
using System.Reflection;
using System.Runtime.InteropServices;

namespace DshRelay
{
    /// <summary>
    /// 启动引导：把内嵌的 WebView2 依赖从内存里挂进当前进程。
    ///
    /// 为什么必须是纯内存方案：WebView2 的托管程序集与原生加载器都不随 Windows 提供，
    /// 若散放在程序目录，用户拷贝时漏一个文件就打不开；而"先释放到磁盘再加载"
    /// 又要求程序对某个目录有写权限——在受限环境（企业策略、受控沙箱、
    /// 只读用户配置）下会直接失败。这里两条路都不走：
    ///   托管程序集 → AssemblyResolve 里用 Assembly.Load(byte[]) 从内存装入；
    ///   原生加载器 → 先释放到一个**进程私有的临时文件**（仅此一处必须落盘，
    ///              因为 Win32 只能从文件路径加载 DLL），并在进程退出时删除。
    ///
    /// 这样即使用户只拷走一个 exe，功能也是完整的。
    /// </summary>
    public static class Bootstrap
    {
        private static readonly object Gate = new object();
        private static bool installed;
        private static string loaderPath;

        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        private static extern bool SetDllDirectory(string path);

        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        private static extern IntPtr LoadLibraryW(string fileName);

        /// <summary>
        /// 需要挂载的依赖：资源名（构建时用的名字） → 目标程序集简单名。
        /// 加载器按架构分开嵌，运行到这里按进程位数挑一份。
        /// </summary>
        private static readonly string[][] Assemblies = new string[][]
        {
            new string[] { "Microsoft.Web.WebView2.Core.dll", "Microsoft.Web.WebView2.Core" },
            new string[] { "Microsoft.Web.WebView2.WinForms.dll", "Microsoft.Web.WebView2.WinForms" },
        };

        /// <summary>
        /// 注册程序集与原生库的解析。**必须在任何 WebView2 类型被使用之前调用**。
        /// </summary>
        public static void Init()
        {
            lock (Gate)
            {
                if (installed) return;
                installed = true;
            }

            AppDomain.CurrentDomain.AssemblyResolve += Resolve;
            PrepareNativeLoader();
        }

        /// <summary>
        /// 把原生加载器落盘并预加载。
        ///
        /// 只有这一处必须写文件：LoadLibrary 只接受路径。
        ///
        /// 落盘位置按可靠性排序，逐个尝试：
        /// 1. **程序自己的目录**——分发时最可靠（程序在那运行就说明有读权限，
        ///    且绝大多数安装位置都可写），随程序一起拷走时天然跟着走；
        /// 2. 系统临时目录——程序目录只读时（装在 Program Files）的退路。
        /// 之所以不优先用用户配置目录：受控环境里那里的写入常被策略拒绝。
        /// </summary>
        private static void PrepareNativeLoader()
        {
            string name = IntPtr.Size == 8 ? "WebView2Loader.x64.dll" : "WebView2Loader.x86.dll";
            byte[] payload = ReadResource(name);
            if (payload == null)
            {
                StartupLog.Write("内嵌的原生加载器缺失：" + name);
                return;
            }

            string baseDirectory = AppDomain.CurrentDomain.BaseDirectory;
            string beside = Path.Combine(baseDirectory, "WebView2Loader.dll");

            // 已经有一份同样大小的就直接用，避免每次启动都写盘。
            if (File.Exists(beside) && new FileInfo(beside).Length == payload.Length)
            {
                loaderPath = beside;
            }
            else
            {
                var candidates = new System.Collections.Generic.List<string>();
                candidates.Add(beside);
                try
                {
                    candidates.Add(Path.Combine(Path.GetTempPath(),
                        "WebView2Loader-" + Guid.NewGuid().ToString("N") + ".dll"));
                }
                catch { }

                foreach (string candidate in candidates)
                {
                    if (!TryWrite(candidate, payload)) continue;
                    loaderPath = candidate;
                    // 临时文件在进程退出时清掉；写在程序目录的那份保留复用。
                    if (!string.Equals(candidate, beside, StringComparison.OrdinalIgnoreCase))
                    {
                        RegisterCleanup(candidate);
                    }
                    break;
                }
            }

            if (loaderPath == null)
            {
                StartupLog.Write("无法写出原生加载器，内嵌浏览器将不可用（中继功能不受影响）");
                return;
            }

            try
            {
                // 先显式加载，再把所在目录加入搜索路径：
                // WebView2 运行时是按 "WebView2Loader.dll" 这个名字去找的。
                LoadLibraryW(loaderPath);
                SetDllDirectory(Path.GetDirectoryName(loaderPath));
                StartupLog.Write("原生加载器已就绪：" + loaderPath);
            }
            catch (Exception error)
            {
                StartupLog.Write("加载原生加载器失败：" + error.Message);
            }
        }

        private static void RegisterCleanup(string path)
        {
            try
            {
                AppDomain.CurrentDomain.ProcessExit += delegate
                {
                    try { if (File.Exists(path)) File.Delete(path); } catch { }
                };
            }
            catch { }
        }

        private static bool TryWrite(string path, byte[] payload)
        {
            try
            {
                string temporary = path + ".part";
                File.WriteAllBytes(temporary, payload);
                if (File.Exists(path)) File.Delete(path);
                File.Move(temporary, path);
                return true;
            }
            catch
            {
                return false;
            }
        }

        /// <summary>
        /// 程序集解析：WebView2 的托管程序集直接从内存装入，不落盘。
        /// </summary>
        private static Assembly Resolve(object sender, ResolveEventArgs args)
        {
            try
            {
                string simpleName = new AssemblyName(args.Name).Name;
                foreach (string[] item in Assemblies)
                {
                    if (!string.Equals(item[1], simpleName, StringComparison.OrdinalIgnoreCase)) continue;
                    byte[] payload = ReadResource(item[0]);
                    if (payload == null)
                    {
                        StartupLog.Write("内嵌程序集缺失：" + item[0]);
                        return null;
                    }
                    StartupLog.Write("已从内存载入：" + simpleName);
                    return Assembly.Load(payload);
                }
            }
            catch (Exception error)
            {
                StartupLog.Write("载入程序集失败（" + args.Name + "）：" + error.Message);
            }
            return null;
        }

        /// <summary>按逻辑名读内嵌资源；名字匹配忽略大小写与命名空间前缀。</summary>
        private static byte[] ReadResource(string name)
        {
            Assembly assembly = Assembly.GetExecutingAssembly();
            foreach (string candidate in assembly.GetManifestResourceNames())
            {
                if (!candidate.EndsWith("." + name, StringComparison.OrdinalIgnoreCase)
                    && !candidate.Equals(name, StringComparison.OrdinalIgnoreCase)) continue;
                using (Stream stream = assembly.GetManifestResourceStream(candidate))
                {
                    if (stream == null) continue;
                    var buffer = new byte[stream.Length];
                    int read = 0;
                    while (read < buffer.Length)
                    {
                        int got = stream.Read(buffer, read, buffer.Length - read);
                        if (got <= 0) break;
                        read += got;
                    }
                    return buffer;
                }
            }
            return null;
        }
    }
}
