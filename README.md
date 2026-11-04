# Minha TV 📺

Player de IPTV para **Android TV** (funciona também em TV Box e celular), feito em Kotlin + Jetpack Compose + ExoPlayer (Media3).

## O que ele faz

- **Ao vivo, Filmes e Séries** separados automaticamente, com as categorias (`group-title`) da lista.
- **Logos** dos canais e capas dos filmes/séries (`tvg-logo`).
- **Séries agrupadas** por nome, com temporadas e episódios (entende `S01E02`, `T1 E5`, `1x02`, links `/series/`).
- **Guia de programação (EPG)**: lê o XMLTV da lista (`x-tvg-url`) ou um link informado, e mostra "agora / a seguir".
- **Importar de página**: cole o link de uma página (ex.: um README do GitHub) e o app encontra **todas** as playlists `.m3u/.m3u8` dentro dela. A página fica salva e, a cada atualização, listas novas que aparecerem nela entram sozinhas.
- **Configuração pelo celular**: o app mostra um endereço tipo `http://192.168.0.10:8765`. Abra no navegador do celular (mesmo Wi-Fi), cole o link e pronto, sem digitar no controle remoto.
- **Atualização automática** a cada 3, 6, 12 ou 24 horas (mesmo com o app fechado) e ao abrir o app.
- **Favoritos**: segure OK num canal/filme, ou aperte MENU durante o vídeo.
- **Zapping**: durante um canal ao vivo, ▲/▼ (ou CH+/CH−) troca de canal. ◀/▶ mostra o que está passando.
- **Continuar de onde parou** em filmes/episódios e próximo episódio automático.
- Opção para **abrir direto no último canal** (bom para quem só quer ligar e assistir).
- Suporta HLS (`.m3u8`), MPEG-TS, MP4/MKV, DASH, `#EXTVLCOPT` e `url|User-Agent=...&Referer=...`.

O app não vem com nenhuma lista: ele toca as listas que você adicionar.

## Como gerar o APK (sem instalar nada no computador)

1. Crie um repositório no GitHub e envie esta pasta inteira para ele (branch `main`).
2. O GitHub Actions compila sozinho (aba **Actions**, ~5 minutos).
3. O APK sai em **Releases** → `MinhaTV.apk` (e também como artefato do workflow).

Cada envio novo gera uma versão nova, que instala **por cima** da anterior (a chave de assinatura fica em `keystore/`).

## Como instalar na TV

Opção mais fácil, pelo app **Downloader** (da Play Store da TV):

1. Na TV: Configurações → Preferências do dispositivo → Segurança → permitir **fontes desconhecidas** para o Downloader.
2. Abra o Downloader e digite o link direto do APK da release.
   - Se o repositório for **público**, o link fixo é `https://github.com/USUARIO/REPO/releases/latest/download/MinhaTV.apk`.
   - Se for privado, baixe o APK no computador/celular e mande para a TV (pendrive, app "Send Files to TV", ou um link encurtado do Google Drive).
3. Abra **Minha TV**. Na primeira vez ele mostra o endereço para configurar pelo celular.

## Usando

1. Na primeira abertura, abra no celular o endereço mostrado na TV e cole o link (playlist ou página com várias playlists).
2. Espere carregar (aparece "Atualizando…" no topo).
3. Pronto: **Ao vivo / Filmes / Séries**. Depois disso não precisa mexer em nada, ele atualiza sozinho.

Em **Ajustes** dá para adicionar mais links, mudar o intervalo de atualização, remover listas e ligar "abrir no último canal".

## Compilar localmente (opcional)

Precisa de JDK 17+ e Android SDK (ou Android Studio):

```bash
./gradlew assembleRelease
# APK em app/build/outputs/apk/release/app-release.apk
```

## Estrutura

```
app/src/main/java/dev/guisousa/minhatv/
  data/M3uParser.kt        leitura do M3U e classificação ao vivo / filme / série
  data/LinkExtractor.kt    acha playlists dentro de páginas (README, HTML)
  data/EpgParser.kt        guia XMLTV (só as próximas 24h dos canais da lista)
  data/Repository.kt       download, cache em disco, atualização, favoritos
  data/PhoneSetupServer.kt página para configurar pelo celular
  work/RefreshWorker.kt    atualização periódica em segundo plano
  ui/                      telas em Compose para controle remoto
  player/PlayerActivity.kt player (ExoPlayer) com zapping
```

## Regras de limpeza

`regras/limpeza.json` guarda só as regras de limpeza (categorias para esconder ou juntar, países, adulto,
exceções de esporte). Não tem links, listas nem senhas. O app traz uma cópia e confere a do GitHub a cada
6 horas; quando `versao` sobe, a TV aplica as regras no que já baixou, em segundo plano. A limpeza é feita
uma vez, na importação, e guardada no banco: as telas só leem o resultado.
