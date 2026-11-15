import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { EngineStatusService } from '../core/services/engine-status.service';
import { IconComponent } from './icon.component';

const DISMISS_KEY = 'vc.engineBannerDismissed';

function readDismissed(): string[] {
  try {
    const v = JSON.parse(localStorage.getItem(DISMISS_KEY) ?? '[]');
    return Array.isArray(v) ? v.filter((x) => typeof x === 'string') : [];
  } catch {
    return [];
  }
}

/** Wyraźna informacja o trybie demonstracyjnym, silniku Piper lub braku połączenia z silnikiem. */
@Component({
  selector: 'vc-engine-banner',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [IconComponent, RouterLink],
  styles: `
    :host { display: block; }
    .callout { align-items: center; padding: 9px 12px 9px 14px; }
    .callout a { color: inherit; font-weight: 600; text-decoration: underline; text-underline-offset: 3px; white-space: nowrap; }
    .compact { font-size: var(--text-xs); }
    .close {
      display: grid; place-items: center; flex-shrink: 0;
      width: 26px; height: 26px; margin: -2px -4px -2px 2px;
      border: 0; border-radius: 6px; background: transparent; color: inherit; opacity: 0.7; cursor: pointer;
      &:hover { opacity: 1; background: color-mix(in srgb, currentColor 10%, transparent); }
    }
  `,
  template: `
    @if (engine.isDemo() && !hidden()) {
      <div class="callout callout-demo" [class.compact]="compact()" role="note">
        <vc-icon name="flask" />
        <div class="callout-body">
          <span><strong>Tryb demonstracyjny.</strong> Model VoxCPM2 nie jest podłączony — zamiast mowy generowany jest sygnał zastępczy, który pozwala przetestować przepływ pracy.</span>
        </div>
        <a routerLink="/settings/engine">Jak podłączyć</a>
        <button class="close" (click)="dismiss()" aria-label="Zamknij informację" title="Zamknij"><vc-icon name="x" [size]="14" /></button>
      </div>
    } @else if (engine.isPiper() && !hidden()) {
      <div class="callout callout-info" [class.compact]="compact()" role="note">
        <vc-icon name="info" />
        <div class="callout-body">
          <span><strong>Silnik Piper (CPU).</strong> Prawdziwa mowa z gotowych głosów — kilka głosów z biblioteki może brzmieć tak samo. Projektowanie i klonowanie głosu wymagają modelu VoxCPM2 (GPU).</span>
        </div>
        <a routerLink="/settings/engine">Szczegóły</a>
        <button class="close" (click)="dismiss()" aria-label="Zamknij informację" title="Zamknij"><vc-icon name="x" [size]="14" /></button>
      </div>
    } @else if (engine.isOffline()) {
      <div class="callout callout-danger" [class.compact]="compact()" role="alert">
        <vc-icon name="plug" />
        <div class="callout-body">
          <span><strong>{{ engine.label() }}.</strong>
            @if (engine.backendDown()) { Nie można połączyć się z serwerem aplikacji. }
            @else { Serwis silnika TTS nie odpowiada — generowanie jest niedostępne. }
          </span>
        </div>
        <a routerLink="/settings/engine">Szczegóły</a>
      </div>
    }
  `,
})
export class EngineBannerComponent {
  protected engine = inject(EngineStatusService);
  readonly compact = input(false);

  /** Zamknięte informacje (osobno dla trybu demo i Piper) — wspólne dla wszystkich ekranów, zapamiętane w przeglądarce. */
  private static readonly dismissed = signal<string[]>(readDismissed());
  protected hidden = computed(() => EngineBannerComponent.dismissed().includes(this.engine.mode()));

  protected dismiss() {
    const next = [...new Set([...EngineBannerComponent.dismissed(), this.engine.mode()])];
    EngineBannerComponent.dismissed.set(next);
    try {
      localStorage.setItem(DISMISS_KEY, JSON.stringify(next));
    } catch {
      /* brak dostępu do pamięci przeglądarki */
    }
  }
}

