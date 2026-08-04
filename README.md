# 云隙泡 (Java-island)

一个仿 macOS 云隙泡的 Windows 桌面应用，支持鼠标感应自动显示、流畅动画效果和系统托盘控制。

## ✨ 特性

- 🎯 **鼠标感应**: 鼠标移到屏幕顶部自动显示
- 🎨 **流畅动画**: 120fps 高帧率动画效果
- 🌓 **系统托盘**: 后台运行，托盘图标控制
- 🔧 **守护进程**: 关闭终端后仍可正常运行
- 🎭 **半圆设计**: 左右两侧半圆边框，美观大方
- ⚡ **快速响应**: 优化的检测范围和响应速度

## 🚀 快速开始

### 第一步：编译项目

打开命令行（CMD或PowerShell），进入项目目录，执行：

```bash
mvn clean package
```

编译成功后，会在 `target` 目录下生成 `Java-island-1.0-SNAPSHOT.jar` 文件。

### 第二步：启动云隙泡

双击运行 `start-daemon.vbs` 文件

- ✅ 完全后台运行，不显示任何窗口
- ✅ 关闭终端后继续运行
- ✅ 系统托盘图标控制

### 第三步：使用云隙泡

1. 在系统托盘找到 🏝 图标
2. 鼠标移到屏幕顶部中央
3. 云隙泡会自动显示

## 📁 项目结构

```
Java-island/
├── src/main/java/              # 源代码目录
│   ├── Main.java               # 主窗口类
│   ├── SystemTrayManager.java  # 系统托盘管理
│   └── DynamicIslandService.java # 业务逻辑服务
├── target/                     # 编译输出目录
├── start-daemon.vbs            # 启动脚本（唯一方式）⭐
├── 使用说明.md                 # 中文使用指南
└── pom.xml                     # Maven配置文件
```

## 📖 文档

- **[使用说明.md](./使用说明.md)** - 完整中文使用指南

## 🎮 使用说明

### 启动方式

**唯一推荐方式**：双击 `start-daemon.vbs`
- ✅ 完全后台运行，无任何窗口
- ✅ 自动检测JAR文件
- ✅ 显示启动提示

### 控制方式

1. **鼠标控制**
   - 移到屏幕顶部 → 显示云隙泡
   - 移开鼠标 → 自动隐藏
   - 点击云隙泡 → 隐藏

2. **托盘图标**
   - 左键点击 → 显示/隐藏
   - 右键菜单 → 显示/隐藏、退出

### 停止程序

- **推荐**: 右键托盘图标 → 选择“退出”
- **强制**: 任务管理器结束 javaw 进程

## ⚙️ 配置说明

### 修改触发距离

编辑 `SystemTrayManager.java` 第12行：
```java
private static final int TRIGGER_DISTANCE = 50; // 改为其他值
```

### 修改动画速度

编辑 `SystemTrayManager.java` 中的 duration 值：
```java
final double durationPhase1 = 5.5;  // 数值越小速度越快
final double durationPhase2 = 18.67;
```

### 修改岛的大小

编辑 `Main.java` 和 `SystemTrayManager.java` 中的尺寸参数：
```java
setSize(180, 50); // 宽度180px，高度50px
```

## 🔧 技术栈

- **Java 11** - 编程语言
- **Swing** - GUI框架
- **Maven** - 构建工具
- **JNA** - 本地访问（可选）

## 💡 常见问题

### Q: 启动后看不到托盘图标？
A: 
1. 确认已执行 `mvn clean package`
2. 检查 `target` 目录下是否有JAR文件
3. 查看任务管理器中是否有javaw进程

### Q: 如何开机自启动？
A: 
将 `start-daemon.vbs` 的快捷方式放到启动文件夹：
```
Win + R → shell:startup → 粘贴快捷方式
```

### Q: 为什么有多个云隙泡实例？
A: 
避免多次运行启动脚本。每次启动前确认没有正在运行的实例。

### Q: 动画卡顿怎么办？
A: 
1. 检查系统资源占用
2. 关闭其他高性能图形应用
3. 调整动画帧率（修改Timer间隔）

## 📝 更新日志

### v1.0.0
- ✅ 基础云隙泡功能
- ✅ 鼠标感应显示
- ✅ 系统托盘集成
- ✅ 流畅动画效果
- ✅ 守护进程支持
- ✅ 多种启动方式
- ✅ 完整文档系统

## 🤝 贡献

欢迎提交 Issue 和 Pull Request！

## 📄 许可证

MIT License

## 🎉 致谢

灵感来源于 macOS 云隙泡设计

---

**享受你的云隙泡体验！** 🏝️
