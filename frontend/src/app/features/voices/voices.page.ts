import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { Voice } from '../../core/api/models';
import { VoicesStore } from '../../core/services/voices.store';
import { CATEGORIES, GENDERS, categoryLabel, languageLabel, languageOptions } from '../../core/util/labels';
import { SelectComponent, SelectOption } from '../../shared/select.component';
import { EngineBannerComponent } from '../../shared/engine-banner.component';
import { IconComponent } from '../../shared/icon.component';
import { PlayButtonComponent } from '../../shared/play-button.component';
import { VoiceAvatarComponent } from '../../shared/voice-avatar.component';
import { VoiceActions } from './voice-actions';

@Component({
  selector: 'vc-voices-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, IconComponent, PlayButtonComponent, VoiceAvatarComponent, EngineBannerComponent, SelectComponent],
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

  kindLabel(v: Voice) {
    return v.kind === 'DESIGNED' ? 'Zaprojektowany' : v.kind === 'CLONED' ? 'Sklonowany' : null;
  }
}
