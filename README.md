# 云隙泡 (Java-island)

一个仿 iOS 灵动岛的 Windows 桌面应用，支持鼠标感应自动显示、流畅动画效果和系统托盘控制。

## ✨ 特性

- 🎯 **鼠标感应**: 鼠标移到屏幕顶部自动显示，移开自动隐藏
- 🎨 **流畅动画**: 高精度定时器驱动（设计 100FPS，实测 ≈87FPS）
- 🎵 **音乐歌词上岛**: 自动检测播放中的音乐并弹出音乐面板，网易云/QQ音乐双来源歌词 + LRCLIB 兜底
- 📷 **设备状态检测**: 摄像头/麦克风使用状态、蓝牙/WiFi/电池状态实时上岛
- 🌤️ **天气显示**: 定位 + 实时天气聚合展示
- 🔒 **单实例保护**: 端口 9127 单实例锁，重复启动自动退出
- 🔧 **守护进程**: MediaInfoDaemon / ncm-server / qqmusic-api 自动拉起与回收
- 🌓 **系统托盘**: 后台运行，托盘菜单控制（设置/退出）

## 🚀 快速开始

### 环境要求

- JDK 25（编译与运行）
- Windows 10/11 真机

### 第一步：编译项目

打开命令行（CMD或PowerShell），进入项目目录，执行：

```bash
mvn clean package
```

编译成功后，会在 `target` 目录下生成 `Java-island-1.1.jar` 文件。

### 第二步：启动云隙泡

**正式版（打包 exe）**：双击运行 `Java-island.exe`（jpackage 产物，守护进程已随包分发）

**开发版（源码运行，进程名同为 Java-island）**：
```bash
powershell -ExecutionPolicy Bypass -File launch.ps1            # 静默启动（stdout → target\app-out.log）
powershell -ExecutionPolicy Bypass -File launch.ps1 -Console   # 带控制台窗口
```

应用启动时会自动拉起并管理三个守护进程（MediaInfoDaemon / ncm-server / qqmusic-api），退出时自动回收。

### 第三步：使用云隙泡

1. 在系统托盘找到 🏝 图标
2. 鼠标移到屏幕顶部中央
3. 云隙泡会自动显示

## 📁 项目结构

```
Java-island/
├── src/main/java/com/island/   # 源代码
│   ├── IslandApplication.java  # 主入口（单实例锁/守护进程管理/退出回收）
│   ├── island/ui/              # 主岛/扩展岛 UI、动画、音乐面板
│   ├── music/                  # 歌词服务（调度/缓存/网易云/QQ音乐 Provider）
│   ├── battery/ bluetooth/     # 电池/蓝牙监控（JNA）
│   ├── wifi/ privacy/ weather/ # WiFi/隐私/天气监控
│   ├── tray/ monitor/ config/  # 托盘/监控调度/配置
│   └── util/                   # 日志、窗口管理等工具
├── src/main/resources/         # 字体/图标/native(MediaInfoDaemon 源码)/config.properties
├── QQMusicapi/                 # QQ音乐 API 子项目（Node.js + Koa，端口 3300）
├── MediaInfoDaemon.exe         # SMTC 媒体信息守护进程（.NET 8）
├── ncm-server.exe              # 网易云 API 代理（端口 3000）
├── target/                     # 编译输出目录
├── 使用说明.md                 # 完整中文使用指南（含音乐功能与歌词来源）
└── pom.xml                     # Maven配置
```

## 📖 文档

- **[使用说明.md](./使用说明.md)** - 完整中文使用指南（含音乐功能与歌词来源、网易云 SMTC 使用提示）
- **[../perf-tools/性能测试报告.md](../perf-tools/性能测试报告.md)** - 生产环境性能测试与评估报告（T1–T13 全量数据）

## 🎮 使用说明

### 启动方式

- **正式版**：双击打包的 `Java-island.exe`
- **开发版**：`powershell -ExecutionPolicy Bypass -File launch.ps1`（进程名同为 Java-island，stdout → target\app-out.log）

应用启动时自动拉起 MediaInfoDaemon / ncm-server / qqmusic-api 三个守护进程（端口/进程探测防重复），托盘退出时自动回收。

### 控制方式

1. **鼠标控制**
   - 移到屏幕顶部 → 显示云隙泡
   - 移开鼠标 → 自动隐藏
   - 点击云隙泡 → 隐藏

2. **托盘图标**
   - 左键点击 → 显示/隐藏
   - 右键菜单 → 设置、退出

### 停止程序

- **推荐**: 右键托盘图标 → 选择“退出”
- **强制**: 任务管理器结束 Java-island.exe 进程

## ⚙️ 配置说明

### 修改触发距离

编辑 `config/AppConstants.java`：
```java
public static final int TRIGGER_DISTANCE = 8; // 距离屏幕顶部像素，越大越易触发
```

### 修改动画速度与尺寸

编辑 `island/ui/IslandUiStyle.java`：
```java
public static final int EXPAND_ANIM_DURATION_MS = 280; // 展开/收起动画时长
public static final int EXPAND_ANIM_FRAME_MS = 10;     // 展开动画帧间隔（100FPS）
public static final int EXPANDED_WIDTH = 450;          // 扩展岛宽度
public static final int EXPANDED_HEIGHT = 54;          // 扩展岛高度
public static final int MUSIC_STOP_AUTO_HIDE_MS = 2 * 60 * 1000; // 音乐停止后自动收回等待
```

### 调试控制台输出

启动参数加 `-Disland.debug=true` 开启高频调试输出（默认静默）。

## 🔧 技术栈

- **Java 25** - 编程语言
- **Swing** - GUI 框架（透明窗口 + 自绘圆角/发光/双缓冲渲染）
- **JNA 5.14** - Win32 API 调用（注册表/蓝牙/电池/WLAN API/高精度定时器）
- **FlatLaf 3.6.1** - 全局深色主题
- **JMTC 0.0.3** - Windows SMTC 媒体控制桥接
- **MediaInfoDaemon** - C#（.NET 8）SMTC 媒体信息守护进程
- **QQMusicapi** - Node.js ≥18（Koa）QQ音乐 API 子项目，源自 [L-1124/QQMusicApi](https://github.com/L-1124/QQMusicApi) 的 JavaScript 移植
- **ncm-server** - 网易云 API 代理，源自 [Binaryify/NeteaseCloudMusicApi](https://github.com/Binaryify/NeteaseCloudMusicApi) 打包
- **Maven** - 构建工具

> 音乐歌词的完整调度链路（ncm-server / QQMusicapi / LRCLIB）见 [使用说明.md](./使用说明.md)「音乐功能与歌词来源」章节。
> 两个歌词来源的原项目：网易云 [NeteaseCloudMusicApi](https://github.com/Binaryify/NeteaseCloudMusicApi)、QQ音乐 [QQMusicApi](https://github.com/L-1124/QQMusicApi)。

## 💡 常见问题

### Q: 启动后看不到托盘图标？
A: 
1. 确认已执行 `mvn clean package`
2. 检查 `target` 目录下是否有JAR文件
3. 查看任务管理器中是否有 Java-island.exe 进程

### Q: 如何开机自启动？
A:
打开托盘菜单 → 设置 → 勾选「开机自启」（写入注册表 `HKCU\...\Run`），或手动将 `Java-island.exe` 快捷方式放入启动文件夹：
```
Win + R → shell:startup → 粘贴快捷方式
```

### Q: 为什么有多个云隙泡实例？
A:
应用内置单实例锁（端口 9127）：重复启动时新实例会自动退出。若出现多实例共存，请检查 9127 端口是否被其他程序占用，或手动结束残留进程后重启。

### Q: 网易云音乐歌词不上岛？
A:
需在网易云音乐客户端设置中打开 SMTC 接口（系统媒体控制），详见 [使用说明.md](./使用说明.md)「使用提示（网易云音乐）」。注意：网易云 SMTC 不报告播放进度，歌词不会跟随进度条移动。

### Q: 动画卡顿怎么办？
A: 
1. 检查系统资源占用
2. 关闭其他高性能图形应用
3. 调整动画帧率（修改Timer间隔）

## 📝 更新日志

### v1.0.0
- ✅ 基础云隙泡功能（鼠标感应/托盘/动画）
- ✅ 音乐歌词上岛（网易云/QQ音乐双来源 + LRCLIB 兜底，封面/歌词二级缓存）
- ✅ 设备状态检测（摄像头/麦克风/蓝牙/WiFi/电池仪表盘）
- ✅ 天气显示与错误报告
- ✅ 单实例锁 + 守护进程启动探测与退出回收
- ✅ 性能优化（WLAN API 替换 netsh、封面独立文件协议、扩展岛窗口复用、高精度定时器 ≈87FPS）
- ✅ jpackage 打包 exe 发布

## 🤝 贡献

欢迎提交 Issue 和 Pull Request！

## 📄 许可证

MIT License

## 🎉 致谢

灵感来源于 iOS 灵动岛设计

---

**享受你的云隙泡体验！** 🏝️
