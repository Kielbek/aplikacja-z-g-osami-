"""Testy serwisu silnika w trybie demonstracyjnym:  ENGINE_MODE=demo pytest"""
import io
import os

os.environ.setdefault("ENGINE_MODE", "demo")
os.environ.setdefault("ENGINE_DATA_DIR", "/tmp/voicecraft-engine-test")

import numpy as np  # noqa: E402
import soundfile as sf  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402

from app.main import app  # noqa: E402

client = TestClient(app)


def test_health_reports_demo_mode():
    body = client.get("/health").json()
    assert body["mode"] == "demo"
    assert body["capabilities"]["cloning"] is False
    assert "demonstracyjny" in body["message"]


def test_synthesize_returns_labeled_wav():
    res = client.post("/v1/synthesize", json={"text": "Dzień dobry, to test.", "voice": {"id": "v1", "gender": "male"}})
    assert res.status_code == 200
    assert res.headers["x-engine-mode"] == "demo"
    data, sr = sf.read(io.BytesIO(res.content))
    assert sr == 24000 and len(data) > sr // 2
    assert np.max(np.abs(data)) <= 1.0


def test_synthesis_is_deterministic_for_same_seed():
    req = {"text": "Powtarzalny wynik.", "voice": {"id": "v1"}, "params": {"seed": 7}}
    assert client.post("/v1/synthesize", json=req).content == client.post("/v1/synthesize", json=req).content


def test_analyze_flags_short_recording():
    buf = io.BytesIO()
    sf.write(buf, np.zeros(24000 * 2, dtype=np.float32), 24000, format="WAV")
    res = client.post("/v1/clone/analyze", files={"file": ("a.wav", buf.getvalue(), "audio/wav")})
    body = res.json()
    assert body["suitable"] is False
    assert any("za krótkie" in i for i in body["issues"])


def test_clone_requires_real_model():
    buf = io.BytesIO()
    sf.write(buf, np.random.default_rng(0).normal(0, 0.1, 24000 * 8).astype(np.float32), 24000, format="WAV")
    res = client.post("/v1/clone/references", files={"file": ("a.wav", buf.getvalue(), "audio/wav")}, data={"consent": "true"})
    assert res.status_code == 503


def test_mp3_encoding():
    wav = client.post("/v1/synthesize", json={"text": "MP3.", "voice": {"id": "v1"}}).content
    res = client.post("/v1/encode/mp3", files={"file": ("a.wav", wav, "audio/wav")}, data={"bitrate": "128"})
    assert res.status_code == 200 and res.headers["content-type"] == "audio/mpeg"
