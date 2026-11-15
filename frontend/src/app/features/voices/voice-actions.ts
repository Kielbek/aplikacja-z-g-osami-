import { Injectable, inject } from '@angular/core';
import { Router } from '@angular/router';
import { ApiService } from '../../core/api/api.service';
import { Voice } from '../../core/api/models';
import { PlayerTrack } from '../../core/services/player.service';
import { ProjectsStore } from '../../core/services/projects.store';
import { PreviewLanguageService } from '../../core/services/preview-language.service';
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
  private previewLang = inject(PreviewLanguageService);

  /** Próbka w wybranym języku (domyślnie: język próbek wybrany przez użytkownika). */
  sampleTrack(v: Voice, lang: string = this.previewLang.language()): PlayerTrack {
    return {
      id: `voice:${v.id}:${lang}`,
      title: `Próbka: ${v.name}`,
      subtitle: [languageLabel(lang), ...v.categories.slice(0, 1).map(categoryLabel)].join(' · '),
      url: this.api.voicePreviewUrl(v.id, lang),
      downloadName: `probka-${v.id}-${lang}`,
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
        // Głos jest wielojęzyczny — projekt dostaje język wybranych próbek
        settings: { ...p.settings, voiceId: v.id, language: this.previewLang.language() },
      });
      this.projects.touch(updated);
      await this.router.navigate(['/editor', p.id]);
    } catch (e) {
      this.toast.apiError('Nie udało się utworzyć projektu', e);
    }
  }
}
