#!/usr/bin/env bash
# Upload to Google Play with the service-account key in ~/.config/play (see scripts/play_upload.py).
#   ./scripts/play-upload.sh check
#   ./scripts/play-upload.sh upload app/build/outputs/bundle/playRelease/app-play-release.aab --track internal --notes notes.txt --draft
set -euo pipefail
cd "$(dirname "$0")/.."
exec ~/.local/share/play-venv/bin/python scripts/play_upload.py "$@"
