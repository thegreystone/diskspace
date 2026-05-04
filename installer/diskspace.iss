; diskspace Windows installer (Inno Setup 6).
; Built in CI by .github/workflows/release.yml; the version is injected via /DMyVersion=...
; To run locally: copy the GraalVM-built diskspace.exe into this folder and:
;   "C:\Program Files (x86)\Inno Setup 6\ISCC.exe" /DMyVersion=0.3.0 diskspace.iss

#ifndef MyVersion
  #define MyVersion "0.0.0"
#endif

[Setup]
; Stable AppId — do NOT change for upgrades to be detected as upgrades.
AppId={{B5A4F1F2-9C3E-4D5A-9C0E-1F2D3E4A5B6C}
AppName=diskspace
AppVersion={#MyVersion}
AppVerName=diskspace {#MyVersion}
AppPublisher=Marcus Hirt
AppPublisherURL=https://hirt.se/
AppSupportURL=https://github.com/thegreystone/diskspace/issues
AppUpdatesURL=https://github.com/thegreystone/diskspace/releases
DefaultDirName={autopf}\diskspace
DefaultGroupName=diskspace
DisableProgramGroupPage=yes
LicenseFile=..\LICENSE
OutputDir=Output
OutputBaseFilename=diskspace-{#MyVersion}-windows-x86_64-setup
SetupIconFile=..\src\windows\assets\icon.ico
UninstallDisplayIcon={app}\diskspace.exe
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
PrivilegesRequiredOverridesAllowed=dialog

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "Create a desktop shortcut"; GroupDescription: "Additional icons:"; Flags: unchecked

[Files]
Source: "diskspace.exe"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
Name: "{group}\diskspace"; Filename: "{app}\diskspace.exe"
Name: "{group}\Uninstall diskspace"; Filename: "{uninstallexe}"
Name: "{commondesktop}\diskspace"; Filename: "{app}\diskspace.exe"; Tasks: desktopicon

[Run]
Filename: "{app}\diskspace.exe"; Description: "Launch diskspace"; Flags: nowait postinstall skipifsilent
