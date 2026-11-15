"""Konfiguracja serwisu silnika TTS (zmienne środowiskowe)."""
from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path


def _bool(name: str, default: bool) -> bool:
    raw = os.getenv(name)
    if raw is None:
        return default
    return raw.strip().lower() in {"1", "true", "yes", "on"}


@dataclass(frozen=True)
class Settings:
    # "auto"   – spróbuj załadować VoxCPM2, w razie niepowodzenia przejdź w tryb demo
    # "voxcpm2" – wymagaj modelu (błąd startu, jeśli nie da się go załadować)
    # "piper"  – lekki silnik Piper na CPU (prawdziwa mowa, gotowe głosy, bez projektowania i klonowania)
    # "demo"   – nie ładuj modelu, generuj sygnał zastępczy (wyraźnie oznaczony)
    # W trybie "auto" kolejność to: VoxCPM2 → Piper (jeśli zainstalowany) → demo.
    engine_mode: str = os.getenv("ENGINE_MODE", "auto").lower()
    model_id: str = os.getenv("VOXCPM_MODEL_ID", "openbmb/VoxCPM2")
    # "auto" — biblioteka wybierze CUDA, potem MPS, potem CPU
    device: str = os.getenv("VOXCPM_DEVICE", "auto")
    load_denoiser: bool = _bool("VOXCPM_LOAD_DENOISER", True)
    data_dir: Path = Path(os.getenv("ENGINE_DATA_DIR", "./engine-data"))
    demo_sample_rate: int = int(os.getenv("DEMO_SAMPLE_RATE", "24000"))
    # Sztuczne opóźnienie syntezy demo (ms) — do testowania kolejki i pasków postępu
    demo_latency_ms: int = int(os.getenv("DEMO_LATENCY_MS", "0"))
    # Jak przekazać opis głosu / instrukcję stylu do VoxCPM2.
    # "prefix" – tekst w postaci "(opis)treść", zgodnie z konwencją voice design VoxCPM2.
    description_format: str = os.getenv("VOXCPM_DESCRIPTION_FORMAT", "prefix")
    allowed_origins: list[str] = field(
        default_factory=lambda: os.getenv("ENGINE_CORS", "http://localhost:8080").split(",")
    )
    load_on_start: bool = _bool("VOXCPM_LOAD_ON_START", True)
    # Klucz dostępu (nagłówek Authorization: Bearer …). Ustaw, gdy silnik jest dostępny z internetu.
    api_key: str = os.getenv("ENGINE_API_KEY", "").strip()
    # Głosy Piper pobierane przy starcie (po przecinku), np. "pl_PL-gosia-medium,pl_PL-darkman-medium"
    piper_preload: list[str] = field(
        default_factory=lambda: [v.strip() for v in os.getenv("PIPER_PRELOAD", "").split(",") if v.strip()]
    )


settings = Settings()
settings.data_dir.mkdir(parents=True, exist_ok=True)
(settings.data_dir / "references").mkdir(parents=True, exist_ok=True)
