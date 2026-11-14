import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { DomSanitizer } from '@angular/platform-browser';
import { ICONS } from './icons';

@Component({
  selector: 'vc-icon',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { 'aria-hidden': 'true', '[style.width.px]': 'size()', '[style.height.px]': 'size()' },
  styles: `
    :host { display: inline-flex; flex-shrink: 0; line-height: 0; }
    svg { width: 100%; height: 100%; }
  `,
  template: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" [attr.stroke-width]="stroke()"
    stroke-linecap="round" stroke-linejoin="round" [innerHTML]="svg()"></svg>`,
})
export class IconComponent {
  private sanitizer = inject(DomSanitizer);
  readonly name = input.required<string>();
  readonly size = input(16);
  readonly stroke = input(1.75);
  readonly filled = input(false);

  // Zawartość pochodzi wyłącznie ze statycznego słownika ICONS.
  protected svg = computed(() => {
    let markup = ICONS[this.name()] ?? '';
    if (this.filled()) markup = markup.replace(/<(polygon|path)/g, '<$1 fill="currentColor"');
    return this.sanitizer.bypassSecurityTrustHtml(markup);
  });
}
