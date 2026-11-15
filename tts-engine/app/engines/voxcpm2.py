"""Adapter VoxCPM2 (OpenBMB, pakiet `voxcpm` >= 2.0).

Cała wiedza o API biblioteki jest zamknięta w tym pliku. Zweryfikowane z voxcpm 2.0.3:
  * VoxCPM.from_pretrained(hf_model_id, load_denoiser=..., device=..., optimize=...)
  * VoxCPM.generate(*args, **kwargs) → _generate(text, prompt_wav_path, prompt_text,
    reference_wav_path, cfg_value, inference_timesteps, normalize, denoise, retry_badcase, ...)
  * Voice design / sterowanie stylem: opis w nawiasie na początku tekstu, np. "(spokojny lektor)Treść".
  * Klonowanie: reference_wav_path; „ultimate cloning” dodatkowo prompt_wav_path + prompt_text
    (oba muszą być podane razem).
  * Częstotliwość próbkowania: model.tts_model.sample_rate (48 kHz).
"""
from __future__ import annotations

import inspect
import logging
from pathlib import Path

import numpy as np

from ..languages import VOXCPM2_LANGUAGES
from ..schemas import Capabilities, GenerationParams, VoiceSpec
from .base import EngineUnavailable, TTSEngine

log = logging.getLogger("voxcpm2")


class VoxCPM2Engine(TTSEngine):
    mode = "voxcpm2"

    def __init__(self, model_id: str, device: str, description_format: str = "prefix", load_denoiser: bool = True):
        from voxcpm import VoxCPM  # import leniwy: pakiet jest opcjonalny

        log.info("Ładowanie modelu %s (urządzenie: %s)…", model_id, device)
        self.model = VoxCPM.from_pretrained(
            model_id,
            load_denoiser=load_denoiser,
            device=None if device in ("", "auto") else device,
        )
        self.model_id = model_id
        self.device = device
        self.description_format = description_format
        self.sample_rate = int(getattr(getattr(self.model, "tts_model", None), "sample_rate", 48000))
        # Uwaga: VoxCPM.generate ma sygnaturę (*args, **kwargs) — właściwe parametry są w _generate.
        target = getattr(self.model, "_generate", self.model.generate)
        self._accepted = {
            name for name, p in inspect.signature(target).parameters.items()
            if p.kind not in (p.VAR_POSITIONAL, p.VAR_KEYWORD)
        }
        if "text" not in self._accepted:
            raise RuntimeError(f"Nieobsługiwana wersja biblioteki voxcpm: brak parametru 'text' w {target}")
        self._references: dict[str, tuple[str, str | None]] = {}
        log.info("Model gotowy (sample_rate=%s)", self.sample_rate)

    def capabilities(self) -> Capabilities:
        # VoxCPM2 nie ma parametru tempa — tempo opisuje się w instrukcji stylu.
        return Capabilities(voice_design=True, cloning=True, style_prompt=True, emotion=True, speed=False,
                            languages=VOXCPM2_LANGUAGES)

    # --- Pomocnicze --------------------------------------------------------
    def _control_text(self, text: str, description: str | None, params: GenerationParams) -> str:
        parts = [p.strip() for p in (description, params.style, params.emotion) if p and p.strip()]
        if not parts or self.description_format != "prefix":
            return text
        # Nawiasy w opisie rozbiłyby format "(opis)tekst"
        control = ", ".join(parts).replace("(", "").replace(")", "")
        return f"({control}){text}"

    def _generate(self, **kwargs) -> np.ndarray:
        seed = kwargs.pop("seed", None)
        if seed is not None:
            try:
                import torch

                torch.manual_seed(int(seed))
            except Exception:  # noqa: BLE001
                pass
        call = {k: v for k, v in kwargs.items() if k in self._accepted and v is not None}
        wav = self.model.generate(**call)
        return np.asarray(wav, dtype=np.float32).reshape(-1)

    @staticmethod
    def _common(params: GenerationParams) -> dict:
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
            kwargs = dict(text=self._control_text(text, None, params), reference_wav_path=path)
            if transcript:  # „ultimate cloning”: oba parametry muszą być podane razem
                kwargs.update(prompt_wav_path=path, prompt_text=transcript)
            return self._generate(**kwargs, **self._common(params))
        return self._generate(text=self._control_text(text, voice.description, params), **self._common(params))

    def design(self, description: str, text: str, language: str, params: GenerationParams) -> np.ndarray:
        return self._generate(text=self._control_text(text, description, params), **self._common(params))

    def register_reference(self, reference_id: str, audio_path: str, transcript: str | None) -> None:
        if not Path(audio_path).exists():
            raise EngineUnavailable("Plik referencyjny nie istnieje.")
        self._references[reference_id] = (audio_path, transcript or None)
