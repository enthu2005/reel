; Inno Setup script for Reel for Windows. Built by make-installer.ps1 (pass the version in with /DAppVersion=1.0.0).
#ifndef AppVersion
  #define AppVersion "1.0.0"
#endif
#define AppName "Reel"
#define BuildDir "..\build\windows\x64\runner\Release"

[Setup]
AppId={{6F1B0C52-4B7D-4E0B-9A55-5EE110000001}
AppName={#AppName}
AppVersion={#AppVersion}
AppPublisher=Enthujan
DefaultDirName={autopf}\{#AppName}
DefaultGroupName={#AppName}
DisableProgramGroupPage=yes
OutputDir=output
OutputBaseFilename=ReelSetup-{#AppVersion}
Compression=lzma2
SolidCompression=yes
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
WizardStyle=modern
SetupIconFile=reel.ico
WizardImageFile=wizard-side.bmp
WizardSmallImageFile=wizard-small.bmp
UninstallDisplayIcon={app}\reel.exe
PrivilegesRequired=lowest
PrivilegesRequiredOverridesAllowed=dialog

[Tasks]
Name: "desktopicon"; Description: "Create a &desktop shortcut"; GroupDescription: "Shortcuts:"

[Files]
Source: "{#BuildDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs
; Optional: drop Microsoft's vc_redist.x64.exe next to this file and it is installed too when missing.
#ifexist "vc_redist.x64.exe"
Source: "vc_redist.x64.exe"; DestDir: "{tmp}"; Flags: deleteafterinstall
#endif

[Icons]
Name: "{autoprograms}\{#AppName}"; Filename: "{app}\reel.exe"
Name: "{autodesktop}\{#AppName}"; Filename: "{app}\reel.exe"; Tasks: desktopicon

[Run]
#ifexist "vc_redist.x64.exe"
Filename: "{tmp}\vc_redist.x64.exe"; Parameters: "/install /quiet /norestart"; StatusMsg: "Installing Microsoft Visual C++ runtime..."; Check: not VCRedistPresent
#endif
Filename: "{app}\reel.exe"; Description: "Start {#AppName}"; Flags: nowait postinstall skipifsilent

[Code]
function VCRedistPresent: Boolean;
var v: Cardinal;
begin
  Result := RegQueryDWordValue(HKLM, 'SOFTWARE\Microsoft\VisualStudio\14.0\VC\Runtimes\x64', 'Installed', v) and (v = 1);
end;
