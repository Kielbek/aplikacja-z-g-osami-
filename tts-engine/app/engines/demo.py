"""Syntezator DEMONSTRACYJNY.

To NIE jest synteza mowy. Generuje sygnał zastępczy o rytmie zbliżonym do mowy
(sylaby, pauzy, intonacja), aby można było przetestować cały przepływ aplikacji
bez modelu VoxCPM2. Odpowiedzi są oznaczane nagłówkiem X-Engine-Mode: demo,
a interfejs wyświetla to użytkownikowi.
"""
from __future__ import annotations

import hashlib
import re

import numpy as np

from ..schemas import Capabilities, GenerationParams, VoiceSpec
from .base import TTSEngine

LANGUAGES = ["pl", "en", "de", "fr", "es", "it", "pt", "nl", "cs", "uk", "ja", "zh", "ko"]

# Formanty (F1, F2, F3) samogłosek – dają barwę zbliżoną do samogłoski
FORMANTS = {
    "a": (800, 1250, 2600), "e": (520, 1850, 2550), "i": (300, 2300, 3000),
    "o": (520, 900, 2450), "u": (340, 800, 2350), "y": (420, 1600, 2500),
}
VOWEL_MAP = str.maketrans({"ą": "o", "ę": "e", "ó": "u", "á": "a", "é": "e", "í": "i", "ö": "o", "ü": "u", "ä": "e"})
VOWELS = set(FORMANTS)


def _seed(*parts: object) -> int:
    return int(hashlib.sha1("|".join(map(str, parts)).encode()).hexdigest()[:8], 16)


class DemoEngine(TTSEngine):
    mode = "demo"

    def __init__(self, sample_rate: int = 24000):
        self.sample_rate = sample_rate

    def capabilities(self) -> Capabilities:
        return Capabilities(voice_design=True, cloning=False, style_prompt=True, emotion=True, speed=True, languages=LANGUAGES)

    # --- API ---------------------------------------------------------------
    def synthesize(self, text: str, language: str, voice: VoiceSpec, params: GenerationParams) -> np.ndarray:
        f0 = self._base_pitch(voice.gender, voice.age, voice.description)
        return self._render(text, f0, params, seed=_seed(text, voice.id, params.seed))

    def design(self, description: str, text: str, language: str, params: GenerationParams) -> np.ndarray:
        f0 = self._base_pitch(None, None, description)
        return self._render(text, f0, params, seed=_seed(description, text, params.seed))

    # --- Wewnętrzne --------------------------------------------------------
    @staticmethod
    def _base_pitch(gender: str | None, age: int | None, description: str | None) -> float:
        d = (description or "").lower()
        if gender is None:
            if re.search(r"męsk|male|\bman\b|mężczyzn", d):
                gender = "male"
            elif re.search(r"żeńsk|kobie|female|woman", d):
                gender = "female"
        f0 = {"male": 112.0, "female": 205.0}.get(gender or "", 160.0)
        if re.search(r"nis|głęb|low|deep", d):
            f0 *= 0.88
        if re.search(r"wysok|jasn|high|bright", d):
            f0 *= 1.12
        if age:
            f0 *= 1.06 if age < 25 else (0.95 if age > 55 else 1.0)
        return f0

    def _syllables(self, text: str) -> list[tuple[str, float]]:
        """Lista (samogłoska, pauza_po) – pauzy wynikają z interpunkcji."""
        out: list[tuple[str, float]] = []
        for token in re.findall(r"[\wąćęłńóśźżäöüáéí]+|[.,;:!?…—-]", text.lower()):
            if re.fullmatch(r"[.,;:!?…—-]", token):
                if out:
                    pause = 0.42 if token in ".!?…" else 0.2
                    v, p = out[-1]
                    out[-1] = (v, max(p, pause))
                continue
            vowels = [c for c in token.translate(VOWEL_MAP) if c in VOWELS] or ["e"]
            for i, v in enumerate(vowels):
                out.append((v, 0.07 if i == len(vowels) - 1 else 0.0))
        return out

    def _render(self, text: str, f0: float, params: GenerationParams, seed: int) -> np.ndarray:
        sr = self.sample_rate
        rng = np.random.default_rng(seed)
        syllables = self._syllables(text) or [("a", 0.2)]
        speed = params.speed
        expr = params.expressiveness
        emotion = (params.emotion or "").lower()
        if emotion in {"radosny", "energiczny", "happy"}:
            f0 *= 1.08
            expr = min(1.0, expr + 0.2)
        elif emotion in {"smutny", "sad", "spokojny", "calm"}:
            f0 *= 0.95
            expr *= 0.6

        pieces: list[np.ndarray] = [np.zeros(int(sr * 0.12), dtype=np.float32)]
        total = len(syllables)
        for idx, (vowel, pause) in enumerate(syllables):
            dur = rng.uniform(0.13, 0.2) / speed
            n = int(sr * dur)
            t = np.arange(n) / sr
            # Intonacja: deklinacja w zdaniu + losowe akcenty zależne od ekspresji
            progress = idx / max(total - 1, 1)
            pitch = f0 * (1.06 - 0.12 * progress) * (1 + rng.normal(0, 0.04 + 0.08 * expr))
            glide = pitch * (1 + (0.03 + 0.06 * expr) * np.sin(np.pi * t / dur))
            phase = 2 * np.pi * np.cumsum(glide) / sr
            f1, f2, f3 = FORMANTS[vowel]
            sig = np.zeros(n, dtype=np.float64)
            for k in range(1, int(3800 // pitch) + 1):
                fk = k * pitch
                gain = sum(1.0 / (1 + ((fk - F) / (F * 0.12)) ** 2) for F in (f1, f2, f3)) / k**0.6
                sig += gain * np.sin(k * phase)
            # Obwiednia sylaby + krótki szumowy "spółgłoskowy" atak
            env = np.sin(np.pi * np.clip(t / dur, 0, 1)) ** 0.7
            sig *= env
            burst = int(n * 0.18)
            if burst > 0 and rng.random() < 0.7:
                noise = rng.normal(0, 0.18, burst) * np.linspace(1, 0, burst)
                sig[:burst] += noise
            amp = 0.55 + 0.35 * expr * rng.random()
            pieces.append((sig / (np.max(np.abs(sig)) + 1e-9) * amp).astype(np.float32))
            if pause:
                pieces.append(np.zeros(int(sr * pause / speed), dtype=np.float32))
        pieces.append(np.zeros(int(sr * 0.2), dtype=np.float32))
        audio = np.concatenate(pieces)
        # Łagodny lowpass (średnia ruchoma) i normalizacja do -3 dBFS
        audio = np.convolve(audio, np.ones(3) / 3, mode="same").astype(np.float32)
        peak = float(np.max(np.abs(audio))) or 1.0
        return audio / peak * 0.7
