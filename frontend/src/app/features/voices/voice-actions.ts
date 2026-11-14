import { Injectable, inject } from '@angular/core';
import { Router } from '@angular/router';
import { ApiService } from '../../core/api/api.service';
import { Voice } from '../../core/api/models';
import { PlayerTrack } from '../../core/services/player.service';
import { ProjectsStore } from '../../core/services/projects.store';
import { ToastService } from '../../core/services/toast.service';
import { VoicesStore } from '../../core/services/voices.store';
import { categoryLabel, languageLabel } from '../../core/util/labels';

/** Akcje wspólne dla listy i szczegółów głosu. */
@Injectable({ providedIn: 'root' })
export class VoiceActions {
  private api = inject(ApiService);
  private router = inject(Router);
  private toast = inject(ToastService);
  private voices = inject(VoicesStore);
  private projects = inject(ProjectsStore);

  sampleTrack(v: Voice): PlayerTrack {
    return {
      id: `voice:${v.id}`,
      title: `Próbka: ${v.name}`,
      subtitle: [languageLabel(v.languages[0] ?? ''), ...v.categories.slice(0, 2).map(categoryLabel)].join(' · '),
      url: this.api.voiceSampleUrl(v.id),
      downloadName: `probka-${v.id}`,
    };
  }

  async toggleFavorite(v: Voice) {
    try {
      const u = await this.voices.toggleFavorite(v);
      this.toast.show(u.favorite ? `Dodano „${v.name}” do ulubionych` : `Usunięto „${v.name}” z ulubionych`);
    } catch (e) {
      this.toast.apiError('Nie udało się zmienić ulubionych', e);
    }
  }

  /** Tworzy nowy projekt z wybranym głosem i otwiera edytor. */
  async useInNewProject(v: Voice) {
    try {
      const p = await this.api.createProject({ name: `Nowy projekt — ${v.name}` });
      const updated = await this.api.updateProject(p.id, {
        settings: { ...p.settings, voiceId: v.id, language: v.languages[0] ?? p.settings.language },
      });
      this.projects.touch(updated);
      await this.router.navigate(['/editor', p.id]);
    } catch (e) {
      this.toast.apiError('Nie udało się utworzyć projektu', e);
    }
  }
}
