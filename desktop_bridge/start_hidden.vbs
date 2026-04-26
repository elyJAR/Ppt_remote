Set WshShell = CreateObject("WScript.Shell")
WshShell.Run chr(34) & "powershell.exe" & Chr(34) & " -WindowStyle Hidden -ExecutionPolicy Bypass -File " & chr(34) & WScript.ScriptFullName & "\..\start_background.ps1" & chr(34), 0
Set WshShell = Nothing