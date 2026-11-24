# Bad Wolf TV — Design System & Identidade Visual Unificada ("Obsidian Cinema")

Todas as plataformas (**Android TV**, **Android Mobile**, **Windows Desktop**, **LG webOS TV**, **LG webOS Esportes** e a página web **`/entrar`**) devem seguir rigorosamente esta identidade visual.

## 1. Paleta de Cores (Tokens)
| Token | Hex | Uso |
|---|---|---|
| `bg` / `Background` | `#06070A` | Fundo principal (preto obsidiana profundo, reduz cansaço visual) |
| `surface` / `Surface` | `#111318` | Cartões, barras, containers elevados |
| `surfaceHigh` / `SurfaceHigh` | `#1A1E28` | Estados hover/focus, inputs, chips ativos, superfícies secundárias |
| `surfaceGlass` | `rgba(17, 19, 24, 0.84)` | Barras flutuantes, overlays e modais |
| `brand` / `Brand` | `#00E5FF` | Ação primária, foco ativo, progresso EPG, marca Bad Wolf |
| `brandSoft` | `rgba(0, 229, 255, 0.14)` | Fundo de badges da marca, pill ativa, glow sutil |
| `brandText` | `#041218` | Texto escuro sobre botão primário `#00E5FF` |
| `live` / `Live` | `#FF3B5C` | Indicador "AO VIVO", ponto pulsante |
| `success` | `#22C55E` | Conta conectada, sucesso, atualizado |
| `error` | `#FF6B6B` | Mensagens de erro |
| `text` / `Text` | `#F8FAFC` | Texto principal |
| `textDim` / `TextDim` | `#94A3B8` | Subtítulos, metadados, descrições |
| `textFaint` / `TextFaint` | `#64748B` | Rótulos auxiliares, horários secundários |
| `hairline` / `Hairline` | `rgba(255, 255, 255, 0.08)` | Bordas sutis de cartões e inputs |

## 2. Raios, Espaçamentos e Elevação
- **Cards de mídia / canais:** `14dp` / `14px`
- **Modais e Cards de Autenticação:** `20dp` / `20px` com borda `1px` `hairline`
- **Inputs e Botões:** `12dp` / `12px`
- **Chips / Pills / Badges:** `999dp` (totalmente arredondados)
- **Foco em TV (Android TV / webOS):** borda `2.5dp` `#00E5FF`, escala `1.03–1.04`, sombra/glow ciano sutil.

## 3. Fluxo Unificado de Autenticação (Conta W4LLE / Bad Wolf)
- **Conta é sempre opcional:** o usuário pode entrar, criar conta grátis ou escolher **"Continuar sem conta"**.
- **Supabase compartilhado:** `https://hkiifyxossklpnsavujw.supabase.co`
  - E-mail interno derivado do usuário: `usuario.trim().toLowerCase().replace(/\s+/g, "") + "@w4lle.tv"`
  - Login: `POST /auth/v1/token?grant_type=password`
  - Cadastro: `POST /auth/v1/signup` com `{ email, password, data: { username } }` (se retornar `refresh_token`, já autentica; se não retornar, faz login imediato).
- **TVs (Android TV, LG webOS TV, LG webOS Esportes) — Pareamento por QR Code:**
  - Gera código de 8 caracteres (`XXXX-XXXX`), exibe QR Code apontando para `https://badwolf.pages.dev/entrar#<CODIGO>` + código grande legível.
  - Instruções em 3 passos claros:
    1. Aponte a câmera do celular para o QR Code
    2. Entre ou **crie sua conta grátis** na página
    3. A TV conecta automaticamente na hora
  - Canal Realtime: `pair-<sha256("badwolf-canal:" + codigo).slice(0,24)>`, payload cifrado em AES-256-GCM com chave `sha256("badwolf-chave:" + codigo)`.
- **Celular (Android Mobile) e PC (Windows Desktop):**
  - Tela/modal dedicada de autenticação com abas **"Entrar"** e **"Criar conta"** (usuário + senha, mínimo 6 caracteres) + botão discreto **"Continuar sem conta"**.
  - Acessível na 1ª abertura (pode pular e nunca mais incomodar) e a qualquer momento pelo avatar/botão **Conta** no topo ou em **Ajustes**.
