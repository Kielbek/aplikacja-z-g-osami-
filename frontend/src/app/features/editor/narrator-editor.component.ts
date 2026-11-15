import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  afterRenderEffect,
  computed,
  effect,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { IconComponent } from '../../shared/icon.component';
import { EditorStore } from './editor.store';

/**
 * Tryb „Jeden lektor”: cały tekst w jednym polu, czytany głosem projektu.
 * Akapity (oddzielone pustą linią) są pod spodem fragmentami — generowanie, wersje
 * i statusy działają tak samo jak w widoku dialogu.
 */
@Component({
  selector: 'vc-narrator-editor',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [IconComponent],
  styles: `
    :host { display: block; }
    textarea {
      display: block;
      width: 100%;
      min-height: 320px;
      padding: 4px 0 24px;
      border: 0;
      outline: none;
      resize: none;
      overflow: hidden;
      background: transparent;
      color: var(--text);
      font: inherit;
      font-size: 18px;
      line-height: 1.75;
      letter-spacing: -0.005em;
      &::placeholder { color: var(--text-4); }
    }
    .note {
      display: flex;
      align-items: center;
      gap: 10px;
      margin-bottom: 16px;
      padding: 9px 12px;
      border-radius: var(--radius-md);
      background: var(--bg-sunken);
      font-size: var(--text-sm);
      color: var(--text-2);
      flex-wrap: wrap;
      .grow { flex: 1 1 220px; }
    }
  `,
  template: `
    @if (store.voiceOverrides(); as n) {
      <div class="note" role="note">
        <vc-icon name="voices" [size]="15" />
        <span class="grow">{{ n }} {{ n === 1 ? 'fragment ma' : 'fragmenty mają' }} własny głos — w trybie „Jeden lektor” widać tylko tekst.</span>
        <button class="btn btn-sm" (click)="useOneVoice()">Czytaj wszystko jednym głosem</button>
      </div>
    }
    <textarea
      #area
      [value]="draft()"
      (input)="onInput($any($event.target).value)"
      (focus)="onFocus()"
      (blur)="onBlur()"
      placeholder="Zacznij pisać tutaj lub wklej tekst, który chcesz zamienić na mowę…"
      aria-label="Tekst do przeczytania"
      spellcheck="true"
    ></textarea>
  `,
})
export class NarratorEditorComponent {
  protected store = inject(EditorStore);
  private area = viewChild.required<ElementRef<HTMLTextAreaElement>>('area');
  protected editing = signal(false);
  private local = signal('');
  /** Podczas pisania źródłem prawdy jest pole tekstowe (kursor nie skacze); poza tym — stan projektu. */
  protected draft = computed(() => (this.editing() ? this.local() : this.store.fullText()));

  constructor() {
    effect(() => {
      if (!this.editing()) this.local.set(this.store.fullText());
    });
    afterRenderEffect(() => {
      this.draft();
      this.autosize();
    });
  }

  protected onInput(value: string) {
    this.local.set(value);
    this.store.setFullText(value);
    this.autosize();
  }

  protected onFocus() {
    // Pole przejmuje aktualny tekst projektu (np. po wybraniu podpowiedzi lub cofnięciu zmian)
    this.local.set(this.store.fullText());
    this.editing.set(true);
  }

  protected onBlur() {
    this.editing.set(false);
  }

  protected useOneVoice() {
    this.store.setSegmentVoice(this.store.segments().map((s) => s.id), null);
  }

  /** Wstawia znacznik pauzy w miejscu kursora. */
  insertPause(token: string) {
    const el = this.area().nativeElement;
    const value = this.draft();
    const start = el.selectionStart ?? value.length;
    const end = el.selectionEnd ?? start;
    const before = value.slice(0, start).replace(/[ \t]+$/, '');
    const after = value.slice(end).replace(/^[ \t]+/, '');
    const insert = (before && !before.endsWith('\n') ? ' ' : '') + `[pauza ${token}]` + (after && !after.startsWith('\n') ? ' ' : '');
    const next = before + insert + after;
    this.editing.set(true);
    this.local.set(next);
    this.store.setFullText(next);
    queueMicrotask(() => {
      el.focus();
      const pos = before.length + insert.length;
      el.setSelectionRange(pos, pos);
    });
  }

  focus() {
    this.area().nativeElement.focus();
  }

  private autosize() {
    const el = this.area().nativeElement;
    el.style.height = 'auto';
    el.style.height = `${el.scrollHeight}px`;
  }
}
