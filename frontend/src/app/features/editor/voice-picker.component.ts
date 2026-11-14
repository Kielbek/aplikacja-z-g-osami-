import { ChangeDetectionStrategy, Component, computed, inject, input, output, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ApiService } from '../../core/api/api.service';
import { Voice } from '../../core/api/models';
import { PlayerTrack } from '../../core/services/player.service';
import { VoicesStore } from '../../core/services/voices.store';
import { categoryLabel, languageLabel } from '../../core/util/labels';
import { DropdownComponent } from '../../shared/dropdown.component';
import { IconComponent } from '../../shared/icon.component';
import { PlayButtonComponent } from '../../shared/play-button.component';
import { VoiceAvatarComponent } from '../../shared/voice-avatar.component';

/** Wybór głosu z wyszukiwarką; ulubione i własne głosy na górze. */
@Component({
  selector: 'vc-voice-picker',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DropdownComponent, IconComponent, VoiceAvatarComponent, PlayButtonComponent, RouterLink],
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
    vc-play-button { --pb: 26px; }
    .foot { display: block; padding: 8px 10px 6px; font-size: var(--text-xs); }
    .none { padding: 16px 10px; color: var(--text-3); font-size: var(--text-sm); text-align: center; }
  `,
  template: `
    <vc-dropdown align="end" [width]="300">
      <button trigger class="current" [attr.aria-label]="'Wybrany głos: ' + (current()?.name ?? 'brak')">
        @if (current(); as v) {
          <vc-voice-avatar [id]="v.id" [name]="v.name" [size]="34" />
          <span class="cur-text">
            <span class="cur-name">{{ v.name }}</span>
            <span class="cur-sub">{{ describe(v) }}</span>
          </span>
        } @else {
          <span class="cur-text"><span class="cur-name">Wybierz głos</span><span class="cur-sub">Brak wybranego głosu</span></span>
        }
        <vc-icon name="chevron-down" [size]="14" />
      </button>
      <div menu>
        <div class="search">
          <div class="input-group">
            <vc-icon name="search" [size]="14" />
            <input class="input" placeholder="Szukaj głosu…" [value]="query()" (input)="query.set($any($event.target).value)" (click)="$event.stopPropagation()" />
          </div>
        </div>
        @for (group of groups(); track group.label) {
          <div class="menu-label">{{ group.label }}</div>
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
  readonly select = output<string>();
  protected query = signal('');

  protected current = computed(() => this.voices.get(this.selectedId()));

  protected groups = computed(() => {
    const q = this.query().trim().toLowerCase();
    const lang = this.language();
    const list = this.voices
      .voices()
      .filter((v) => !q || `${v.name} ${v.description} ${v.traits.join(' ')}`.toLowerCase().includes(q));
    const own = list.filter((v) => v.kind !== 'LIBRARY');
    const fav = list.filter((v) => v.kind === 'LIBRARY' && v.favorite);
    const inLang = list.filter((v) => v.kind === 'LIBRARY' && !v.favorite && v.languages.includes(lang));
    const other = list.filter((v) => v.kind === 'LIBRARY' && !v.favorite && !v.languages.includes(lang));
    return [
      { label: 'Ulubione', voices: fav },
      { label: 'Moje głosy', voices: own },
      { label: languageLabel(lang), voices: inLang },
      { label: 'Inne języki', voices: other },
    ].filter((g) => g.voices.length);
  });

  protected describe(v: Voice) {
    return [languageLabel(v.languages[0] ?? ''), ...v.categories.slice(0, 2).map(categoryLabel)].join(' · ');
  }

  protected sample(v: Voice): PlayerTrack {
    return { id: `voice:${v.id}`, title: `Próbka: ${v.name}`, subtitle: this.describe(v), url: this.api.voiceSampleUrl(v.id) };
  }
}
