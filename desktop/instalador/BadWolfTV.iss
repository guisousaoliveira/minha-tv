; Instalador do Bad Wolf TV para Windows (Inno Setup 6.7+).
; Monta a partir do app gerado por `:desktop:createDistributable`:
;   iscc /DAppVersion=1.0.200 desktop\instalador\BadWolfTV.iss
; Sai em desktop\build\instalador\BadWolfTV-Windows.exe

#ifndef AppVersion
  #define AppVersion "1.0.0"
#endif
#ifndef SourceDir
  #define SourceDir "..\build\compose\binaries\main\app\Bad Wolf TV"
#endif
#define AppName "Bad Wolf TV"
#define AppExe "Bad Wolf TV.exe"

[Setup]
AppId={{B7D3E0A2-5C41-4E8F-9A6B-2F1D7C0E4B93}
AppName={#AppName}
AppVersion={#AppVersion}
AppVerName={#AppName} {#AppVersion}
AppPublisher={#AppName}
VersionInfoVersion={#AppVersion}
; mesma pasta das versões antigas (instalador MSI), assim nada fica duplicado
DefaultDirName={localappdata}\{#AppName}
DisableDirPage=yes
DisableWelcomePage=no
DisableReadyPage=no
DisableProgramGroupPage=yes
UsePreviousAppDir=no
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
OutputDir=..\build\instalador
OutputBaseFilename=BadWolfTV-Windows
SetupIconFile=..\src\main\resources\icone.ico
UninstallDisplayIcon={app}\{#AppExe}
UninstallDisplayName={#AppName}
Compression=lzma2/max
SolidCompression=yes
CloseApplications=yes
RestartApplications=no
WizardStyle=modern dark
WizardBackColor=#0a0a0a
WizardImageFile=lateral-100.png,lateral-150.png,lateral-200.png
WizardSmallImageFile=logo-100.png,logo-150.png,logo-200.png

[Languages]
Name: "ptbr"; MessagesFile: "compiler:Languages\BrazilianPortuguese.isl"

[Messages]
ptbr.WelcomeLabel1=Bem-vindo ao Bad Wolf TV
ptbr.WelcomeLabel2=Filmes, séries e TV ao vivo no seu computador.%n%nVamos instalar o Bad Wolf TV {#AppVersion}. É rapidinho.
ptbr.FinishedHeadingLabel=Pronto!
ptbr.FinishedLabelNoIcons=O Bad Wolf TV está instalado.
ptbr.FinishedLabel=O Bad Wolf TV está instalado. Você pode abrir pelo atalho na área de trabalho ou no menu Iniciar.

[Tasks]
Name: "desktopicon"; Description: "Criar atalho na área de trabalho"; GroupDescription: "Atalhos:"

[Files]
Source: "{#SourceDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\{#AppName}"; Filename: "{app}\{#AppExe}"
Name: "{autodesktop}\{#AppName}"; Filename: "{app}\{#AppExe}"; Tasks: desktopicon

[Run]
Filename: "{app}\{#AppExe}"; Description: "Abrir o Bad Wolf TV agora"; Flags: nowait postinstall skipifsilent

[UninstallDelete]
Type: filesandordirs; Name: "{app}"

[Code]
const
  UninstKey = 'Software\Microsoft\Windows\CurrentVersion\Uninstall\{B7D3E0A2-5C41-4E8F-9A6B-2F1D7C0E4B93}_is1';
  UninstRoot = 'Software\Microsoft\Windows\CurrentVersion\Uninstall';

var
  ModoPage: TInputOptionWizardPage;
  VersaoAtual: String;   // versão já instalada ('' = nenhuma)
  MsiCodigo: String;     // código do instalador antigo (MSI), se ainda estiver lá

// Procura o Bad Wolf TV antigo, instalado pelo instalador MSI (até a versão 1.0.134).
function AcharMsiAntigo(Root: Integer; var Versao: String): String;
var
  Chaves: TArrayOfString;
  I: Integer;
  Nome, Desinst: String;
begin
  Result := '';
  if not RegGetSubkeyNames(Root, UninstRoot, Chaves) then exit;
  for I := 0 to GetArrayLength(Chaves) - 1 do
  begin
    if RegQueryStringValue(Root, UninstRoot + '\' + Chaves[I], 'DisplayName', Nome) and (Nome = '{#AppName}')
       and RegQueryStringValue(Root, UninstRoot + '\' + Chaves[I], 'UninstallString', Desinst)
       and (Pos('msiexec', Lowercase(Desinst)) > 0) then
    begin
      RegQueryStringValue(Root, UninstRoot + '\' + Chaves[I], 'DisplayVersion', Versao);
      Result := Chaves[I];
      exit;
    end;
  end;
end;

procedure DetectarInstalacao();
var
  V: String;
begin
  VersaoAtual := '';
  MsiCodigo := '';
  if RegQueryStringValue(HKCU, UninstKey, 'DisplayVersion', V) then
  begin
    VersaoAtual := V;
  end;
  V := '';
  MsiCodigo := AcharMsiAntigo(HKCU, V);
  if MsiCodigo = '' then MsiCodigo := AcharMsiAntigo(HKLM, V);
  if (MsiCodigo <> '') and (VersaoAtual = '') then VersaoAtual := V;
  if (VersaoAtual = '') and FileExists(ExpandConstant('{localappdata}\{#AppName}\{#AppExe}')) then
    VersaoAtual := '?';
end;

procedure InitializeWizard();
var
  Sub: String;
begin
  DetectarInstalacao();
  if VersaoAtual = '?' then
    Sub := 'Encontramos o Bad Wolf TV neste computador.'
  else if VersaoAtual = '{#AppVersion}' then
    Sub := 'Esta mesma versão (' + VersaoAtual + ') já está instalada neste computador.'
  else
    Sub := 'A versão ' + VersaoAtual + ' já está instalada. Esta é a versão {#AppVersion}.';
  ModoPage := CreateInputOptionPage(wpWelcome,
    'O Bad Wolf TV já está instalado', Sub,
    'Você quer instalar por cima?' + #13#10#13#10 +
    'Atualizar mantém tudo como está. Reinstalar apaga o app antigo e instala de novo ' +
    '(sua lista, favoritos e configurações continuam, a não ser que você escolha apagar).', True, False);
  ModoPage.Add('Sim, instalar por cima (atualizar)');
  ModoPage.Add('Reinstalar do zero');
  ModoPage.Add('Reinstalar do zero e apagar minha lista e configurações');
  ModoPage.SelectedValueIndex := 0;
end;

function ShouldSkipPage(PageID: Integer): Boolean;
begin
  Result := (PageID = ModoPage.ID) and (VersaoAtual = '');
end;

function UpdateReadyMemo(Space, NewLine, MemoUserInfoInfo, MemoDirInfo, MemoTypeInfo,
  MemoComponentsInfo, MemoGroupInfo, MemoTasksInfo: String): String;
begin
  Result := '';
  if VersaoAtual <> '' then
    case ModoPage.SelectedValueIndex of
      0: Result := 'Instalar por cima da versão já instalada.' + NewLine + NewLine;
      1: Result := 'Reinstalar do zero (mantém a lista e as configurações).' + NewLine + NewLine;
      2: Result := 'Reinstalar do zero e apagar a lista, os favoritos e as configurações.' + NewLine + NewLine;
    end;
  Result := Result + MemoTasksInfo;
end;

function PrepareToInstall(var NeedsRestart: Boolean): String;
var
  Codigo: Integer;
begin
  Result := '';
  // o instalador antigo (MSI) sai sempre: os arquivos novos vão para a mesma pasta
  if MsiCodigo <> '' then
  begin
    WizardForm.PreparingLabel.Caption := 'Removendo a versão antiga...';
    if not Exec(ExpandConstant('{sys}\msiexec.exe'), '/x ' + MsiCodigo + ' /qn /norestart', '',
                SW_HIDE, ewWaitUntilTerminated, Codigo) or ((Codigo <> 0) and (Codigo <> 1605) and (Codigo <> 3010)) then
      Log('msiexec /x terminou com ' + IntToStr(Codigo));
  end;
  if (VersaoAtual <> '') and (ModoPage.SelectedValueIndex >= 1) then
  begin
    // não chama o desinstalador antigo: ele continua rodando em segundo plano e apagaria arquivos novos
    DelTree(ExpandConstant('{app}'), True, True, True);
    if ModoPage.SelectedValueIndex = 2 then
    begin
      DelTree(ExpandConstant('{userappdata}\BadWolfTV'), True, True, True);
      DelTree(ExpandConstant('{localappdata}\BadWolfTV'), True, True, True);
    end;
  end;
end;
