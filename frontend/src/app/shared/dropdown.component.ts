import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  HostListener,
  input,
  signal,
  viewChild,
} from '@angular/core';

/**
 * Menu rozwijane. Użycie:
 *   <vc-dropdown> <button trigger>…</button> <div menu>…<button class="menu-item">…</button></div> </vc-dropdown>
 * Panel jest pozycjonowany "fixed", więc nie jest przycinany przez przewijane kontenery.
 */
@Component({
  selector: 'vc-dropdown',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    :host { display: inline-flex; position: relative; }
    .menu { position: fixed; max-height: min(420px, calc(100vh - 24px)); overflow-y: auto; }
  `,
  template: `
    <span class="trigger" (click)="toggle($event)" style="display:contents"><ng-content select="[trigger]" /></span>
    <div
      #panel
      class="menu"
      role="menu"
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
  private panel = viewChild.required<ElementRef<HTMLElement>>('panel');

  constructor(private el: ElementRef<HTMLElement>) {}

  toggle(e: MouseEvent) {
    e.stopPropagation();
    if (this.open()) return this.close();
    const trigger = (e.currentTarget as HTMLElement).firstElementChild as HTMLElement | null;
    const rect = (trigger ?? this.el.nativeElement).getBoundingClientRect();
    this.open.set(true);
    requestAnimationFrame(() => {
      const panel = this.panel().nativeElement;
      const ph = panel.offsetHeight;
      const pw = Math.max(panel.offsetWidth, this.width());
      let top = rect.bottom + 6;
      if (top + ph > window.innerHeight - 12) top = Math.max(12, rect.top - ph - 6);
      let left = this.align() === 'end' ? rect.right - pw : rect.left;
      left = Math.max(12, Math.min(left, window.innerWidth - pw - 12));
      this.pos.set({ top, left });
      (panel.querySelector('.menu-item:not(:disabled)') as HTMLElement | null)?.focus({ preventScroll: true });
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
  @HostListener('window:scroll')
  onViewport() {
    this.close();
  }
}
