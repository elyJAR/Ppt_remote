; NSIS Installer Script for PPT Remote Bridge

!include "MUI2.nsh"

; General
Name "PPT Remote Bridge"
OutFile "dist\PptRemoteBridgeSetup_NSIS.exe"
InstallDir "$PROGRAMFILES\PptRemoteBridge"
RequestExecutionLevel admin

; UI Settings
!define MUI_ABORTWARNING
!define MUI_ICON "${NSISDIR}\Contrib\Graphics\Icons\modern-install.ico"

; Finish page: offer to launch the app immediately
; IMPORTANT: Launch via explorer.exe to drop Admin privileges. If launched directly,
; it runs as Admin and cannot communicate with PowerPoint running as a normal user.
!define MUI_FINISHPAGE_RUN "explorer.exe"
!define MUI_FINISHPAGE_RUN_PARAMETERS "$INSTDIR\PptRemoteBridge.exe"
!define MUI_FINISHPAGE_RUN_TEXT "Launch PPT Remote Bridge now"

; Pages
!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_INSTFILES
Page custom StartupPage StartupPageLeave
!insertmacro MUI_PAGE_FINISH

!insertmacro MUI_UNPAGE_WELCOME
!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES
!insertmacro MUI_UNPAGE_FINISH

; Languages
!insertmacro MUI_LANGUAGE "English"

; ── Custom startup page variables ────────────────────────────────────────────
Var StartupCheckbox
Var StartupState

Function StartupPage
    nsDialogs::Create 1018
    Pop $0

    ${NSD_CreateLabel} 0 0 100% 40u "Would you like PPT Remote Bridge to start automatically when Windows starts?"
    Pop $0

    ${NSD_CreateCheckbox} 0 50u 100% 12u "Start PPT Remote Bridge with Windows"
    Pop $StartupCheckbox
    ${NSD_SetState} $StartupCheckbox ${BST_CHECKED}   ; checked by default

    nsDialogs::Show
FunctionEnd

Function StartupPageLeave
    ${NSD_GetState} $StartupCheckbox $StartupState
FunctionEnd

; ── Installer section ─────────────────────────────────────────────────────────
Section "MainSection" SEC01
    SetOutPath "$INSTDIR"
    File /r "dist\PptRemoteBridge\*.*"

    ; Create uninstaller
    WriteUninstaller "$INSTDIR\Uninstall.exe"

    ; Add/remove programs entry
    WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\PptRemoteBridge" \
        "DisplayName" "PPT Remote Bridge"
    WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\PptRemoteBridge" \
        "UninstallString" "$INSTDIR\Uninstall.exe"
    WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\PptRemoteBridge" \
        "DisplayIcon" "$INSTDIR\PptRemoteBridge.exe"

    ; Startup registry — add or skip based on checkbox
    ${If} $StartupState == ${BST_CHECKED}
        WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Run" \
            "PptRemoteBridge" "$INSTDIR\PptRemoteBridge.exe"
    ${EndIf}

    ; Shortcuts
    CreateDirectory "$SMPROGRAMS\PPT Remote Bridge"
    CreateShortcut "$SMPROGRAMS\PPT Remote Bridge\PPT Remote Bridge.lnk" "$INSTDIR\PptRemoteBridge.exe"
    CreateShortcut "$SMPROGRAMS\PPT Remote Bridge\Uninstall.lnk" "$INSTDIR\Uninstall.exe"
    CreateShortcut "$DESKTOP\PPT Remote Bridge.lnk" "$INSTDIR\PptRemoteBridge.exe"
SectionEnd

; ── Uninstaller section ───────────────────────────────────────────────────────
Section "Uninstall"
    ; Remove startup entry
    DeleteRegValue HKCU "Software\Microsoft\Windows\CurrentVersion\Run" "PptRemoteBridge"

    ; Remove Add/Remove Programs entry
    DeleteRegKey HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\PptRemoteBridge"

    ; Remove shortcuts
    Delete "$DESKTOP\PPT Remote Bridge.lnk"
    Delete "$SMPROGRAMS\PPT Remote Bridge\PPT Remote Bridge.lnk"
    Delete "$SMPROGRAMS\PPT Remote Bridge\Uninstall.lnk"
    RMDir "$SMPROGRAMS\PPT Remote Bridge"

    RMDir /r "$INSTDIR"
SectionEnd

