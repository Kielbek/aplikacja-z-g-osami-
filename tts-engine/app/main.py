"""VoiceCraft AI — serwis silnika TTS (VoxCPM2).

Uruchomienie:  uvicorn app.main:app --port 8808
"""
from __future__ import annotations

import logging
import time
import uuid

import numpy as np
from fastapi import FastAPI, File, Form, HTTPException, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import Response

from . import audio
from .config import settings
from .engines.base import EngineUnavailable, TTSEngine
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


def _load_engine() -> TTSEngine:
    global load_error
    if settings.engine_mode == "demo":
        log.warning("ENGINE_MODE=demo — model VoxCPM2 nie będzie ładowany.")
        return DemoEngine(settings.demo_sample_rate, settings.demo_latency_ms)
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
        log.warning("Nie udało się załadować VoxCPM2 (%s). Przechodzę w tryb DEMO.", load_error)
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
    return HealthResponse(
        status="ok",
        mode=engine.mode,  # type: ignore[arg-type]
        model=None if is_demo else settings.model_id,
        device=None if is_demo else settings.device,
        sample_rate=engine.sample_rate,
        capabilities=engine.capabilities(),
        message=msg,
    )


@app.post("/v1/synthesize")
def synthesize(req: SynthesizeRequest) -> Response:
    started = time.perf_counter()
    try:
        samples = engine.synthesize(req.text, req.language, req.voice, req.params)
    except EngineUnavailable as exc:
        raise HTTPException(503, str(exc)) from exc
    log.info("synthesize %d znaków → %.2fs audio w %.2fs [%s]", len(req.text), len(samples) / engine.sample_rate,
             time.perf_counter() - started, engine.mode)
    return _wav_response(samples)


@app.post("/v1/voice-design")
def voice_design(req: VoiceDesignRequest) -> Response:
    if not engine.capabilities().voice_design:
        raise HTTPException(501, "Silnik nie obsługuje projektowania głosu.")
    return _wav_response(engine.design(req.description, req.text, req.language, req.params))


@app.post("/v1/clone/analyze", response_model=AudioAnalysis)
async def clone_analyze(file: UploadFile = File(...)) -> AudioAnalysis:
    raw = await file.read()
    try:
        return audio.analyze(raw)
    except Exception as exc:  # noqa: BLE001
        raise HTTPException(422, f"Nie udało się odczytać pliku audio: {exc}") from exc


@app.post("/v1/clone/references", response_model=CloneProfileResponse)
async def clone_reference(
    file: UploadFile = File(...),
    transcript: str = Form(""),
    consent: bool = Form(False),
) -> CloneProfileResponse:
    if not consent:
        raise HTTPException(400, "Wymagane jest potwierdzenie zgody właściciela głosu.")
    if not engine.capabilities().cloning:
        raise HTTPException(
            503,
            "Klonowanie głosu wymaga załadowanego modelu VoxCPM2. Silnik działa w trybie demonstracyjnym.",
        )
    raw = await file.read()
    analysis = audio.analyze(raw)
    if not analysis.suitable:
        raise HTTPException(422, "Nagranie nie spełnia wymagań: " + " ".join(analysis.issues))
    mono, sr, _ = audio.read_audio(raw)
    ref_id = f"ref_{uuid.uuid4().hex[:12]}"
    path = settings.data_dir / "references" / f"{ref_id}.wav"
    path.write_bytes(audio.to_wav_bytes(mono[: sr * 60], sr))
    if transcript.strip():
        path.with_suffix(".txt").write_text(transcript.strip(), "utf-8")
    engine.register_reference(ref_id, str(path), transcript.strip() or None)
    return CloneProfileResponse(reference_id=ref_id, duration_sec=analysis.duration_sec)


@app.post("/v1/encode/mp3")
async def encode_mp3(file: UploadFile = File(...), bitrate: int = Form(192)) -> Response:
    if bitrate not in {96, 128, 160, 192, 256, 320}:
        raise HTTPException(400, "Nieobsługiwany bitrate.")
    return Response(content=audio.wav_to_mp3(await file.read(), bitrate), media_type="audio/mpeg")
