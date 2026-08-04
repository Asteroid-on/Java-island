using System.Diagnostics;
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

    static async Task Main()
    {
        _hasProc = ScanProc();  // 初始化时立即扫描，避免事件触发的 Flush 拿到 false
        _last = """{"hasSession":false,"hasMusicProcess":false}""";
        Write(_last);
        for (int i = 0; i < 100; i++) { _s = await GetS(); if (_s != null) break; await Task.Delay(100); }
        if (_s != null) { _hasProc = ScanProc(); _s.TimelinePropertiesChanged += OnChanged; _s.PlaybackInfoChanged += OnChanged; _s.MediaPropertiesChanged += OnChanged; await Flush(); }
        _ = Task.Run(Loop);
        await Task.Delay(-1);
    }

    static void OnChanged(GlobalSystemMediaTransportControlsSession s, object e)
    {
        _hasProc = ScanProc();  // 事件触发时立即更新进程检测
        _ = Task.Run(Flush);
    }

    static async Task Loop()
    {
        while (true)
        {
            try
            {
                var f = await GetS();
                if (f != null && f != _s) { if (_s != null) { _s.TimelinePropertiesChanged -= OnChanged; _s.PlaybackInfoChanged -= OnChanged; _s.MediaPropertiesChanged -= OnChanged; } _s = f; _s.TimelinePropertiesChanged += OnChanged; _s.PlaybackInfoChanged += OnChanged; _s.MediaPropertiesChanged += OnChanged; }
                else if (f == null) _s = null;
                _hasProc = ScanProc();
                if (_s != null) await Flush();
                else await FlushEmpty();
            }
            catch { }
            await Task.Delay(1000);
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

    static long _lastWrite;
    static async Task Flush() { try { var j = await BuildJsonAsync(); var now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(); if (j != _last || now - _lastWrite > 3000) { _last = j; _lastWrite = now; Write(j); } } catch { } }
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
            try { var tl = _s.GetTimelineProperties(); p = tl.Position.Ticks; e = tl.EndTime.Ticks; } catch { }
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

    static void Write(string c) {
        try {
            var tmp = PosFile + ".tmp";
            File.WriteAllText(tmp, c, new UTF8Encoding(false));
            File.Move(tmp, PosFile, true);
        } catch { }
    }
    static string Esc(string? s) => string.IsNullOrEmpty(s) ? "" : s.Replace("\\","\\\\").Replace("\"","\\\"").Replace("\n","\\n").Replace("\r","\\r");
}
