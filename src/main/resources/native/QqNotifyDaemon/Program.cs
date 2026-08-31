using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.Json;
using Windows.UI.Notifications;
using Windows.UI.Notifications.Management;

namespace QqNotifyDaemon;

/// <summary>
/// QQ 消息通知守护进程：通过 WinRT UserNotificationListener 读取通知中心里
/// QQ（NTQQ）的 Toast，解析发送者/内容/群名后原子写入 %TEMP%/qq_notify.json，
/// Java 端 WatchService 毫秒级感知（协议与 WechatNotifyDaemon 对齐）。
///
/// 关键背景：本机激活目录中该类的注册名为
/// Windows.UI.Notifications.Management.UserNotificationListener（Management 命名空间），
/// 标准命名空间名不可激活（0x80040154）；.NET 内置投影已提供 Management 命名空间
/// 类型，直接使用即可，无需包身份/稀疏包/自定义投影。
///
/// 双通道：NotificationChanged 事件 + 2s 轮询兜底，按通知 Id + CreationTime 基线去重。
/// </summary>
internal static class Program
{
    /// <summary>轮询兜底间隔：事件订阅在本机不可用（0x80070490），轮询即主通道，
    /// 取 250ms 平衡实时性与开销（GetNotificationsAsync 为本地 WinRT 调用，代价极低）</summary>
    const int PollIntervalMs = 250;
    /// <summary>通知访问被拒后的重试间隔</summary>
    const int AccessRetryMs = 30000;
    /// <summary>已处理通知 Id 环形容量（防集合无限增长）</summary>
    const int DedupCapacity = 1000;

    static readonly object _fireLock = new();
    static readonly HashSet<uint> _processedIds = new();
    static readonly Queue<uint> _processedOrder = new();

    /// <summary>基线时间：仅处理 daemon 启动后创建的通知，防历史消息回放</summary>
    static DateTime _baselineUtc = DateTime.MaxValue;

    static long _seq;
    static string _appId = "";
    static string _sender = "";
    static string _content = "";
    static string _groupName = "";

    static readonly string _statePath =
        Path.Combine(Path.GetTempPath(), "qq_notify.json");

    static UserNotificationListener? _listener;

    [DllImport("user32.dll")]
    static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")]
    static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint pid);

    static async Task Main()
    {
        try { Console.OutputEncoding = Encoding.UTF8; } catch { }
        Console.Error.WriteLine("[QqDaemon] QqNotifyDaemon starting");

        _listener = UserNotificationListener.Current;

        // 通知访问授权：首次调用弹出系统同意框；拒绝则等待用户开启后重试
        while (true)
        {
            UserNotificationListenerAccessStatus access;
            try
            {
                access = _listener.GetAccessStatus();
                if (access != UserNotificationListenerAccessStatus.Allowed)
                {
                    access = await _listener.RequestAccessAsync();
                }
            }
            catch (Exception e)
            {
                Console.Error.WriteLine($"[QqDaemon] 请求通知访问异常: {e.Message}，{AccessRetryMs / 1000}s 后重试");
                await Task.Delay(AccessRetryMs);
                continue;
            }
            if (access == UserNotificationListenerAccessStatus.Allowed) break;
            Console.Error.WriteLine($"[QqDaemon] NOTIFICATION_ACCESS_DENIED (status={access})，" +
                $"请在系统设置中允许通知访问，{AccessRetryMs / 1000}s 后重试");
            await Task.Delay(AccessRetryMs);
        }
        Console.Error.WriteLine("[QqDaemon] 通知访问已授权");

        _baselineUtc = DateTime.UtcNow;
        await ScanAsync(); // 基线扫描：存量通知全部标记已处理，不触发

        // 事件通道：失败时仅靠轮询兜底
        try
        {
            _listener.NotificationChanged += (s, e) => { _ = ScanAsync(); };
            Console.Error.WriteLine("[QqDaemon] 事件通道已订阅");
        }
        catch (Exception e)
        {
            Console.Error.WriteLine($"[QqDaemon] 事件通道订阅失败（{e.GetType().Name} hr=0x{e.HResult:X8}），仅轮询兜底");
        }

        while (true)
        {
            await Task.Delay(PollIntervalMs);
            try { await ScanAsync(); } catch { }
        }
    }

    /// <summary>
    /// 拉取全部 Toast 并处理新增的 QQ 通知。事件与轮询双通道共用，
    /// Id 去重 + CreationTime 基线过滤保证并发下不双报、不回放历史。
    /// </summary>
    static async Task ScanAsync()
    {
        var listener = _listener;
        if (listener == null) return;

        IReadOnlyList<UserNotification> list;
        try
        {
            list = await listener.GetNotificationsAsync(NotificationKinds.Toast);
        }
        catch
        {
            return;
        }

        foreach (var n in list)
        {
            uint id = n.Id;
            lock (_fireLock)
            {
                if (n.CreationTime.UtcDateTime <= _baselineUtc) continue;
                if (!_processedIds.Add(id)) continue;
                _processedOrder.Enqueue(id);
                while (_processedOrder.Count > DedupCapacity)
                {
                    _processedIds.Remove(_processedOrder.Dequeue());
                }
            }
            try
            {
                ProcessNotification(n);
            }
            catch (Exception e)
            {
                Console.Error.WriteLine($"[QqDaemon] 处理通知异常: {e.Message}");
            }
        }
    }

    /// <summary>解析单条通知：仅 QQ 消息（排除微信与 QQ音乐）触发</summary>
    static void ProcessNotification(UserNotification n)
    {
        string appName;
        try
        {
            appName = n.AppInfo?.DisplayInfo?.DisplayName ?? "";
        }
        catch
        {
            return;
        }
        if (appName.Length == 0) return;
        // 微信走消息库检测独立链路（PC 版不发系统 Toast）；QQ音乐名称含 "QQ" 需排除
        if (appName.Contains("微信", StringComparison.OrdinalIgnoreCase)
                || appName.Contains("wechat", StringComparison.OrdinalIgnoreCase)
                || appName.Contains("音乐", StringComparison.OrdinalIgnoreCase)
                || appName.Contains("music", StringComparison.OrdinalIgnoreCase))
        {
            return;
        }
        if (!appName.Contains("QQ", StringComparison.OrdinalIgnoreCase)) return;

        IReadOnlyList<string>? texts;
        try
        {
            var binding = n.Notification?.Visual?
                .GetBinding(KnownNotificationBindings.ToastGeneric);
            var elements = binding?.GetTextElements();
            texts = elements?.Select(t => t.Text ?? "").ToList();
        }
        catch
        {
            return;
        }
        if (texts == null || texts.Count < 2) return;

        // QQ Toast 文本布局：私聊 [发送者, 内容]；群聊 [发送者, 内容, 群名]（按实测顺序解析，
        // 若布局变化仅需调整此处索引）
        lock (_fireLock)
        {
            _appId = appName;
            _sender = texts[0];
            _content = texts[1];
            _groupName = texts.Count >= 3 ? texts[2] : "";
            _seq++;
            WriteState();
        }
        Console.Error.WriteLine($"[QqDaemon] QQ 消息 seq={_seq} sender={_sender} group={(_groupName.Length > 0 ? _groupName : "-")}");
    }

    /// <summary>
    /// 原子写状态文件（tmp + rename）：Java 端 WatchService 监听 %TEMP% 即时感知。
    /// 前台窗口属于 QQ 时跳过（用户正在看 QQ，不打扰）。
    /// </summary>
    static void WriteState()
    {
        if (IsQqForeground())
        {
            Console.Error.WriteLine("[QqDaemon] QQ 窗口处于前台，跳过弹窗触发");
            return;
        }
        try
        {
            var payload = new
            {
                seq = _seq,
                appId = _appId,
                sender = _sender,
                content = _content,
                groupName = _groupName,
                timestamp = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()
            };
            string json = JsonSerializer.Serialize(payload);
            string tmp = _statePath + ".tmp";
            File.WriteAllText(tmp, json);
            File.Move(tmp, _statePath, overwrite: true);
        }
        catch (Exception e)
        {
            Console.Error.WriteLine($"[QqDaemon] 状态写入失败: {e.Message}");
        }
    }

    /// <summary>前台窗口是否属于 QQ 进程（聊天界面开着不打扰，语义对齐微信链路）</summary>
    static bool IsQqForeground()
    {
        try
        {
            IntPtr fg = GetForegroundWindow();
            if (fg == IntPtr.Zero) return false;
            GetWindowThreadProcessId(fg, out uint fgPid);
            return Process.GetProcesses().Any(p =>
            {
                try
                {
                    if ((uint)p.Id != fgPid) return false;
                    string name = p.ProcessName.ToLowerInvariant();
                    return name == "qq"; // 精确匹配 QQ.exe，排除 QQMusic
                }
                catch { return false; }
            });
        }
        catch
        {
            return false;
        }
    }
}
