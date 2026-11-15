"""Test adaptera VoxCPM2 na atrapie biblioteki o sygnaturze identycznej z voxcpm 2.0.3."""
import sys
import types

import numpy as np
import pytest

from app.schemas import GenerationParams, VoiceSpec


class FakeVoxCPM:
    calls: list[dict] = []

    def __init__(self):
        self.tts_model = types.SimpleNamespace(sample_rate=48000)

    @classmethod
    def from_pretrained(cls, hf_model_id="openbmb/VoxCPM2", load_denoiser=True, zipenhancer_model_id="x",
                        cache_dir=None, local_files_only=False, optimize=True, device=None, **kwargs):
        return cls()

    # Dokładnie jak w voxcpm 2.0.3: publiczne generate(*args, **kwargs) → _generate(...)
    def generate(self, *args, **kwargs):
        return self._generate(*args, **kwargs)

    def _generate(self, text, prompt_wav_path=None, prompt_text=None, reference_wav_path=None, cfg_value=2.0,
                  inference_timesteps=10, min_len=2, max_len=4096, normalize=False, denoise=False,
                  retry_badcase=True, retry_badcase_max_times=3, retry_badcase_ratio_threshold=6.0, streaming=False):
        if (prompt_wav_path is None) != (prompt_text is None):
            raise ValueError("prompt_wav_path and prompt_text must both be provided or both be None")
        FakeVoxCPM.calls.append(dict(text=text, prompt_wav_path=prompt_wav_path, prompt_text=prompt_text,
                                     reference_wav_path=reference_wav_path, cfg_value=cfg_value,
                                     inference_timesteps=inference_timesteps, normalize=normalize))
        return np.zeros(4800, dtype=np.float32)


@pytest.fixture()
def engine(monkeypatch):
    monkeypatch.setitem(sys.modules, "voxcpm", types.SimpleNamespace(VoxCPM=FakeVoxCPM))
    FakeVoxCPM.calls.clear()
    from app.engines.voxcpm2 import VoxCPM2Engine

    return VoxCPM2Engine("openbmb/VoxCPM2", "auto")


def test_passes_text_and_params_through_var_kwargs_generate(engine):
    out = engine.synthesize("Dzień dobry.", "pl", VoiceSpec(id="v", description="spokojny lektor"),
                            GenerationParams(style="ciepło", cfg_value=3.0, inference_timesteps=20))
    assert out.shape == (4800,)
    call = FakeVoxCPM.calls[-1]
    assert call["text"] == "(spokojny lektor, ciepło)Dzień dobry."
    assert call["cfg_value"] == 3.0 and call["inference_timesteps"] == 20
    assert engine.sample_rate == 48000


def test_clone_without_transcript_uses_reference_only(engine, tmp_path):
    ref = tmp_path / "ref.wav"
    ref.write_bytes(b"x")
    engine.register_reference("r1", str(ref), None)
    engine.synthesize("Tekst.", "pl", VoiceSpec(id="c", reference_id="r1"), GenerationParams())
    call = FakeVoxCPM.calls[-1]
    assert call["reference_wav_path"] == str(ref)
    assert call["prompt_wav_path"] is None and call["prompt_text"] is None


def test_clone_with_transcript_uses_ultimate_cloning(engine, tmp_path):
    ref = tmp_path / "ref.wav"
    ref.write_bytes(b"x")
    engine.register_reference("r2", str(ref), "Transkrypcja nagrania.")
    engine.synthesize("Tekst.", "pl", VoiceSpec(id="c", reference_id="r2"), GenerationParams(style="szybciej"))
    call = FakeVoxCPM.calls[-1]
    assert call["prompt_wav_path"] == str(ref) and call["prompt_text"] == "Transkrypcja nagrania."
    assert call["text"] == "(szybciej)Tekst."


def test_parentheses_in_description_do_not_break_format(engine):
    engine.design("Głos (męski) niski", "Test.", "pl", GenerationParams())
    assert FakeVoxCPM.calls[-1]["text"] == "(Głos męski niski)Test."


def test_languages_match_voxcpm2(engine):
    langs = engine.capabilities().languages
    assert "pl" in langs and len(langs) == 30
    assert "uk" not in langs and "cs" not in langs
