import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  afterNextRender,
  computed,
  effect,
  inject,
  input,
  viewChild,
} from '@angular/core';
import { AccountStore } from '../../core/services/account.store';
import { EngineStatusService } from '../../core/services/engine-status.service';
import { PlayerService } from '../../core/services/player.service';
import { formatDuration, formatNumber } from '../../core/util/format';
import { SEGMENT_STATUS } from '../../core/util/labels';
import { DropdownComponent } from '../../shared/dropdown.component';
import { IconComponent } from '../../shared/icon.component';
import { EditorSegment, EditorStore } from './editor.store';

@Component({
  selector: 'vc-segment-row',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [IconComponent, DropdownComponent],
  styleUrl: './segment-row.component.scss',
  host: {
    '[class.is-selected]': 'selected()',
    '[class.is-playing]': 'isPlaying()',
    '[class.is-busy]': 'seg().generating || seg().queued',
    '[attr.data-status]': 'seg().status',
  },
  template: `
    <div class="gutter">
      <span class="index tabular">{{ index() + 1 }}</span>
      <label class="select" [title]="selected() ? 'Odznacz' : 'Zaznacz fragment'">
        <span class="checkbox"><input type="checkbox" [checked]="selected()" (change)="store.toggleSelect(seg().id)" [attr.aria-label]="'Zaznacz fragment ' + (index() + 1)" /></span>
      </label>
    </div>

    <div class="body">
      <textarea
        #ta
        class="text"
        rows="1"
        spellcheck="true"
        [value]="seg().text"
        [placeholder]="index() === 0 && total() === 1 ? 'Wpisz lub wklej tekst. Akapity zostaną automatycznie podzielone na fragmenty…' : 'Pusty fragment'"
        [attr.aria-label]="'Tekst fragmentu ' + (index() + 1)"
        (input)="onInput()"
        (keydown)="onKey($event)"
        (paste)="onPaste($event)"
      ></textarea>

      <div class="meta">
        @if (seg().generating) {
          <span class="state busy"><span class="spinner"></span>Generowanie…</span>
        } @else if (seg().queued) {
          <span class="state"><vc-icon name="clock" [size]="12" />W kolejce</span>
        } @else {
          <span class="state" [class]="'tone-' + statusInfo().tone">
            <span class="dot" [class]="'dot-' + statusInfo().tone"></span>{{ statusInfo().label }}
          </span>
          @if (seg().status === 'ready' || seg().status === 'stale') {
            <span class="sep">·</span><span class="tabular">{{ duration() }}</span>
          }
          @if (seg().engineMode === 'demo' && seg().audioVersion) {
            <span class="badge badge-demo" title="Wygenerowano w trybie demonstracyjnym">Demo</span>
          }
        }
        <span class="chars tabular">{{ chars() }} zn.</span>
        @if (seg().error && !seg().generating) {
          <span class="error" [title]="seg().error">{{ seg().error }}</span>
        }
      </div>
    </div>

    <div class="actions">
      <button
        class="btn btn-ghost btn-icon btn-sm"
        [disabled]="!seg().audioVersion || seg().generating"
        (click)="play()"
        [attr.aria-label]="isPlaying() ? 'Pauza' : 'Odsłuchaj fragment'"
        [title]="seg().status === 'stale' ? 'Odsłuchaj poprzednią wersję' : 'Odsłuchaj fragment'"
      >
        @if (isLoading()) { <span class="spinner"></span> }
        @else { <vc-icon [name]="isPlaying() ? 'pause' : 'play'" [size]="13" /> }
      </button>
      <button
        class="btn btn-sm gen"
        [disabled]="!canGenerate()"
        (click)="store.generateOne(seg().id)"
        [title]="seg().audioVersion ? 'Wygeneruj ponownie tylko ten fragment (Ctrl+Enter)' : 'Wygeneruj ten fragment (Ctrl+Enter)'"
      >
        <vc-icon [name]="seg().audioVersion ? 'refresh' : 'voices'" [size]="13" />
        <span class="gen-label">{{ seg().audioVersion ? 'Ponów' : 'Generuj' }}</span>
      </button>
      <vc-dropdown [width]="230">
        <button trigger class="btn btn-ghost btn-icon btn-sm" aria-label="Więcej akcji"><vc-icon name="more" /></button>
        <div menu>
          <button class="menu-item" (click)="store.splitIntoSentences(seg().id)"><vc-icon name="scissors" />Podziel na zdania</button>
          <button class="menu-item" [disabled]="index() === total() - 1" (click)="store.mergeWithNext(seg().id)"><vc-icon name="merge" />Połącz z następnym</button>
          <button class="menu-item" (click)="store.addSegment(seg().id)"><vc-icon name="list-plus" />Dodaj fragment poniżej</button>
          <button class="menu-item" (click)="store.duplicate(seg().id)"><vc-icon name="copy" />Duplikuj</button>
          <div class="menu-sep"></div>
          <button class="menu-item" [disabled]="index() === 0" (click)="store.move(seg().id, -1)"><vc-icon name="chevron-up" />Przenieś wyżej</button>
          <button class="menu-item" [disabled]="index() === total() - 1" (click)="store.move(seg().id, 1)"><vc-icon name="chevron-down" />Przenieś niżej</button>
          <div class="menu-sep"></div>
          <button class="menu-item menu-item-danger" (click)="store.remove(seg().id)"><vc-icon name="trash" />Usuń fragment</button>
        </div>
      </vc-dropdown>
    </div>

    @if (seg().generating) { <div class="progress" aria-hidden="true"></div> }
  `,
})
export class SegmentRowComponent {
  protected store = inject(EditorStore);
  private player = inject(PlayerService);
  private engine = inject(EngineStatusService);
  private account = inject(AccountStore);

  readonly seg = input.required<EditorSegment>();
  readonly index = input.required<number>();
  readonly total = input.required<number>();

  private ta = viewChild.required<ElementRef<HTMLTextAreaElement>>('ta');

  protected selected = computed(() => this.store.selection().has(this.seg().id));
  protected statusInfo = computed(() => SEGMENT_STATUS[this.seg().status]);
  protected duration = computed(() => formatDuration(this.seg().durationMs));
  protected chars = computed(() => formatNumber(this.seg().text.trim().length));
  protected trackId = computed(() => `seg:${this.seg().id}`);
  protected isPlaying = computed(() => this.player.isPlaying(this.trackId()));
  protected isLoading = computed(() => this.player.isLoading(this.trackId()));
  protected canGenerate = computed(
    () =>
      !!this.seg().text.trim() && !this.seg().generating && !this.store.batch() && !this.engine.isOffline() && !this.store.planBlock(),
  );

  constructor() {
    afterNextRender(() => this.autosize());
    // Synchronizacja tekstu z modelu (np. po podziale/scaleniu) bez utraty kursora przy pisaniu
    effect(() => {
      const text = this.seg().text;
      const el = this.ta()?.nativeElement;
      if (el && el.value !== text) {
        el.value = text;
        this.autosize();
      }
    });
    effect(() => {
      const req = this.store.focusRequest();
      if (req && req.id === this.seg().id) {
        queueMicrotask(() => {
          const el = this.ta().nativeElement;
          el.focus();
          el.setSelectionRange(req.caret, req.caret);
          this.autosize();
          this.store.focusRequest.set(null);
        });
      }
    });
  }

  protected onInput() {
    this.autosize();
    this.store.setText(this.seg().id, this.ta().nativeElement.value);
  }

  protected onKey(e: KeyboardEvent) {
    const el = this.ta().nativeElement;
    const atStart = el.selectionStart === 0 && el.selectionEnd === 0;
    const atEnd = el.selectionStart === el.value.length;
    if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') {
      e.preventDefault();
      if (this.canGenerate()) void this.store.generateOne(this.seg().id);
      return;
    }
    if (e.key === 'Enter' && !e.shiftKey && !e.altKey) {
      e.preventDefault();
      this.store.splitAt(this.seg().id, el.selectionStart);
      return;
    }
    if (e.key === 'Backspace' && atStart && this.index() > 0) {
      e.preventDefault();
      this.store.mergeWithPrevious(this.seg().id);
      return;
    }
    if (e.key === 'ArrowUp' && atStart) this.focusSibling(-1, e);
    if (e.key === 'ArrowDown' && atEnd) this.focusSibling(1, e);
  }

  protected onPaste(e: ClipboardEvent) {
    if (this.account.account()?.autoSplitOnPaste === false) return;
    const text = e.clipboardData?.getData('text/plain') ?? '';
    if (!/\n\s*\n/.test(text) && text.length <= 400) return;
    const el = this.ta().nativeElement;
    if (this.store.insertParagraphs(this.seg().id, el.selectionStart, el.selectionEnd, text)) e.preventDefault();
  }

  protected play() {
    const t = this.store.segmentTrack(this.seg());
    if (t) void this.player.play(t);
  }

  private focusSibling(delta: number, e: KeyboardEvent) {
    const list = this.store.segments();
    const target = list[this.index() + delta];
    if (!target) return;
    e.preventDefault();
    this.store.focusRequest.set({ id: target.id, caret: delta < 0 ? target.text.length : 0 });
  }

  private autosize() {
    const el = this.ta()?.nativeElement;
    if (!el) return;
    el.style.height = 'auto';
    el.style.height = `${el.scrollHeight}px`;
  }
}
