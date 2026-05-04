; ============================================================
; PPT Remote Bridge — Inno Setup installer script
;
; Prerequisites:
;   1. Build the EXE first:  .\build_exe.ps1
;   2. Install Inno Setup 6: https://jrsoftware.org/isinfo.php
;   3. Compile this script:  iscc installer.iss
;      (or open in Inno Setup IDE and press F9)
;
; Output: dist\PptRemoteBridgeSetup.exe
; ============================================================

#define AppName      "PPT Remote Bridge"
#define AppVersion   "0.5.1"
#define AppPublisher "AntiGravity Projects"
#define AppURL       "https://github.com/elyJAR/Ppt_remote"
#define AppExeName   "PptRemoteBridge.exe"
#define SetupExeName "PptRemoteBridgeSetup"

[Setup]
AppId={{A1B2C3D4-E5F6-7890-ABCD-EF1234567890}
AppName={#AppName}
AppVersion={#AppVersion}
AppPublisher={#AppPublisher}
AppPublisherURL={#AppURL}
AppSupportURL={#AppURL}
AppUpdatesURL={#AppURL}
DefaultDirName={autopf}\PptRemoteBridge
DefaultGroupName={#AppName}
AllowNoIcons=yes
OutputDir=dist
OutputBaseFilename={#SetupExeName}
Compression=lzma2/ultra64
SolidCompression=yes
WizardStyle=modern
SetupIconFile=app_icon.ico
PrivilegesRequired=lowest
PrivilegesRequiredOverridesAllowed=dialog
UninstallDisplayIcon={app}\{#AppExeName}
UninstallDisplayName={#AppName}
VersionInfoVersion={#AppVersion}
VersionInfoCompany={#AppPublisher}
VersionInfoDescription={#AppName} Installer

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon";    Description: "{cm:CreateDesktopIcon}";    GroupDescription: "{cm:AdditionalIcons}"; Flags: unchecked
Name: "startupentry";   Description: "Start automatically when Windows starts"; GroupDescription: "Startup:"; Flags: unchecked

[Files]
; Main executable and dependencies (built by build_exe.ps1 in onedir mode)
Source: "dist\PptRemoteBridge\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{group}\{#AppName}";          Filename: "{app}\{#AppExeName}"
Name: "{group}\Uninstall {#AppName}"; Filename: "{uninstallexe}"
Name: "{userdesktop}\{#AppName}";     Filename: "{app}\{#AppExeName}"; Tasks: desktopicon

[Registry]
; Auto-start at login (only when user selects the startup task)
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; \
  ValueType: string; ValueName: "PptRemoteBridge"; \
  ValueData: """{app}\{#AppExeName}"""; \
  Flags: uninsdeletevalue; Tasks: startupentry

[Run]
; Offer to launch the bridge after installation
Filename: "{app}\{#AppExeName}"; \
  Description: "{cm:LaunchProgram,{#StringChange(AppName, '&', '&&')}}"; \
  Flags: nowait postinstall skipifsilent

[UninstallRun]
; Stop the bridge before uninstalling
Filename: "{cmd}"; Parameters: "/c taskkill /f /im {#AppExeName}"; \
  Flags: runhidden; RunOnceId: "StopBridge"

[Code]
// Show a warning if the EXE hasn't been built yet
function InitializeSetup(): Boolean;
begin
  Result := True;
end;
