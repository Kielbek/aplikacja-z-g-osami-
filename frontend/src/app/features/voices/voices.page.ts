import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { Voice } from '../../core/api/models';
import { VoicesStore } from '../../core/services/voices.store';
import { CATEGORIES, GENDERS, avatarStyle, categoryLabel, initials, languageLabel, languageOptions } from '../../core/util/labels';
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
  protected language = signal('');
  protected gender = signal('');
  protected category = signal('');

  protected CATEGORIES = CATEGORIES;
  protected GENDERS = Object.entries(GENDERS);
  protected categoryLabel = categoryLabel;
  protected languageLabel = languageLabel;

  protected languages = computed(() => [...new Set(this.store.voices().flatMap((v) => v.languages))].sort((a, b) => (a === 'pl' ? -1 : b === 'pl' ? 1 : a.localeCompare(b))));

  protected counts = computed(() => {
    const all = this.store.voices();
    return { all: all.length, favorites: all.filter((v) => v.favorite).length, mine: all.filter((v) => v.kind !== 'LIBRARY').length };
  });

  protected filtered = computed(() => {
    const q = this.query().trim().toLowerCase();
    return this.store.voices().filter((v) => {
      if (this.tab() === 'favorites' && !v.favorite) return false;
      if (this.tab() === 'mine' && v.kind === 'LIBRARY') return false;
      if (this.language() && !v.languages.includes(this.language())) return false;
      if (this.gender() && v.gender !== this.gender()) return false;
      if (this.category() && !v.categories.includes(this.category())) return false;
      if (q && !`${v.name} ${v.description} ${v.traits.join(' ')}`.toLowerCase().includes(q)) return false;
      return true;
    });
  });

  protected languageOpts = computed<SelectOption[]>(() => [
    { value: '', label: 'Wszystkie języki' },
    ...languageOptions(this.languages()).map((o) => ({
      ...o,
      hint: `${this.store.voices().filter((v) => v.languages.includes(o.value)).length} gł.`,
    })),
  ]);
  protected genderOpts: SelectOption[] = [{ value: '', label: 'Każda barwa' }, ...Object.entries(GENDERS).map(([value, label]) => ({ value, label }))];

  protected hasFilters = computed(() => !!(this.query() || this.language() || this.gender() || this.category()));

  constructor() {
    void this.store.load(true);
  }

  clearFilters() {
    this.query.set('');
    this.language.set('');
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
