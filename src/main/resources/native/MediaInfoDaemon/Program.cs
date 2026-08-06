using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using Windows.Media.Control;
using Windows.Storage.Streams;

class Program
{
    [DllImport("user32.dll")] static extern bool IsIconic(IntPtr hWnd);
    [DllImport("user32.dll")] static extern bool IsWindowVisible(IntPtr hWnd);

    static readonly string PosFile = Path.Combine(Path.GetTempPath(), "media_info.json");
    static readonly string[] Players = ["cloudmusic","qqmusic","kugou","kwmusic","spotify","foobar2000","music.ui","wmplayer"];
    static string _last = "";
    static GlobalSystemMediaTransportControlsSession? _s;
    static volatile bool _hasProc;

    // ── 暂停感知的插值：累积实际播放时长，排除暂停间隔 ──
    static long _accumulatedPlayTicks = 0;        // 已累积的实际播放时长（100ns ticks）
    static long _playStartStopwatch = 0;          // 当前播放段的 Stopwatch 起点，0=未播放
    static bool _wasPlaying = false;              // 上一轮 isPlaying 状态，检测播放↔暂停跳变
    static string _interpTrackId = "";            // 插值对应的歌曲，切歌时重置
    static string _lastSrcAppId = "";             // 检测会话切换
    static string _lastTrackId = "";              // 检测歌曲切换（title|artist 组合变化）
    const long UIA_THROTTLE_MS = 500;             // UIA 调用节流间隔（避免性能问题）
    static long _uiaLastCheckMs = 0;              // 上次 UIA 调用的时间戳
    static long _lastKnownPosition = 0;           // 上次成功获取的位置（SMTC 或 UIA），失败时保留
    static int _zeroPositionCount = 0;            // 连续未获取到新位置的次数（诊断用）
    static string _lastPositionSource = "NONE";   // 上次位置来源：SMTC/UIA/NONE
    // TimelinePropertiesChanged 事件缓存（从事件参数直接提取，避免轮询回读的竞态）
    static long _cachedPositionTicks = 0;
    static long _cachedEndTimeTicks = 0;
    static volatile bool _timelineUpdated = false;

    static async Task Main()
    {
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
                if (f != null && f != _s) { if (_s != null) { _s.TimelinePropertiesChanged -= OnTimelineChanged; _s.PlaybackInfoChanged -= OnChanged; _s.MediaPropertiesChanged -= OnChanged; } _s = f; _s.TimelinePropertiesChanged += OnTimelineChanged; _s.PlaybackInfoChanged += OnChanged; _s.MediaPropertiesChanged += OnChanged; }
                else if (f == null) _s = null;
                _hasProc = ScanProc();
                if (_s != null) await Flush();
                else await FlushEmpty();
            }
            catch { }
            await Task.Delay(500);
        }
    }

    static bool ScanProc() { foreach (var n in Players) try { if (Process.GetProcessesByName(n).Length > 0) return true; } catch { } return false; }

    /// <summary>检查 SMTC sourceAppId 对应的进程是否确实在运行。</summary>
    static bool IsSourceAppRunning(string src) {
        if (string.IsNullOrEmpty(src)) return false;
        var srcLower = src.ToLowerInvariant();
        foreach (var n in Players) {
            if (srcLower.Contains(n.ToLowerInvariant())) {
                try { return Process.GetProcessesByName(n).Length > 0; }
                catch { return false; }
            }
        }
        return false;  // 未知来源，视为未运行
    }

    static async Task<GlobalSystemMediaTransportControlsSession?> GetS()
    {
        try
        {
            var m = await GlobalSystemMediaTransportControlsSessionManager.RequestAsync();
            var s = m.GetCurrentSession();
            // 优先已知播放器：遍历所有会话，找到第一个已知来源
            if (s == null)
            {
                var sessions = new List<GlobalSystemMediaTransportControlsSession>();
                foreach (var x in m.GetSessions()) sessions.Add(x);
                // 先尝试匹配已知播放器
                foreach (var x in sessions)
                {
                    try
                    {
                        string src = x.SourceAppUserModelId ?? "";
                        foreach (var p in Players)
                            if (src.ToLowerInvariant().Contains(p))
                                return x;
                    }
                    catch { }
                }
                // 都没有匹配 → 取第一个
                if (sessions.Count > 0) s = sessions[0];
            }
            return s;
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
                if (mp.Thumbnail != null) try
                {
                    var sm = await mp.Thumbnail.OpenReadAsync();
                    if (sm.Size > 0 && sm.Size < 1_048_576)
                    {
                        var dr = new DataReader(sm.GetInputStreamAt(0));
                        await dr.LoadAsync((uint)sm.Size);
                        var b = new byte[sm.Size];
                        dr.ReadBytes(b);
                        th = Convert.ToBase64String(b);
                    }
                } catch { }
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

            // 第 1b 层：缓存为空时回退到轮询（3 次快速重试，50ms 间隔）
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
                    _uiaLastCheckMs = 0;
                    _accumulatedPlayTicks = 0;    // ★ 切歌 → 累积时长归零
                    _playStartStopwatch = 0;
                    _wasPlaying = false;
                    _interpTrackId = "";
                    rawPTicks = 0;
                }
                _lastSrcAppId = src;
                _lastTrackId = trackId;

                // ★ 暂停感知插值：累积实际播放时长，暂停期间冻结
                if (rawPTicks == 0)
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

                // 第 2 层：UIA 进度条兜底（每 500ms 一次，切歌时立即尝试）
                long uiaTicks = 0;
                if (rawPTicks == 0 && isPlaying)
                {
                    if (_uiaLastCheckMs == 0 || (nowMs - _uiaLastCheckMs) >= UIA_THROTTLE_MS)
                    {
                        uiaTicks = ReadSliderPositionTicks();
                        if (uiaTicks > 0)
                        {
                            _uiaLastCheckMs = nowMs;
                            Console.Error.WriteLine($"[Daemon] UIA OK pos={uiaTicks / 10000}ms");
                        }
                        else
                        {
                            _uiaLastCheckMs = nowMs;
                            if (_zeroPositionCount == 0)
                                Console.Error.WriteLine("[Daemon] UIA 读取失败（窗口可能最小化或CEF渲染）");
                        }
                    }
                }

                // 融合：SMTC 优先 → UIA 兜底 → 保留上次位置
                string posSrc = "NONE";
                if (rawPTicks > 0)
                {
                    p = rawPTicks;
                    _lastKnownPosition = rawPTicks;
                    posSrc = "SMTC";
                    _zeroPositionCount = 0;
                }
                else if (uiaTicks > 0)
                {
                    p = uiaTicks;
                    _lastKnownPosition = uiaTicks;
                    posSrc = "UIA";
                    _zeroPositionCount = 0;
                }
                else
                {
                    p = _lastKnownPosition;
                    _zeroPositionCount++;
                    if (_zeroPositionCount == 1 || _zeroPositionCount % 10 == 0)
                        Console.Error.WriteLine($"[Daemon] ⚠ 位置未更新 x{_zeroPositionCount} (SMTC={rawPTicks},UIA={uiaTicks})，保留上次值={p / 10000}ms");
                }

                // 位置来源变化时输出日志
                if (!posSrc.Equals(_lastPositionSource, StringComparison.Ordinal))
                {
                    Console.Error.WriteLine($"[Daemon] 位置来源切换: {_lastPositionSource} → {posSrc} p={p / 10000}ms");
                    _lastPositionSource = posSrc;
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
            _uiaLastCheckMs = 0;
            _zeroPositionCount = 0;
            _lastPositionSource = "NONE";
        }
        // hasSession: SMTC 源应用的进程确实在运行 + 有歌名
        bool hs = !string.IsNullOrEmpty(t) && IsSourceAppRunning(src);
        bool minimized = _hasProc && Players.Any(n => { try { return Process.GetProcessesByName(n).Any(p => { try { var h = p.MainWindowHandle; return h == IntPtr.Zero || IsIconic(h) || !IsWindowVisible(h); } catch { return false; } }); } catch { return false; } });
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
        sb.Append("\"thumbnail\":\"").Append(th).Append("\",");
        sb.Append("\"isMinimized\":").Append(minimized ? "true" : "false");
        sb.Append("}");
        return sb.ToString();
    }

    /// <summary>
    /// 通过 UI Automation 读取音乐播放器窗口的进度条滑块位置。
    /// </summary>
    static long ReadSliderPositionTicks()
    {
        try
        {
            foreach (string procName in Players)
            {
                Process[]? procs = null;
                try { procs = Process.GetProcessesByName(procName); } catch { continue; }
                if (procs == null || procs.Length == 0) continue;
                foreach (var proc in procs)
                {
                    try
                    {
                        IntPtr hWnd = proc.MainWindowHandle;
                        if (hWnd == IntPtr.Zero) continue;
                        var window = System.Windows.Automation.AutomationElement.FromHandle(hWnd);
                        if (window == null) continue;
                        var sliders = window.FindAll(
                            System.Windows.Automation.TreeScope.Descendants,
                            new System.Windows.Automation.PropertyCondition(
                                System.Windows.Automation.AutomationElement.ControlTypeProperty,
                                System.Windows.Automation.ControlType.Slider));
                        if (sliders == null) continue;
                        foreach (System.Windows.Automation.AutomationElement slider in sliders)
                        {
                            try
                            {
                                var range = slider.GetCurrentPattern(
                                    System.Windows.Automation.RangeValuePattern.Pattern)
                                    as System.Windows.Automation.RangeValuePattern;
                                if (range != null && range.Current.Maximum > 0)
                                {
                                    double value = range.Current.Value;
                                    if (value >= 0)
                                        return (long)(value * 10000);
                                }
                            }
                            catch { }
                        }
                    }
                    catch { }
                }
            }
        }
        catch { }
        return 0;
    }

    static void Write(string c) {
        try {
            var tmp = PosFile + ".tmp";
            File.WriteAllText(tmp, c, new UTF8Encoding(false));
            File.Move(tmp, PosFile, true);
        } catch { }
    }
    static string Esc(string? s) => string.IsNullOrEmpty(s) ? "" : s.Replace("\\","\\\\").Replace("\"","\\\"").Replace("\n","\\n").Replace("\r","\\r");
}
