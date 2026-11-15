import { ChangeDetectionStrategy, Component, inject, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { EngineStatusService } from '../core/services/engine-status.service';
import { IconComponent } from './icon.component';

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
  `,
  template: `
    @if (engine.isDemo()) {
      <div class="callout callout-demo" [class.compact]="compact()" role="note">
        <vc-icon name="flask" />
        <div class="callout-body">
          <span><strong>Tryb demonstracyjny.</strong> Model VoxCPM2 nie jest podłączony — zamiast mowy generowany jest sygnał zastępczy, który pozwala przetestować przepływ pracy.</span>
        </div>
        <a routerLink="/settings/engine">Jak podłączyć</a>
      </div>
    } @else if (engine.isPiper()) {
      <div class="callout callout-info" [class.compact]="compact()" role="note">
        <vc-icon name="info" />
        <div class="callout-body">
          <span><strong>Silnik Piper (CPU).</strong> Prawdziwa mowa z gotowych głosów — kilka głosów z biblioteki może brzmieć tak samo. Projektowanie i klonowanie głosu wymagają modelu VoxCPM2 (GPU).</span>
        </div>
        <a routerLink="/settings/engine">Szczegóły</a>
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
}
