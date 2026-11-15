import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

/** Słupki fali pod napisem — stały „podpis” marki (deterministyczny). */
export function waveBars(count: number): number[] {
  return Array.from({ length: count }, (_, i) => 0.2 + 0.8 * Math.abs(Math.sin(i * 0.55) * Math.sin(i * 0.19 + 1)));
}

/**
 * Logo Narrivy: sam napis (Inter Tight) z falą nagrania pod spodem — jak ścieżka audio na osi czasu.
 * `size` = wielkość liter w pikselach. W małych rozmiarach fala ma mniej, grubszych słupków.
 */
@Component({
  selector: 'vc-logo',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { role: 'img', 'aria-label': 'Narrivy', '[style.font-size.px]': 'size()' },
  styles: `
    :host {
      display: inline-block;
      position: relative;
      padding-bottom: 0.3em;
      color: var(--text);
      line-height: 1;
    }
    .word {
      display: block;
      font-family: 'Inter Tight Variable', 'Inter Tight', var(--font-sans);
      font-weight: 700;
      letter-spacing: -0.025em;
      white-space: nowrap;
    }
    svg {
      position: absolute;
      left: 0.02em;
      bottom: 0;
      width: calc(100% - 0.04em);
      height: 0.17em;
      fill: currentColor;
    }
  `,
  template: `
    <span class="word" aria-hidden="true">narrivy</span>
    <svg [attr.viewBox]="'0 0 ' + bars().length * 3 + ' 20'" preserveAspectRatio="none" aria-hidden="true">
      @for (h of bars(); track $index) {
        <rect [attr.x]="$index * 3 + (3 - barWidth()) / 2" [attr.y]="10 - h * 10" [attr.width]="barWidth()" [attr.height]="h * 20" [attr.rx]="barWidth() / 2" />
      }
    </svg>
  `,
})
export class LogoComponent {
  readonly size = input(24);
  /** Mniej słupków w małych rozmiarach — fala pozostaje czytelna (nie zlewa się w linię). */
  protected bars = computed(() => waveBars(this.size() >= 36 ? 34 : this.size() >= 24 ? 24 : 16));
  protected barWidth = computed(() => (this.size() >= 36 ? 1.6 : 1.9));
}
