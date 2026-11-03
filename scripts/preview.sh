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
curl -s -m 20 --data-urlencode "url=https://iptv-org.github.io/iptv/countries/br.m3u" \
  --data-urlencode "name=Teste" http://127.0.0.1:8765/ > /dev/null || echo "falhou ao enviar a lista"
shot importando 15
shot inicio 60

key KEYCODE_DPAD_UP KEYCODE_DPAD_UP KEYCODE_DPAD_RIGHT
shot menu-ao-vivo
key KEYCODE_DPAD_DOWN
shot ao-vivo 3
key KEYCODE_DPAD_RIGHT
shot ao-vivo-canal
key KEYCODE_DPAD_CENTER
shot player-carregando 4
shot player 15
key KEYCODE_DPAD_CENTER
shot player-barra 1
key KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT
shot player-barra-botoes 1
key KEYCODE_BACK
shot player-sem-barra 1
key KEYCODE_BACK
shot volta-ao-vivo
key KEYCODE_DPAD_UP KEYCODE_DPAD_UP KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT
shot ajustes 3
adb logcat -d -t 400 '*:E' > "$OUT/erros.txt" || true
