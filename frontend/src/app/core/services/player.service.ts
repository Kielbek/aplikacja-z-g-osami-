import { Injectable, computed, inject, signal } from '@angular/core';
import { ApiService } from '../api/api.service';
import { ApiError } from '../api/models';
import { ToastService } from './toast.service';

export interface PlayerTrack {
  /** Stabilny identyfikator, np. "seg:<id>", "project:<id>", "voice:<id>". */
  id: string;
  title: string;
  subtitle?: string;
  /** Adres API z plikiem audio (lub null przy lokalnym pliku). */
  url: string | null;
  /** Znaczniki granic fragmentów (0..1) — rysowane na waveformie. */
  markers?: number[];
  downloadName?: string;
}

interface CachedAudio {
  objectUrl: string;
  peaks: number[];
  duration: number;
  engineMode: string | null;
}

const PEAK_COUNT = 900;

/**
 * Globalny odtwarzacz audio. Ładuje plik, liczy waveform (Web Audio API)
 * i udostępnia stan przez sygnały. Jeden odtwarzacz na całą aplikację.
 */
@Injectable({ providedIn: 'root' })
export class PlayerService {
  private api = inject(ApiService);
  private toast = inject(ToastService);
  private audio = new Audio();
  private cache = new Map<string, CachedAudio>();
  private ctx: AudioContext | null = null;
  private loadSeq = 0;
  private raf = 0;
  /** Wywoływane po dojściu nagrania do końca (np. kolejny fragment w odsłuchu na bieżąco). */
  private onEnded: (() => void) | null = null;

  readonly track = signal<PlayerTrack | null>(null);
  readonly status = signal<'idle' | 'loading' | 'ready' | 'error'>('idle');
  readonly playing = signal(false);
  readonly currentTime = signal(0);
  readonly duration = signal(0);
  readonly peaks = signal<number[]>([]);
  readonly engineMode = signal<string | null>(null);
  readonly volume = signal(this.readVolume());
  readonly muted = signal(false);

  readonly progress = computed(() => (this.duration() > 0 ? this.currentTime() / this.duration() : 0));
  readonly isDemo = computed(() => this.engineMode() === 'demo');

  constructor() {
    this.audio.preload = 'auto';
    this.audio.volume = this.volume();
    this.audio.addEventListener('play', () => {
      this.playing.set(true);
      this.tick();
    });
    this.audio.addEventListener('pause', () => {
      this.playing.set(false);
      cancelAnimationFrame(this.raf);
    });
    this.audio.addEventListener('ended', () => {
      this.playing.set(false);
      this.currentTime.set(this.duration());
      const next = this.onEnded;
      this.onEnded = null;
      next?.();
    });
    this.audio.addEventListener('loadedmetadata', () => {
      if (Number.isFinite(this.audio.duration)) this.duration.set(this.audio.duration);
    });
    this.audio.addEventListener('timeupdate', () => this.currentTime.set(this.audio.currentTime));
  }

  isActive(id: string) {
    return this.track()?.id === id;
  }

  isPlaying(id: string) {
    return this.track()?.id === id && this.playing();
  }

  isLoading(id: string) {
    return this.track()?.id === id && this.status() === 'loading';
  }

  /** Ładuje i odtwarza utwór. Ponowne wywołanie dla tego samego utworu przełącza play/pauza. */
  async play(track: PlayerTrack, opts: { autoplay?: boolean; force?: boolean; onEnded?: () => void } = {}) {
    const autoplay = opts.autoplay ?? true;
    // Nowy utwór kasuje kontynuację poprzedniego (np. użytkownik włączył inny fragment)
    this.onEnded = opts.onEnded ?? null;
    if (!opts.force && this.track()?.id === track.id && this.track()?.url === track.url && this.status() === 'ready') {
      this.track.set(track);
      this.toggle();
      return;
    }
    if (!track.url) return;
    const seq = ++this.loadSeq;
    this.audio.pause();
    this.track.set(track);
    this.status.set('loading');
    this.currentTime.set(0);
    this.peaks.set([]);
    try {
      let cached = opts.force ? undefined : this.cache.get(track.url);
      if (!cached) {
        const res = await this.api.audio(track.url);
        cached = await this.prepare(res.blob, res.engineMode);
        this.cache.set(track.url, cached);
      }
      if (seq !== this.loadSeq) return;
      this.mount(cached, autoplay);
    } catch (e) {
      if (seq !== this.loadSeq) return;
      this.status.set('error');
      this.toast.error('Nie udało się wczytać nagrania', (e as ApiError).message);
    }
  }

  /** Odtwarza lokalny plik (np. nagranie referencyjne przed wysłaniem). */
  async playBlob(blob: Blob, track: Omit<PlayerTrack, 'url'>, autoplay = true) {
    this.onEnded = null;
    const seq = ++this.loadSeq;
    this.audio.pause();
    this.track.set({ ...track, url: null });
    this.status.set('loading');
    try {
      const cached = await this.prepare(blob, null);
      if (seq !== this.loadSeq) return;
      this.mount(cached, autoplay);
    } catch {
      this.status.set('error');
      this.toast.error('Nie można odtworzyć pliku', 'Przeglądarka nie rozpoznała formatu audio.');
    }
  }

  toggle() {
    if (this.status() !== 'ready') return;
    if (this.audio.paused) {
      if (this.audio.ended || this.currentTime() >= this.duration() - 0.05) this.audio.currentTime = 0;
      void this.audio.play();
    } else {
      this.audio.pause();
    }
  }

  pause() {
    this.audio.pause();
  }

  seek(fraction: number) {
    if (!this.duration()) return;
    const t = Math.max(0, Math.min(1, fraction)) * this.duration();
    this.audio.currentTime = t;
    this.currentTime.set(t);
  }

  skip(seconds: number) {
    if (!this.duration()) return;
    this.seek((this.audio.currentTime + seconds) / this.duration());
  }

  setVolume(v: number) {
    const vol = Math.max(0, Math.min(1, v));
    this.audio.volume = vol;
    this.volume.set(vol);
    if (vol > 0 && this.muted()) this.toggleMute();
    try {
      localStorage.setItem('vc.volume', String(vol));
    } catch {
      /* ignoruj */
    }
  }

  toggleMute() {
    this.audio.muted = !this.audio.muted;
    this.muted.set(this.audio.muted);
  }

  download() {
    const t = this.track();
    if (!t || !this.audio.src) return;
    const a = document.createElement('a');
    a.href = this.audio.src;
    a.download = (t.downloadName ?? t.title.replace(/[^\p{L}\p{N}]+/gu, '-')) + (this.isDemo() ? '_DEMO' : '') + '.wav';
    a.click();
  }

  /** Usuwa z pamięci podręcznej (np. po ponownym wygenerowaniu). */
  invalidate(urlPrefix: string) {
    for (const [key, val] of this.cache) {
      if (key.startsWith(urlPrefix)) {
        URL.revokeObjectURL(val.objectUrl);
        this.cache.delete(key);
      }
    }
  }

  close() {
    this.audio.pause();
    this.audio.removeAttribute('src');
    this.track.set(null);
    this.status.set('idle');
    this.peaks.set([]);
    this.duration.set(0);
    this.currentTime.set(0);
  }

  // ---------------------------------------------------------------------

  private mount(c: CachedAudio, autoplay: boolean) {
    this.audio.src = c.objectUrl;
    this.duration.set(c.duration);
    this.peaks.set(c.peaks);
    this.engineMode.set(c.engineMode);
    this.status.set('ready');
    if (autoplay) void this.audio.play().catch(() => this.playing.set(false));
  }

  private async prepare(blob: Blob, engineMode: string | null): Promise<CachedAudio> {
    const objectUrl = URL.createObjectURL(blob);
    let peaks: number[] = [];
    let duration = 0;
    try {
      this.ctx ??= new AudioContext();
      const buffer = await this.ctx.decodeAudioData(await blob.arrayBuffer());
      duration = buffer.duration;
      peaks = computePeaks(buffer.getChannelData(0), PEAK_COUNT);
    } catch {
      /* format nieobsługiwany przez Web Audio — odtwarzanie nadal możliwe */
    }
    return { objectUrl, peaks, duration, engineMode };
  }

  private tick = () => {
    this.currentTime.set(this.audio.currentTime);
    if (!this.audio.paused) this.raf = requestAnimationFrame(this.tick);
  };

  private readVolume() {
    try {
      const v = parseFloat(localStorage.getItem('vc.volume') ?? '0.9');
      return Number.isFinite(v) ? v : 0.9;
    } catch {
      return 0.9;
    }
  }
}

export function computePeaks(data: Float32Array, count: number): number[] {
  const size = Math.max(1, Math.floor(data.length / count));
  const peaks: number[] = [];
  let max = 0;
  for (let i = 0; i < count; i++) {
    let peak = 0;
    const start = i * size;
    const end = Math.min(start + size, data.length);
    for (let j = start; j < end; j++) {
      const v = Math.abs(data[j]);
      if (v > peak) peak = v;
    }
    peaks.push(peak);
    if (peak > max) max = peak;
  }
  return max > 0 ? peaks.map((p) => p / max) : peaks;
}
