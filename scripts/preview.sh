#!/usr/bin/env bash
# Abre o app numa TV Android virtual e tira prints das telas principais.
# Usa só canais abertos e gratuitos (iptv-org) para ter conteúdo na tela.
set -u
OUT=previews
mkdir -p "$OUT"
n=0
shot() { n=$((n+1)); sleep "${2:-2}"; adb exec-out screencap -p > "$OUT/$(printf %02d $n)-$1.png"; echo "print $n: $1"; }
key() { for k in "$@"; do adb shell input keyevent "$k"; sleep 0.6; done; }

adb install -r MinhaTV.apk
adb shell am start -n dev.guisousa.minhatv/.ui.MainActivity
shot boas-vindas 10

# coloca uma lista de teste pelo mesmo formulário que o celular usa
adb forward tcp:8765 tcp:8765
for u in https://iptv-org.github.io/iptv/countries/br.m3u https://iptv-org.github.io/iptv/languages/por.m3u https://iptv-org.github.io/iptv/countries/pt.m3u; do
  curl -s -m 20 --data-urlencode "url=$u" http://127.0.0.1:8765/ > /dev/null || echo "falhou ao enviar $u"
  sleep 3
done
shot importando 15
shot inicio 60

# Início: desce para o primeiro cartão e testa o fim da fileira
key KEYCODE_DPAD_DOWN
shot inicio-cartao
key KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT
shot inicio-fim-da-fileira

# Player: abre o canal, mostra a barra, anda nos botões
key KEYCODE_DPAD_CENTER
shot player-carregando 3
shot player 15
key KEYCODE_DPAD_CENTER
shot player-barra 1
adb shell input keyevent KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT
shot player-barra-botoes 1
adb shell input keyevent KEYCODE_DPAD_CENTER
shot player-painel-canais 2
key KEYCODE_BACK
key KEYCODE_BACK
shot player-sem-barra 1
key KEYCODE_DPAD_RIGHT
shot player-info 1
key KEYCODE_BACK KEYCODE_BACK
shot volta-inicio 3

# Ao vivo
for i in 1 2 3 4 5 6; do key KEYCODE_DPAD_UP; done
key KEYCODE_DPAD_LEFT KEYCODE_DPAD_LEFT KEYCODE_DPAD_LEFT KEYCODE_DPAD_RIGHT
shot ao-vivo 4
key KEYCODE_DPAD_DOWN
shot ao-vivo-categoria
key KEYCODE_DPAD_RIGHT
shot ao-vivo-canal

# Ajustes
for i in 1 2 3 4 5 6; do key KEYCODE_DPAD_UP; done
key KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT
shot ajustes 3
adb logcat -d -t 400 '*:E' > "$OUT/erros.txt" || true
adb logcat -d -s MinhaTV:I > "$OUT/minhatv-log.txt" || true
