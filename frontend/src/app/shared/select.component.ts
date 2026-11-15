import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  inject,
  HostListener,
  computed,
  input,
  model,
  signal,
  viewChild,
} from '@angular/core';
import { FlagComponent } from './flag.component';
import { IconComponent } from './icon.component';
import { isMobile } from './viewport';

export interface SelectOption<T = string> {
  value: T;
  label: string;
  /** Drugi wiersz / opis po prawej. */
  hint?: string;
  /** Nagłówek grupy (opcje z tą samą grupą są wyświetlane razem). */
  group?: string;
  disabled?: boolean;
  /** Krótki tekst przy zablokowanej opcji, np. „Starter+”. */
  badge?: string;
  /** Kod języka — przed etykietą wyświetlana jest flaga. */
  flag?: string | null;
}

let uid = 0;

/**
 * Lista rozwijana w stylu aplikacji. Desktop: popover pod polem. Telefon: panel od dołu.
 * Obsługa klawiatury: strzałki, Home/End, Enter, Escape, wyszukiwanie po pierwszych literach.
 * Użycie: <vc-select [options]="opts" [(value)]="wartość" label="Język" />
 */
@Component({
  selector: 'vc-select',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [IconComponent, FlagComponent],
  styleUrl: './select.component.scss',
  host: { '[class.is-open]': 'open()', '[class.is-disabled]': 'disabled()', '[class.sm]': "size() === 'sm'" },
  template: `
    <button
      #trigger
      type="button"
      class="trigger"
      role="combobox"
      aria-haspopup="listbox"
      [id]="id + '-trigger'"
      [attr.aria-expanded]="open()"
      [attr.aria-controls]="id + '-list'"
      [attr.aria-label]="label()"
      [disabled]="disabled()"
      (click)="toggle()"
      (keydown)="onTriggerKey($event)"
    >
      @if (selected(); as s) {
        @if (s.flag) { <vc-flag [lang]="s.flag" /> }
        <span class="value">{{ s.label }}</span>
        @if (showHintInTrigger() && s.hint) { <span class="value-hint">{{ s.hint }}</span> }
      } @else {
        <span class="placeholder">{{ placeholder() }}</span>
      }
      <vc-icon class="chev" name="chevron-down" [size]="14" />
    </button>

    @if (open()) {
      @if (mobile()) { <div class="scrim" (click)="close()"></div> }
      <div
        #panel
        class="pop"
        [class.sheet]="mobile()"
        [class.above]="placeAbove()"
        [style.top.px]="mobile() ? null : pos().top"
        [style.left.px]="mobile() ? null : pos().left"
        [style.width.px]="mobile() ? null : pos().width"
        (keydown)="onListKey($event)"
      >
        @if (mobile()) {
          <div class="sheet-head">
            <span class="grabber"></span>
            <div class="sheet-title">{{ label() ?? placeholder() }}</div>
          </div>
        }
        @if (isSearchable()) {
          <div class="search">
            <vc-icon name="search" [size]="14" />
            <input #search type="text" [value]="query()" (input)="onSearch($any($event.target).value)" placeholder="Szukaj…" [attr.aria-controls]="id + '-list'" aria-autocomplete="list" />
          </div>
        }
        <ul class="options" role="listbox" [id]="id + '-list'" [attr.aria-label]="label()" [attr.aria-activedescendant]="activeId()" tabindex="-1" #list>
          @for (o of filtered(); track $index; let i = $index) {
            @if (o.group && o.group !== filtered()[i - 1]?.group) {
              <li class="group" role="presentation">{{ o.group }}</li>
            }
            <li
              role="option"
              class="opt"
              [id]="id + '-opt-' + i"
              [class.is-active]="i === active()"
              [class.is-selected]="o.value === value()"
              [class.is-disabled]="o.disabled"
              [attr.aria-selected]="o.value === value()"
              [attr.aria-disabled]="o.disabled || null"
              (pointermove)="active.set(i)"
              (click)="choose(o)"
            >
              @if (o.flag) { <vc-flag class="opt-flag" [lang]="o.flag" [size]="13" /> }
              <span class="opt-text">
                <span class="opt-label">{{ o.label }}</span>
                @if (o.hint) { <span class="opt-hint">{{ o.hint }}</span> }
              </span>
              @if (o.badge) { <span class="badge"><vc-icon name="lock" [size]="10" />{{ o.badge }}</span> }
              @if (o.value === value()) { <vc-icon class="check" name="check" [size]="15" /> }
            </li>
          } @empty {
            <li class="empty-opt" role="presentation">Brak wyników</li>
          }
        </ul>
      </div>
    }
  `,
})
export class SelectComponent<T = string> {
  readonly options = input.required<SelectOption<T>[]>();
  readonly value = model<T | null>(null);
  readonly label = input<string>();
  readonly placeholder = input('Wybierz…');
  readonly searchable = input<boolean | undefined>(undefined);
  readonly disabled = input(false);
  readonly size = input<'md' | 'sm'>('md');
  readonly showHintInTrigger = input(false);

  protected id = `vc-select-${++uid}`;
  protected open = signal(false);
  protected query = signal('');
  protected active = signal(0);
  protected pos = signal({ top: 0, left: 0, width: 0 });
  protected placeAbove = signal(false);
  protected mobile = isMobile;

  private trigger = viewChild.required<ElementRef<HTMLButtonElement>>('trigger');
  private panel = viewChild<ElementRef<HTMLElement>>('panel');
  private list = viewChild<ElementRef<HTMLElement>>('list');
  private search = viewChild<ElementRef<HTMLInputElement>>('search');
  private typeahead = '';
  private typeaheadTimer: ReturnType<typeof setTimeout> | null = null;

  protected selected = computed(() => this.options().find((o) => o.value === this.value()) ?? null);
  protected isSearchable = computed(() => this.searchable() ?? this.options().length > 8);
  protected filtered = computed(() => {
    const q = this.query().trim().toLowerCase();
    return q ? this.options().filter((o) => `${o.label} ${o.hint ?? ''} ${o.group ?? ''}`.toLowerCase().includes(q)) : this.options();
  });
  protected activeId = computed(() => (this.filtered().length ? `${this.id}-opt-${this.active()}` : null));

  constructor(private host: ElementRef<HTMLElement>) {
    // Zdarzenie scroll nie propaguje się — nasłuchujemy w fazie przechwytywania
    const onScroll = (e: Event) => this.onScroll(e);
    document.addEventListener('scroll', onScroll, true);
    inject(DestroyRef).onDestroy(() => document.removeEventListener('scroll', onScroll, true));
  }

  toggle() {
    if (this.open()) this.close();
    else this.openPanel();
  }

  openPanel() {
    if (this.disabled()) return;
    this.query.set('');
    const idx = this.options().findIndex((o) => o.value === this.value());
    this.active.set(Math.max(0, idx));
    this.measure();
    this.open.set(true);
    requestAnimationFrame(() => {
      this.measure();
      // Na telefonie nie otwieramy klawiatury automatycznie — fokus na liście
      if (this.search() && !this.mobile()) this.search()!.nativeElement.focus({ preventScroll: true });
      else this.list()?.nativeElement.focus({ preventScroll: true });
      this.scrollActive();
    });
  }

  close(restoreFocus = true) {
    if (!this.open()) return;
    this.open.set(false);
    if (restoreFocus) this.trigger().nativeElement.focus({ preventScroll: true });
  }

  protected choose(o: SelectOption<T>) {
    if (o.disabled) return;
    this.value.set(o.value);
    this.close();
  }

  protected onSearch(q: string) {
    this.query.set(q);
    this.active.set(Math.max(0, this.filtered().findIndex((o) => !o.disabled)));
  }

  protected onTriggerKey(e: KeyboardEvent) {
    if (['ArrowDown', 'ArrowUp', 'Enter', ' '].includes(e.key)) {
      e.preventDefault();
      this.openPanel();
    }
  }

  protected onListKey(e: KeyboardEvent) {
    const list = this.filtered();
    const move = (dir: 1 | -1, from = this.active()) => {
      let i = from;
      for (let n = 0; n < list.length; n++) {
        i = (i + dir + list.length) % list.length;
        if (!list[i].disabled) break;
      }
      this.active.set(i);
      this.scrollActive();
    };
    switch (e.key) {
      case 'ArrowDown':
        e.preventDefault();
        move(1);
        break;
      case 'ArrowUp':
        e.preventDefault();
        move(-1);
        break;
      case 'Home':
        if (this.isSearchable()) return;
        e.preventDefault();
        move(1, -1);
        break;
      case 'End':
        if (this.isSearchable()) return;
        e.preventDefault();
        move(-1, list.length);
        break;
      case 'Enter': {
        e.preventDefault();
        const o = list[this.active()];
        if (o) this.choose(o);
        break;
      }
      case 'Escape':
        e.preventDefault();
        e.stopPropagation();
        this.close();
        break;
      case 'Tab':
        this.close(false);
        break;
      default:
        if (!this.isSearchable() && e.key.length === 1 && /\S/.test(e.key)) this.typeAhead(e.key);
    }
  }

  private typeAhead(ch: string) {
    this.typeahead += ch.toLowerCase();
    if (this.typeaheadTimer) clearTimeout(this.typeaheadTimer);
    this.typeaheadTimer = setTimeout(() => (this.typeahead = ''), 600);
    const i = this.filtered().findIndex((o) => !o.disabled && o.label.toLowerCase().startsWith(this.typeahead));
    if (i >= 0) {
      this.active.set(i);
      this.scrollActive();
    }
  }

  private scrollActive() {
    requestAnimationFrame(() => {
      const el = this.list()?.nativeElement.querySelector<HTMLElement>('.opt.is-active');
      el?.scrollIntoView({ block: 'nearest' });
    });
  }

  private measure() {
    const r = this.trigger().nativeElement.getBoundingClientRect();
    const panelH = this.panel()?.nativeElement.offsetHeight ?? 320;
    const width = Math.max(r.width, 200);
    const below = window.innerHeight - r.bottom;
    const above = below < panelH + 12 && r.top > below;
    this.placeAbove.set(above);
    const left = Math.max(8, Math.min(r.left, window.innerWidth - width - 8));
    this.pos.set({ top: above ? Math.max(8, r.top - panelH - 6) : r.bottom + 6, left, width });
  }

  @HostListener('document:pointerdown', ['$event'])
  protected onDocPointer(e: PointerEvent) {
    if (!this.open()) return;
    const t = e.target as Node;
    if (this.host.nativeElement.contains(t)) return;
    this.close(false);
  }

  /** Przewijanie strony (poza samą listą) zamyka popover, aby nie „odjechał” od pola. */
  private onScroll(e: Event) {
    if (!this.open() || this.mobile()) return;
    const panel = this.panel()?.nativeElement;
    if (panel && panel.contains(e.target as Node)) return;
    this.close(false);
  }

  @HostListener('window:resize')
  protected onResize() {
    if (this.open() && !this.mobile()) this.close(false);
  }
}
