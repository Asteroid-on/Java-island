' ============================================
' 云隙泡后台启动脚本（成功静默，失败弹窗提示）
' ============================================

Dim objShell, fso, currentDir, jarPath, daemonPath, ncmPath, qqmusicDir
Dim logPath, logFile, nodeExe
Set objShell = CreateObject("WScript.Shell")
Set fso = CreateObject("Scripting.FileSystemObject")

currentDir = fso.GetParentFolderName(WScript.ScriptFullName)
jarPath = currentDir & "\target\Java-island-1.0-SNAPSHOT.jar"
daemonPath = currentDir & "\MediaInfoDaemon.exe"
ncmPath = currentDir & "\ncm-server.exe"
qqmusicDir = currentDir & "\QQMusicapi"
logPath = currentDir & "\startup.log"

Set logFile = fso.OpenTextFile(logPath, 2, True)
logFile.WriteLine "[startup] " & Now & " - starting..."

' 查找 Node.js
nodeExe = FindNode(fso, objShell)
If nodeExe = "" Then
    logFile.WriteLine "[startup] ERROR: Node.js not found!"
    logFile.Close
    MsgBox "[FAIL] Node.js not found!" & vbCrLf & vbCrLf & _
           "Please install Node.js (>=18) from https://nodejs.org" & vbCrLf & _
           "Or set NODE_PATH environment variable.", _
           vbCritical, "QQMusic API Startup Failed"
    Set fso = Nothing
    Set objShell = Nothing
    WScript.Quit 1
End If
logFile.WriteLine "[startup] Node.js: " & nodeExe

' ── 启动 .NET 8 SMTC 媒体信息守护进程 ──
If fso.FileExists(daemonPath) Then
    objShell.Run """" & daemonPath & """", 0, False
    logFile.WriteLine "[startup] MediaInfoDaemon launched"
End If

' ── 启动 ncm-server (网易云 API 代理) ──
If fso.FileExists(ncmPath) Then
    objShell.Run """" & ncmPath & """", 0, False
    logFile.WriteLine "[startup] ncm-server launched"
End If

' ── 启动 qqmusic-server (QQ音乐 API 代理) ──
If fso.FolderExists(qqmusicDir) Then
    objShell.CurrentDirectory = qqmusicDir
    objShell.Run """" & nodeExe & """ src\server.js", 0, False
    logFile.WriteLine "[startup] qqmusic-api launched (" & qqmusicDir & ")"
End If

' ── Java 应用（已禁用）──
' If Not fso.FileExists(jarPath) Then
'     logFile.WriteLine "[startup] ERROR: JAR not found: " & jarPath
'     logFile.Close
'     MsgBox "[FAIL] JAR file not found!" & vbCrLf & vbCrLf & _
'            "Build: mvn clean package" & vbCrLf & vbCrLf & _
'            "Path:" & vbCrLf & jarPath, _
'            vbCritical, "Startup Failed"
'     Set fso = Nothing
'     Set objShell = Nothing
'     WScript.Quit 1
' End If
' 
' objShell.Run "javaw -jar """ & jarPath & """", 0, False
logFile.WriteLine "[startup] Java application SKIPPED"
logFile.Close
Set fso = Nothing
Set objShell = Nothing

Function FindNode(fso, objShell)
    Dim paths(6), i
    paths(0) = "C:\Program Files\nodejs\node.exe"
    paths(1) = "C:\Program Files (x86)\nodejs\node.exe"
    paths(2) = objShell.ExpandEnvironmentStrings("%LOCALAPPDATA%") & "\Programs\nodejs\node.exe"
    paths(3) = objShell.ExpandEnvironmentStrings("%APPDATA%") & "\nvm\node.exe"
    paths(4) = objShell.ExpandEnvironmentStrings("%USERPROFILE%") & "\scoop\apps\nodejs\current\node.exe"
    paths(5) = objShell.ExpandEnvironmentStrings("%USERPROFILE%") & "\AppData\Local\fnm\node.exe"
    paths(6) = FindInPath(objShell, "node.exe")
    For i = 0 To 6
        If paths(i) <> "" And fso.FileExists(paths(i)) Then
            FindNode = paths(i)
            Exit Function
        End If
    Next
    FindNode = ""
End Function

Function FindInPath(objShell, exeName)
    Dim pathEnv, dirs, i, fullPath
    pathEnv = objShell.ExpandEnvironmentStrings("%PATH%")
    dirs = Split(pathEnv, ";")
    For i = 0 To UBound(dirs)
        fullPath = dirs(i) & "\" & exeName
        If fso.FileExists(fullPath) Then
            FindInPath = fullPath
            Exit Function
        End If
    Next
    FindInPath = ""
End Function
