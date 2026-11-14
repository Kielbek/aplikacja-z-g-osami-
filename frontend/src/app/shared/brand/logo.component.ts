import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { DomSanitizer } from '@angular/platform-browser';
import { BRAND_LOGO, LOGO_CONCEPTS, LogoVariant } from './logo-marks';

/**
 * Logo VoiceCraft AI.
 *  - `tile`: znak na grafitowym kafelku (ikona aplikacji),
 *  - `mark`: sam znak w kolorze tekstu,
 *  - `wordmark`: dodaje nazwę obok znaku.
 */
@Component({
  selector: 'vc-logo',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { '[attr.aria-label]': 'wordmark() ? null : "VoiceCraft AI"', role: 'img' },
  styles: `
    :host { display: inline-flex; align-items: center; gap: 10px; color: var(--text); }
    .tile {
      display: grid; place-items: center; flex-shrink: 0;
      border-radius: 26%; background: var(--graphite); color: var(--bg);
      --logo-ac: #8ea6f0;
      box-shadow: inset 0 1px 0 rgba(255,255,255,.08), 0 1px 2px rgba(20,22,26,.18);
    }
    .bare { display: grid; place-items: center; flex-shrink: 0; color: var(--text); --logo-ac: var(--accent); }
    svg { width: 100%; height: 100%; display: block; }
    .word { font-weight: 650; letter-spacing: -0.022em; line-height: 1; white-space: nowrap; }
    .word span { color: var(--text-3); font-weight: 500; margin-left: .18em; }
    :host-context([data-theme='dark']) .tile { --logo-ac: #3b5bc0; }
  `,
  template: `
    <span [class.tile]="tile()" [class.bare]="!tile()" [style.width.px]="size()" [style.height.px]="size()">
      <svg viewBox="0 0 32 32" aria-hidden="true" [innerHTML]="markup()"></svg>
    </span>
    @if (wordmark()) {
      <span class="word" [style.font-size.px]="size() * 0.62">VoiceCraft<span>AI</span></span>
    }
  `,
})
export class LogoComponent {
  private sanitizer = inject(DomSanitizer);
  readonly variant = input<LogoVariant>(BRAND_LOGO);
  readonly size = input(26);
  readonly tile = input(true);
  readonly wordmark = input(false);

  // Znaczniki pochodzą wyłącznie ze statycznej listy LOGO_CONCEPTS.
  protected markup = computed(() => {
    const c = LOGO_CONCEPTS.find((x) => x.id === this.variant()) ?? LOGO_CONCEPTS[0];
    return this.sanitizer.bypassSecurityTrustHtml(c.mark);
  });
}
