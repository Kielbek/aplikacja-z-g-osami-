"""Adapter VoxCPM2 (OpenBMB).

Cała wiedza o API biblioteki `voxcpm` jest zamknięta w tym pliku.
Po aktualizacji modelu sprawdź sygnaturę `VoxCPM.generate` — adapter przekazuje
tylko te argumenty, które dana wersja biblioteki faktycznie przyjmuje.
"""
from __future__ import annotations

import inspect
import logging
from pathlib import Path

import numpy as np

from ..schemas import Capabilities, GenerationParams, VoiceSpec
from .base import EngineUnavailable, TTSEngine

log = logging.getLogger("voxcpm2")

LANGUAGES = ["pl", "en", "de", "fr", "es", "it", "pt", "nl", "cs", "uk", "ja", "zh", "ko"]


class VoxCPM2Engine(TTSEngine):
    mode = "voxcpm2"

    def __init__(self, model_id: str, device: str, description_format: str = "prefix"):
        from voxcpm import VoxCPM  # import leniwy: pakiet jest opcjonalny

        log.info("Ładowanie modelu %s na %s…", model_id, device)
        self.model = VoxCPM.from_pretrained(model_id)
        self.model_id = model_id
        self.device = device
        self.description_format = description_format
        self.sample_rate = int(getattr(getattr(self.model, "tts_model", None), "sample_rate", 48000))
        self._accepted = set(inspect.signature(self.model.generate).parameters)
        self._references: dict[str, tuple[str, str | None]] = {}
        log.info("Model gotowy (sample_rate=%s)", self.sample_rate)

    def capabilities(self) -> Capabilities:
        return Capabilities(voice_design=True, cloning=True, style_prompt=True, emotion=True, speed=False, languages=LANGUAGES)

    # --- Pomocnicze --------------------------------------------------------
    def _control_text(self, text: str, description: str | None, params: GenerationParams) -> str:
        parts = [p for p in (description, params.style, params.emotion) if p]
        if not parts or self.description_format != "prefix":
            return text
        return f"({', '.join(parts)}){text}"

    def _generate(self, **kwargs) -> np.ndarray:
        if kwargs.get("seed") is not None:
            try:
                import torch

                torch.manual_seed(int(kwargs["seed"]))
            except Exception:  # noqa: BLE001
                pass
        call = {k: v for k, v in kwargs.items() if k in self._accepted}
        wav = self.model.generate(**call)
        return np.asarray(wav, dtype=np.float32).reshape(-1)

    def _common(self, params: GenerationParams) -> dict:
        return dict(
            cfg_value=params.cfg_value,
            inference_timesteps=params.inference_timesteps,
            normalize=params.normalize,
            denoise=params.denoise,
            retry_badcase=True,
            seed=params.seed,
        )

    # --- API ---------------------------------------------------------------
    def synthesize(self, text: str, language: str, voice: VoiceSpec, params: GenerationParams) -> np.ndarray:
        if voice.reference_id:
            ref = self._references.get(voice.reference_id)
            if not ref:
                raise EngineUnavailable(f"Brak nagrania referencyjnego {voice.reference_id} w silniku.")
            path, transcript = ref
            return self._generate(
                text=self._control_text(text, None, params),
                prompt_wav_path=path,
                prompt_text=transcript,
                reference_wav_path=path,
                **self._common(params),
            )
        return self._generate(text=self._control_text(text, voice.description, params), **self._common(params))

    def design(self, description: str, text: str, language: str, params: GenerationParams) -> np.ndarray:
        return self._generate(text=self._control_text(text, description, params), **self._common(params))

    def register_reference(self, reference_id: str, audio_path: str, transcript: str | None) -> None:
        if not Path(audio_path).exists():
            raise EngineUnavailable("Plik referencyjny nie istnieje.")
        self._references[reference_id] = (audio_path, transcript or None)
