"""VoiceCraft AI — serwis silnika TTS (VoxCPM2).

Uruchomienie:  uvicorn app.main:app --port 8808
"""
from __future__ import annotations

import logging
import re
import time
import uuid

import numpy as np
from fastapi import FastAPI, File, Form, HTTPException, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import Response

from . import audio
from .config import settings
from .engines.base import EngineUnavailable, ReferenceMissing, TTSEngine
from .engines.demo import DemoEngine
from .schemas import (
    AudioAnalysis,
    CloneProfileResponse,
    HealthResponse,
    SynthesizeRequest,
    VoiceDesignRequest,
)

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
log = logging.getLogger("engine")

app = FastAPI(title="VoiceCraft TTS Engine", version="0.1.0")
app.add_middleware(CORSMiddleware, allow_origins=settings.allowed_origins, allow_methods=["*"], allow_headers=["*"],
                   expose_headers=["X-Engine-Mode", "X-Duration-Ms", "X-Sample-Rate"])

engine: TTSEngine
load_error: str | None = None


@app.middleware("http")
async def require_api_key(request, call_next):
    """Przy ustawionym ENGINE_API_KEY każde żądanie musi mieć nagłówek Authorization: Bearer <klucz>."""
    if settings.api_key:
        import hmac

        from fastapi.responses import JSONResponse

        got = request.headers.get("authorization", "")
        if not hmac.compare_digest(got.encode(), f"Bearer {settings.api_key}".encode()):
            return JSONResponse({"detail": "Brak lub niepoprawny klucz silnika (ENGINE_API_KEY)."}, status_code=401)
    return await call_next(request)


def _load_piper() -> TTSEngine:
    from .engines.piper import PiperEngine

    eng = PiperEngine(settings.data_dir / "piper", settings.piper_preload)
    log.info("Silnik Piper gotowy (CPU). Głosy pobierane przy pierwszym użyciu do %s", settings.data_dir / "piper")
    return eng


def _load_engine() -> TTSEngine:
    global load_error
    if settings.engine_mode == "demo":
        log.warning("ENGINE_MODE=demo — model VoxCPM2 nie będzie ładowany.")
        return DemoEngine(settings.demo_sample_rate, settings.demo_latency_ms)
    if settings.engine_mode == "piper":
        return _load_piper()
    try:
        from .engines.voxcpm2 import VoxCPM2Engine

        eng = VoxCPM2Engine(settings.model_id, settings.device, settings.description_format, settings.load_denoiser)
        refs = settings.data_dir / "references"
        for wav in refs.glob("*.wav"):
            txt = wav.with_suffix(".txt")
            eng.register_reference(wav.stem, str(wav), txt.read_text("utf-8") if txt.exists() else None)
        return eng
    except Exception as exc:  # noqa: BLE001
        load_error = f"{type(exc).__name__}: {exc}"
        if settings.engine_mode == "voxcpm2":
            raise
        try:
            eng = _load_piper()
            log.warning("Nie udało się załadować VoxCPM2 (%s). Używam silnika Piper (CPU).", load_error)
            return eng
        except Exception as exc:  # noqa: BLE001
            load_error += f"; Piper: {type(exc).__name__}: {exc}"
        log.warning("Nie udało się załadować VoxCPM2 ani Piper (%s). Przechodzę w tryb DEMO.", load_error)
        return DemoEngine(settings.demo_sample_rate, settings.demo_latency_ms)


engine = _load_engine()


def _wav_response(samples: np.ndarray) -> Response:
    data = audio.to_wav_bytes(samples, engine.sample_rate)
    return Response(
        content=data,
        media_type="audio/wav",
        headers={
            "X-Engine-Mode": engine.mode,
            "X-Duration-Ms": str(int(len(samples) / engine.sample_rate * 1000)),
            "X-Sample-Rate": str(engine.sample_rate),
        },
    )


@app.get("/health", response_model=HealthResponse)
def health() -> HealthResponse:
    is_demo = engine.mode == "demo"
    msg = None
    if is_demo:
        msg = "Tryb demonstracyjny: model VoxCPM2 nie jest załadowany. Generowany jest sygnał zastępczy, nie mowa."
        if load_error:
            msg += f" Powód: {load_error}"
    elif engine.mode == "piper":
        msg = ("Silnik Piper (CPU): prawdziwa mowa z gotowych głosów — kilka głosów biblioteki może brzmieć tak samo. "
               "Projektowanie głosu z opisu i klonowanie wymagają modelu VoxCPM2 (GPU).")
    return HealthResponse(
        status="ok",
        mode=engine.mode,  # type: ignore[arg-type]
        model=None if is_demo else getattr(engine, "model_name", settings.model_id),
        device=None if is_demo else getattr(engine, "device_name", settings.device),
        sample_rate=engine.sample_rate,
        capabilities=engine.capabilities(),
        message=msg,
    )


@app.post("/v1/synthesize")
def synthesize(req: SynthesizeRequest) -> Response:
    started = time.perf_counter()
    try:
        samples = engine.synthesize(req.text, req.language, req.voice, req.params)
    except ReferenceMissing as exc:
        # Backend rozpoznaje ten kod i rejestruje nagranie wzorcowe ponownie (z własnej kopii)
        raise HTTPException(409, f"REFERENCE_MISSING:{exc.reference_id}") from exc
    except EngineUnavailable as exc:
        raise HTTPException(503, str(exc)) from exc
    log.info("synthesize %d znaków → %.2fs audio w %.2fs [%s]", len(req.text), len(samples) / engine.sample_rate,
             time.perf_counter() - started, engine.mode)
    return _wav_response(samples)


@app.post("/v1/voice-design")
def voice_design(req: VoiceDesignRequest) -> Response:
    if not engine.capabilities().voice_design:
        raise HTTPException(501, "Projektowanie głosu z opisu wymaga modelu VoxCPM2 — bieżący silnik ma tylko gotowe głosy.")
    return _wav_response(engine.design(req.description, req.text, req.language, req.params))


@app.post("/v1/clone/analyze", response_model=AudioAnalysis)
async def clone_analyze(file: UploadFile = File(...)) -> AudioAnalysis:
    raw = await file.read()
    try:
        return audio.analyze(raw)
    except Exception as exc:  # noqa: BLE001
        raise HTTPException(422, f"Nie udało się odczytać pliku audio: {exc}") from exc


REFERENCE_ID = re.compile(r"^[A-Za-z0-9_-]{4,80}$")


@app.post("/v1/clone/references", response_model=CloneProfileResponse)
async def clone_reference(
    file: UploadFile = File(...),
    transcript: str = Form(""),
    consent: bool = Form(False),
    reference_id: str | None = Form(None),
    trusted: bool = Form(False),
) -> CloneProfileResponse:
    """Rejestruje nagranie referencyjne.

    reference_id — stały identyfikator (ponowna rejestracja po restarcie silnika nadpisuje plik),
    trusted — nagranie wzorcowe wygenerowane przez sam model: bez oceny jakości nagrania.
    """
    if reference_id is not None and not REFERENCE_ID.match(reference_id):
        raise HTTPException(400, "Niepoprawny identyfikator nagrania referencyjnego.")
    if not consent:
        raise HTTPException(400, "Wymagane jest potwierdzenie zgody właściciela głosu.")
    if not engine.capabilities().cloning:
        raise HTTPException(
            503,
            "Klonowanie głosu wymaga załadowanego modelu VoxCPM2. Bieżący silnik go nie obsługuje.",
        )
    raw = await file.read()
    try:
        analysis = audio.analyze(raw)
    except Exception as exc:  # noqa: BLE001
        raise HTTPException(422, f"Nie udało się odczytać pliku audio: {exc}") from exc
    if not analysis.suitable and not trusted:
        raise HTTPException(422, "Nagranie nie spełnia wymagań: " + " ".join(analysis.issues))
    mono, sr, _ = audio.read_audio(raw)
    ref_id = reference_id or f"ref_{uuid.uuid4().hex[:12]}"
    path = settings.data_dir / "references" / f"{ref_id}.wav"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(audio.to_wav_bytes(mono[: sr * 60], sr))
    if transcript.strip():
        path.with_suffix(".txt").write_text(transcript.strip(), "utf-8")
    else:
        path.with_suffix(".txt").unlink(missing_ok=True)
    engine.register_reference(ref_id, str(path), transcript.strip() or None)
    return CloneProfileResponse(reference_id=ref_id, duration_sec=analysis.duration_sec)


@app.post("/v1/encode/mp3")
async def encode_mp3(file: UploadFile = File(...), bitrate: int = Form(192)) -> Response:
    if bitrate not in {96, 128, 160, 192, 256, 320}:
        raise HTTPException(400, "Nieobsługiwany bitrate.")
    return Response(content=audio.wav_to_mp3(await file.read(), bitrate), media_type="audio/mpeg")
