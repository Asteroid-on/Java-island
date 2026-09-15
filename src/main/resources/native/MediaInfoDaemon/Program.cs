using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;
using Windows.Media.Control;
using Windows.Storage.Streams;

class Program
{
    [DllImport("user32.dll")] static extern bool IsIconic(IntPtr hWnd);
    [DllImport("user32.dll")] static extern bool IsWindowVisible(IntPtr hWnd);
    [DllImport("user32.dll")] static extern bool EnumWindows(EnumWindowsProc lpEnumFunc, IntPtr lParam);
    [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint lpdwProcessId);
    [DllImport("user32.dll", EntryPoint = "GetWindowTextLengthW")] static extern int GetWindowTextLength(IntPtr hWnd);
    [DllImport("user32.dll", EntryPoint = "GetWindowLongW")] static extern int GetWindowLong(IntPtr hWnd, int nIndex);
    [DllImport("user32.dll")] static extern bool GetWindowRect(IntPtr hWnd, out RECT lpRect);
    [DllImport("dwmapi.dll")] static extern int DwmGetWindowAttribute(IntPtr hwnd, int dwAttribute, out int pvAttribute, int cbAttribute);

    delegate bool EnumWindowsProc(IntPtr hWnd, IntPtr lParam);

    [StructLayout(LayoutKind.Sequential)] struct RECT { public int Left, Top, Right, Bottom; }

    const int GWL_EXSTYLE = -20;
    const int WS_EX_TOOLWINDOW = 0x00000080;
    /** 点击穿透浮窗（如桌面歌词）：不是可交互主窗口，不能计作“播放器窗口可见” */
    const int WS_EX_TRANSPARENT = 0x00000020;
    const int DWMWA_CLOAKED = 14;
    /** 认定“主窗口”的最小尺寸（小于此值的是浮窗/提示窗，不代表播放器主窗口可见） */
    const int MAIN_WINDOW_MIN_PX = 100;
    /** 窗口状态探测结果缓存时长：Flush 会随 SMTC 事件高频触发，避免每次全量枚举窗口 */
    const long WindowProbeTtlMs = 250;
    /** “不可见”需持续观察这么久而不是一次快照，防止播放器启动中窗口尚未创建、
        或窗口瞬时重建等瞬态被当作“已最小化”而误弹音乐岛；变为“可见”则立即生效 */
    const long HiddenConfirmMs = 800;

    static readonly string PosFile = Path.Combine(Path.GetTempPath(), "media_info.json");
    static readonly string ThumbFile = Path.Combine(Path.GetTempPath(), "media_thumb.bin");
    static readonly string[] Players = ["cloudmusic", "QQMusic", "SodaMusic"];

    /// <summary>
    /// SMTC SourceAppUserModelId 关键字 → 播放器进程名映射。
    /// 窗口状态必须按“当前活跃会话对应的那个播放器”判定，
    /// 否则多播放器并存时会把别的播放器窗口状态算到正在播放的播放器头上。
    /// </summary>
    static readonly (string Key, string Proc)[] SourceProcessMap = [
        ("cloudmusic", "cloudmusic"),   // 网易云音乐（桌面版）
        ("netease", "cloudmusic"),      // 网易云（UWP/包名系）
        ("qqmusic", "QQMusic"),         // QQ音乐
        ("tencent", "QQMusic"),         // 腾讯系兜底
        ("sodamusic", "SodaMusic"),     // 汽水音乐
        ("qishui", "SodaMusic"),        // 汽水音乐（qishui.com 系）
        ("luna", "SodaMusic"),          // 汽水音乐（com.luna.music 包名）
        ("汽水", "SodaMusic"),          // 汽水音乐（实测 AUMID 为中文名）
    ];

    /// <summary>单实例互斥体：防止应用多次启动导致多个 daemon 并存（内存泄漏）</summary>
    static readonly bool SingletonCreated;
    static readonly Mutex SingletonMutex;

    static Program()
    {
        SingletonMutex = new Mutex(true, "JavaIsland_MediaInfoDaemon_Singleton", out SingletonCreated);
    }

    // SMTC SourceAppUserModelId 白名单（只检测白名单中的播放器，忽略浏览器等）
    static readonly string[] SrcWhitelist = [
        "cloudmusic",       // 网易云音乐
        "netease",          // 网易云 (UWP)
        "qqmusic",          // QQ音乐 (小写)
        "QQMusic",          // QQ音乐 (驼峰)
        "qqmusic.exe",      // QQ音乐 (含.exe后缀)
        "QQMusic.exe",      // QQ音乐 (驼峰+.exe)
        "tencent",          // 腾讯系 (兜底)
        "sodamusic",        // 汽水音乐 (SodaMusic.exe)
        "qishui",           // 汽水音乐 (qishui.com 系)
        "luna.music",       // 汽水音乐 (com.luna.music 包名系)
        "汽水音乐",          // 汽水音乐 (实测 SMTC AUMID 为中文名)
    ];

    static string _last = "";
    static GlobalSystemMediaTransportControlsSession? _s;
    static volatile bool _hasProc;

    // 上次有效媒体属性（TryGetMediaPropertiesAsync 瞬态返回空时沿用）
    static string _lastTitle = "", _lastArtist = "", _lastAlbum = "";

    // ── 暂停感知的插值：累积实际播放时长，排除暂停间隔 ──
    static long _accumulatedPlayTicks = 0;        // 已累积的实际播放时长（100ns ticks）
    static long _playStartStopwatch = 0;          // 当前播放段的 Stopwatch 起点，0=未播放
    static bool _wasPlaying = false;              // 上一轮 isPlaying 状态，检测播放↔暂停跳变
    static string _interpTrackId = "";            // 插值对应的歌曲，切歌时重置
    static string _lastSrcAppId = "";             // 检测会话切换
    static string _lastTrackId = "";              // 检测歌曲切换（title|artist 组合变化）
    static long _lastKnownPosition = 0;           // 上次成功获取的位置，SMTC 失败时保留
    static int _zeroPositionCount = 0;            // 连续未获取到新位置的次数（诊断用）
    // TimelinePropertiesChanged 事件缓存（从事件参数直接提取，避免轮询回读的竞态）
    static long _cachedPositionTicks = 0;
    static long _cachedEndTimeTicks = 0;
    static volatile bool _timelineUpdated = false;

    // ── 会话丢失宽限：SMTC 会话瞬态抖动（浏览器会话抢占/系统短暂不可用）时，
    //    连续 N 轮（500ms/轮）拿不到白名单会话才清空状态，避免面板偶发闪断 ──
    static int _missStreak = 0;
    const int MissGrace = 3;

    // ── 封面缩略图缓存：仅变化时重读 SMTC 流并写独立文件（不再内嵌 base64 进 JSON）──
    static byte[]? _thumbBytes = null;         // 上次读取的缩略图字节（会话内缓存，避免每 500ms 重读流）
    static string _thumbHash = "";             // 对应字节的 SHA256（前 16 字符）
    static volatile bool _thumbDirty = true;   // 事件触发置脏，下一轮 Flush 重读缩略图

    static async Task Main()
    {
        // 控制台输出统一 UTF-8：daemon 输出重定向到 daemon.log，
        // 默认控制台编码（中文 Windows 为 GBK 系）会让中文日志乱码。
        Console.OutputEncoding = Encoding.UTF8;

        // 单实例保护：已有实例运行时直接退出，避免进程泄漏
        if (!SingletonCreated)
        {
            Console.Error.WriteLine("[Daemon] 已有 MediaInfoDaemon 实例在运行，本实例退出。");
            return;
        }
        _hasProc = ScanProc();  // 初始化时立即扫描，避免事件触发的 Flush 拿到 false
        _last = """{"hasSession":false,"hasMusicProcess":false}""";
        Write(_last);
        for (int i = 0; i < 100; i++) { _s = await GetS(); if (_s != null) break; await Task.Delay(100); }
        if (_s != null) { _hasProc = ScanProc(); _s.TimelinePropertiesChanged += OnTimelineChanged; _s.PlaybackInfoChanged += OnChanged; _s.MediaPropertiesChanged += OnChanged; await Flush(); }
        _ = Task.Run(Loop);
        await Task.Delay(-1);
    }

    static void OnChanged(GlobalSystemMediaTransportControlsSession s, object e)
    {
        _hasProc = ScanProc();  // 事件触发时立即更新进程检测
        _thumbDirty = true;     // 媒体属性/播放信息变化 → 缩略图可能变化，置脏待重读
        _ = Task.Run(Flush);
    }

    /// <summary>TimelinePropertiesChanged 专用：从事件触发时刻直接读取位置，避免异步回读的竞态</summary>
    static void OnTimelineChanged(GlobalSystemMediaTransportControlsSession s, object e)
    {
        Console.Error.WriteLine("[Daemon] 🔔 TimelinePropertiesChanged 事件已触发!");
        try
        {
            var tl = s.GetTimelineProperties();
            _cachedPositionTicks = tl.Position.Ticks;
            _cachedEndTimeTicks = tl.EndTime.Ticks;
            _timelineUpdated = true;
            Console.Error.WriteLine($"[Daemon] TimelineEvent pos={_cachedPositionTicks / 10000}ms end={_cachedEndTimeTicks / 10000}ms");
        }
        catch (Exception ex)
        {
            Console.Error.WriteLine($"[Daemon] TimelineEvent 异常: {ex.GetType().Name} - {ex.Message}");
        }
        _hasProc = ScanProc();
        _ = Task.Run(Flush);
    }

    static async Task Loop()
    {
        while (true)
        {
            try
            {
                var f = await GetS();
                if (f != null)
                {
                    _missStreak = 0;
                    if (f != _s) { if (_s != null) { _s.TimelinePropertiesChanged -= OnTimelineChanged; _s.PlaybackInfoChanged -= OnChanged; _s.MediaPropertiesChanged -= OnChanged; } _s = f; _s.TimelinePropertiesChanged += OnTimelineChanged; _s.PlaybackInfoChanged += OnChanged; _s.MediaPropertiesChanged += OnChanged; }
                    _hasProc = ScanProc();
                    await Flush();
                }
                else
                {
                    _hasProc = ScanProc();
                    _missStreak++;
                    if (_missStreak >= MissGrace)
                    {
                        // 连续多轮无白名单会话 → 确认真实丢失，清空状态（保留上次会话则先解绑事件）
                        if (_s != null)
                        {
                            Console.Error.WriteLine($"[Daemon] 会话连续 {_missStreak} 轮未命中，清空状态");
                            try { _s.TimelinePropertiesChanged -= OnTimelineChanged; _s.PlaybackInfoChanged -= OnChanged; _s.MediaPropertiesChanged -= OnChanged; } catch { }
                            _s = null;
                        }
                        await FlushEmpty();
                    }
                    // 宽限期内保留上次会话数据，不写文件，避免瞬态抖动导致面板闪断
                }
            }
            catch { }
            await Task.Delay(500);
        }
    }

    static bool ScanProc() { foreach (var n in Players) try { if (Process.GetProcessesByName(n).Length > 0) return true; } catch { } return false; }

    /// <summary>检查 sourceAppId 是否在白名单中 + 对应进程是否确实在运行。</summary>
    static bool IsSourceWhitelistedAndRunning(string src) {
        if (string.IsNullOrEmpty(src)) return false;
        var srcLower = src.ToLowerInvariant();
        // 1. 白名单检查（中文条目如"汽水音乐"不受 ToLower 影响，直接 Contains 即可）
        if (!SrcWhitelist.Any(w => srcLower.Contains(w))) return false;
        // 2. 进程运行检查（白名单匹配但不在 Players 进程名中 → 仍视为有效，如 UWP/中文名 AUMID）
        foreach (var n in Players) {
            if (srcLower.Contains(n.ToLowerInvariant())) {
                try { return Process.GetProcessesByName(n).Length > 0; }
                catch { return false; }
            }
        }
        return true;
    }

    /// <summary>会话对应的播放器进程名（按 AUMID 关键字定位；无法定位时退回全部白名单播放器）</summary>
    static string[] PlayerProcsForSource(string? src)
    {
        if (!string.IsNullOrEmpty(src))
        {
            var low = src.ToLowerInvariant();
            foreach (var (key, proc) in SourceProcessMap)
                if (low.Contains(key)) return new[] { proc };
        }
        return Players;
    }

    // 窗口状态探测缓存（TtlMs 内复用，避免高频 Flush 重复枚举进程/窗口）
    static bool _winProbeValid;
    static string? _winProbeSrc;
    static long _winProbeAtMs;
    static bool _winProbeHidden;
    static bool _winProbeKnown;
    static long _hiddenSinceMs;
    static string _lastWinDesc = "";

    /// <summary>
    /// 当前活跃会话对应播放器的主窗口是否处于最小化/不可见状态。
    /// known=false 表示窗口状态无法确定（解析不到对应播放器进程），
    /// 此时按“不满足弹出条件”返回 hidden=false，保证宁可不弹也不误弹。
    /// </summary>
    static bool IsPlayerWindowHidden(string? src, out bool known)
    {
        long now = Environment.TickCount64;
        bool rawHidden;
        if (_winProbeValid && string.Equals(_winProbeSrc, src, StringComparison.OrdinalIgnoreCase)
            && now - _winProbeAtMs < WindowProbeTtlMs)
        {
            // 缓存命中（源未变）：继续用上次实测结果，仅重新走一遍确认计时
            rawHidden = _winProbeHidden;
            known = _winProbeKnown;
        }
        else
        {
            var (hidden, k) = ProbePlayerWindowHidden(src);
            _winProbeHidden = hidden;
            _winProbeKnown = k;
            // 会话对应播放器变了 → 窗口状态重新观察
            if (!string.Equals(_winProbeSrc, src, StringComparison.OrdinalIgnoreCase)) _hiddenSinceMs = 0;
            _winProbeSrc = src;
            _winProbeAtMs = now;
            _winProbeValid = true;
            rawHidden = hidden;
            known = k;
        }
        // 防误弹确认：不可见需持续满 HiddenConfirmMs；一旦可见立即重置
        bool reported = rawHidden && known;
        if (!reported) _hiddenSinceMs = 0;
        else if (_hiddenSinceMs == 0) _hiddenSinceMs = now;
        else if (now - _hiddenSinceMs < HiddenConfirmMs) reported = false;

        string desc = !known ? "unknown" : (reported ? "minimized/hidden" : (rawHidden ? "hidden(确认中)" : "visible"));
        if (desc != _lastWinDesc)
        {
            _lastWinDesc = desc;
            Console.Error.WriteLine($"[Daemon] 播放器窗口状态: {desc} src={src}");
        }
        return reported;
    }

    static (bool hidden, bool known) ProbePlayerWindowHidden(string? src)
    {
        var pids = new HashSet<uint>();
        foreach (var n in PlayerProcsForSource(src))
        {
            try { foreach (var p in Process.GetProcessesByName(n)) pids.Add((uint)p.Id); } catch { }
        }
        if (pids.Count == 0) return (false, false);
        return (!HasVisibleMainWindow(pids), true);
    }

    /// <summary>
    /// 目标进程集合是否存在“可见、未最小化、未被 DWM 遮蔽、有标题且尺寸达标”的顶层窗口。
    ///
    /// <para>必须枚举窗口而非取 MainWindowHandle：多进程（Electron 系）播放器的子进程
    /// MainWindowHandle 恒为 IntPtr.Zero，旧实现
    /// <c>Players.Any(n =&gt; 进程 MainWindowHandle == 0 || IsIconic || !IsWindowVisible)</c>
    /// 有两个致命问题：</para>
    /// <list type="number">
    /// <item>任一子进程无主窗口就算命中 Any → 播放器窗口明明可见仍上报“已最小化”；</item>
    /// <item>跨全部播放器取 Any → 另一播放器被最小化/隐藏也会让正在播放且窗口可见的播放器被误判。</item>
    /// </list>
    /// <para>两者叠加导致“只要播放就弹出音乐岛”。</para>
    /// </summary>
    static bool HasVisibleMainWindow(HashSet<uint> pids)
    {
        bool found = false;
        try
        {
            EnumWindows((hWnd, _) =>
            {
                if (found) return false;
                try
                {
                    if (!IsWindowVisible(hWnd) || IsIconic(hWnd)) return true;   // 隐藏或最小化：不算可见
                    if (GetWindowTextLength(hWnd) <= 0) return true;             // 无标题：托盘残留窗/输入法窗等
                    if (!GetWindowRect(hWnd, out var r)) return true;
                    if (r.Right - r.Left < MAIN_WINDOW_MIN_PX || r.Bottom - r.Top < MAIN_WINDOW_MIN_PX) return true;
                    if (IsCloaked(hWnd)) return true;                            // 其它虚拟桌面/被遮蔽：当前不可见
                    // 工具窗/点击穿透浮窗（桌面歌词等）不代表播放器主窗口可见
                    if ((GetWindowLong(hWnd, GWL_EXSTYLE) & (WS_EX_TOOLWINDOW | WS_EX_TRANSPARENT)) != 0) return true;
                    GetWindowThreadProcessId(hWnd, out var pid);
                    if (!pids.Contains(pid)) return true;
                }
                catch { return true; }
                found = true;
                return false;
            }, IntPtr.Zero);
        }
        catch { }
        return found;
    }

    /// <summary>DWM 遮蔽（窗口在其它虚拟桌面/已被宿主隐藏）；接口不可用时按未遮蔽处理。</summary>
    static bool IsCloaked(IntPtr hWnd)
    {
        try
        {
            return DwmGetWindowAttribute(hWnd, DWMWA_CLOAKED, out int cloaked, sizeof(int)) == 0 && cloaked != 0;
        }
        catch { return false; }
    }

    /// <summary>
    /// 白名单未命中诊断日志去重：同一 SourceAppUserModelId 只记录一次。
    /// 旧版每 500ms 轮询都打一条（如浏览器 SMTC 会话长期存在时，
    /// daemon.log 会被同一行刷到数 MB），改为按唯一源去重。
    /// </summary>
    static readonly HashSet<string> _loggedMisses = new(StringComparer.OrdinalIgnoreCase);

    static void LogWhitelistMiss(string src)
    {
        lock (_loggedMisses)
        {
            if (!_loggedMisses.Add(src)) return;
        }
        var hex = string.Join(" ", src.Select(c => ((int)c).ToString("X2")));
        Console.Error.WriteLine($"[Daemon] ⚠ 白名单未命中: \"{src}\" (hex: {hex})（同源后续不再重复记录）");
    }

    /// <summary>
    /// 会话选择结果日志去重：仅在选择结果（源+状态）变化时记录。
    /// 旧版每 500ms 轮询都打一条选择日志，单一会话长期存在时会刷屏。
    /// </summary>
    static string? _lastSelectLogKey;

    static void LogSelect(string key, string msg)
    {
        if (_lastSelectLogKey == key) return;
        _lastSelectLogKey = key;
        Console.Error.WriteLine(msg);
    }

    /// <summary>检查 SMTC 会话是否属于白名单播放器。</summary>
    static bool IsWhitelistedSession(GlobalSystemMediaTransportControlsSession? x) {
        if (x == null) return false;
        try {
            string src = x.SourceAppUserModelId ?? "";
            if (string.IsNullOrEmpty(src)) return false;
            bool matched = SrcWhitelist.Any(w => src.ToLowerInvariant().Contains(w));
            if (!matched) LogWhitelistMiss(src);
            return matched;
        } catch { return false; }
    }

    /// <summary>
    /// 获取 SMTC 会话管理器（带超时兜底）。
    ///
    /// <para>实测发现系统 GSMTC 服务可能整体挂起（某些播放器会话异常时，
    /// RequestAsync 永不返回）。无超时时整个 daemon 会卷死在 Main/Loop 的
    /// await 上，hasMusicProcess 等状态也无法再写入。加 2.5s 超时，
    /// 挂起时按无会话处理（宽限计数照常走），不阻塞其余逻辑。</para>
    /// </summary>
    static int _reqTimeoutCount = 0;

    static async Task<GlobalSystemMediaTransportControlsSessionManager?> RequestManagerWithTimeout()
    {
        try
        {
            var task = GlobalSystemMediaTransportControlsSessionManager.RequestAsync().AsTask();
            var completed = await Task.WhenAny(task, Task.Delay(2500));
            if (completed != task)
            {
                if (++_reqTimeoutCount == 1 || _reqTimeoutCount % 120 == 0)
                    Console.Error.WriteLine($"[Daemon] ⚠ SMTC RequestAsync 超时 x{_reqTimeoutCount}（系统媒体服务挂起），本轮按无会话处理");
                return null;
            }
            return await task;
        }
        catch { return null; }
    }

    /// <summary>
    /// 获取当前活跃的白名单 SMTC 会话，<b>优先选择正在播放 (Playing) 的会话</b>。
    ///
    /// <para>多播放器场景（如网易云+QQ音乐同时运行）下的选择策略：</para>
    /// <list type="number">
    /// <item>收集所有白名单会话并读取各自的 PlaybackStatus</item>
    /// <item>优先返回 <c>Playing</c> 状态的会话（哪个播放器正在出声就显示哪个）</item>
    /// <item>其次返回 <c>Paused</c> 状态的会话</item>
    /// <item>最后降级到 GetCurrentSession / 首个白名单会话</item>
    /// </list>
    /// </summary>
    static async Task<GlobalSystemMediaTransportControlsSession?> GetS()
    {
        try
        {
            var m = await RequestManagerWithTimeout();
            if (m == null) return null;

            // ── 1. 收集所有会话 ──
            var sessions = new List<GlobalSystemMediaTransportControlsSession>();
            foreach (var x in m.GetSessions()) sessions.Add(x);

            // ── 2. 收集所有白名单会话及其播放状态 ──
            var whitelisted = new List<(GlobalSystemMediaTransportControlsSession session, string status, string src)>();
            foreach (var x in sessions)
            {
                try
                {
                    string src = x.SourceAppUserModelId ?? "";
                    if (string.IsNullOrEmpty(src)) continue;
                    if (!SrcWhitelist.Any(w => src.ToLowerInvariant().Contains(w)))
                    {
                        LogWhitelistMiss(src);
                        continue;
                    }
                    string status = "Closed";
                    try { var pb = x.GetPlaybackInfo(); status = pb?.PlaybackStatus.ToString() ?? "Closed"; }
                    catch { }
                    whitelisted.Add((x, status, src));
                }
                catch { }
            }

            if (whitelisted.Count == 0)
            {
                // 所有会话均不在白名单（如仅剩浏览器 MSEdge 会话）→ 返回 null，
                // 交由 Loop 的宽限计数处理；不再兜底采用非白名单会话——
                // 其 hasSession 必然为 false（白名单校验不通过），只会污染面板状态。
                if (sessions.Count > 0)
                {
                    LogSelect($"allMiss:{sessions.Count}:{sessions[0].SourceAppUserModelId}",
                        $"[Daemon] 所有 {sessions.Count} 个会话均不在白名单 (首: {sessions[0].SourceAppUserModelId})");
                }
                return null;
            }

            // ── 3. 优先级 1：Playing 状态的会话（关键修复：多播放器场景下优先活跃播放器）──
            var playing = whitelisted.FirstOrDefault(w =>
                w.status.Equals("Playing", StringComparison.OrdinalIgnoreCase));
            if (playing.session != null)
            {
                LogSelect($"playing:{playing.src}",
                    $"[Daemon] ✅ Playing 优先 → {playing.src} status={playing.status}");
                return playing.session;
            }

            // ── 4. 优先级 2：Paused 状态的会话 ──
            var paused = whitelisted.FirstOrDefault(w =>
                w.status.Equals("Paused", StringComparison.OrdinalIgnoreCase));
            if (paused.session != null)
            {
                LogSelect($"paused:{paused.src}", $"[Daemon] ⏸ Paused 会话 → {paused.src}");
                return paused.session;
            }

            // ── 5. 优先级 3：GetCurrentSession（白名单内）──
            var cur = m.GetCurrentSession();
            if (IsWhitelistedSession(cur))
            {
                LogSelect($"cur:{cur!.SourceAppUserModelId}",
                    $"[Daemon] GetCurrentSession → {cur.SourceAppUserModelId}");
                return cur;
            }
            if (cur != null)
            {
                LogSelect($"curBlocked:{cur.SourceAppUserModelId}",
                    $"[Daemon] GetCurrentSession 被白名单拦截: {cur.SourceAppUserModelId}");
            }

            // ── 6. 优先级 4：第一个白名单会话（Closed/Stopped 等）──
            var first = whitelisted[0];
            LogSelect($"first:{first.src}:{first.status}",
                $"[Daemon] 首个白名单会话: {first.src} status={first.status}");
            return first.session;
        }
        catch { return null; }
    }

    static async Task Flush() { try { var j = await BuildJsonAsync(); _last = j; Write(j); } catch { } }
    static async Task FlushEmpty() { try { var j = await BuildJsonAsync(); if (j != _last) { _last = j; Write(j); } } catch { } }

    static async Task<string> BuildJsonAsync()
    {
        string t = "", a = "", al = "", th = "", st = "Closed", src = "";
        long p = 0, e = 0;
        if (_s != null)
        {
            try { src = _s.SourceAppUserModelId ?? ""; } catch { }
            try
            {
                var mp = await _s.TryGetMediaPropertiesAsync();
                t = Esc(mp.Title); a = Esc(mp.Artist); al = Esc(mp.AlbumTitle);
                if (!string.IsNullOrEmpty(t)) { _lastTitle = t; _lastArtist = a; _lastAlbum = al; }
                else if (!string.IsNullOrEmpty(_lastTitle) && src.Equals(_lastSrcAppId, StringComparison.OrdinalIgnoreCase))
                {
                    // SMTC 偶发返回空媒体属性（切歌瞬间/播放器未及时更新）→ 沿用上次标题，
                    // 避免 hasSession 因空标题瞬态置 false 导致面板闪断（下一轮真实标题到达即覆盖）
                    t = _lastTitle; a = _lastArtist; al = _lastAlbum;
                    Console.Error.WriteLine("[Daemon] 媒体属性瞬态为空，沿用上次标题: " + t);
                }
            if (mp.Thumbnail != null) try
            {
                if (_thumbDirty)
                {
                    var sm = await mp.Thumbnail.OpenReadAsync();
                    if (sm.Size > 0 && sm.Size < 1_048_576)
                    {
                        var dr = new DataReader(sm.GetInputStreamAt(0));
                        await dr.LoadAsync((uint)sm.Size);
                        var b = new byte[sm.Size];
                        dr.ReadBytes(b);
                        string h = Convert.ToHexString(SHA256.HashData(b)).Substring(0, 16);
                        if (!h.Equals(_thumbHash, StringComparison.Ordinal))
                        {
                            _thumbBytes = b;
                            _thumbHash = h;
                            WriteBytes(ThumbFile, b);
                            Console.Error.WriteLine($"[Daemon] 封面已更新: {b.Length} 字节 hash={h}");
                        }
                    }
                    else
                    {
                        _thumbBytes = null;
                        _thumbHash = "";
                    }
                    _thumbDirty = false;
                }
            } catch { }
            if (_thumbBytes != null) { th = _thumbHash; }
            else { _thumbBytes = null; _thumbHash = ""; _thumbDirty = false; }
            } catch { }
            try { var pb = _s.GetPlaybackInfo(); if (pb != null) st = pb.PlaybackStatus.ToString(); } catch { }

            // ══════════════════════════════════════════════════════════════
            // 位置获取：SMTC 事件缓存优先 → SMTC 轮询 → UIA 兜底 → 保留上次值
            // ══════════════════════════════════════════════════════════════
            long rawPTicks = 0, rawETicks = 0;

            // 第 1a 层：SMTC TimelinePropertiesChanged 事件缓存（无竞态）
            if (_timelineUpdated)
            {
                rawPTicks = _cachedPositionTicks;
                rawETicks = _cachedEndTimeTicks;
                _timelineUpdated = false;
                Console.Error.WriteLine($"[Daemon] SMTC(cached) pos={rawPTicks / 10000}ms end={rawETicks / 10000}ms");
            }

            // 第 1b 层：缓存为空/为 0 时回退到轮询（3 次快速重试，50ms 间隔）
            if (rawPTicks == 0)
            {
                int smtcFailures = 0;
                for (int retry = 0; retry < 3; retry++)
                {
                    try
                    {
                        var tl = _s.GetTimelineProperties();
                        rawPTicks = tl.Position.Ticks;
                        rawETicks = tl.EndTime.Ticks;
                        if (rawPTicks > 0) break;
                    }
                    catch (Exception ex)
                    {
                        smtcFailures++;
                        Console.Error.WriteLine($"[Daemon] SMTC retry={retry} 异常: {ex.GetType().Name} - {ex.Message}");
                    }
                    if (rawPTicks == 0 && retry < 2)
                        await Task.Delay(50);
                }
                // LastUpdatedTime 不再用于插值（网易云的 LastUpdatedTime 不可靠）
                if (rawPTicks == 0 && smtcFailures > 0)
                    Console.Error.WriteLine($"[Daemon] SMTC 轮询失败 (failures={smtcFailures})");
            }
            e = rawETicks;

            {
                long nowMs = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
                bool isPlaying = st.Equals("Playing", StringComparison.OrdinalIgnoreCase);

                // 会话/歌曲切换 → 重置状态
                bool sessionChanged = !src.Equals(_lastSrcAppId, StringComparison.OrdinalIgnoreCase);
                string trackId = t + "|" + a;
                bool trackChanged = !string.IsNullOrEmpty(trackId)
                                    && !trackId.Equals(_lastTrackId, StringComparison.OrdinalIgnoreCase);
                if (sessionChanged || trackChanged)
                {
                    _lastKnownPosition = 0;
                    _accumulatedPlayTicks = 0;    // ★ 切歌 → 累积时长归零
                    _playStartStopwatch = 0;
                    _wasPlaying = false;
                    _interpTrackId = "";
                    rawPTicks = 0;
                }
                _lastSrcAppId = src;
                _lastTrackId = trackId;

                // ★ 暂停感知插值：仅对网易云音乐生效（QQ音乐 SMTC 可靠报告位置）
                string srcLower = (src ?? "").ToLowerInvariant();
                bool isNetease = srcLower.Contains("cloudmusic") || srcLower.Contains("netease");
                if (rawPTicks == 0 && isNetease)
                {
                    long nowSw = System.Diagnostics.Stopwatch.GetTimestamp();

                    // 状态跳变：暂停 → 播放 → 记录新播放段起点
                    if (isPlaying && !_wasPlaying)
                    {
                        _playStartStopwatch = nowSw;
                        _interpTrackId = trackId;
                        Console.Error.WriteLine($"[Daemon] 播放开始: accTicks={_accumulatedPlayTicks / 10000}ms");
                    }
                    // 状态跳变：播放 → 暂停 → 将当前段时长加入累积
                    else if (!isPlaying && _wasPlaying && _playStartStopwatch > 0)
                    {
                        double segmentSec = (double)(nowSw - _playStartStopwatch) / System.Diagnostics.Stopwatch.Frequency;
                        _accumulatedPlayTicks += (long)(segmentSec * TimeSpan.TicksPerSecond);
                        _playStartStopwatch = 0;
                        Console.Error.WriteLine($"[Daemon] 暂停: segment={segmentSec:F2}s totalAcc={_accumulatedPlayTicks / 10000}ms");
                    }

                    // 计算实际播放时长（累积 + 当前段）
                    if (isPlaying && _playStartStopwatch > 0)
                    {
                        double currentSegSec = (double)(nowSw - _playStartStopwatch) / System.Diagnostics.Stopwatch.Frequency;
                        long currentSegTicks = (long)(currentSegSec * TimeSpan.TicksPerSecond);
                        long actualTicks = _accumulatedPlayTicks + currentSegTicks;
                        if (actualTicks > 0)
                        {
                            rawPTicks = actualTicks;
                            long diffMs = Math.Abs(actualTicks - _lastKnownPosition) / 10000;
                            if (diffMs > 250 && _lastKnownPosition > 0)
                                Console.Error.WriteLine($"[Daemon] 偏差修正: actual={actualTicks / 10000}ms last={_lastKnownPosition / 10000}ms (diff={diffMs}ms)");
                            if (_zeroPositionCount == 0 || currentSegSec % 30 < 0.6)
                                Console.Error.WriteLine($"[Daemon] 插值: actual={actualTicks / 10000}ms (acc={_accumulatedPlayTicks / 10000}ms+seg={currentSegTicks / 10000}ms)");
                        }
                    }
                }

                _wasPlaying = isPlaying;

                // SMTC 拿到位置 → 使用；否则保留上次值。
                // 注：汽水音乐播放中位置静止，播放期推进由 Java 层汽水专用估计器完成（见 MusicSessionController），
                // 本进程保持原始上报语义，不影响 QQ音乐/网易云。
                if (rawPTicks > 0)
                {
                    p = rawPTicks;
                    _lastKnownPosition = rawPTicks;
                    _zeroPositionCount = 0;
                }
                else
                {
                    p = _lastKnownPosition;
                    _zeroPositionCount++;
                    if (_zeroPositionCount == 1 || _zeroPositionCount % 10 == 0)
                        Console.Error.WriteLine($"[Daemon] ⚠ 位置未更新 x{_zeroPositionCount} (SMTC={rawPTicks})，保留上次值={p / 10000}ms");
                }
            }
        }
        else
        {
            // 会话丢失 → 重置所有状态
            _lastKnownPosition = 0;
            _accumulatedPlayTicks = 0;            // ★ 累积时长归零
            _playStartStopwatch = 0;
            _wasPlaying = false;
            _interpTrackId = "";
            _lastSrcAppId = "";
            _lastTrackId = "";
            _zeroPositionCount = 0;
            _lastTitle = "";
            _lastArtist = "";
            _lastAlbum = "";
            _thumbBytes = null;                   // 会话丢失 → 清空封面缓存
            _thumbHash = "";
            _thumbDirty = true;
        }
        // hasSession: 白名单 + 进程运行 + 有歌名
        bool hs = !string.IsNullOrEmpty(t) && IsSourceWhitelistedAndRunning(src);
        // isMinimized：仅指“当前活跃会话对应播放器”的主窗口最小化/不可见（且已持续足够久）。
        // 旧实现基于全部播放器进程取 Any（子进程 MainWindowHandle 为 0 即命中），
        // 几乎恒为 true，导致音乐岛“只要播放就弹出”，现已改为真实枚举顶层窗口。
        bool minimized = hs && IsPlayerWindowHidden(src, out _);
        if (!hs) _hiddenSinceMs = 0;   // 无会话时清零确认计时，避免下次播放开局即被判“已持续不可见”
        var sb = new StringBuilder();
        sb.Append("{");
        sb.Append("\"hasSession\":").Append(hs ? "true" : "false").Append(",");
        sb.Append("\"hasMusicProcess\":").Append(_hasProc ? "true" : "false").Append(",");
        sb.Append("\"title\":\"").Append(t).Append("\",");
        sb.Append("\"artist\":\"").Append(a).Append("\",");
        sb.Append("\"album\":\"").Append(al).Append("\",");
        sb.Append("\"playbackStatus\":\"").Append(st).Append("\",");
        sb.Append("\"positionTicks\":").Append(p).Append(",");
        sb.Append("\"endTimeTicks\":").Append(e).Append(",");
        sb.Append("\"sourceAppId\":\"").Append(Esc(src)).Append("\",");
        sb.Append("\"thumbnail\":\"\",");
        sb.Append("\"thumbFile\":\"").Append(_thumbBytes != null ? "media_thumb.bin" : "").Append("\",");
        sb.Append("\"thumbHash\":\"").Append(th).Append("\",");
        sb.Append("\"isMinimized\":").Append(minimized ? "true" : "false");
        sb.Append("}");
        return sb.ToString();
    }

    static void Write(string c) {
        try {
            var tmp = PosFile + ".tmp";
            File.WriteAllText(tmp, c, new UTF8Encoding(false));
            File.Move(tmp, PosFile, true);
        } catch { }
    }

    static void WriteBytes(string path, byte[] data) {
        try {
            var tmp = path + ".tmp";
            File.WriteAllBytes(tmp, data);
            File.Move(tmp, path, true);
        } catch { }
    }
    static string Esc(string? s) => string.IsNullOrEmpty(s) ? "" : s.Replace("\\","\\\\").Replace("\"","\\\"").Replace("\n","\\n").Replace("\r","\\r");
}
