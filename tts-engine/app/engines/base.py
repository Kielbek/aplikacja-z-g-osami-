"""Wspólny interfejs silników. Kolejny silnik = nowa implementacja tej klasy."""
from __future__ import annotations

from abc import ABC, abstractmethod

import numpy as np

from ..schemas import Capabilities, GenerationParams, VoiceSpec


class EngineUnavailable(RuntimeError):
    """Funkcja wymaga prawdziwego modelu, który nie jest załadowany."""


class TTSEngine(ABC):
    mode: str
    sample_rate: int

    @abstractmethod
    def capabilities(self) -> Capabilities: ...

    @abstractmethod
    def synthesize(self, text: str, language: str, voice: VoiceSpec, params: GenerationParams) -> np.ndarray:
        """Zwraca próbki mono float32 w zakresie [-1, 1] z częstotliwością self.sample_rate."""

    @abstractmethod
    def design(self, description: str, text: str, language: str, params: GenerationParams) -> np.ndarray: ...

    def register_reference(self, reference_id: str, audio_path: str, transcript: str | None) -> None:
        raise EngineUnavailable("Ten silnik nie obsługuje klonowania głosu.")
