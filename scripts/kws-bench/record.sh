#!/usr/bin/env bash
# Records the wake phrase and near-misses with macOS voices as 16 kHz WAVs: <phrase>__<voice>.wav
set -euo pipefail
out=${1:-out}; mkdir -p "$out"
for v in Samantha Daniel Karen Moira Rishi Fred; do
  for p in "Hey Korvy" "hey curvy" "hey Corey" "hey Korea" "hey groovy" "hey Kirby" "hey carve it" "hey coffee"; do
    say -v "$v" -o "$out/$(echo "$p" | tr ' A-Z' '_a-z')__$v.wav" --data-format=LEI16@16000 "$p"
  done
done
