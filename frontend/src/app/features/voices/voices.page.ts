import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { Voice } from '../../core/api/models';
import { VoicesStore } from '../../core/services/voices.store';
import { LIBRARY_CATEGORIES, avatarStyle, categoryLabel, initials, languageLabel, personaLabel } from '../../core/util/labels';
import { PreviewLanguageService } from '../../core/services/preview-language.service';
import { portraitUrl } from '../../shared/voice-portraits';
import { SelectComponent, SelectOption } from '../../shared/select.component';
import { EngineBannerComponent } from '../../shared/engine-banner.component';
import { FlagComponent } from '../../shared/flag.component';
import { IconComponent } from '../../shared/icon.component';
import { PlayButtonComponent } from '../../shared/play-button.component';
import { VoiceActions } from './voice-actions';

@Component({
  selector: 'vc-voices-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, IconComponent, PlayButtonComponent, EngineBannerComponent, SelectComponent, FlagComponent],
  styleUrl: './voices.page.scss',
  templateUrl: './voices.page.html',
})
export class VoicesPage {
  protected store = inject(VoicesStore);
  protected actions = inject(VoiceActions);
  private router = inject(Router);

  /** Z danych trasy: all | favorites | mine */
  readonly tab = input<'all' | 'favorites' | 'mine'>('all');

  protected query = signal('');
  /** Grupa persony: female | male | child | senior */
  protected gender = signal('');
  protected category = signal('');
  /** Język próbek — przycisk „Posłuchaj” odtwarza zdanie w tym języku. */
  protected preview = inject(PreviewLanguageService);

  protected CATEGORIES = LIBRARY_CATEGORIES;
  protected categoryLabel = categoryLabel;
  protected languageLabel = languageLabel;
  protected personaLabel = personaLabel;

  protected counts = computed(() => {
    const all = this.store.voices();
    return { all: all.length, favorites: all.filter((v) => v.favorite).length, mine: all.filter((v) => v.kind !== 'LIBRARY').length };
  });

  protected filtered = computed(() => {
    const q = this.query().trim().toLowerCase();
    return this.store.voices().filter((v) => {
      if (this.tab() === 'favorites' && !v.favorite) return false;
      if (this.tab() === 'mine' && v.kind === 'LIBRARY') return false;
      if (this.gender() && personaGroup(v) !== this.gender()) return false;
      if (this.category() && !v.categories.includes(this.category())) return false;
      if (q && !`${v.name} ${v.description} ${v.traits.join(' ')}`.toLowerCase().includes(q)) return false;
      return true;
    });
  });

  protected genderOpts: SelectOption[] = [
    { value: '', label: 'Każda persona' },
    { value: 'female', label: 'Kobiety' },
    { value: 'male', label: 'Mężczyźni' },
    { value: 'child', label: 'Dzieci' },
    { value: 'senior', label: 'Seniorzy' },
  ];

  protected hasFilters = computed(() => !!(this.query() || this.gender() || this.category()));

  /** Sekcje według kategorii zastosowań (bez filtra kategorii i wyszukiwania); inaczej jedna lista wyników. */
  protected sections = computed(() => {
    const list = this.filtered();
    if (this.category() || this.query().trim()) return [{ id: 'results', label: '', voices: list }];
    const own = list.filter((v) => v.kind !== 'LIBRARY');
    const groups = LIBRARY_CATEGORIES.map((c) => ({ id: c.id, label: c.label, voices: list.filter((v) => v.kind === 'LIBRARY' && v.categories[0] === c.id) }));
    const rest = list.filter((v) => v.kind === 'LIBRARY' && !LIBRARY_CATEGORIES.some((c) => c.id === v.categories[0]));
    return [{ id: 'own', label: 'Moje głosy', voices: own }, ...groups, { id: 'other', label: 'Pozostałe', voices: rest }].filter((g) => g.voices.length);
  });

  constructor() {
    void this.store.load(true);
  }

  clearFilters() {
    this.query.set('');
    this.gender.set('');
    this.category.set('');
  }

  open(v: Voice) {
    void this.router.navigate(['/voices', v.id]);
  }

  /** Pełny portret (karty są duże) lub null — wtedy inicjały. */
  protected portrait(id: string) {
    return portraitUrl(id, 512);
  }
  protected avatar = avatarStyle;
  protected initials = initials;

  private barsCache = new Map<string, number[]>();
  /** Stała, „podpis” fali dla głosu — dekoracja tła karty. */
  protected bars(id: string): number[] {
    let b = this.barsCache.get(id);
    if (!b) {
      let h = 0;
      for (const c of id) h = (h * 31 + c.charCodeAt(0)) >>> 0;
      const seed = (h % 1000) / 100;
      b = Array.from({ length: 60 }, (_, i) => 6 + Math.abs(Math.sin(i * 0.55 + seed) * Math.sin(i * 0.17 + seed * 2)) * 88);
      this.barsCache.set(id, b);
    }
    return b;
  }

  kindLabel(v: Voice) {
    return v.kind === 'DESIGNED' ? 'Zaprojektowany' : v.kind === 'CLONED' ? 'Sklonowany' : null;
  }
}

/** Grupa persony do filtra: dzieci (< 14), seniorzy (65+), kobiety, mężczyźni. */
function personaGroup(v: Voice): string {
  if (v.age != null && v.age < 14) return 'child';
  if (v.age != null && v.age >= 65) return 'senior';
  return v.gender ?? '';
}
