"""Lekki silnik Piper (rhasspy) — prawdziwa mowa na zwykłym procesorze, bez GPU.

Przeznaczenie: testy i praca na komputerze bez karty graficznej. Piper ma gotowe głosy
(po kilka na język) — nie projektuje głosu z opisu i nie klonuje głosu. Głosy z biblioteki
Narrivy są mapowane na najbliższy głos Piper (język + płeć), więc kilka głosów
biblioteki może mieć tę samą barwę.

Modele (~60 MB każdy) są pobierane przy pierwszym użyciu z repozytorium rhasspy/piper-voices
(Hugging Face) do katalogu danych silnika i potem działają offline.
Zweryfikowane z pakietem piper-tts 1.8 (PiperVoice.load, synthesize → AudioChunk, SynthesisConfig).
"""
from __future__ import annotations

import logging
import threading
from pathlib import Path

import numpy as np

from ..schemas import Capabilities, GenerationParams, VoiceSpec
from .base import EngineUnavailable, TTSEngine

log = logging.getLogger("piper")

# Głosy Piper wg języka i płci (jakość "medium", 22,05 kHz)
CATALOG: dict[str, dict[str, str]] = {
    "pl": {"female": "pl_PL-gosia-medium", "male": "pl_PL-darkman-medium"},
    "en": {"female": "en_US-amy-medium", "male": "en_US-ryan-medium"},
    "de": {"female": "de_DE-thorsten-medium", "male": "de_DE-thorsten-medium"},
    "fr": {"female": "fr_FR-siwis-medium", "male": "fr_FR-siwis-medium"},
    "es": {"female": "es_ES-davefx-medium", "male": "es_ES-davefx-medium"},
    "it": {"female": "it_IT-paola-medium", "male": "it_IT-paola-medium"},
    "pt": {"female": "pt_BR-faber-medium", "male": "pt_BR-faber-medium"},
}

# Wybrane głosy biblioteki z dopasowaną barwą (reszta: język + płeć)
VOICE_OVERRIDES: dict[str, str] = {
    "en-claire": "en_GB-alba-medium",
    "en-maya": "en_US-amy-medium",
    "en-james": "en_US-ryan-medium",
}

TARGET_RATE = 22050


class PiperEngine(TTSEngine):
    mode = "piper"
    sample_rate = TARGET_RATE
    model_name = "Piper (rhasspy/piper-voices)"
    device_name = "cpu"

    def __init__(self, voices_dir: Path, preload: list[str] | None = None):
        from piper import PiperVoice  # import leniwy: pakiet jest opcjonalny
        from piper.config import SynthesisConfig

        self._PiperVoice = PiperVoice
        self._SynthesisConfig = SynthesisConfig
        self.voices_dir = voices_dir
        self.voices_dir.mkdir(parents=True, exist_ok=True)
        self._loaded: dict[str, object] = {}
        self._lock = threading.Lock()
        for name in preload or []:
            self._voice(name)

    def capabilities(self) -> Capabilities:
        return Capabilities(voice_design=False, cloning=False, style_prompt=False, emotion=False, speed=True,
                            languages=list(CATALOG.keys()))

    # --- Pomocnicze --------------------------------------------------------
    @staticmethod
    def pick(voice: VoiceSpec, language: str) -> str:
        if voice.id in VOICE_OVERRIDES and VOICE_OVERRIDES[voice.id].startswith(language):
            return VOICE_OVERRIDES[voice.id]
        lang = CATALOG.get(language)
        if not lang:
            raise EngineUnavailable(f"Silnik Piper nie ma głosu dla języka „{language}”. Dostępne: {', '.join(CATALOG)}.")
        return lang["male" if voice.gender == "male" else "female"]

    def _voice(self, name: str):
        with self._lock:
            if name in self._loaded:
                return self._loaded[name]
            model = self.voices_dir / f"{name}.onnx"
            if not model.exists() or not model.with_suffix(".onnx.json").exists():
                log.info("Pobieranie głosu Piper %s…", name)
                try:
                    from piper.download_voices import download_voice

                    download_voice(name, self.voices_dir)
                except Exception as exc:  # noqa: BLE001
                    for f in (model, model.with_suffix(".onnx.json")):
                        f.unlink(missing_ok=True)
                    raise EngineUnavailable(
                        f"Nie udało się pobrać głosu Piper {name} (wymagany dostęp do huggingface.co): {exc}"
                    ) from exc
            v = self._PiperVoice.load(str(model))
            self._loaded[name] = v
            return v

    def _render(self, text: str, name: str, params: GenerationParams) -> np.ndarray:
        voice = self._voice(name)
        cfg = self._SynthesisConfig(
            length_scale=1.0 / max(0.5, min(2.0, params.speed)),
            # Ekspresja → zmienność intonacji (domyślnie Piper: 0.667)
            noise_scale=0.4 + 0.5 * params.expressiveness,
            noise_w_scale=0.8,
            normalize_audio=params.normalize,
        )
        chunks = [np.asarray(c.audio_float_array, dtype=np.float32).reshape(-1) for c in voice.synthesize(text, syn_config=cfg)]
        if not chunks:
            return np.zeros(int(TARGET_RATE * 0.2), dtype=np.float32)
        rate = int(voice.config.sample_rate)
        gap = np.zeros(int(rate * 0.12), dtype=np.float32)  # krótka pauza między zdaniami
        audio = np.concatenate([x for c in chunks for x in (c, gap)][:-1])
        return _resample(audio, rate, TARGET_RATE)

    # --- API ---------------------------------------------------------------
    def synthesize(self, text: str, language: str, voice: VoiceSpec, params: GenerationParams) -> np.ndarray:
        if voice.reference_id:
            raise EngineUnavailable("Głos sklonowany wymaga modelu VoxCPM2 — silnik Piper ma tylko gotowe głosy.")
        return self._render(text, self.pick(voice, language), params)

    def design(self, description: str, text: str, language: str, params: GenerationParams) -> np.ndarray:
        raise EngineUnavailable("Projektowanie głosu z opisu wymaga modelu VoxCPM2 — silnik Piper ma tylko gotowe głosy.")


def _resample(x: np.ndarray, src: int, dst: int) -> np.ndarray:
    if src == dst or len(x) == 0:
        return x.astype(np.float32)
    n = int(round(len(x) * dst / src))
    return np.interp(np.linspace(0, len(x) - 1, n), np.arange(len(x)), x).astype(np.float32)
