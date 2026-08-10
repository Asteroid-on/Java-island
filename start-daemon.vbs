' ============================================
' 云隙泡后台启动脚本（成功静默，失败弹窗提示）
' ============================================

Dim objShell, fso, currentDir, jarPath, daemonPath, ncmPath, qqmusicDir
Set objShell = CreateObject("WScript.Shell")
Set fso = CreateObject("Scripting.FileSystemObject")

currentDir = fso.GetParentFolderName(WScript.ScriptFullName)
jarPath = currentDir & "\target\Java-island-1.0-SNAPSHOT.jar"
daemonPath = currentDir & "\MediaInfoDaemon.exe"
ncmPath = currentDir & "\ncm-server.exe"
qqmusicDir = currentDir & "\QQMusicapi"

' ── 启动 .NET 8 SMTC 媒体信息守护进程（静默后台）──
If fso.FileExists(daemonPath) Then
    objShell.Run """" & daemonPath & """", 0, False
End If

' ── 启动 ncm-server (网易云 API 代理) ──
If fso.FileExists(ncmPath) Then
    objShell.Run """" & ncmPath & """", 0, False
End If

' ── 启动 qqmusic-server (QQ音乐 API 代理) ──
If fso.FolderExists(qqmusicDir) Then
    objShell.CurrentDirectory = qqmusicDir
    objShell.Run "node src\server.js", 0, False
End If

If Not fso.FileExists(jarPath) Then
    MsgBox "[FAIL] JAR file not found!" & vbCrLf & vbCrLf & _
           "Build: mvn clean package" & vbCrLf & vbCrLf & _
           "Path:" & vbCrLf & jarPath, _
           vbCritical, "Startup Failed"
    Set fso = Nothing
    Set objShell = Nothing
    WScript.Quit 1
End If

objShell.Run "javaw -jar """ & jarPath & """", 0, False
Set fso = Nothing
Set objShell = Nothing
