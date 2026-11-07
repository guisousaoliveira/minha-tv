# Plano: contas + Bad Wolf TV no celular e no Windows

## O que existe hoje

| | W4LLE TV (site) | Bad Wolf TV (TV) |
|---|---|---|
| Feito em | React + Supabase | Kotlin / Compose (Android TV) |
| Conta | usuário + senha (Supabase Auth, e-mail falso por baixo) | não tem |
| "Já assisti" / progresso | tabela `watch_progress` (por **código TMDB**) | guardado só na TV (por link) |
| Minha lista | tabela `user_lists` (por código TMDB) | guardado só na TV (por link) |
| Acesso ao catálogo | – | uma chave única igual para todo mundo |

O catálogo do Bad Wolf já tem o código TMDB de cada filme/série (`fichas/*.json` → `tmdb`,
e `tmdb.json` faz o caminho inverso). Então **dá para usar o mesmo histórico do W4LLE**.

## 1. Contas (base de tudo)

Usar o **mesmo Supabase do W4LLE** (projeto `hkiifyxossklpnsavujw`). Quem já tem conta no W4LLE
entra no Bad Wolf com o mesmo usuário e senha, e o histórico aparece.

**Conta é opcional** (igual no W4LLE): o app abre e funciona sem login, guardando tudo no
aparelho. Entrar na conta serve só para **sincronizar** entre TV, celular, Windows e site.
Ao entrar pela primeira vez, o que já estava guardado no aparelho sobe para a conta (junta, não apaga).
Sair da conta: o app continua funcionando, só para de sincronizar.

**Sem bloqueio nenhum**: qualquer conta do W4LLE entra no Bad Wolf, e conta nova funciona na hora.
O banco não muda: usa as tabelas que já existem (`profiles`, `watch_progress`, `user_lists`).
Nada de página /admin nem de ativar/desativar. (Se um dia quiser bloquear, dá para pôr depois.)

Login na TV sem digitar senha no controle: a TV mostra um **código / QR**, a pessoa abre no
celular, entra na conta e pronto (igual Netflix/YouTube). No celular e no Windows, login normal.

Sincronizar (TV, celular, Windows e site):
- Continuar assistindo (posição + duração) → `watch_progress`
- Já assisti → `user_lists` (`watched`)
- Minha lista → `user_lists` (`watchlist`)
- Canais: favoritos ficam no aparelho por enquanto (o W4LLE não tem canal igual).

## 2. Organização do código

Um projeto só (minha-tv-v2), dividido em partes:

- `core` – catálogo, busca, contas, sincronização, saúde dos links (sem tela). Usado por todos.
- `tv` – o app atual da TV (telas de controle remoto).
- `mobile` – app de celular: **telas novas** pensadas para toque (a da TV não serve direto no
  celular: é feita para setinha do controle e tela deitada). Usa o mesmo player (ExoPlayer).
- `desktop` – app de Windows (Compose para desktop). Telas parecidas com as da TV, com mouse e
  teclado; player com **VLC/mpv** por baixo (o ExoPlayer só existe no Android). Instalador `.exe`.

## 3. Ordem

1. **Contas**: login opcional com a conta W4LLE no app da TV (por código / QR), em Configurações.
2. **Sincronização** de progresso / já assisti / minha lista na TV.
3. **Celular** (começa por ele: reaproveita mais coisa, mesmo player).
4. **Windows**.
5. **Site W4LLE**: continua igual no navegador (com anúncio, sem bloqueio nenhum).
   Só sai o app Android antigo do W4LLE (DownloadView, aviso de atualização) e entra uma chamada
   para o Bad Wolf TV (TV, celular, Windows, sem anúncio).

Cada etapa sai com versão própria e pode ir para produção sozinha.
