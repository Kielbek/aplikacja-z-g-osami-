import { ChangeDetectionStrategy, Component, WritableSignal, computed, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { ApiService } from '../../core/api/api.service';
import { DesignPreview, Gender } from '../../core/api/models';
import { AccountStore } from '../../core/services/account.store';
import { EngineStatusService } from '../../core/services/engine-status.service';
import { PlayerService, PlayerTrack } from '../../core/services/player.service';
import { ToastService } from '../../core/services/toast.service';
import { VoicesStore } from '../../core/services/voices.store';
import { formatDuration } from '../../core/util/format';
import { CATEGORIES, languageLabel, languageOptions } from '../../core/util/labels';
import { SelectComponent } from '../../shared/select.component';
import { EngineBannerComponent } from '../../shared/engine-banner.component';
import { IconComponent } from '../../shared/icon.component';
import { PlayButtonComponent } from '../../shared/play-button.component';
import { WaveformComponent } from '../../shared/waveform.component';

interface Variant extends DesignPreview {
  n: number;
  description: string;
}

const TIMBRES = ['niski', 'średni', 'wysoki', 'ciepły', 'chłodny', 'aksamitny', 'jasny', 'chropowaty', 'oddechowy', 'dźwięczny'];
const EXAMPLE = 'Męski głos, około 35 lat, niski, spokojny, profesjonalny, naturalny, idealny do narracji dokumentalnych.';

@Component({
  selector: 'vc-voice-design-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, IconComponent, PlayButtonComponent, WaveformComponent, EngineBannerComponent, SelectComponent],
  styleUrl: './voice-design.page.scss',
  templateUrl: './voice-design.page.html',
})
export class VoiceDesignPage {
  private api = inject(ApiService);
  private router = inject(Router);
  private toast = inject(ToastService);
  private voices = inject(VoicesStore);
  protected engine = inject(EngineStatusService);
  protected player = inject(PlayerService);
  protected account = inject(AccountStore);

  protected TIMBRES = TIMBRES;
  protected CATEGORIES = CATEGORIES;
  protected languageLabel = languageLabel;
  protected languageOpts = computed(() => languageOptions(this.engine.languages()));
  protected fmt = formatDuration;

  // Opis
  protected freeText = signal('');
  protected gender = signal<Gender | null>(null);
  protected useAge = signal(false);
  protected age = signal(35);
  protected timbres = signal<string[]>([]);
  protected characters = signal<string[]>([]);
  protected pace = signal<'wolne' | 'umiarkowane' | 'szybkie' | null>(null);

  // Próbka
  protected language = signal('pl');
  protected sampleText = signal('Dzień dobry. To jest próbka nowego głosu przygotowanego w VoiceCraft AI. Brzmi naturalnie, spokojnie i wyraźnie.');
  protected expressiveness = signal(0.5);

  // Wyniki
  protected variants = signal<Variant[]>([]);
  protected selected = signal<string | null>(null);
  protected generating = signal(false);
  protected name = signal('');
  protected saving = signal(false);

  protected description = computed(() => {
    const parts: string[] = [];
    const g = this.gender();
    if (g) parts.push(g === 'female' ? 'Kobiecy głos' : g === 'male' ? 'Męski głos' : 'Neutralny głos');
    if (this.useAge()) parts.push(`około ${this.age()} lat`);
    parts.push(...this.timbres());
    parts.push(...this.characters().map((c) => CATEGORIES.find((x) => x.id === c)!.label.toLowerCase()));
    if (this.pace()) parts.push(`tempo ${this.pace()}`);
    const structured = parts.join(', ');
    const free = this.freeText().trim();
    if (!structured) return free;
    const head = structured.charAt(0).toUpperCase() + structured.slice(1);
    return free ? `${head}. ${free}` : `${head}.`;
  });

  /** Piper ma tylko gotowe głosy — projektowanie z opisu wymaga VoxCPM2 (tryb demo pozwala przetestować przepływ). */
  protected designAvailable = computed(() => this.engine.caps()?.voice_design !== false);
  protected canGenerate = computed(
    () => this.description().length >= 10 && this.sampleText().trim().length > 0 && !this.generating() && !this.engine.isOffline() && this.designAvailable(),
  );
  protected selectedVariant = computed(() => this.variants().find((v) => v.previewId === this.selected()) ?? null);
  protected canSave = computed(
    () => !!this.selectedVariant() && this.name().trim().length > 0 && !this.saving() && !this.account.customVoiceLimitReached(),
  );

  useExample() {
    this.freeText.set(EXAMPLE);
  }

  toggle(list: WritableSignal<string[]>, value: string) {
    list.update((l) => (l.includes(value) ? l.filter((x) => x !== value) : [...l, value]));
  }

  track(v: Variant): PlayerTrack {
    return { id: `design:${v.previewId}`, title: `Wariant ${v.n}`, subtitle: 'Projekt głosu', url: this.api.designPreviewUrl(v.previewId) };
  }

  isActive(v: Variant) {
    return this.player.isActive(`design:${v.previewId}`);
  }

  async generate() {
    if (!this.canGenerate()) return;
    this.generating.set(true);
    try {
      const desc = this.description();
      const res = await this.api.designPreview({
        description: desc,
        text: this.sampleText().trim(),
        language: this.language(),
        expressiveness: this.expressiveness(),
        seed: Math.floor(Math.random() * 1_000_000),
      });
      const v: Variant = { ...res, n: this.variants().length + 1, description: desc };
      this.variants.update((list) => [v, ...list]);
      this.selected.set(v.previewId);
      void this.account.refreshUsage();
      void this.player.play(this.track(v));
    } catch (e) {
      this.toast.apiError('Nie udało się wygenerować próbki', e);
    } finally {
      this.generating.set(false);
    }
  }

  async save() {
    const v = this.selectedVariant();
    if (!v || !this.canSave()) return;
    this.saving.set(true);
    try {
      const voice = await this.api.saveDesignedVoice({
        name: this.name().trim(),
        description: v.description,
        gender: this.gender(),
        age: this.useAge() ? this.age() : null,
        categories: this.characters(),
        traits: this.timbres(),
        language: this.language(),
        previewId: v.previewId,
        sampleText: this.sampleText().trim(),
        engineMode: v.engineMode,
      });
      this.voices.upsert(voice);
      void this.account.refreshUsage();
      this.toast.success(`Zapisano głos „${voice.name}”`, 'Znajdziesz go w sekcji „Moje głosy” i w edytorze.');
      void this.router.navigate(['/voices', voice.id]);
    } catch (e) {
      this.toast.apiError('Nie udało się zapisać głosu', e);
    } finally {
      this.saving.set(false);
    }
  }
}
