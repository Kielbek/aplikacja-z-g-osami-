import { ChangeDetectionStrategy, Component, computed, inject, input, output, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ApiService } from '../../core/api/api.service';
import { Voice } from '../../core/api/models';
import { PlayerTrack } from '../../core/services/player.service';
import { VoicesStore } from '../../core/services/voices.store';
import { LIBRARY_CATEGORIES, categoryLabel, personaLabel } from '../../core/util/labels';
import { DropdownComponent } from '../../shared/dropdown.component';
import { FlagComponent } from '../../shared/flag.component';
import { IconComponent } from '../../shared/icon.component';
import { PlayButtonComponent } from '../../shared/play-button.component';
import { VoiceAvatarComponent } from '../../shared/voice-avatar.component';

/** Wybór głosu z wyszukiwarką; ulubione i własne głosy na górze. */
@Component({
  selector: 'vc-voice-picker',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DropdownComponent, IconComponent, VoiceAvatarComponent, PlayButtonComponent, RouterLink, FlagComponent],
  styles: `
    :host { display: block; }
    vc-dropdown { display: block; }
    .current {
      all: unset; box-sizing: border-box; cursor: pointer; width: 100%;
      display: flex; align-items: center; gap: 10px; padding: 8px 10px;
      border: 1px solid var(--border); border-radius: var(--radius-md); background: var(--surface);
      box-shadow: var(--shadow-xs); transition: border-color var(--dur-fast) var(--ease); text-align: left;
    }
    .current:hover { border-color: var(--border-strong); }
    .current:focus-visible { outline: 2px solid var(--accent); outline-offset: 1px; }
    .cur-text { flex: 1; min-width: 0; display: flex; flex-direction: column; line-height: 1.35; }
    .cur-name { font-size: var(--text-sm); font-weight: 600; }
    .cur-sub { font-size: var(--text-xs); color: var(--text-3); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
    .search { padding: 4px 4px 6px; }
    .item { height: auto; padding: 6px 8px; gap: 10px; text-align: left; }
    .item .it { flex: 1; min-width: 0; display: flex; flex-direction: column; line-height: 1.35; }
    .item .nm { font-size: var(--text-sm); font-weight: 550; display: flex; align-items: center; gap: 6px; }
    .item .sb { font-size: var(--text-xs); color: var(--text-3); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
    .item.sel { background: var(--accent-soft); }
    .check { color: var(--accent); }
    .sb vc-flag, .cur-sub vc-flag { margin-right: 5px; vertical-align: -1px; }
    .menu-label { display: flex; align-items: center; gap: 6px; }
    vc-play-button { --pb: 26px; }
    .foot { display: block; padding: 8px 10px 6px; font-size: var(--text-xs); }
    .chip-trigger {
      all: unset; box-sizing: border-box; cursor: pointer; display: inline-flex; align-items: center; gap: 5px;
      height: 20px; padding: 0 7px 0 2px; border-radius: 999px; font-size: var(--text-2xs); font-weight: 550;
      color: var(--text-2); background: var(--bg-sunken); border: 1px solid var(--border-subtle); white-space: nowrap;
    }
    .chip-trigger:hover { border-color: var(--border-strong); color: var(--text); }
    .chip-trigger.inherited { color: var(--text-3); font-weight: 450; }
    .chip-trigger:focus-visible { outline: 2px solid var(--accent); outline-offset: 1px; }
    .none { padding: 16px 10px; color: var(--text-3); font-size: var(--text-sm); text-align: center; }
  `,
  template: `
    <vc-dropdown [align]="compact() ? 'start' : 'end'" [width]="300">
      @if (compact()) {
        <button trigger class="chip-trigger" [class.inherited]="!selectedId()" [attr.aria-label]="'Głos fragmentu: ' + (current()?.name ?? 'głos projektu')"
          [title]="selectedId() ? 'Głos tego fragmentu' : 'Fragment używa głosu projektu — kliknij, aby zmienić'">
          @if (current(); as v) { <vc-voice-avatar [id]="v.id" [name]="v.name" [size]="16" /> {{ v.name }} }
          @else if (fallback(); as f) { <vc-voice-avatar [id]="f.id" [name]="f.name" [size]="16" /> {{ f.name }} }
          @else { Wybierz głos }
          <vc-icon name="chevron-down" [size]="10" />
        </button>
      } @else {
      <button trigger class="current" [attr.aria-label]="'Wybrany głos: ' + (current()?.name ?? 'brak')">
        @if (current(); as v) {
          <vc-voice-avatar [id]="v.id" [name]="v.name" [size]="34" />
          <span class="cur-text">
            <span class="cur-name">{{ v.name }}</span>
            <span class="cur-sub"><vc-flag [lang]="language()" [size]="10" />{{ describe(v) }}</span>
          </span>
        } @else {
          <span class="cur-text"><span class="cur-name">Wybierz głos</span><span class="cur-sub">Brak wybranego głosu</span></span>
        }
        <vc-icon name="chevron-down" [size]="14" />
      </button>
      }
      <div menu>
        @if (compact() && fallback(); as f) {
          <button class="menu-item item" [class.sel]="!selectedId()" (click)="select.emit(null)">
            <vc-voice-avatar [id]="f.id" [name]="f.name" [size]="28" />
            <span class="it"><span class="nm">Głos projektu</span><span class="sb">{{ f.name }} — wspólny dla fragmentów bez własnego głosu</span></span>
            @if (!selectedId()) { <vc-icon class="check" name="check" [size]="14" /> }
          </button>
          <div class="menu-sep"></div>
        }
        <div class="search">
          <div class="input-group">
            <vc-icon name="search" [size]="14" />
            <input class="input" placeholder="Szukaj głosu…" [value]="query()" (input)="query.set($any($event.target).value)" (click)="$event.stopPropagation()" />
          </div>
        </div>
        @for (group of groups(); track group.label) {
          <div class="menu-label">@if (group.lang) { <vc-flag [lang]="group.lang" [size]="10" /> }{{ group.label }}</div>
          @for (v of group.voices; track v.id) {
            <button class="menu-item item" [class.sel]="v.id === selectedId()" (click)="select.emit(v.id)">
              <vc-voice-avatar [id]="v.id" [name]="v.name" [size]="28" />
              <span class="it">
                <span class="nm">{{ v.name }} @if (v.favorite) { <vc-icon name="star" [size]="11" [filled]="true" style="color:var(--warning)" /> }</span>
                <span class="sb">{{ describe(v) }}</span>
              </span>
              @if (v.id === selectedId()) { <vc-icon class="check" name="check" [size]="14" /> }
              <vc-play-button [track]="sample(v)" label="Odsłuchaj próbkę" [iconSize]="10" (click)="$event.stopPropagation()" />
            </button>
          }
        }
        @if (!groups().length) { <div class="none">Brak głosów pasujących do wyszukiwania.</div> }
        <div class="menu-sep"></div>
        <a class="foot" routerLink="/voices">Przeglądaj bibliotekę głosów →</a>
      </div>
    </vc-dropdown>
  `,
})
export class VoicePickerComponent {
  private voices = inject(VoicesStore);
  private api = inject(ApiService);

  readonly selectedId = input<string | null>(null);
  readonly language = input<string>('pl');
  /** Tryb kompaktowy (chip w wierszu fragmentu) z opcją „Głos projektu”. */
  readonly compact = input(false);
  readonly fallbackId = input<string | null>(null);
  readonly select = output<string | null>();
  protected query = signal('');

  protected current = computed(() => this.voices.get(this.selectedId()));
  protected fallback = computed(() => this.voices.get(this.fallbackId()));

  protected groups = computed(() => {
    const q = this.query().trim().toLowerCase();
    const list = this.voices
      .voices()
      .filter((v) => !q || `${v.name} ${v.description} ${v.traits.join(' ')}`.toLowerCase().includes(q));
    const own = list.filter((v) => v.kind !== 'LIBRARY');
    const fav = list.filter((v) => v.kind === 'LIBRARY' && v.favorite);
    // Głosy biblioteki są wielojęzyczne (30 języków) — grupowane według zastosowania
    const byCategory = LIBRARY_CATEGORIES.map((c) => ({
      label: c.label,
      voices: list.filter((v) => v.kind === 'LIBRARY' && !v.favorite && v.categories[0] === c.id),
      lang: null as string | null,
    }));
    const rest = list.filter((v) => v.kind === 'LIBRARY' && !v.favorite && !LIBRARY_CATEGORIES.some((c) => c.id === v.categories[0]));
    return [
      { label: 'Ulubione', voices: fav, lang: null as string | null },
      { label: 'Moje głosy', voices: own, lang: null as string | null },
      ...byCategory,
      { label: 'Pozostałe', voices: rest, lang: null as string | null },
    ].filter((g) => g.voices.length);
  });

  protected describe(v: Voice) {
    return [personaLabel(v.gender, v.age), ...v.categories.slice(0, 1).map(categoryLabel)].join(' · ');
  }

  protected sample(v: Voice): PlayerTrack {
    // Próbka w języku projektu — tak będzie brzmiał ten głos w tym projekcie
    const lang = this.language();
    return { id: `voice:${v.id}:${lang}`, title: `Próbka: ${v.name}`, subtitle: this.describe(v), url: this.api.voicePreviewUrl(v.id, lang) };
  }
}
