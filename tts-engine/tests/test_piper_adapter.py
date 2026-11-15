"""Adapter Piper na atrapie pakietu piper-tts (API zgodne z wersją 1.8)."""
import sys
import types

import numpy as np
import pytest

from app.schemas import GenerationParams, VoiceSpec


class FakeChunk:
    def __init__(self, n):
        self.audio_float_array = np.full(n, 0.1, dtype=np.float32)


class FakeVoice:
    loaded: list[str] = []
    configs: list[object] = []

    def __init__(self, path):
        self.path = path
        self.config = types.SimpleNamespace(sample_rate=16000 if "low" in path else 22050)

    @classmethod
    def load(cls, path):
        cls.loaded.append(path)
        return cls(path)

    def synthesize(self, text, syn_config=None):
        FakeVoice.configs.append(syn_config)
        return [FakeChunk(1000) for _ in text.split(".") if _.strip()]


class FakeConfig:
    def __init__(self, **kw):
        self.__dict__.update(kw)


@pytest.fixture()
def engine(monkeypatch, tmp_path):
    downloads = []

    def download_voice(name, d):
        downloads.append(name)
        (d / f"{name}.onnx").write_bytes(b"x")
        (d / f"{name}.onnx.json").write_text("{}")

    piper = types.ModuleType("piper")
    piper.PiperVoice = FakeVoice
    cfg = types.ModuleType("piper.config")
    cfg.SynthesisConfig = FakeConfig
    dl = types.ModuleType("piper.download_voices")
    dl.download_voice = download_voice
    monkeypatch.setitem(sys.modules, "piper", piper)
    monkeypatch.setitem(sys.modules, "piper.config", cfg)
    monkeypatch.setitem(sys.modules, "piper.download_voices", dl)
    FakeVoice.loaded.clear()
    FakeVoice.configs.clear()
    from app.engines.piper import PiperEngine

    e = PiperEngine(tmp_path / "piper")
    e.downloads = downloads
    return e


def test_maps_language_and_gender_and_downloads_once(engine):
    female = engine.synthesize("Dzień dobry. Miłego dnia.", "pl", VoiceSpec(id="pl-anna", gender="female"), GenerationParams())
    engine.synthesize("Kolejne zdanie.", "pl", VoiceSpec(id="pl-zofia", gender="female"), GenerationParams())
    engine.synthesize("Cześć.", "pl", VoiceSpec(id="pl-tomasz", gender="male"), GenerationParams())
    assert engine.downloads == ["pl_PL-gosia-medium", "pl_PL-darkman-medium"]
    # dwa zdania po 1000 próbek + przerwa 0,12 s, częstotliwość 22 050 Hz
    assert len(female) == 2000 + int(22050 * 0.12)
    assert female.dtype == np.float32


def test_speed_and_expressiveness_map_to_synthesis_config(engine):
    engine.synthesize("Test.", "en", VoiceSpec(id="en-claire", gender="female"), GenerationParams(speed=2.0, expressiveness=1.0))
    cfg = FakeVoice.configs[-1]
    assert cfg.length_scale == 0.5 and cfg.noise_scale == pytest.approx(0.9)
    assert engine.downloads[-1] == "en_GB-alba-medium"


def test_unsupported_language_cloning_and_design_are_explicit(engine):
    from app.engines.base import EngineUnavailable

    with pytest.raises(EngineUnavailable, match="języka"):
        engine.synthesize("Test.", "ja", VoiceSpec(id="x"), GenerationParams())
    with pytest.raises(EngineUnavailable, match="VoxCPM2"):
        engine.synthesize("Test.", "pl", VoiceSpec(id="c", reference_id="ref_1"), GenerationParams())
    with pytest.raises(EngineUnavailable, match="VoxCPM2"):
        engine.design("opis głosu", "Test.", "pl", GenerationParams())
    caps = engine.capabilities()
    assert caps.voice_design is False and caps.cloning is False and caps.speed is True


def test_resampling_to_common_rate():
    from app.engines.piper import _resample

    out = _resample(np.ones(16000, dtype=np.float32), 16000, 22050)
    assert len(out) == 22050
