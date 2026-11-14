import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { ApiService } from '../../core/api/api.service';
import { ApiError, AudioAnalysis } from '../../core/api/models';
import { AccountStore } from '../../core/services/account.store';
import { EngineStatusService } from '../../core/services/engine-status.service';
import { PlayerService } from '../../core/services/player.service';
import { ToastService } from '../../core/services/toast.service';
import { VoicesStore } from '../../core/services/voices.store';
import { formatSeconds } from '../../core/util/format';
import { languageLabel } from '../../core/util/labels';
import { IconComponent } from '../../shared/icon.component';
import { WaveformComponent } from '../../shared/waveform.component';

const ACCEPT = ['audio/wav', 'audio/x-wav', 'audio/wave', 'audio/mpeg', 'audio/mp3', 'audio/flac', 'audio/x-flac', 'audio/ogg'];
const MAX_MB = 50;

@Component({
  selector: 'vc-voice-clone-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, IconComponent, WaveformComponent],
  styleUrl: './voice-clone.page.scss',
  templateUrl: './voice-clone.page.html',
})
export class VoiceClonePage {
  private api = inject(ApiService);
  private router = inject(Router);
  private toast = inject(ToastService);
  private voices = inject(VoicesStore);
  protected engine = inject(EngineStatusService);
  protected player = inject(PlayerService);
  protected account = inject(AccountStore);
  protected planAllows = computed(() => this.account.plan()?.voiceCloning ?? true);
  protected cloningPlan = computed(() => this.account.lowestPlan((p) => p.voiceCloning)?.name ?? 'Pro');

  protected file = signal<File | null>(null);
  protected dragOver = signal(false);
  protected analysis = signal<AudioAnalysis | null>(null);
  protected analyzing = signal(false);
  protected analysisError = signal<string | null>(null);
  protected consent = signal(false);
  protected name = signal('');
  protected transcript = signal('');
  protected language = signal('pl');
  protected creating = signal(false);
  protected createError = signal<string | null>(null);

  protected languageLabel = languageLabel;
  protected fmt = formatSeconds;

  protected cloningAvailable = computed(() => this.engine.caps()?.cloning === true);
  protected step = computed(() => {
    if (!this.file()) return 1;
    if (!this.analysis()) return 2;
    if (!this.consent()) return 3;
    return 4;
  });
  protected canCreate = computed(
    () => this.planAllows() && !this.account.customVoiceLimitReached() && this.cloningAvailable() && !!this.analysis()?.suitable && this.consent() && this.name().trim().length > 0 && !this.creating(),
  );
  protected blockReason = computed(() => {
    if (!this.planAllows()) return `Klonowanie jest dostępne od planu ${this.cloningPlan()}.`;
    if (this.account.customVoiceLimitReached()) return `Osiągnięto limit własnych głosów w planie ${this.account.plan()?.name}.`;
    if (!this.cloningAvailable()) {
      return this.engine.isOffline()
        ? 'Silnik TTS jest niedostępny.'
        : 'Klonowanie wymaga załadowanego modelu VoxCPM2 — silnik działa w trybie demonstracyjnym.';
    }
    if (!this.file()) return 'Dodaj nagranie referencyjne.';
    if (!this.analysis()) return 'Poczekaj na analizę nagrania.';
    if (!this.analysis()!.suitable) return 'Nagranie nie spełnia wymagań.';
    if (!this.consent()) return 'Potwierdź zgodę właściciela głosu.';
    if (!this.name().trim()) return 'Nadaj nazwę głosowi.';
    return null;
  });
  protected localTrackId = computed(() => `upload:${this.file()?.name}:${this.file()?.size}`);
  protected localActive = computed(() => this.player.isActive(this.localTrackId()));

  onDrop(e: DragEvent) {
    e.preventDefault();
    this.dragOver.set(false);
    const f = e.dataTransfer?.files?.[0];
    if (f) void this.setFile(f);
  }

  onPick(e: Event) {
    const f = (e.target as HTMLInputElement).files?.[0];
    if (f) void this.setFile(f);
    (e.target as HTMLInputElement).value = '';
  }

  async setFile(f: File) {
    if (f.type && !ACCEPT.includes(f.type) && !/\.(wav|mp3|flac|ogg)$/i.test(f.name)) {
      this.toast.error('Nieobsługiwany format pliku', 'Użyj WAV, MP3, FLAC lub OGG.');
      return;
    }
    if (f.size > MAX_MB * 1024 * 1024) {
      this.toast.error('Plik jest za duży', `Maksymalny rozmiar to ${MAX_MB} MB.`);
      return;
    }
    this.file.set(f);
    this.analysis.set(null);
    this.analysisError.set(null);
    this.createError.set(null);
    if (!this.name()) this.name.set(f.name.replace(/\.[^.]+$/, '').replace(/[_-]+/g, ' ').slice(0, 60));
    void this.player.playBlob(f, { id: this.localTrackId(), title: f.name, subtitle: 'Nagranie referencyjne' }, false);
    await this.analyze();
  }

  async analyze() {
    const f = this.file();
    if (!f) return;
    this.analyzing.set(true);
    try {
      this.analysis.set(await this.api.analyzeReference(f));
    } catch (e) {
      this.analysisError.set((e as ApiError).message);
    } finally {
      this.analyzing.set(false);
    }
  }

  removeFile() {
    if (this.localActive()) this.player.close();
    this.file.set(null);
    this.analysis.set(null);
    this.analysisError.set(null);
  }

  playLocal() {
    const f = this.file();
    if (!f) return;
    if (this.localActive() && this.player.status() === 'ready') this.player.toggle();
    else void this.player.playBlob(f, { id: this.localTrackId(), title: f.name, subtitle: 'Nagranie referencyjne' });
  }

  async create() {
    const f = this.file();
    if (!f || !this.canCreate()) return;
    this.creating.set(true);
    this.createError.set(null);
    try {
      const voice = await this.api.createClone(f, {
        name: this.name().trim(),
        transcript: this.transcript().trim(),
        language: this.language(),
        consent: this.consent(),
      });
      this.voices.upsert(voice);
      void this.account.refreshUsage();
      this.toast.success(`Utworzono profil głosu „${voice.name}”`);
      void this.router.navigate(['/voices', voice.id]);
    } catch (e) {
      this.createError.set((e as ApiError).message);
    } finally {
      this.creating.set(false);
    }
  }

  check(ok: boolean, warn = false) {
    return ok ? 'ok' : warn ? 'warn' : 'bad';
  }

  sizeLabel(bytes: number) {
    return bytes > 1024 * 1024 ? `${(bytes / 1024 / 1024).toFixed(1)} MB` : `${Math.round(bytes / 1024)} KB`;
  }
}
