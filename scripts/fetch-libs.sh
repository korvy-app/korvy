#!/usr/bin/env bash
# Downloads prebuilt native libraries that aren't on Maven Central into app/libs (git-ignored), checking SHA-256.
#   sherpa-onnx: on-device neural text-to-speech (Kokoro, Piper voices). Apache-2.0. https://github.com/k2-fsa/sherpa-onnx
set -euo pipefail
cd "$(dirname "$0")/../app/libs" 2>/dev/null || { mkdir -p "$(dirname "$0")/../app/libs"; cd "$(dirname "$0")/../app/libs"; }
fetch() { # name url sha256
  if [ -f "$1" ] && echo "$3  $1" | shasum -a 256 -c - >/dev/null 2>&1; then echo "ok: $1"; return; fi
  curl -sL -o "$1.tmp" "$2"
  echo "$3  $1.tmp" | shasum -a 256 -c - >/dev/null || { echo "Checksum mismatch for $1"; rm -f "$1.tmp"; exit 1; }
  mv "$1.tmp" "$1"; echo "fetched: $1"
}
# Pinned to the last 1.x release: sherpa-onnx 2.0 removed its built-in espeak-ng phonemizer, so Piper/Kokoro
# voices can't turn text into sounds without a separate phonemizer.
fetch sherpa-onnx-1.13.8.aar https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar \
  633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96
