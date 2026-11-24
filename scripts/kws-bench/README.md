# Wake-word test bench

Runs the app's keyword model (sherpa-onnx, the same 3.3M gigaspeech model) on recordings, to tune wake-word
spellings and sensitivity without a phone. Speech comes from macOS `say` in several voices, so it's repeatable.

```
python3 -m venv /tmp/kws && /tmp/kws/bin/pip install sherpa-onnx==1.13.8 numpy
# model: https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01.tar.bz2
./record.sh out/                       # "Hey Korvy" and near-misses in 6 voices (macOS)
/tmp/kws/bin/python -I bench.py MODEL_DIR out/ "korvi,corvi,corvie,korvie" 0.25
```
The table shows, per phrase and voice, which spelling fired ("·" = nothing). Wanted: every voice on "hey_korvy",
nothing anywhere else. Findings (2026-11-23) are in docs/KORVY_SETUP_PROGRESS.md, Phase 2.
