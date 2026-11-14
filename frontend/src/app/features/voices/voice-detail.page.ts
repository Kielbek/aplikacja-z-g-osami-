import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { ApiService } from '../../core/api/api.service';
import { ApiError, Voice } from '../../core/api/models';
import { AccountStore } from '../../core/services/account.store';
import { ConfirmService } from '../../core/services/confirm.service';
import { EngineStatusService } from '../../core/services/engine-status.service';
import { PlayerService } from '../../core/services/player.service';
import { ToastService } from '../../core/services/toast.service';
import { VoicesStore } from '../../core/services/voices.store';
import { formatDate, formatSeconds } from '../../core/util/format';
import { categoryLabel, genderLabel, languageLabel } from '../../core/util/labels';
import { IconComponent } from '../../shared/icon.component';
import { VoiceAvatarComponent } from '../../shared/voice-avatar.component';
import { WaveformComponent } from '../../shared/waveform.component';
import { VoiceActions } from './voice-actions';

@Component({
  selector: 'vc-voice-detail-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, IconComponent, VoiceAvatarComponent, WaveformComponent],
  styleUrl: './voice-detail.page.scss',
  templateUrl: './voice-detail.page.html',
})
export class VoiceDetailPage {
  private api = inject(ApiService);
  private router = inject(Router);
  private toast = inject(ToastService);
  private confirm = inject(ConfirmService);
  private store = inject(VoicesStore);
  protected account = inject(AccountStore);
  protected engine = inject(EngineStatusService);
  protected player = inject(PlayerService);
  protected actions = inject(VoiceActions);

  readonly id = input.required<string>();
  protected voice = signal<Voice | null>(null);
  protected error = signal<string | null>(null);
  protected editing = signal(false);
  protected draftName = signal('');
  protected draftDescription = signal('');
  protected showPrompt = signal(false);

  protected categoryLabel = categoryLabel;
  protected languageLabel = languageLabel;
  protected genderLabel = genderLabel;
  protected formatDate = formatDate;
  protected fmt = formatSeconds;

  protected track = computed(() => (this.voice() ? this.actions.sampleTrack(this.voice()!) : null));
  protected active = computed(() => !!this.track() && this.player.isActive(this.track()!.id));
  protected isDefault = computed(() => this.account.account()?.defaultVoiceId === this.voice()?.id);
  protected kindLabel = computed(() => {
    const k = this.voice()?.kind;
    return k === 'DESIGNED' ? 'Zaprojektowany z opisu' : k === 'CLONED' ? 'Sklonowany z nagrania' : 'Biblioteka VoiceCraft';
  });

  constructor() {
    effect(() => void this.load(this.id()));
    // Synchronizacja z listą (np. zmiana ulubionych)
    effect(() => {
      const fromStore = this.store.get(this.id());
      if (fromStore) this.voice.set(fromStore);
    });
  }

  async load(id: string) {
    this.error.set(null);
    try {
      this.voice.set(await this.api.voice(id));
    } catch (e) {
      this.error.set((e as ApiError).message);
    }
  }

  playSample() {
    const t = this.track();
    if (t) void this.player.play(t);
  }

  async setDefault() {
    const v = this.voice();
    if (!v) return;
    try {
      await this.account.update({ defaultVoiceId: v.id, defaultLanguage: v.languages[0] ?? 'pl' });
      this.toast.success(`„${v.name}” będzie domyślnym głosem nowych projektów`);
    } catch (e) {
      this.toast.apiError('Nie zapisano ustawienia', e);
    }
  }

  startEdit() {
    const v = this.voice()!;
    this.draftName.set(v.name);
    this.draftDescription.set(v.description);
    this.editing.set(true);
  }

  async saveEdit() {
    try {
      const v = await this.api.updateVoice(this.id(), { name: this.draftName(), description: this.draftDescription() });
      this.store.upsert(v);
      this.voice.set(v);
      this.editing.set(false);
      this.toast.success('Zapisano zmiany');
    } catch (e) {
      this.toast.apiError('Nie zapisano zmian', e);
    }
  }

  async remove() {
    const v = this.voice();
    if (!v) return;
    const ok = await this.confirm.ask({
      title: 'Usunąć głos?',
      message: `Głos „${v.name}” zostanie usunięty. Projekty, które go używają, będą wymagały wybrania innego głosu.`,
      confirmLabel: 'Usuń głos',
      danger: true,
    });
    if (!ok) return;
    try {
      await this.api.deleteVoice(v.id);
      this.store.remove(v.id);
      void this.account.refreshUsage();
      this.toast.show('Głos został usunięty.');
      void this.router.navigate(['/voices/mine']);
    } catch (e) {
      this.toast.apiError('Nie udało się usunąć głosu', e);
    }
  }
}
