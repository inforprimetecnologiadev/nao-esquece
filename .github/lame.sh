#!/usr/bin/env bash
# Baixa o LAME 3.100 (codificador de MP3, código aberto, LGPL) e confere o arquivo antes de compilar no APK.
set -euo pipefail
DEST=android/app/src/main/cpp/lame
[ -f "$DEST/include/lame.h" ] && { echo "LAME já está em $DEST"; exit 0; }
SHA=ddfe36cab873794038ae2c1210557ad34857a4b6bdc515785d1da9e175b1da1e
T=$(mktemp -d)
for U in https://downloads.sourceforge.net/project/lame/lame/3.100/lame-3.100.tar.gz \
         https://deb.debian.org/debian/pool/main/l/lame/lame_3.100.orig.tar.gz; do
  echo "baixando $U"
  if curl -fsSL --retry 3 --max-time 120 -o "$T/lame.tgz" "$U"; then
    GOT=$(sha256sum "$T/lame.tgz" | cut -d' ' -f1); echo "sha256 $GOT"
    [ "$GOT" = "$SHA" ] && break
    echo "sha256 diferente do esperado"; rm -f "$T/lame.tgz"
  fi
done
[ -f "$T/lame.tgz" ] || { echo "não consegui baixar o LAME conferido"; exit 1; }
tar -xzf "$T/lame.tgz" -C "$T"
mkdir -p "$DEST/include" "$DEST/libmp3lame"
cp "$T"/lame-3.100/include/lame.h "$DEST/include/"
cp "$T"/lame-3.100/libmp3lame/*.c "$T"/lame-3.100/libmp3lame/*.h "$DEST/libmp3lame/"
mkdir -p "$DEST/libmp3lame/vector"
cp "$T"/lame-3.100/libmp3lame/vector/*.h "$DEST/libmp3lame/vector/"   # só os cabeçalhos (sem o código SSE do PC)
cp "$T"/lame-3.100/COPYING "$DEST/COPYING"
ls "$DEST/libmp3lame" | wc -l
