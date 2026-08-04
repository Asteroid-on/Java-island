using System;
using System.IO;
using System.Text;
using System.Collections.Generic;
using System.Runtime.InteropServices;
using System.Windows.Automation;
using Windows.Media.Control;
using Windows.Foundation;
using Windows.Storage.Streams;

/// <summary>
/// Windows SMTC 媒体信息查询助手 — 查询当前系统媒体会话信息，输出JSON到stdout。
/// 如SMTC不可用，则回退到读取音乐播放器窗口标题。
/// 
/// 编译命令（在项目根目录执行）：
/// C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe ^
///   /target:exe /out:MediaInfoHelper.exe /nostdlib+ ^
///   /reference:"C:\Windows\Microsoft.NET\Framework64\v4.0.30319\mscorlib.dll" ^
///   /reference:"C:\Windows\Microsoft.NET\Framework64\v4.0.30319\System.dll" ^
///   /reference:"C:\Windows\Microsoft.NET\Framework64\v4.0.30319\System.Core.dll" ^
///   /reference:"C:\Windows\Microsoft.NET\Framework64\v4.0.30319\System.Runtime.dll" ^
///   /reference:"C:\Windows\System32\WinMetadata\Windows.Foundation.winmd" ^
///   /reference:"C:\Windows\System32\WinMetadata\Windows.Media.winmd" ^
///   /reference:"C:\Windows\System32\WinMetadata\Windows.Storage.winmd" ^
///   src\main\resources\native\MediaInfoHelper.cs
/// </summary>
class MediaInfoHelper
{
    private static readonly string[] MusicPlayers = {
        "cloudmusic.exe",  // 网易云音乐
        "qqmusic.exe",     // QQ音乐
        "kugou.exe",       // 酷狗音乐
        "kwmusic.exe",     // 酷我音乐
        "spotify.exe",     // Spotify
        "foobar2000.exe",  // foobar2000
        "music.ui.exe",    // Apple Music
        "wmplayer.exe"     // Windows Media Player
    };

    [DllImport("user32.dll", CharSet = CharSet.Auto)]
    static extern int GetWindowText(IntPtr hWnd, StringBuilder text, int count);

    [DllImport("user32.dll")]
    static extern int GetWindowTextLength(IntPtr hWnd);

    [DllImport("user32.dll")]
    static extern bool EnumWindows(EnumWindowsProc lpEnumFunc, IntPtr lParam);

    [DllImport("user32.dll")]
    static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint lpdwProcessId);

    [DllImport("user32.dll")]
    static extern bool GetWindowRect(IntPtr hWnd, out RECT lpRect);

    [DllImport("user32.dll")]
    static extern bool EnumChildWindows(IntPtr hWndParent, EnumWindowsProc lpEnumFunc, IntPtr lParam);

    delegate bool EnumWindowsProc(IntPtr hWnd, IntPtr lParam);

    [StructLayout(LayoutKind.Sequential)]
    struct RECT { public int Left, Top, Right, Bottom; }

    static void Main()
    {
        bool daemon = Environment.GetCommandLineArgs().Length > 1
                      && Environment.GetCommandLineArgs()[1] == "--daemon";
        if (daemon)
        {
            RunDaemon();
            return;
        }
        string json = QueryMediaInfo();
        Console.OutputEncoding = Encoding.UTF8;
        Console.WriteLine(json);
    }

    static void RunDaemon()
    {
        string posFile = Path.Combine(Path.GetTempPath(), "media_info.json");
        string lastJson = "";
        // 先写一次初始状态
        try
        {
            lastJson = QueryMediaInfo();
            File.WriteAllText(posFile, lastJson, Encoding.UTF8);
        }
        catch { }

        while (true)
        {
            try
            {
                string json = QueryMediaInfo();
                if (json != lastJson)
                {
                    File.WriteAllText(posFile, json, Encoding.UTF8);
                    lastJson = json;
                }
            }
            catch { }
            System.Threading.Thread.Sleep(100);
        }
    }

    static string QueryMediaInfo()
    {
        try
        {
            // ── 1. 检测音乐播放器进程 ──
            bool hasMusicProcess = false;
            foreach (string procName in MusicPlayers)
            {
                try
                {
                    var procs = System.Diagnostics.Process.GetProcessesByName(
                        Path.GetFileNameWithoutExtension(procName));
                    if (procs != null && procs.Length > 0)
                    {
                        hasMusicProcess = true;
                        break;
                    }
                }
                catch { }
            }

            if (!hasMusicProcess)
                return "{\"hasSession\":false,\"hasMusicProcess\":false}";

            // ── 2. 尝试 SMTC ──
            var op = GlobalSystemMediaTransportControlsSessionManager.RequestAsync();
            while (op.Status == AsyncStatus.Started) { System.Threading.Thread.Sleep(20); }
            if (op.Status != AsyncStatus.Completed)
                return "{\"hasSession\":false,\"hasMusicProcess\":true,\"error\":\"SMTC not available\"}";

            var manager = op.GetResults();

            // 先 GetCurrentSession，不行再枚举 GetSessions
            GlobalSystemMediaTransportControlsSession session = manager.GetCurrentSession();
            if (session == null)
            {
                var sessionsOp = manager.GetSessions();
                if (sessionsOp != null)
                {
                    var sessions = new List<GlobalSystemMediaTransportControlsSession>();
                    try { foreach (var s in sessionsOp) { sessions.Add(s); } } catch { }
                    if (sessions.Count > 0) session = sessions[0];
                }
            }

            // ── 3. 提取媒体信息（SMTC 或窗口标题兜底）──
            string title = "", artist = "", album = "", thumbnailBase64 = "";
            string status = "Closed";
            long positionTicks = 0, endTimeTicks = 0;
            string sourceAppId = "";
            string desktopLyrics = "";
            bool hasSmTcSession = false;

            if (session != null)
            {
                hasSmTcSession = true;
                // SMTC 成功
                try { sourceAppId = session.SourceAppUserModelId ?? ""; } catch { }

                var propsOp = session.TryGetMediaPropertiesAsync();
                while (propsOp.Status == AsyncStatus.Started) { System.Threading.Thread.Sleep(20); }
                if (propsOp.Status == AsyncStatus.Completed)
                {
                    var props = propsOp.GetResults();
                    title = EscapeJson(props.Title ?? "");
                    artist = EscapeJson(props.Artist ?? "");
                    album = EscapeJson(props.AlbumTitle ?? "");

                    var thumbRef = props.Thumbnail;
                    if (thumbRef != null)
                    {
                        try
                        {
                            var streamOp = thumbRef.OpenReadAsync();
                            while (streamOp.Status == AsyncStatus.Started) { System.Threading.Thread.Sleep(20); }
                            if (streamOp.Status == AsyncStatus.Completed)
                            {
                                var stream = streamOp.GetResults();
                                if (stream != null && stream.Size > 0 && stream.Size < 1048576)
                                {
                                    var inputStream = stream.GetInputStreamAt(0);
                                    var reader = new DataReader(inputStream);
                                    var loadOp = reader.LoadAsync((uint)stream.Size);
                                    while (loadOp.Status == AsyncStatus.Started) { System.Threading.Thread.Sleep(20); }
                                    if (loadOp.Status == AsyncStatus.Completed)
                                    {
                                        byte[] bytes = new byte[stream.Size];
                                        reader.ReadBytes(bytes);
                                        thumbnailBase64 = Convert.ToBase64String(bytes);
                                    }
                                }
                            }
                        }
                        catch { }
                    }
                }

                try
                {
                    var pbInfo = session.GetPlaybackInfo();
                    if (pbInfo != null) status = pbInfo.PlaybackStatus.ToString();
                }
                catch { }

                // ── 获取播放位置：重试轮询 TimelineProperties（网易云延迟上报）──
                for (int retry = 0; retry < 5; retry++)
                {
                    try
                    {
                        var tl = session.GetTimelineProperties();
                        positionTicks = tl.Position.Ticks;
                        endTimeTicks = tl.EndTime.Ticks;
                        if (positionTicks > 0) break;
                    }
                    catch { }
                    if (positionTicks == 0 && retry < 4)
                        System.Threading.Thread.Sleep(60);
                }

                // ── SMTC 拿不到位置 → UIA 进度条兜底 ──
                if (positionTicks == 0)
                {
                    long uiaPos = ReadProgressFromUIA();
                    if (uiaPos > 0) positionTicks = uiaPos;
                }

                // ── 读桌面歌词窗口文本（拖动后歌词立即变化，用于反向推算位置）──
                desktopLyrics = GetDesktopLyricsText();
            }
            else
            {
                // ── 兜底：读取音乐播放器窗口标题 ──
                string windowTitle = GetMusicPlayerWindowTitle();
                if (!string.IsNullOrEmpty(windowTitle))
                {
                    string[] parts = windowTitle.Split(new[] { " - " }, StringSplitOptions.None);
                    if (parts.Length >= 2)
                    {
                        title = EscapeJson(parts[0].Trim());
                        artist = EscapeJson(parts[1].Trim());
                    }
                    else
                    {
                        title = EscapeJson(windowTitle.Trim());
                    }
                    status = "Playing";
                }
            }

            // hasSession 仅当有歌曲标题时才为 true，避免空 SMTC 会话残留（如后台进程）导致误判
            bool hasSession = !string.IsNullOrEmpty(title);

            // ── 4. 构造 JSON ──
            StringBuilder sb = new StringBuilder();
            sb.Append("{");
            sb.Append("\"hasSession\":").Append(hasSession ? "true" : "false").Append(",");
            sb.Append("\"hasMusicProcess\":true,");
            sb.Append("\"title\":\"").Append(title).Append("\",");
            sb.Append("\"artist\":\"").Append(artist).Append("\",");
            sb.Append("\"album\":\"").Append(album).Append("\",");
            sb.Append("\"playbackStatus\":\"").Append(status).Append("\",");
            sb.Append("\"positionTicks\":").Append(positionTicks).Append(",");
            sb.Append("\"endTimeTicks\":").Append(endTimeTicks).Append(",");
            sb.Append("\"sourceAppId\":\"").Append(EscapeJson(sourceAppId)).Append("\",");
            sb.Append("\"thumbnail\":\"").Append(thumbnailBase64).Append("\",");
            sb.Append("\"desktopLyrics\":\"").Append(EscapeJson(desktopLyrics)).Append("\"");
            sb.Append("}");

            return sb.ToString();
        }
        catch (Exception ex)
        {
            return "{\"error\":\"" + EscapeJson(ex.Message) + "\",\"hasSession\":false}";
        }
    }

    static string GetMusicPlayerWindowTitle()
    {
        foreach (string procName in MusicPlayers)
        {
            try
            {
                var procs = System.Diagnostics.Process.GetProcessesByName(
                    Path.GetFileNameWithoutExtension(procName));
                if (procs != null && procs.Length > 0)
                {
                    foreach (var proc in procs)
                    {
                        try
                        {
                            IntPtr hWnd = proc.MainWindowHandle;
                            if (hWnd != IntPtr.Zero)
                            {
                                int len = GetWindowTextLength(hWnd);
                                if (len > 0)
                                {
                                    StringBuilder sb = new StringBuilder(len + 1);
                                    GetWindowText(hWnd, sb, sb.Capacity);
                                    string wt = sb.ToString().Trim();
                                    if (!string.IsNullOrEmpty(wt))
                                        return wt;
                                }
                            }
                        }
                        catch { }
                    }
                }
            }
            catch { }
        }
        return "";
    }

    // ── 桌面歌词窗口扫描（状态变量）──
    static string _foundLyricsText = "";

    /// <summary>
    /// 用 UI Automation TreeWalker 穿透 CEF 读取桌面歌词文本。
    /// CEF 窗口通过 UIA 暴露文档树，TextPattern 可读取其渲染的文本内容。
    /// </summary>
    static string GetDesktopLyricsText()
    {
        try
        {
            _foundLyricsText = "";
            foreach (string procName in MusicPlayers)
            {
                string bareName = Path.GetFileNameWithoutExtension(procName);
                var procs = System.Diagnostics.Process.GetProcessesByName(bareName);
                if (procs == null || procs.Length == 0) continue;

                foreach (var proc in procs)
                {
                    try
                    {
                        IntPtr hWnd = proc.MainWindowHandle;
                        if (hWnd == IntPtr.Zero) continue;

                        AutomationElement root = AutomationElement.FromHandle(hWnd);
                        if (root == null) continue;

                        // 递归遍历所有 UIA 子孙元素
                        WalkTreeForLyrics(root);
                        if (!string.IsNullOrEmpty(_foundLyricsText)) return _foundLyricsText;
                    }
                    catch { }
                }
            }
        }
        catch { }
        return "";
    }

    static void WalkTreeForLyrics(AutomationElement element)
    {
        if (element == null) return;
        try
        {
            // 检查当前元素的 Name 属性
            string name = "";
            try { name = element.Current.Name ?? ""; } catch { }
            if (IsLyricsCandidate(name)) { _foundLyricsText = name; return; }

            // 尝试 TextPattern（文档内容）
            try
            {
                var textPattern = element.GetCurrentPattern(TextPattern.Pattern) as TextPattern;
                if (textPattern != null)
                {
                    var docRange = textPattern.DocumentRange;
                    if (docRange != null)
                    {
                        string docText = docRange.GetText(-1).Trim();
                        if (IsLyricsCandidate(docText))
                        {
                            // 文档文本通常很长，取最后一行（当前歌词）
                            string[] lines = docText.Split('\n');
                            foreach (string line in lines)
                            {
                                string trimmed = line.Trim();
                                if (IsLyricsCandidate(trimmed))
                                {
                                    _foundLyricsText = trimmed;
                                    return;
                                }
                            }
                            // 全部都没有中文 → 不是歌词文档
                        }
                    }
                }
            }
            catch { }

            // 尝试 ValuePattern（编辑框内容）
            try
            {
                var valPattern = element.GetCurrentPattern(ValuePattern.Pattern) as ValuePattern;
                if (valPattern != null)
                {
                    string val = valPattern.Current.Value ?? "";
                    if (IsLyricsCandidate(val)) { _foundLyricsText = val; return; }
                }
            }
            catch { }

            // 递归子元素
            var children = element.FindAll(TreeScope.Children, Condition.TrueCondition);
            if (children != null)
            {
                foreach (AutomationElement child in children)
                {
                    WalkTreeForLyrics(child);
                    if (!string.IsNullOrEmpty(_foundLyricsText)) return;
                }
            }
        }
        catch { }
    }

    /// <summary>判断文本是否像歌词（包含中文且不是纯符号）</summary>
    static bool IsLyricsCandidate(string text)
    {
        if (string.IsNullOrEmpty(text)) return false;
        if (text == "网易云音乐" || text.Length < 2) return false;
        // 排除纯数字/纯英文/路径
        foreach (char c in text)
        {
            if (c >= 0x4E00 && c <= 0x9FFF) return true; // 有中文才可能是歌词
        }
        return false;
    }

    /// <summary>
    /// 用 UI Automation 读取音乐播放器窗口的进度条滑块位置。
    /// 遍历已配置的播放器进程，找到第一个可用的 Slider 控件，读取 Value/Maximum 并转为 ticks。
    /// </summary>
    static long ReadProgressFromUIA()
    {
        try
        {
            foreach (string procName in MusicPlayers)
            {
                string bareName = Path.GetFileNameWithoutExtension(procName);
                var procs = System.Diagnostics.Process.GetProcessesByName(bareName);
                if (procs == null || procs.Length == 0) continue;

                foreach (var proc in procs)
                {
                    try
                    {
                        IntPtr hWnd = proc.MainWindowHandle;
                        if (hWnd == IntPtr.Zero) continue;

                        AutomationElement window = AutomationElement.FromHandle(hWnd);
                        if (window == null) continue;

                        // 查找所有 Slider 控件（进度条）
                        var sliders = window.FindAll(TreeScope.Descendants,
                            new PropertyCondition(AutomationElement.ControlTypeProperty, ControlType.Slider));

                        if (sliders != null)
                        {
                            foreach (AutomationElement slider in sliders)
                            {
                                try
                                {
                                    var range = slider.GetCurrentPattern(RangeValuePattern.Pattern)
                                        as RangeValuePattern;
                                    if (range != null && range.Current.Maximum > 0)
                                    {
                                        // value 通常是毫秒，转为 100 纳秒 ticks（×10000）
                                        double value = range.Current.Value;
                                        if (value >= 0)
                                            return (long)(value * 10000);
                                    }
                                }
                                catch { }
                            }
                        }
                    }
                    catch { }
                }
            }
        }
        catch { }
        return 0;
    }

    static string EscapeJson(string s)
    {
        if (string.IsNullOrEmpty(s)) return "";
        return s.Replace("\\", "\\\\")
                .Replace("\"", "\\\"")
                .Replace("\n", "\\n")
                .Replace("\r", "\\r")
                .Replace("\t", "\\t");
    }
}
