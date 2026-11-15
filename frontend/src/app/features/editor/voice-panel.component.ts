import { ChangeDetectionStrategy, Component, computed, inject, output, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { VoiceSettings } from '../../core/api/models';
import { AccountStore } from '../../core/services/account.store';
import { EngineStatusService } from '../../core/services/engine-status.service';
import { VoicesStore } from '../../core/services/voices.store';
import { EMOTIONS, STYLE_PRESETS, languageLabel, languageOptions } from '../../core/util/labels';
import { SelectComponent, SelectOption } from '../../shared/select.component';
import { IconComponent } from '../../shared/icon.component';
import { EditorStore } from './editor.store';
import { DictionaryPanelComponent } from './dictionary-panel.component';
import { VoicePickerComponent } from './voice-picker.component';

/** Prawy panel: silnik, język, głos i parametry generowania projektu. */
@Component({
  selector: 'vc-voice-panel',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [IconComponent, VoicePickerComponent, RouterLink, SelectComponent, DictionaryPanelComponent],
  styleUrl: './voice-panel.component.scss',
  templateUrl: './voice-panel.component.html',
})
export class VoicePanelComponent {
  protected store = inject(EditorStore);
  protected engine = inject(EngineStatusService);
  private voices = inject(VoicesStore);
  protected account = inject(AccountStore);
  protected advancedAllowed = computed(() => this.account.plan()?.advancedControls ?? true);
  protected advancedPlan = computed(() => this.account.lowestPlan((p) => p.advancedControls)?.name ?? '');
  readonly close = output<void>();

  protected advancedOpen = signal(false);
  protected EMOTIONS = EMOTIONS;
  protected STYLE_PRESETS = STYLE_PRESETS;
  protected languageLabel = languageLabel;
  protected languageOptions = computed(() => languageOptions(this.engine.languages()));
  protected engineOptions: SelectOption[] = [{ value: 'voxcpm2', label: 'VoxCPM2', hint: 'OpenBMB · synteza, projektowanie i klonowanie głosu' }];

  protected s = computed(() => this.store.settings());
  protected caps = computed(() => this.engine.caps());
  protected readyCount = computed(() => this.store.segments().filter((x) => x.status === 'ready' || x.status === 'stale').length);
  protected voiceLangMismatch = computed(() => {
    const v = this.voices.get(this.s()?.voiceId);
    const lang = this.s()?.language;
    return !!v && !!lang && v.kind === 'LIBRARY' && !v.languages.includes(lang);
  });

  set<K extends keyof VoiceSettings>(key: K, value: VoiceSettings[K]) {
    this.store.updateSettings({ [key]: value } as Partial<VoiceSettings>);
  }

  num(e: Event) {
    return parseFloat((e.target as HTMLInputElement).value);
  }

  pct(value: number, min: number, max: number) {
    return ((value - min) / (max - min)) * 100;
  }

  /** Domyślna instrukcja wybranego głosu (np. „Relaks”), używana, gdy projekt nie ustawia stylu ani emocji. */
  protected voiceInstruction = computed(() => this.voices.get(this.s()?.voiceId)?.defaultInstruction ?? null);

  toggleEmotion(e: string) {
    this.set('emotion', this.s()?.emotion === e ? null : e);
  }

  applyPreset(value: string) {
    this.set('style', this.s()?.style === value ? null : value);
  }

  randomSeed() {
    this.set('seed', Math.floor(Math.random() * 1_000_000));
  }

  resetAdvanced() {
    this.store.updateSettings({ cfgValue: 2, inferenceTimesteps: 10, normalize: true, denoise: false, seed: null });
  }

  speedLabel(v: number) {
    if (v < 0.9) return 'Wolniej';
    if (v > 1.1) return 'Szybciej';
    return 'Normalnie';
  }
}
