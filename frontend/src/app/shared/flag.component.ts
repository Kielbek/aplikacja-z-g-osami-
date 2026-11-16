import { ChangeDetectionStrategy, Component, computed, input, signal } from '@angular/core';
import { flagCountry } from '../core/util/labels';

/**
 * Flaga kraju dla języka głosu. Pliki SVG są dołączone do aplikacji (public/flags, z pakietu flag-icons, licencja MIT),
 * więc przeglądarka nie łączy się z zewnętrznym serwerem (brak przekazywania adresu IP stronie trzeciej).
 * Gdy flaga się nie wczyta, element znika — obok zawsze jest nazwa języka.
 */
@Component({
  selector: 'vc-flag',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    :host { display: inline-flex; flex-shrink: 0; line-height: 0; }
    img {
      height: var(--h);
      width: calc(var(--h) * 4 / 3);
      object-fit: cover;
      border-radius: 2px;
      box-shadow: 0 0 0 1px color-mix(in srgb, var(--text) 12%, transparent);
    }
  `,
  template: `
    @if (country(); as c) {
      @if (!failed()) {
        <img [src]="'flags/' + c + '.svg'" alt="" aria-hidden="true" [style.--h.px]="size()"
          loading="lazy" decoding="async" draggable="false" (error)="failed.set(true)" />
      }
    }
  `,
})
export class FlagComponent {
  /** Kod języka (pl, en…). */
  readonly lang = input.required<string>();
  /** Opcjonalnie głos — np. angielski amerykański vs brytyjski. */
  readonly voiceId = input<string | null>(null);
  readonly size = input(12);
  protected failed = signal(false);
  protected country = computed(() => flagCountry(this.lang(), this.voiceId()));
}
