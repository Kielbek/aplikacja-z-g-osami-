"""Kontrakt API serwisu silnika. Spring Boot (backend) korzysta wyłącznie z tych modeli."""
from __future__ import annotations

from typing import Literal, Optional

from pydantic import BaseModel, Field

EngineMode = Literal["voxcpm2", "piper", "demo"]


class Capabilities(BaseModel):
    voice_design: bool
    cloning: bool
    style_prompt: bool
    emotion: bool
    speed: bool
    languages: list[str]


class HealthResponse(BaseModel):
    status: Literal["ok", "loading", "error"]
    mode: EngineMode
    model: Optional[str] = None
    device: Optional[str] = None
    sample_rate: int
    capabilities: Capabilities
    message: Optional[str] = None


class VoiceSpec(BaseModel):
    """Głos do syntezy. Głos biblioteczny/zaprojektowany = opis tekstowy,
    głos sklonowany = identyfikator nagrania referencyjnego."""

    id: str
    description: Optional[str] = None
    reference_id: Optional[str] = None
    # Podpowiedzi wykorzystywane wyłącznie przez syntezator demonstracyjny
    gender: Optional[Literal["female", "male", "neutral"]] = None
    age: Optional[int] = None


class GenerationParams(BaseModel):
    style: Optional[str] = Field(None, description="Instrukcja sposobu mówienia, np. 'spokojnie, ciepło'")
    emotion: Optional[str] = None
    speed: float = Field(1.0, ge=0.5, le=2.0)
    expressiveness: float = Field(0.5, ge=0.0, le=1.0)
    cfg_value: float = Field(2.0, ge=0.5, le=5.0)
    inference_timesteps: int = Field(10, ge=4, le=50)
    normalize: bool = True
    denoise: bool = False
    seed: Optional[int] = None


class SynthesizeRequest(BaseModel):
    text: str = Field(..., min_length=1, max_length=5000)
    language: str = "pl"
    voice: VoiceSpec
    params: GenerationParams = GenerationParams()


class VoiceDesignRequest(BaseModel):
    description: str = Field(..., min_length=3, max_length=1000)
    text: str = Field(..., min_length=1, max_length=1000)
    language: str = "pl"
    params: GenerationParams = GenerationParams()


class AudioAnalysis(BaseModel):
    duration_sec: float
    sample_rate: int
    channels: int
    peak_dbfs: float
    rms_dbfs: float
    clipping_ratio: float
    silence_ratio: float
    estimated_snr_db: float
    issues: list[str]
    suitable: bool


class CloneProfileResponse(BaseModel):
    reference_id: str
    duration_sec: float
