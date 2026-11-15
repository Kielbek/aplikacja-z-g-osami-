import { Injectable, computed, inject, signal } from '@angular/core';
import { ApiService } from '../api/api.service';
import { VOXCPM2_LANGUAGES, languageLabel } from '../util/labels';

const KEY = 'vc.previewLanguage';

/**
 * Język próbek głosów. Przycisk „Posłuchaj próbki” odtwarza krótkie zdanie w aktualnie wybranym języku
 * (np. po przełączeniu na włoski — każdy głos mówi po włosku z natywnym akcentem).
 */
@Injectable({ providedIn: 'root' })
export class PreviewLanguageService {
  private api = inject(ApiService);
  readonly language = signal<string>(read());
  /** Zdania próbek z backendu (30 języków). */
  readonly texts = signal<Record<string, string>>({});
  readonly options = computed(() =>
    VOXCPM2_LANGUAGES.map((c) => ({ value: c, label: languageLabel(c), hint: c.toUpperCase(), flag: c })).sort((a, b) =>
      a.value === 'pl' ? -1 : b.value === 'pl' ? 1 : a.label.localeCompare(b.label, 'pl'),
    ),
  );
  readonly text = computed(() => this.texts()[this.language()] ?? '');
  private loaded = false;

  constructor() {
    void this.load();
  }

  set(lang: string) {
    this.language.set(lang);
    try {
      localStorage.setItem(KEY, lang);
    } catch {
      /* brak dostępu do pamięci przeglądarki */
    }
  }

  async load() {
    if (this.loaded) return;
    this.loaded = true;
    try {
      const list = await this.api.previewLanguages();
      this.texts.set(Object.fromEntries(list.map((x) => [x.code, x.text])));
    } catch {
      this.loaded = false;
    }
  }
}

function read(): string {
  try {
    const v = localStorage.getItem(KEY);
    return v && VOXCPM2_LANGUAGES.includes(v) ? v : 'pl';
  } catch {
    return 'pl';
  }
}
