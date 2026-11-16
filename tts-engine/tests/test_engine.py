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


def test_api_key_required_when_configured(monkeypatch):
    import dataclasses

    from fastapi.testclient import TestClient

    from app import main

    monkeypatch.setattr(main, "settings", dataclasses.replace(main.settings, api_key="tajny-klucz"))
    c = TestClient(main.app)
    assert c.get("/health").status_code == 401
    assert c.get("/health", headers={"Authorization": "Bearer zly"}).status_code == 401
    assert c.get("/health", headers={"Authorization": "Bearer tajny-klucz"}).status_code == 200


def test_model_calls_are_serialized_under_concurrent_requests(monkeypatch):
    """Równoległe żądania nie mogą wywoływać modelu jednocześnie (jedno GPU = jedno wywołanie naraz)."""
    import threading
    import time as _time

    from app import main

    active, peak, guard = [0], [0], threading.Lock()
    real = main.engine.synthesize

    def tracked(*args):
        with guard:
            active[0] += 1
            peak[0] = max(peak[0], active[0])
        _time.sleep(0.05)
        try:
            return real(*args)
        finally:
            with guard:
                active[0] -= 1

    monkeypatch.setattr(main.engine, "synthesize", tracked)
    req = {"text": "Równolegle.", "voice": {"id": "v1"}}
    results = []
    threads = [threading.Thread(target=lambda: results.append(client.post("/v1/synthesize", json=req))) for _ in range(4)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    assert all(r.status_code == 200 for r in results)
    assert peak[0] == 1
    assert any(int(r.headers["x-queue-wait-ms"]) > 0 for r in results)


def test_security_config_is_fail_closed():
    """Produkcja: bez silnego klucza silnik nie startuje (zamiast działać otwarty)."""
    import dataclasses

    from app.config import security_problem, settings as base

    assert security_problem(dataclasses.replace(base, api_key="", require_key=False)) is None  # sieć lokalna
    assert "nie jest ustawiony" in security_problem(dataclasses.replace(base, api_key="", require_key=True))
    assert "za krótki" in security_problem(dataclasses.replace(base, api_key="tajne", require_key=True))
    assert security_problem(dataclasses.replace(base, api_key="a" * 64, require_key=True)) is None


def test_api_key_and_upload_limit_are_enforced(monkeypatch):
    import dataclasses

    from app import main

    monkeypatch.setattr(main, "settings", dataclasses.replace(main.settings, api_key="k" * 40))
    assert client.get("/health").status_code == 401
    assert client.get("/health", headers={"Authorization": "Bearer zly"}).status_code == 401
    assert client.get("/health", headers={"Authorization": "Bearer " + "k" * 40}).status_code == 200
    monkeypatch.setattr(main, "settings", dataclasses.replace(main.settings, max_upload_mb=1))
    big = client.post("/v1/clone/analyze", content=b"0" * 1_200_000,
                      headers={"Authorization": "Bearer " + "k" * 40, "Content-Type": "application/octet-stream"})
    assert big.status_code == 413
