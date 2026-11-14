import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ApiService } from '../../core/api/api.service';
import { ApiError, GenerationRecord, HistoryItem } from '../../core/api/models';
import { AccountStore } from '../../core/services/account.store';
import { ConfirmService } from '../../core/services/confirm.service';
import { PlayerTrack } from '../../core/services/player.service';
import { ToastService } from '../../core/services/toast.service';
import { formatDay, formatDuration, formatNumber, formatTime } from '../../core/util/format';
import { IconComponent } from '../../shared/icon.component';
import { PlayButtonComponent } from '../../shared/play-button.component';

type Filter = 'all' | 'segment' | 'voice_design' | 'export' | 'error';

const TYPE: Record<GenerationRecord['type'], { label: string; icon: string }> = {
  segment: { label: 'Fragment', icon: 'type' },
  voice_design: { label: 'Projekt głosu', icon: 'wand' },
  voice_sample: { label: 'Próbka głosu', icon: 'voices' },
  export: { label: 'Eksport', icon: 'download' },
};

@Component({
  selector: 'vc-history-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, IconComponent, PlayButtonComponent],
  styleUrl: './history.page.scss',
  templateUrl: './history.page.html',
})
export class HistoryPage {
  private api = inject(ApiService);
  private toast = inject(ToastService);
  private confirm = inject(ConfirmService);
  protected account = inject(AccountStore);

  protected items = signal<HistoryItem[]>([]);
  protected loaded = signal(false);
  protected error = signal<string | null>(null);
  protected filter = signal<Filter>('all');

  protected TYPE = TYPE;
  protected fmtDur = formatDuration;
  protected fmtTime = formatTime;
  protected fmtNum = formatNumber;

  protected groups = computed(() => {
    const f = this.filter();
    const list = this.items().filter(({ record: r }) =>
      f === 'all' ? true : f === 'error' ? r.status === 'error' : r.type === f,
    );
    const out: { day: string; items: HistoryItem[] }[] = [];
    for (const it of list) {
      const day = formatDay(it.record.createdAt);
      const last = out[out.length - 1];
      if (last && last.day === day) last.items.push(it);
      else out.push({ day, items: [it] });
    }
    return out;
  });

  constructor() {
    void this.load();
    void this.account.refreshUsage();
  }

  async load() {
    try {
      this.items.set(await this.api.history());
      this.error.set(null);
    } catch (e) {
      this.error.set((e as ApiError).message);
    } finally {
      this.loaded.set(true);
    }
  }

  track(it: HistoryItem): PlayerTrack | null {
    if (!it.audioAvailable) return null;
    const r = it.record;
    return {
      id: `history:${r.id}`,
      title: r.projectName ?? TYPE[r.type].label,
      subtitle: `${TYPE[r.type].label} · ${formatTime(r.createdAt)}`,
      url: this.api.historyAudioUrl(r.id),
    };
  }

  async clear() {
    const ok = await this.confirm.ask({
      title: 'Wyczyścić historię?',
      message: 'Wpisy historii zostaną usunięte. Projekty i ich nagrania pozostaną bez zmian. Wykorzystanie limitu w bieżącym okresie zostanie przeliczone.',
      confirmLabel: 'Wyczyść historię',
      danger: true,
    });
    if (!ok) return;
    try {
      await this.api.clearHistory();
      this.items.set([]);
      void this.account.refreshUsage();
      this.toast.show('Historia została wyczyszczona.');
    } catch (e) {
      this.toast.error('Nie udało się wyczyścić historii', (e as ApiError).message);
    }
  }
}
