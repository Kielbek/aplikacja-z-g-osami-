import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { map } from 'rxjs';
import { LogoComponent } from '../../shared/brand/logo.component';

/** Wersja dokumentów — taka sama jak LegalDocuments.TERMS_VERSION w backendzie. */
export const LEGAL_VERSION = '2026-11-15';

/**
 * Regulamin i polityka prywatności (z informacją o cookies). Strony publiczne.
 * Treść jest szablonem przygotowanym pod funkcje aplikacji — przed udostępnieniem usługi
 * musi ją zweryfikować prawnik i uzupełnić dane usługodawcy.
 */
@Component({
  selector: 'vc-legal-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, LogoComponent],
  styleUrl: './legal.page.scss',
  templateUrl: './legal.page.html',
})
export class LegalPage {
  private route = inject(ActivatedRoute);
  protected doc = toSignal(this.route.data.pipe(map((d) => d['doc'] as 'terms' | 'privacy')), { initialValue: 'terms' as const });
  protected title = computed(() => (this.doc() === 'terms' ? 'Regulamin' : 'Polityka prywatności i cookies'));
  protected version = LEGAL_VERSION;
}
