; Instalador do Bad Wolf TV para Windows (Inno Setup 6.7+).
; Monta a partir do app gerado por `:desktop:createDistributable`:
;   iscc /DAppVersion=1.0.200 desktop\instalador\BadWolfTV.iss
; Sai em desktop\build\instalador\BadWolfTV-Windows.exe
;
; Visual minimalista: janela escura sem borda, logo redondo, botão branco "Instale agora"
; e barra de progresso fina. Tudo é desenhado no [Code]; as telas padrão ficam escondidas.
; As imagens (circulo-*.png e botao-*.png) saem do script imgs do instalador, em 100/150/200%.

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
UsePreviousAppDir=yes
DisableDirPage=yes
DisableWelcomePage=yes
DisableReadyPage=yes
DisableProgramGroupPage=yes
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
CloseApplications=no
RestartApplications=no
WizardStyle=modern dark hidebevels
WizardBackColor=#000000
WizardResizable=no

[Languages]
Name: "ptbr"; MessagesFile: "compiler:Languages\BrazilianPortuguese.isl"

[Files]
Source: "heroi-*.png"; Flags: dontcopy
Source: "botao-*.png"; Flags: dontcopy
Source: "{#SourceDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\{#AppName}"; Filename: "{app}\{#AppExe}"
Name: "{autodesktop}\{#AppName}"; Filename: "{app}\{#AppExe}"

[UninstallDelete]
Type: filesandordirs; Name: "{app}"

[Code]
const
  UninstKey = 'Software\Microsoft\Windows\CurrentVersion\Uninstall\{B7D3E0A2-5C41-4E8F-9A6B-2F1D7C0E4B93}_is1';
  UninstRoot = 'Software\Microsoft\Windows\CurrentVersion\Uninstall';
  WM_SYSCOMMAND = $0112;
  SC_MINIMIZE = $F020;
  SWP_NOSIZE = 1;
  SWP_NOZORDER = 4;
  SWP_NOACTIVATE = $10;
  VK_LBUTTON = 1;
  // cores no formato do Delphi ($BBGGRR)
  COR_FUNDO = $000000;
  COR_CINZA = $8F8A8A;   // #8A8A8F
  COR_TRILHA = $333030;  // #303033
  LARGURA = 520;
  ALTURA = 440;
  LARGURA_BARRA = 280;

type
  TPonto = record X, Y: Longint; end;
  TRetangulo = record L, T, R, B: Longint; end;

var
  VersaoAtual: String;   // versão já instalada ('' = nenhuma)
  MsiCodigo: String;     // código do instalador antigo (MSI), se ainda estiver lá
  Modo: Integer;         // 0 = por cima, 1 = do zero, 2 = do zero e apagar lista e configurações
  Escala: String;        // '100', '150' ou '200' (qual imagem usar)
  Concluido: Boolean;
  Fundo: TPanel;
  Heroi, Barra: TBitmapImage;
  UltimoP, PaginaInicio: Integer;
  Botao: TBitmapButton;
  BotaoAntes: Boolean;   // o botão do mouse estava apertado na última olhada
  Arrastando: Boolean;
  InicioMouse, InicioJanela: TPonto;
  Info, Local, Reinstalar, Status, BtnMin, BtnFechar: TNewStaticText;

function SetWindowPos(Wnd, After: HWND; X, Y, CX, CY: Integer; Flags: UINT): BOOL; external 'SetWindowPos@user32.dll stdcall';
function GetAsyncKeyState(Key: Integer): Integer; external 'GetAsyncKeyState@user32.dll stdcall';
function GetCursorPos(var P: TPonto): BOOL; external 'GetCursorPos@user32.dll stdcall';
function WindowFromPoint(X, Y: Longint): HWND; external 'WindowFromPoint@user32.dll stdcall';
function GetWindowRect(Wnd: HWND; var R: TRetangulo): BOOL; external 'GetWindowRect@user32.dll stdcall';
function GetAncestor(Wnd: HWND; Flags: UINT): HWND; external 'GetAncestor@user32.dll stdcall';
function SetTimer(Wnd: HWND; Id, Ms: LongWord; Func: LongWord): LongWord; external 'SetTimer@user32.dll stdcall';

function DwmSetWindowAttribute(Wnd: HWND; Attr: Integer; var Value: Integer; Size: Integer): Integer;
  external 'DwmSetWindowAttribute@dwmapi.dll stdcall delayload';

// Procura o Bad Wolf TV antigo, instalado pelo instalador MSI (até a versão 1.0.134).
function AcharMsiAntigo(Root: Integer; var Versao: String): String;
var
  Chaves: TArrayOfString;
  I: Integer;
  NomeApp, Desinst: String;
begin
  Result := '';
  if not RegGetSubkeyNames(Root, UninstRoot, Chaves) then exit;
  for I := 0 to GetArrayLength(Chaves) - 1 do
  begin
    if RegQueryStringValue(Root, UninstRoot + '\' + Chaves[I], 'DisplayName', NomeApp) and (NomeApp = '{#AppName}')
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
    VersaoAtual := V;
  V := '';
  MsiCodigo := AcharMsiAntigo(HKCU, V);
  if MsiCodigo = '' then MsiCodigo := AcharMsiAntigo(HKLM, V);
  if (MsiCodigo <> '') and (VersaoAtual = '') then VersaoAtual := V;
  if (VersaoAtual = '') and FileExists(ExpandConstant('{localappdata}\{#AppName}\{#AppExe}')) then
    VersaoAtual := '?';
end;

// ---------- peças da tela ----------

function Png(Base: String): String;
begin
  Result := Base + '-' + Escala + '.png';
  ExtractTemporaryFile(Result);
  Result := ExpandConstant('{tmp}\') + Result;
end;

procedure Centralizar(T: TNewStaticText);
begin
  T.Left := (Fundo.ClientWidth - T.Width) div 2;
end;

procedure Escrever(T: TNewStaticText; Texto: String);
begin
  T.Caption := Texto;
  Centralizar(T);
end;

function NovoTexto(Texto: String; Tamanho: Integer; Cor: TColor; Y: Integer): TNewStaticText;
begin
  Result := TNewStaticText.Create(WizardForm);
  Result.Parent := Fundo;
  Result.AutoSize := True;
  Result.Font.Name := 'Segoe UI';
  Result.Font.Size := Tamanho;
  Result.Font.Color := Cor;
  Result.Top := ScaleY(Y);
  Escrever(Result, Texto);
end;

procedure TrocarBotao(Base, Legenda: String);
begin
  Botao.PngImage.LoadFromFile(Png(Base));
  Botao.Caption := Legenda;
end;

procedure DesenharBarra(P: Integer);
begin
  // só redesenha quando a porcentagem muda (senão a barra pisca)
  if P = UltimoP then exit;
  UltimoP := P;
  with Barra.Bitmap.Canvas do
  begin
    Pen.Color := COR_TRILHA;
    Brush.Color := COR_TRILHA;
    Rectangle(0, 0, Barra.Width, Barra.Height);
    Pen.Color := clWhite;
    Brush.Color := clWhite;
    if P > 0 then Rectangle(0, 0, Barra.Width * P div 100, Barra.Height);
  end;
  Barra.Repaint;
end;

function Dentro(C: TControl; X, Y: Integer): Boolean;
begin
  Result := C.Visible and (X >= C.Left - ScaleX(6)) and (X < C.Left + C.Width + ScaleX(6))
            and (Y >= C.Top - ScaleY(6)) and (Y < C.Top + C.Height + ScaleY(6));
end;

// A janela não tem barra de título: segurar o mouse no fundo (fora dos botões) arrasta a janela.
// O Inno não avisa quando o mouse é apertado, então olha o mouse a cada 10 ms e move a janela junto.
procedure OlharMouse(Wnd: HWND; Msg: UINT; Id: UINT_PTR; Tempo: DWORD);
var
  Pt: TPonto;
  R: TRetangulo;
  Apertado: Boolean;
  Alvo: HWND;
  X, Y: Integer;
begin
  Apertado := (GetAsyncKeyState(VK_LBUTTON) and $8000) <> 0;
  GetCursorPos(Pt);
  if not Apertado then
    Arrastando := False
  else if Arrastando then
  begin
    if (Pt.X <> InicioMouse.X) or (Pt.Y <> InicioMouse.Y) then
      SetWindowPos(WizardForm.Handle, 0, InicioJanela.X + Pt.X - InicioMouse.X, InicioJanela.Y + Pt.Y - InicioMouse.Y,
                   0, 0, SWP_NOSIZE or SWP_NOZORDER or SWP_NOACTIVATE);
  end
  else if not BotaoAntes then
  begin
    Alvo := WindowFromPoint(Pt.X, Pt.Y);
    if GetAncestor(Alvo, 2) = WizardForm.Handle then
    begin
      GetWindowRect(WizardForm.Handle, R);
      X := Pt.X - R.L;
      Y := Pt.Y - R.T;
      // não pega o botão branco, os links e o minimizar/fechar
      if not (Dentro(Botao, X, Y) or Dentro(Local, X, Y) or Dentro(Reinstalar, X, Y)
              or Dentro(BtnMin, X, Y) or Dentro(BtnFechar, X, Y)) then
      begin
        Arrastando := True;
        InicioMouse := Pt;
        InicioJanela.X := R.L;
        InicioJanela.Y := R.T;
      end;
    end;
  end;
  BotaoAntes := Apertado;
end;

procedure MinimizarClick(Sender: TObject);
begin
  SendMessage(WizardForm.Handle, WM_SYSCOMMAND, SC_MINIMIZE, 0);
end;

procedure FecharClick(Sender: TObject);
begin
  if Concluido then
    WizardForm.NextButton.OnClick(WizardForm.NextButton)
  else
    WizardForm.Close;
end;

// Se o app está aberto, o Windows não deixa abrir o .exe dele para escrita
function AppAberto(): Boolean;
var
  Exe: String;
  F: TFileStream;
begin
  Result := False;
  Exe := AddBackslash(WizardForm.DirEdit.Text) + '{#AppExe}';
  if not FileExists(Exe) then exit;
  try
    F := TFileStream.Create(Exe, fmOpenReadWrite or fmShareExclusive);
    F.Free;
  except
    Result := True;
  end;
end;

// O app aberto prende os arquivos e a instalação travaria. Pede para fechar antes.
function FecharApp(): Boolean;
var
  Codigo, I: Integer;
begin
  Result := not AppAberto();
  if Result then exit;
  if TaskDialogMsgBox('O {#AppName} está aberto',
       'Para instalar, o app precisa ser fechado. Se estiver assistindo algo, o vídeo vai parar.',
       mbConfirmation, MB_OKCANCEL, ['Fechar e instalar'], 0) <> IDOK then exit;
  Exec(ExpandConstant('{sys}	askkill.exe'), '/F /T /IM "{#AppExe}"', '', SW_HIDE, ewWaitUntilTerminated, Codigo);
  // espera o Windows soltar os arquivos (até uns 5 segundos)
  for I := 1 to 25 do
  begin
    if not AppAberto() then
    begin
      Sleep(500);
      Result := True;
      exit;
    end;
    Sleep(200);
  end;
  MsgBox('Não deu para fechar o {#AppName}. Feche o app e tente de novo.', mbError, MB_OK);
end;

procedure Instalar();
begin
  WizardForm.NextButton.OnClick(WizardForm.NextButton);
end;

procedure BotaoClick(Sender: TObject);
var
  Codigo: Integer;
begin
  if Concluido then
  begin
    ExecAsOriginalUser(ExpandConstant('{app}\{#AppExe}'), '', '', SW_SHOW, ewNoWait, Codigo);
    WizardForm.NextButton.OnClick(WizardForm.NextButton);
  end
  else
  begin
    Modo := 0;
    Instalar();
  end;
end;

procedure LocalClick(Sender: TObject);
var
  Dir: String;
begin
  Dir := WizardForm.DirEdit.Text;
  if not BrowseForFolder('Escolha onde instalar o {#AppName}:', Dir, True) then exit;
  if CompareText(ExtractFileName(RemoveBackslash(Dir)), '{#AppName}') <> 0 then
    Dir := AddBackslash(Dir) + '{#AppName}';
  WizardForm.DirEdit.Text := Dir;
  Escrever(Local, MinimizePathName(Dir, Local.Font, ScaleX(LARGURA - 80)) + '  ·  Escolha o local');
end;

procedure ReinstalarClick(Sender: TObject);
begin
  case TaskDialogMsgBox('Reinstalar do zero',
         'O app antigo é apagado e instalado de novo. Quer manter sua lista, favoritos e configurações?',
         mbConfirmation, MB_YESNOCANCEL, ['Manter minha lista', 'Apagar tudo'], 0) of
    IDYES: Modo := 1;
    IDNO: Modo := 2;
  else
    exit;
  end;
  Instalar();
end;

procedure MostrarProgresso(Texto: String);
begin
  Botao.Visible := False;
  Local.Visible := False;
  Reinstalar.Visible := False;
  UltimoP := -1;
  DesenharBarra(0);
  Barra.Visible := True;
  Escrever(Status, Texto);
  Status.Visible := True;
  Fundo.Repaint;
end;

procedure InitializeWizard();
var
  Valor, Y: Integer;
begin
  DetectarInstalacao();
  Modo := 0;
  Concluido := False;
  if ScaleX(100) >= 175 then Escala := '200'
  else if ScaleX(100) >= 125 then Escala := '150'
  else Escala := '100';

  // janela sem borda, do tamanho do conteúdo
  WizardForm.BorderStyle := bsNone;
  WizardForm.ClientWidth := ScaleX(LARGURA);
  WizardForm.ClientHeight := ScaleY(ALTURA);
  WizardForm.Color := COR_FUNDO;
  WizardForm.Caption := 'Instalar o {#AppName}';
  // cantos arredondados do Windows 11 (no Windows 10 não faz nada)
  try
    Valor := 2;
    DwmSetWindowAttribute(WizardForm.Handle, 33, Valor, 4);
  except
  end;

  // fundo que cobre as telas padrão do instalador
  Fundo := TPanel.Create(WizardForm);
  Fundo.Parent := WizardForm;
  Fundo.SetBounds(0, 0, WizardForm.ClientWidth, WizardForm.ClientHeight);
  Fundo.BevelOuter := bvNone;
  Fundo.Color := COR_FUNDO;
  Fundo.ParentBackground := False;
  Fundo.BringToFront;

  // minimizar e fechar no canto
  BtnFechar := NovoTexto(#$E8BB, 9, COR_CINZA, 14);
  BtnFechar.Font.Name := 'Segoe MDL2 Assets';
  BtnFechar.Left := Fundo.ClientWidth - ScaleX(30);
  BtnFechar.Cursor := crHand;
  BtnFechar.OnClick := @FecharClick;
  BtnMin := NovoTexto(#$E921, 9, COR_CINZA, 14);
  BtnMin.Font.Name := 'Segoe MDL2 Assets';
  BtnMin.Left := Fundo.ClientWidth - ScaleX(62);
  BtnMin.Cursor := crHand;
  BtnMin.OnClick := @MinimizarClick;

  // brilho ciano, lobo e nome (uma imagem só, que pinta a janela inteira de preto)
  Heroi := TBitmapImage.Create(WizardForm);
  Heroi.Parent := Fundo;
  Heroi.SetBounds(0, 0, Fundo.ClientWidth, Fundo.ClientHeight);
  Heroi.Stretch := True;
  Heroi.BackColor := COR_FUNDO;
  Heroi.PngImage.LoadFromFile(Png('heroi'));
  Heroi.SendToBack;

  if VersaoAtual = '' then
    Info := NovoTexto('Versão {#AppVersion}', 9, COR_CINZA, 254)
  else if (VersaoAtual = '?') or (VersaoAtual = '{#AppVersion}') then
    Info := NovoTexto('O {#AppName} já está instalado. Você quer instalar por cima?', 9, COR_CINZA, 254)
  else
    Info := NovoTexto('A versão ' + VersaoAtual + ' já está instalada. Instalar a {#AppVersion} por cima?', 9, COR_CINZA, 254);

  // botão branco
  Botao := TBitmapButton.Create(WizardForm);
  Botao.Parent := Fundo;
  Botao.SetBounds((Fundo.ClientWidth - ScaleX(200) - 4) div 2, ScaleY(300), ScaleX(200) + 4, ScaleY(40) + 4);
  Botao.Stretch := True;
  Botao.Cursor := crHand;
  Botao.OnClick := @BotaoClick;
  if VersaoAtual = '' then
    TrocarBotao('botao-instalar', 'Instale agora')
  else
    TrocarBotao('botao-porcima', 'Instalar por cima');

  Y := 360;
  Local := NovoTexto('Escolha o local', 9, COR_CINZA, Y);
  Local.Cursor := crHand;
  Local.OnClick := @LocalClick;
  Reinstalar := NovoTexto('Reinstalar do zero', 9, COR_CINZA, Y);
  Reinstalar.Cursor := crHand;
  Reinstalar.OnClick := @ReinstalarClick;
  // já instalado: instala na mesma pasta; a opção é reinstalar do zero
  Local.Visible := VersaoAtual = '';
  Reinstalar.Visible := VersaoAtual <> '';

  // barra de progresso fina
  Barra := TBitmapImage.Create(WizardForm);
  Barra.Parent := Fundo;
  Barra.SetBounds((Fundo.ClientWidth - ScaleX(LARGURA_BARRA)) div 2, ScaleY(318), ScaleX(LARGURA_BARRA), ScaleY(3));
  Barra.Bitmap.Width := Barra.Width;
  Barra.Bitmap.Height := Barra.Height;
  Barra.Visible := False;

  Status := NovoTexto('', 9, COR_CINZA, 334);
  Status.Visible := False;

  BotaoAntes := False;
  Arrastando := False;
  SetTimer(WizardForm.Handle, 4711, 10, CreateCallback(@OlharMouse));

  // a tela que fica à mostra (as outras são puladas)
  PaginaInicio := CreateCustomPage(wpWelcome, '', '').ID;
end;

procedure CurPageChanged(CurPageID: Integer);
begin
  Fundo.BringToFront;
  if CurPageID = wpInstalling then
    MostrarProgresso('Extraindo arquivos... 0%')
  else if CurPageID = wpFinished then
  begin
    Concluido := True;
    Barra.Visible := False;
    Escrever(Info, 'Pronto! O {#AppName} está instalado.');
    Status.Visible := False;
    TrocarBotao('botao-abrir', 'Abrir o Bad Wolf TV');
    Botao.Visible := True;
  end;
  Fundo.Repaint;
end;

// passa por aqui tanto no clique quanto no Enter
function NextButtonClick(CurPageID: Integer): Boolean;
begin
  Result := True;
  if CurPageID = PaginaInicio then Result := FecharApp();
end;

procedure CurInstallProgressChanged(CurProgress, MaxProgress: Integer);
var
  P: Integer;
begin
  if MaxProgress <= 0 then exit;
  P := Round(CurProgress * 100.0 / MaxProgress);
  if P > 100 then P := 100;
  if P = UltimoP then exit;
  DesenharBarra(P);
  Escrever(Status, 'Extraindo arquivos... ' + IntToStr(P) + '%');
  Status.Repaint;
end;

function PrepareToInstall(var NeedsRestart: Boolean): String;
var
  Codigo: Integer;
begin
  Result := '';
  MostrarProgresso('Preparando...');
  // o instalador antigo (MSI) sai sempre: os arquivos novos vão para a mesma pasta
  if MsiCodigo <> '' then
  begin
    Escrever(Status, 'Removendo a versão antiga...');
    Fundo.Repaint;
    if not Exec(ExpandConstant('{sys}\msiexec.exe'), '/x ' + MsiCodigo + ' /qn /norestart', '',
                SW_HIDE, ewWaitUntilTerminated, Codigo) or ((Codigo <> 0) and (Codigo <> 1605) and (Codigo <> 3010)) then
      Log('msiexec /x terminou com ' + IntToStr(Codigo));
  end;
  if (VersaoAtual <> '') and (Modo >= 1) then
  begin
    Escrever(Status, 'Apagando a versão antiga...');
    Fundo.Repaint;
    // não chama o desinstalador antigo: ele continua rodando em segundo plano e apagaria arquivos novos
    DelTree(ExpandConstant('{app}'), True, True, True);
    if Modo = 2 then
    begin
      DelTree(ExpandConstant('{userappdata}\BadWolfTV'), True, True, True);
      DelTree(ExpandConstant('{localappdata}\BadWolfTV'), True, True, True);
    end;
  end;
end;
