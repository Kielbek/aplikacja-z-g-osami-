"""Narzędzia audio: kodowanie WAV/MP3 i rzeczywista analiza nagrań referencyjnych."""
from __future__ import annotations

import io

import lameenc
import numpy as np
import soundfile as sf

from .schemas import AudioAnalysis


def to_wav_bytes(samples: np.ndarray, sample_rate: int) -> bytes:
    buf = io.BytesIO()
    clipped = np.clip(samples.astype(np.float32), -1.0, 1.0)
    sf.write(buf, clipped, sample_rate, format="WAV", subtype="PCM_16")
    return buf.getvalue()


def wav_to_mp3(wav_bytes: bytes, bitrate: int = 192) -> bytes:
    data, sr = sf.read(io.BytesIO(wav_bytes), dtype="int16", always_2d=True)
    encoder = lameenc.Encoder()
    encoder.set_bit_rate(bitrate)
    encoder.set_in_sample_rate(sr)
    encoder.set_channels(data.shape[1])
    encoder.set_quality(2)
    return bytes(encoder.encode(data.tobytes()) + encoder.flush())


def read_audio(raw: bytes) -> tuple[np.ndarray, int, int]:
    """Zwraca (mono float32, sample_rate, liczba kanałów źródła)."""
    data, sr = sf.read(io.BytesIO(raw), dtype="float32", always_2d=True)
    channels = data.shape[1]
    return data.mean(axis=1), sr, channels


def _db(x: float) -> float:
    return float(20 * np.log10(max(x, 1e-9)))


def analyze(raw: bytes) -> AudioAnalysis:
    mono, sr, channels = read_audio(raw)
    duration = len(mono) / sr if sr else 0.0
    peak = float(np.max(np.abs(mono))) if len(mono) else 0.0
    rms = float(np.sqrt(np.mean(mono**2))) if len(mono) else 0.0
    clipping = float(np.mean(np.abs(mono) >= 0.999)) if len(mono) else 0.0

    # Energia w ramkach 30 ms – oszacowanie ciszy i SNR (szum = 10. percentyl)
    frame = max(int(sr * 0.03), 1)
    n = len(mono) // frame
    if n > 0:
        frames = mono[: n * frame].reshape(n, frame)
        energy = np.sqrt(np.mean(frames**2, axis=1)) + 1e-9
        noise = max(float(np.percentile(energy, 10)), 10 ** (-80 / 20))
        speech = float(np.percentile(energy, 90))
        silence_ratio = float(np.mean(energy < max(noise * 2.0, 10 ** (-50 / 20))))
        snr = _db(speech) - _db(noise)
    else:
        silence_ratio, snr = 1.0, 0.0

    issues: list[str] = []
    if duration < 5:
        issues.append("Nagranie jest za krótkie — potrzeba co najmniej 5 sekund (zalecane 10–30 s).")
    if duration > 60:
        issues.append("Nagranie jest dłuższe niż 60 s — zostanie użyty jego początkowy fragment.")
    if sr < 16000:
        issues.append("Niska częstotliwość próbkowania (< 16 kHz) obniży jakość klonu.")
    if clipping > 0.001:
        issues.append("Wykryto przesterowanie (clipping). Nagraj ponownie z niższym poziomem.")
    if _db(rms) < -35:
        issues.append("Nagranie jest bardzo ciche.")
    if snr < 20:
        issues.append("Wysoki poziom szumu tła — wybierz cichsze pomieszczenie.")
    if silence_ratio > 0.5:
        issues.append("Ponad połowa nagrania to cisza.")

    blocking = duration < 5 or clipping > 0.01
    return AudioAnalysis(
        duration_sec=round(duration, 2),
        sample_rate=sr,
        channels=channels,
        peak_dbfs=round(_db(peak), 1),
        rms_dbfs=round(_db(rms), 1),
        clipping_ratio=round(clipping, 5),
        silence_ratio=round(silence_ratio, 3),
        estimated_snr_db=round(snr, 1),
        issues=issues,
        suitable=not blocking,
    )
