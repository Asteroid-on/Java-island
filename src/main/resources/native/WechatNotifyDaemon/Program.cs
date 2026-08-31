using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text;

/// <summary>
/// 微信 PC 消息通知守护进程（零注入、零 Hook、不封号）。
///
/// 检测原理：微信收到消息时实时写入本地消息库
/// （微信 4.x：xwechat_files\&lt;wxid&gt;\db_storage\message\message_*.db[-wal]，
///   微信 3.x：WeChat Files\&lt;wxid&gt;\Msg\msg_*.db）。
/// 本进程只读文件元数据（修改时间/大小），不读库内容、不注入微信进程、
/// 不 Hook、不读取微信内存，无任何封号风险。
/// 注：微信 PC 版（含 4.x）不支持 Windows 系统通知（Toast），
/// 故不依赖 UserNotificationListener 通知中心通道。
///
/// 与 Java 侧的通信协议（沿用 MediaInfoDaemon 模式）：
/// 将最新一条微信通知原子写入 %TEMP%\wechat_notify.json。
/// Java 侧每 500ms 轮询 JSON，通过自增 seq 检测新通知；
/// 微信图标使用 Java 内置资源 icons/wechat.png，不经 daemon 传输。
///
/// 命令行参数：
///   --test-once  无微信环境联调：启动 1.5s 后输出一条模拟微信通知
/// </summary>
class Program
{
    static readonly string PosFile = Path.Combine(Path.GetTempPath(), "wechat_notify.json");

    /// <summary>单实例互斥体：防止应用多次启动导致多个 daemon 并存</summary>
    static readonly bool SingletonCreated;
    static readonly Mutex SingletonMutex;

    static long _seq;
    static string _appId = "";
    static string _title = "";
    static string _body = "";
    static string _wechatExe = "";

    // ── 消息数据库写入检测 ──
    /// <summary>监控的消息库文件清单（message_*.db / -wal 等，任一变化即视为新消息）</summary>
    static readonly List<(string path, long size, DateTime mtime)> _msgDbWatch = new();
    static long _msgDbLastFireMs;
    static readonly object _fireLock = new();
    /// <summary>
    /// 触发后抑制窗口：同一条消息落库分两批写入（实测批次间隔约 10s，
    /// 第二批为微信异步索引/会话更新），首次写入立即触发（不影响首条延迟），
    /// 15s 窗口内的后续批次合并为一次通知。
    /// </summary>
    static readonly long MSG_DB_SUPPRESS_MS = 15_000;
    /// <summary>诊断日志节流：抑制写入的日志最短间隔</summary>
    static long _diagLastMs;
    /// <summary>消息库目录事件监听（实时通道，轮询为兜底）</summary>
    static FileSystemWatcher? _msgWatcher;
    /// <summary>已输出日志的消息库目录（重定位同目录时静默）</summary>
    static string _msgDbDirLog = "";

    static Program()
    {
        SingletonMutex = new Mutex(true, "JavaIsland_WechatNotifyDaemon_Singleton", out SingletonCreated);
    }

    static async Task Main(string[] args)
    {
        if (!SingletonCreated)
        {
            Console.Error.WriteLine("[WechatDaemon] 已有 WechatNotifyDaemon 实例在运行，本实例退出。");
            return;
        }
        Console.OutputEncoding = Encoding.UTF8;
        Console.Error.WriteLine($"[WechatDaemon] 启动 (pid={Environment.ProcessId})");

        // 基线状态（Java 侧可感知 daemon 存活）
        WriteState();

        // 启动预探测微信 exe 路径：首条消息到达触发通知时跳转路径已就绪（异步不阻塞启动）；
        // 微信未运行的场景下不再依赖“首次通知后才探测”的延迟路径。
        _ = Task.Run(() =>
        {
            try
            {
                string exe = FindWechatExe();
                if (!string.IsNullOrEmpty(exe) && exe != _wechatExe)
                {
                    _wechatExe = exe;
                    WriteState();
                }
            }
            catch { }
        });

        if (args.Contains("--test-once"))
        {
            _ = Task.Run(async () =>
            {
                await Task.Delay(1500);
                SimulateWechatNotification();
            });
        }

        // 消息数据库写入检测：微信来消息的可靠信号
        _ = Task.Run(PollMessageDbLoop);

        // 常驻：由 Java 侧进程退出时 destroy 回收
        await Task.Delay(-1);
    }

    /// <summary>
    /// 消息数据库写入检测（轮询兜底）：每 150ms 比较微信消息库文件的 修改时间/大小。
    /// 实时通道为 FileSystemWatcher 事件监听（EnsureMessageWatcher），本循环仅作兜底。
    /// </summary>
    static async Task PollMessageDbLoop()
    {
        ResolveMessageDbWatch();
        if (_msgDbWatch.Count == 0)
        {
            Console.Error.WriteLine("[WechatDaemon] 未找到微信消息数据库，db 检测通道暂不可用（每 10s 重试定位）");
        }
        long lastResolve = Environment.TickCount64;
        while (true)
        {
            await Task.Delay(150);
            long now = Environment.TickCount64;
            // 定位失败 → 每 10s 重试；定位成功 → 每 10 分钟检查一次（跟随目录变更，不刷日志）
            long resolveInterval = _msgDbWatch.Count > 0 ? 600_000 : 10_000;
            if (now - lastResolve > resolveInterval)
            {
                lastResolve = now;
                ResolveMessageDbWatch();
            }
            if (_msgDbWatch.Count == 0) continue;
            // 建立/重建事件监听（目录未变时静默）
            EnsureMessageWatcher(Path.GetDirectoryName(_msgDbWatch[0].path));

            bool changed = false;
            for (int i = 0; i < _msgDbWatch.Count; i++)
            {
                var (path, size, mtime) = _msgDbWatch[i];
                try
                {
                    var fi = new FileInfo(path);
                    if (!fi.Exists) continue;
                    long newSize = fi.Length;
                    DateTime newMtime = fi.LastWriteTimeUtc;
                    if (newSize != size || newMtime != mtime)
                    {
                        _msgDbWatch[i] = (path, newSize, newMtime);
                        changed = true;
                    }
                }
                catch { }
            }
            if (!changed) continue;
            FireMessageNotification("poll");
        }
    }

    /// <summary>触发一次微信通知（事件驱动/轮询兜底双通道共用）。
    /// 触发后抑制窗口 MSG_DB_SUPPRESS_MS 合并同一条消息的多批次落库写入；
    /// 首次写入立即触发（延迟仅受事件感知时间影响），后续批次被抑制。
    /// 微信主窗口可见（用户正开着聊天界面）时不触发。
    /// </summary>
    static void FireMessageNotification(string source)
    {
        // 聊天界面已打开：不弹窗（消息写入/已读更新都不打扰）
        if (IsWechatWindowVisibleCached())
        {
            long nowVis = Environment.TickCount64;
            if (nowVis - _diagLastMs > 500)
            {
                _diagLastMs = nowVis;
                Console.Error.WriteLine("[WechatDaemon][DIAG] 微信窗口可见，跳过通知");
            }
            return;
        }
        lock (_fireLock)
        {
            long now = Environment.TickCount64;
            long since = now - _msgDbLastFireMs;
            if (since < MSG_DB_SUPPRESS_MS)
            {
                // 诊断：记录被抑制写入的间隔分布，用于确定微信落库批次模式（稳定后移除）
                if (now - _diagLastMs > 500)
                {
                    _diagLastMs = now;
                    Console.Error.WriteLine($"[WechatDaemon][DIAG] 抑制后续写入 since={since}ms src={source}");
                }
                return;
            }
            _msgDbLastFireMs = now;
        }
        Console.Error.WriteLine($"[WechatDaemon] 检测到微信消息库写入，触发通知 src={source} t={DateTime.Now:HH:mm:ss.fff}");
        _appId = "WeChat (消息库检测)";
        _title = "微信";
        _body = "你收到了一条微信消息";
        _seq++;
        // 立即输出状态（Java 端毫秒级感知）；微信 exe 探测异步补充，不阻塞通知链路
        WriteState();
        _ = Task.Run(() =>
        {
            try
            {
                string exe = FindWechatExe();
                if (!string.IsNullOrEmpty(exe) && exe != _wechatExe)
                {
                    _wechatExe = exe;
                    WriteState();
                }
            }
            catch { }
        });
    }

    /// <summary>微信主窗口可见性缓存（300ms）：事件风暴时避免每事件重复枚举进程与窗口</summary>
    static bool _wechatVisibleCached;
    static long _wechatVisibleCacheMs;

    static bool IsWechatWindowVisibleCached()
    {
        long now = Environment.TickCount64;
        if (now - _wechatVisibleCacheMs < 300) return _wechatVisibleCached;
        _wechatVisibleCached = IsWechatWindowVisible();
        _wechatVisibleCacheMs = now;
        return _wechatVisibleCached;
    }

    /// <summary>
    /// 微信主窗口是否可见（用户正开着聊天界面）。
    /// 遍历微信进程（Weixin/WeChat）的可见顶层窗口，标题含「微信」即视为聊天界面打开。
    /// </summary>
    static bool IsWechatWindowVisible()
    {
        try
        {
            var pids = new HashSet<uint>();
            foreach (var name in new[] { "Weixin", "WeChat" })
            {
                try
                {
                    foreach (var p in Process.GetProcessesByName(name)) pids.Add((uint)p.Id);
                }
                catch { }
            }
            if (pids.Count == 0) return false;

            bool visible = false;
            EnumWindows((h, _) =>
            {
                GetWindowThreadProcessId(h, out uint pid);
                if (!pids.Contains(pid)) return true;
                if (!IsWindowVisible(h)) return true;
                int len = GetWindowTextLength(h);
                if (len <= 0) return true;
                var sb = new StringBuilder(len + 1);
                GetWindowText(h, sb, len + 1);
                if (sb.ToString().Contains("微信"))
                {
                    visible = true;
                    return false; // 找到即停止
                }
                return true;
            }, IntPtr.Zero);
            return visible;
        }
        catch
        {
            return false;
        }
    }

    delegate bool EnumWindowsProc(IntPtr hWnd, IntPtr lParam);

    [DllImport("user32.dll")]
    static extern bool EnumWindows(EnumWindowsProc lpEnumFunc, IntPtr lParam);
    [DllImport("user32.dll")]
    static extern bool IsWindowVisible(IntPtr hWnd);
    [DllImport("user32.dll")]
    static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint lpdwProcessId);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    static extern int GetWindowTextLength(IntPtr hWnd);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    static extern int GetWindowText(IntPtr hWnd, StringBuilder lpString, int nMaxCount);

    /// <summary>
    /// 消息库目录事件监听（FileSystemWatcher）：写入事件几十毫秒内触发通知，真正实时。
    /// 目录变更（多账号切换/数据迁移）时自动重建；失败时轮询兜底继续工作。
    /// </summary>
    static void EnsureMessageWatcher(string? dir)
    {
        if (string.IsNullOrEmpty(dir)) return;
        if (_msgWatcher != null && string.Equals(_msgWatcher.Path, dir, StringComparison.OrdinalIgnoreCase)) return;
        try
        {
            _msgWatcher?.Dispose();
            _msgWatcher = new FileSystemWatcher(dir)
            {
                Filter = "*",
                NotifyFilter = NotifyFilters.LastWrite | NotifyFilters.Size,
                InternalBufferSize = 64 * 1024,
            };
            _msgWatcher.Changed += (_, _) => FireMessageNotification("watcher");
            _msgWatcher.EnableRaisingEvents = true;
            Console.Error.WriteLine($"[WechatDaemon] 消息库事件监听已建立: {dir}");
        }
        catch (Exception ex)
        {
            Console.Error.WriteLine($"[WechatDaemon] 消息库事件监听失败（轮询兜底继续）: {ex.Message}");
            _msgWatcher?.Dispose();
            _msgWatcher = null;
        }
    }

    /// <summary>
    /// 定位微信消息数据库并建立监控清单（跨机器/跨用户通用，不依赖本机硬编码路径）：
    /// 微信 4.x：&lt;数据目录&gt;\xwechat_files\&lt;wxid&gt;\db_storage\message\message_*.db[-wal]
    /// 微信 3.x：&lt;数据目录&gt;\WeChat Files\&lt;wxid&gt;\Msg\msg_*.db
    /// 数据目录解析优先级：
    ///   1. 注册表 HKCU\SOFTWARE\Tencent\Weixin（微信 4.x）的 InstallPath/OldFileSavePath
    ///      及其父目录（自定义安装位置如 E:\微信\Weixin 的数据目录在其旁）；
    ///   2. 注册表 HKCU\SOFTWARE\Tencent\WeChat 的 FileSavePath（微信 3.x 自定义存储）；
    ///   3. 用户文档目录 + 所有逻辑盘根目录及其一级子目录探测
    ///      （微信数据目录可放在任意自定义位置）；
    /// 多账号：取消息库修改时间最新的用户目录。定位失败后每 10s 重试自动跟随目录变更。
    /// </summary>
    static void ResolveMessageDbWatch()
    {
        try
        {
            var roots = new List<string>();

            // 1. 注册表：微信 4.x Weixin 键（InstallPath / OldFileSavePath 及其父目录）
            try
            {
                using var wxKey = Microsoft.Win32.Registry.CurrentUser.OpenSubKey(@"SOFTWARE\Tencent\Weixin");
                string? installPath = wxKey?.GetValue("InstallPath") as string;
                string? oldSavePath = wxKey?.GetValue("OldFileSavePath") as string;
                if (!string.IsNullOrEmpty(installPath))
                {
                    var parent = Path.GetDirectoryName(Path.TrimEndingDirectorySeparator(installPath));
                    if (parent != null) roots.Add(parent);
                }
                if (!string.IsNullOrEmpty(oldSavePath))
                {
                    roots.Add(oldSavePath);
                    var parent = Path.GetDirectoryName(Path.TrimEndingDirectorySeparator(oldSavePath));
                    if (parent != null) roots.Add(parent);
                }
            }
            catch { }

            // 2. 注册表：微信 3.x FileSavePath
            try
            {
                using var key = Microsoft.Win32.Registry.CurrentUser.OpenSubKey(@"SOFTWARE\Tencent\WeChat");
                string? savePath = key?.GetValue("FileSavePath") as string;
                if (!string.IsNullOrEmpty(savePath)) roots.Add(savePath);
            }
            catch { }

            // 3. 文档目录 + 各盘根目录及其一级子目录
            roots.Add(Environment.GetFolderPath(Environment.SpecialFolder.MyDocuments));
            var drives = new List<string>();
            try { drives.AddRange(Environment.GetLogicalDrives()); } catch { }
            foreach (var drive in drives)
            {
                roots.Add(drive);
                try
                {
                    foreach (var sub in Directory.GetDirectories(drive))
                        roots.Add(sub);
                }
                catch { }
            }

            foreach (var root in roots)
            {
                try
                {
                    if (!Directory.Exists(root)) continue;
                    // 微信 4.x：xwechat_files
                    var xdir = Path.Combine(root, "xwechat_files");
                    if (Directory.Exists(xdir) && BuildWatchList(xdir, "db_storage", "message")) return;
                    // 微信 3.x：WeChat Files
                    var wdir = Path.Combine(root, "WeChat Files");
                    if (Directory.Exists(wdir) && BuildWatchList(wdir, "Msg", null)) return;
                }
                catch { }
            }
        }
        catch { }
        _msgDbWatch.Clear();
    }

    /// <summary>
    /// 在 &lt;base&gt;\&lt;user&gt;\&lt;sub&gt;\[&lt;leaf&gt;\] 下找修改时间最新的用户目录，
    /// 监控其全部消息库分片（message_*.db* / msg_*.db*，含 WAL）。
    /// 重建监控清单时保留与旧清单交集文件的基线，避免重复触发历史通知。
    /// </summary>
    static bool BuildWatchList(string baseDir, string subDir, string? leaf)
    {
        try
        {
            string? bestUser = null;
            DateTime bestTime = DateTime.MinValue;
            foreach (var userDir in Directory.GetDirectories(baseDir))
            {
                string scan = Path.Combine(userDir, subDir);
                if (leaf != null) scan = Path.Combine(scan, leaf);
                if (!Directory.Exists(scan)) continue;
                foreach (var pattern in new[] { "message_*.db*", "msg_*.db*" })
                {
                    foreach (var f in Directory.GetFiles(scan, pattern, SearchOption.TopDirectoryOnly))
                    {
                        DateTime t = File.GetLastWriteTime(f);
                        if (t > bestTime) { bestTime = t; bestUser = userDir; }
                    }
                }
            }
            if (bestUser == null) return false;

            string dir = Path.Combine(bestUser, subDir);
            if (leaf != null) dir = Path.Combine(dir, leaf);
            // 旧清单按路径索引，重建时保留交集文件的基线（避免重定位误报历史变化）
            var oldBaseline = new Dictionary<string, (long size, DateTime mtime)>();
            foreach (var (p, s, m) in _msgDbWatch) oldBaseline[p] = (s, m);
            _msgDbWatch.Clear();
            foreach (var pattern in new[] { "message_*.db*", "msg_*.db*" })
            {
                foreach (var f in Directory.GetFiles(dir, pattern, SearchOption.TopDirectoryOnly))
                {
                    if (oldBaseline.TryGetValue(f, out var ob))
                    {
                        _msgDbWatch.Add((f, ob.size, ob.mtime));
                    }
                    else
                    {
                        var fi = new FileInfo(f);
                        _msgDbWatch.Add((f, fi.Length, fi.LastWriteTimeUtc));
                    }
                }
            }
            if (_msgDbWatch.Count == 0) return false;
            // 目录变化时才输出日志（重定位同目录时静默）
            if (!dir.Equals(_msgDbDirLog, StringComparison.OrdinalIgnoreCase))
            {
                _msgDbDirLog = dir;
                Console.Error.WriteLine($"[WechatDaemon] 消息库监控已建立: {dir}（{_msgDbWatch.Count} 个文件）");
            }
            return true;
        }
        catch { return false; }
    }

    /// <summary>--test-once：模拟一条微信通知（无微信环境的端到端联调）</summary>
    static void SimulateWechatNotification()
    {
        Console.Error.WriteLine("[WechatDaemon] --test-once: 输出模拟微信通知");
        _appId = "WeChat (模拟)";
        _title = "微信";
        _body = "你收到了一条微信消息";
        _seq++;
        WriteState();
    }

    /// <summary>
    /// 查找微信 exe 路径：优先探测正在运行的微信进程
    /// （Weixin=微信 4.0，WeChat=微信 3.x，WeChatAppEx=微信小程序容器），
    /// 微信未运行时回退到常见安装路径（含自定义目录如 E:\微信\Weixin）。
    /// </summary>
    static string FindWechatExe()
    {
        foreach (var name in new[] { "Weixin", "WeChat", "wechat", "WeChatAppEx" })
        {
            try
            {
                foreach (var p in Process.GetProcessesByName(name))
                {
                    try
                    {
                        string? f = p.MainModule?.FileName;
                        if (!string.IsNullOrEmpty(f) && File.Exists(f)) return f;
                    }
                    catch { }
                }
            }
            catch { }
        }
        return FindWechatExeOnDisk() ?? _wechatExe;
    }

    /// <summary>
    /// 磁盘路径探测（微信未运行时），按可靠性排序：
    ///   1. 注册表 HKCU\SOFTWARE\Tencent\Weixin 的 InstallPath（微信 4.x 自身写入）；
    ///   2. 卸载注册项（Weixin/WeChat，HKCU+HKLM × 默认/32 位视图）的 DisplayIcon/InstallLocation；
    ///   3. 常见固定目录（用户级安装优先：微信 4.x 默认装在当前用户下）；
    ///   4. 所有逻辑盘根目录及一级子目录通用探测（替代硬编码盘符，
    ///      兼容 E:\微信\Weixin 等任意自定义安装位置）。
    /// </summary>
    static string? FindWechatExeOnDisk()
    {
        // 1. 微信 4.x 自身注册表键：InstallPath 为微信安装时写入，最权威
        try
        {
            using var wxKey = Microsoft.Win32.Registry.CurrentUser.OpenSubKey(@"SOFTWARE\Tencent\Weixin");
            if (wxKey?.GetValue("InstallPath") is string installPath && !string.IsNullOrEmpty(installPath))
            {
                foreach (var c in ExpandInstallPath(installPath, "Weixin.exe"))
                {
                    try { if (File.Exists(c)) return c; } catch { }
                }
            }
        }
        catch { }

        // 2. 卸载注册项（跨版本/跨安装方式最通用）
        string? fromUninstall = FindExeViaUninstallKeys();
        if (fromUninstall != null) return fromUninstall;

        // 3. 常见固定目录（盘符用系统 API 取值，不硬编码 C:\）
        string localAppData = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
        string programFiles = Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles);
        string programFilesX86 = Environment.GetFolderPath(Environment.SpecialFolder.ProgramFilesX86);
        string[] fixedCandidates = {
            Path.Combine(localAppData, "Tencent", "Weixin", "Weixin.exe"),
            Path.Combine(localAppData, "Tencent", "WeChatApp", "WeChat.exe"),
            Path.Combine(programFiles, "Tencent", "Weixin", "Weixin.exe"),
            Path.Combine(programFilesX86, "Tencent", "Weixin", "Weixin.exe"),
            Path.Combine(programFiles, "Tencent", "WeChat", "WeChat.exe"),
            Path.Combine(programFilesX86, "Tencent", "WeChat", "WeChat.exe"),
        };
        foreach (var c in fixedCandidates)
        {
            try { if (File.Exists(c)) return c; } catch { }
        }

        // 4. 通用探测：所有逻辑盘根目录 + 一级子目录（与消息库定位同款模式），
        //    覆盖任意自定义安装目录（如 E:\微信\Weixin、F:\Tools\Tencent\WeChat）
        string[] relPaths = {
            Path.Combine("Tencent", "Weixin", "Weixin.exe"),
            Path.Combine("Tencent", "WeChat", "WeChat.exe"),
        };
        var roots = new List<string>();
        try { roots.AddRange(Environment.GetLogicalDrives()); } catch { }
        var firstLevel = new List<string>();
        foreach (var drive in roots)
        {
            try { firstLevel.AddRange(Directory.GetDirectories(drive)); } catch { }
        }
        foreach (var root in roots.Concat(firstLevel))
        {
            foreach (var rel in relPaths)
            {
                try
                {
                    var c = Path.Combine(root, rel);
                    if (File.Exists(c)) return c;
                }
                catch { }
            }
        }
        return null;
    }

    /// <summary>
    /// InstallPath/InstallLocation 派生候选：值本身可能是 exe 全路径、
    /// 安装目录（exe 在其内），也可能数据目录与安装目录同级（如 E:\微信\Weixin）。
    /// </summary>
    static IEnumerable<string> ExpandInstallPath(string installPath, string exeName)
    {
        var trimmed = Path.TrimEndingDirectorySeparator(installPath);
        if (trimmed.EndsWith(".exe", StringComparison.OrdinalIgnoreCase))
        {
            yield return trimmed;
            yield break;
        }
        yield return Path.Combine(trimmed, exeName);
        var parent = Path.GetDirectoryName(trimmed);
        if (parent != null)
        {
            yield return Path.Combine(parent, "Weixin", "Weixin.exe");
            yield return Path.Combine(parent, "WeChat", "WeChat.exe");
        }
    }

    /// <summary>经卸载注册项查找微信 exe：HKCU/HKLM × 默认/32 位视图 × Weixin/WeChat</summary>
    static string? FindExeViaUninstallKeys()
    {
        var hives = new[] { Microsoft.Win32.RegistryHive.CurrentUser, Microsoft.Win32.RegistryHive.LocalMachine };
        var views = new[] { Microsoft.Win32.RegistryView.Default, Microsoft.Win32.RegistryView.Registry32 };
        var names = new[] { "Weixin", "WeChat" };
        foreach (var hive in hives)
        {
            foreach (var view in views)
            {
                try
                {
                    using var baseKey = Microsoft.Win32.RegistryKey.OpenBaseKey(hive, view);
                    foreach (var name in names)
                    {
                        using var key = baseKey.OpenSubKey(
                            $@"SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\{name}");
                        if (key == null) continue;
                        // DisplayIcon 通常直接是 exe 完整路径（可能带 ",0" 图标索引后缀）
                        if (key.GetValue("DisplayIcon") is string icon && !string.IsNullOrEmpty(icon))
                        {
                            var iconPath = icon.Contains(',') ? icon.Split(',')[0].Trim() : icon.Trim();
                            try { if (File.Exists(iconPath)) return iconPath; } catch { }
                        }
                        if (key.GetValue("InstallLocation") is string loc && !string.IsNullOrEmpty(loc))
                        {
                            foreach (var c in ExpandInstallPath(loc, name + ".exe"))
                            {
                                try { if (File.Exists(c)) return c; } catch { }
                            }
                        }
                    }
                }
                catch { }
            }
        }
        return null;
    }

    /// <summary>写状态 JSON（原子写：tmp + rename，与 MediaInfoDaemon 协议一致）</summary>
    static void WriteState()
    {
        try
        {
            var sb = new StringBuilder();
            sb.Append('{');
            sb.Append("\"seq\":").Append(_seq).Append(',');
            sb.Append("\"appId\":\"").Append(Esc(_appId)).Append("\",");
            sb.Append("\"title\":\"").Append(Esc(_title)).Append("\",");
            sb.Append("\"body\":\"").Append(Esc(_body)).Append("\",");
            sb.Append("\"wechatExe\":\"").Append(Esc(_wechatExe)).Append("\",");
            sb.Append("\"timestamp\":").Append(DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());
            sb.Append('}');
            AtomicWrite(PosFile, Encoding.UTF8.GetBytes(sb.ToString()));
        }
        catch (Exception ex)
        {
            Console.Error.WriteLine($"[WechatDaemon] 状态写入失败: {ex.Message}");
        }
    }

    static void AtomicWrite(string path, byte[] data)
    {
        string tmp = path + ".tmp";
        File.WriteAllBytes(tmp, data);
        File.Move(tmp, path, true);
    }

    static string Esc(string? s) => string.IsNullOrEmpty(s) ? ""
        : s.Replace("\\", "\\\\").Replace("\"", "\\\"").Replace("\n", "\\n").Replace("\r", "\\r");
}
