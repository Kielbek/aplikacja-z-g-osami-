import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  HostListener,
  inject,
  input,
  signal,
  viewChild,
} from '@angular/core';
import { isMobile } from './viewport';

/**
 * Menu rozwijane. Użycie:
 *   <vc-dropdown> <button trigger>…</button> <div menu>…<button class="menu-item">…</button></div> </vc-dropdown>
 * Desktop: panel „fixed” przy przycisku (nie jest przycinany przez przewijane kontenery).
 * Telefon: panel wysuwany od dołu ekranu.
 */
@Component({
  selector: 'vc-dropdown',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    :host { display: inline-flex; position: relative; }
    .menu { position: fixed; max-height: min(420px, calc(100vh - 24px)); overflow-y: auto; }
    .scrim { position: fixed; inset: 0; z-index: 84; background: var(--scrim); animation: vc-fade 160ms var(--ease); }
    .menu.sheet {
      left: 0 !important; right: 0; bottom: 0; top: auto !important; min-width: 0 !important;
      max-height: 75vh; z-index: 85; border-radius: 18px 18px 0 0;
      padding: 6px 8px calc(10px + env(safe-area-inset-bottom));
      animation: vc-sheet-in 240ms cubic-bezier(0.2, 0.8, 0.2, 1);
    }
    .menu.sheet::before { content: ''; display: block; width: 36px; height: 4px; border-radius: 4px; background: var(--border-strong); margin: 4px auto 10px; }
    .menu.sheet ::ng-deep .menu-item { height: 46px; font-size: var(--text-base); border-radius: var(--radius-md); }
    @keyframes vc-fade { from { opacity: 0; } }
    @keyframes vc-sheet-in { from { transform: translateY(100%); } to { transform: none; } }
  `,
  template: `
    <span class="trigger" (click)="toggle($event)" style="display:contents"><ng-content select="[trigger]" /></span>
    @if (open() && mobile()) { <div class="scrim" (click)="close()"></div> }
    <div
      #panel
      class="menu"
      role="menu"
      [class.sheet]="mobile()"
      [hidden]="!open()"
      [style.top.px]="pos().top"
      [style.left.px]="pos().left"
      [style.min-width.px]="width()"
      (click)="onMenuClick($event)"
    >
      <ng-content select="[menu]" />
    </div>
  `,
})
export class DropdownComponent {
  readonly align = input<'start' | 'end'>('end');
  readonly width = input(200);
  readonly open = signal(false);
  readonly pos = signal({ top: 0, left: 0 });
  protected mobile = isMobile;
  private panel = viewChild.required<ElementRef<HTMLElement>>('panel');

  constructor(private el: ElementRef<HTMLElement>) {
    const onScroll = (e: Event) => {
      if (this.open() && !this.mobile() && !this.panel().nativeElement.contains(e.target as Node)) this.close();
    };
    document.addEventListener('scroll', onScroll, true);
    inject(DestroyRef).onDestroy(() => document.removeEventListener('scroll', onScroll, true));
  }

  toggle(e: MouseEvent) {
    e.stopPropagation();
    if (this.open()) return this.close();
    const trigger = (e.currentTarget as HTMLElement).firstElementChild as HTMLElement | null;
    const rect = (trigger ?? this.el.nativeElement).getBoundingClientRect();
    this.open.set(true);
    requestAnimationFrame(() => {
      const panel = this.panel().nativeElement;
      if (!this.mobile()) {
        const ph = panel.offsetHeight;
        const pw = Math.max(panel.offsetWidth, this.width());
        let top = rect.bottom + 6;
        if (top + ph > window.innerHeight - 12) top = Math.max(12, rect.top - ph - 6);
        let left = this.align() === 'end' ? rect.right - pw : rect.left;
        left = Math.max(12, Math.min(left, window.innerWidth - pw - 12));
        this.pos.set({ top, left });
      }
      const target = this.mobile() ? panel.querySelector('.menu-item:not(:disabled)') : panel.querySelector('input, .menu-item:not(:disabled)');
      (target as HTMLElement | null)?.focus({ preventScroll: true });
    });
    this.pos.set({ top: rect.bottom + 6, left: this.align() === 'end' ? rect.right - this.width() : rect.left });
  }

  close() {
    this.open.set(false);
  }

  onMenuClick(e: MouseEvent) {
    if ((e.target as HTMLElement).closest('.menu-item')) this.close();
  }

  @HostListener('document:pointerdown', ['$event'])
  onDoc(e: PointerEvent) {
    if (this.open() && !this.el.nativeElement.contains(e.target as Node)) this.close();
  }

  @HostListener('document:keydown.escape')
  onEsc() {
    this.close();
  }

  @HostListener('window:resize')
  onViewport() {
    if (!this.mobile()) this.close();
  }
}
