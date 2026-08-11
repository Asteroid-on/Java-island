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
    static readonly string[] Players = ["cloudmusic", "QQMusic"];

    // SMTC SourceAppUserModelId 白名单（只检测白名单中的播放器，忽略浏览器等）
    static readonly string[] SrcWhitelist = [
        "cloudmusic",       // 网易云音乐
        "netease",          // 网易云 (UWP)
        "qqmusic",          // QQ音乐 (小写)
        "QQMusic",          // QQ音乐 (驼峰)
        "qqmusic.exe",      // QQ音乐 (含.exe后缀)
        "QQMusic.exe",      // QQ音乐 (驼峰+.exe)
        "tencent",          // 腾讯系 (兜底)
    ];

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
    static long _lastKnownPosition = 0;           // 上次成功获取的位置，SMTC 失败时保留
    static int _zeroPositionCount = 0;            // 连续未获取到新位置的次数（诊断用）
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

    /// <summary>检查 sourceAppId 是否在白名单中 + 对应进程是否确实在运行。</summary>
    static bool IsSourceWhitelistedAndRunning(string src) {
        if (string.IsNullOrEmpty(src)) return false;
        var srcLower = src.ToLowerInvariant();
        // 1. 白名单检查
        if (!SrcWhitelist.Any(w => srcLower.Contains(w))) return false;
        // 2. 进程运行检查（白名单匹配但不在 Players 进程名中 → 仍视为有效，如 UWP）
        foreach (var n in Players) {
            if (srcLower.Contains(n.ToLowerInvariant())) {
                try { return Process.GetProcessesByName(n).Length > 0; }
                catch { return false; }
            }
        }
        return true;
    }

    /// <summary>检查 SMTC 会话是否属于白名单播放器。</summary>
    static bool IsWhitelistedSession(GlobalSystemMediaTransportControlsSession? x) {
        if (x == null) return false;
        try {
            string src = x.SourceAppUserModelId ?? "";
            if (string.IsNullOrEmpty(src)) return false;
            bool matched = SrcWhitelist.Any(w => src.ToLowerInvariant().Contains(w));
            if (!matched) {
                // 诊断：输出未命中白名单的 SourceAppUserModelId 及其 hex 编码
                var hex = string.Join(" ", src.Select(c => ((int)c).ToString("X2")));
                Console.Error.WriteLine($"[Daemon] ⚠ 白名单未命中: \"{src}\" (hex: {hex})");
            }
            return matched;
        } catch { return false; }
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
            var m = await GlobalSystemMediaTransportControlsSessionManager.RequestAsync();

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
                        var hex = string.Join(" ", src.Select(c => ((int)c).ToString("X2")));
                        Console.Error.WriteLine($"[Daemon] ⚠ 白名单未命中: \"{src}\" (hex: {hex})");
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
                // 所有会话均不在白名单 → 兜底：检测到音乐进程在运行 → 使用第一个会话
                if (sessions.Count > 0)
                {
                    Console.Error.WriteLine($"[Daemon] 所有 {sessions.Count} 个会话均不在白名单 (首: {sessions[0].SourceAppUserModelId})");
                    if (ScanProc())
                    {
                        Console.Error.WriteLine($"[Daemon] 兜底：音乐进程在运行，使用首个会话: {sessions[0].SourceAppUserModelId}");
                        return sessions[0];
                    }
                }
                return null;
            }

            // ── 3. 优先级 1：Playing 状态的会话（关键修复：多播放器场景下优先活跃播放器）──
            var playing = whitelisted.FirstOrDefault(w =>
                w.status.Equals("Playing", StringComparison.OrdinalIgnoreCase));
            if (playing.session != null)
            {
                Console.Error.WriteLine($"[Daemon] ✅ Playing 优先 → {playing.src} status={playing.status}");
                return playing.session;
            }

            // ── 4. 优先级 2：Paused 状态的会话 ──
            var paused = whitelisted.FirstOrDefault(w =>
                w.status.Equals("Paused", StringComparison.OrdinalIgnoreCase));
            if (paused.session != null)
            {
                Console.Error.WriteLine($"[Daemon] ⏸ Paused 会话 → {paused.src}");
                return paused.session;
            }

            // ── 5. 优先级 3：GetCurrentSession（白名单内）──
            var cur = m.GetCurrentSession();
            if (IsWhitelistedSession(cur))
            {
                Console.Error.WriteLine($"[Daemon] GetCurrentSession → {cur!.SourceAppUserModelId}");
                return cur;
            }
            if (cur != null)
            {
                Console.Error.WriteLine($"[Daemon] GetCurrentSession 被白名单拦截: {cur.SourceAppUserModelId}");
                if (ScanProc())
                {
                    Console.Error.WriteLine($"[Daemon] 兜底(GetCurrentSession)：音乐进程在运行，使用此会话: {cur.SourceAppUserModelId}");
                    return cur;
                }
            }

            // ── 6. 优先级 4：第一个白名单会话（Closed/Stopped 等）──
            var first = whitelisted[0];
            Console.Error.WriteLine($"[Daemon] 首个白名单会话: {first.src} status={first.status}");
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

                // SMTC 拿到位置 → 使用；否则保留上次值
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
        }
        // hasSession: 白名单 + 进程运行 + 有歌名
        bool hs = !string.IsNullOrEmpty(t) && IsSourceWhitelistedAndRunning(src);
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

    static void Write(string c) {
        try {
            var tmp = PosFile + ".tmp";
            File.WriteAllText(tmp, c, new UTF8Encoding(false));
            File.Move(tmp, PosFile, true);
        } catch { }
    }
    static string Esc(string? s) => string.IsNullOrEmpty(s) ? "" : s.Replace("\\","\\\\").Replace("\"","\\\"").Replace("\n","\\n").Replace("\r","\\r");
}
