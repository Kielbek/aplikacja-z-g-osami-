#!/usr/bin/env bash
# Uruchomienie silnika VoxCPM2 na maszynie z GPU (np. wynajęty pod RunPod / Vast.ai z obrazem PyTorch + CUDA 12).
#
#   ENGINE_API_KEY=<klucz> bash tts-engine/scripts/run-gpu.sh
#
# Pierwsze uruchomienie instaluje zależności i pobiera model (kilka GB) — to potrwa kilka minut.
set -euo pipefail
cd "$(dirname "$0")/.."
: "${ENGINE_API_KEY:?Ustaw ENGINE_API_KEY (ten sam klucz wpiszesz w .env aplikacji)}"

if [ ! -d .venv ]; then
  # --system-site-packages: używa PyTorcha z obrazu GPU zamiast pobierać go ponownie
  python3 -m venv --system-site-packages .venv
fi
. .venv/bin/activate
pip install -q -U pip
pip install -q -r requirements-voxcpm.txt

python3 -c "import torch; print('CUDA dostępne:', torch.cuda.is_available(), '|', torch.cuda.get_device_name(0) if torch.cuda.is_available() else 'brak GPU')"

export ENGINE_MODE=voxcpm2
# Bez silnego klucza silnik nie wystartuje (jest dostępny z internetu)
export ENGINE_REQUIRE_KEY=true
export ENGINE_DATA_DIR="${ENGINE_DATA_DIR:-$PWD/engine-data}"
exec uvicorn app.main:app --host 0.0.0.0 --port "${PORT:-8808}"
