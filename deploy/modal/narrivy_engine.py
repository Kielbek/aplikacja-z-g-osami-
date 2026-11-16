"""Silnik mowy Narrivy (VoxCPM2) na Modal — karta graficzna uruchamiana tylko na czas generowania.

Wdrożenie (z katalogu głównego repozytorium):

    pip install modal && modal setup
    modal secret create narrivy-engine ENGINE_API_KEY=$(openssl rand -hex 32)
    modal deploy deploy/modal/narrivy_engine.py

Po wdrożeniu Modal wypisze adres https://…modal.run — wpisz go w .env aplikacji jako ENGINE_URL,
a klucz z sekretu jako ENGINE_API_KEY (podejrzysz go w panelu Modal → Secrets). Zalecane: ENGINE_HEALTH_TTL=30m.

Koszty: Modal nalicza czas pracy karty (z miesięcznej puli planu Starter). Po SCALEDOWN_S sekundach bez żądań
kontener jest wyłączany, a pierwsze żądanie po przerwie czeka na start i wczytanie modelu (zimny start).
"""
from __future__ import annotations

from pathlib import Path

import modal

# Karty w kolejności preferencji: gdy w danej chwili brak wolnych L4, Modal bierze następną z listy
# (bez tego żądania potrafią czekać w kolejce na kartę wiele minut). Wszystkie mają 24 GB+ i bfloat16.
GPU = ["L4", "A10G", "L40S"]
SCALEDOWN_S = 120     # ile sekund bez żądań, zanim karta zostanie wyłączona (krócej = taniej, częstsze zimne starty)


def _engine_dir() -> Path:
    """Katalog tts-engine w repozytorium. Plik jest wczytywany dwa razy: lokalnie przy `modal deploy`
    (deploy/modal/narrivy_engine.py — kod silnika jest wtedy dołączany do obrazu) i w kontenerze Modal
    (/root/narrivy_engine.py — bez repozytorium; ścieżka nie jest tam już potrzebna)."""
    here = Path(__file__).resolve()
    return here.parents[2] / "tts-engine" if len(here.parents) > 2 else Path("/srv/tts-engine")


ENGINE_DIR = _engine_dir()

image = (
    modal.Image.debian_slim(python_version="3.12")
    .apt_install("libsndfile1", "git")
    .pip_install(
        "fastapi>=0.115",
        "uvicorn[standard]>=0.30",
        "numpy>=1.26",
        "soundfile>=0.12",
        "lameenc>=1.7",
        "python-multipart>=0.0.9",
        "voxcpm>=2.0.3",
        "torch",
    )
    .env({
        "ENGINE_MODE": "voxcpm2",
        "ENGINE_REQUIRE_KEY": "true",      # bez klucza silnik nie wystartuje
        "ENGINE_DATA_DIR": "/data",
        "HF_HOME": "/data/hf",             # model pobierany raz i trzymany na wolumenie
        "VOXCPM_LOAD_ON_START": "true",
    })
    .add_local_dir(str(ENGINE_DIR / "app"), remote_path="/srv/app")
)

# Model (kilka GB) i nagrania referencyjne głosów — przetrwają restarty kontenera
data = modal.Volume.from_name("narrivy-engine-data", create_if_missing=True)

app = modal.App("narrivy-engine")


@app.function(
    image=image,
    gpu=GPU,
    volumes={"/data": data},
    secrets=[modal.Secret.from_name("narrivy-engine")],
    scaledown_window=SCALEDOWN_S,
    timeout=600,
    max_containers=1,                      # jedna karta — kolejkę żądań prowadzi backend Narrivy
)
@modal.concurrent(max_inputs=8)
@modal.asgi_app()
def engine():
    import sys

    sys.path.insert(0, "/srv")
    from app.main import app as fastapi_app  # ładuje model przy starcie kontenera

    return fastapi_app
