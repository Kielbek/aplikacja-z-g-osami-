import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { ApiError } from '../../core/api/models';
import { AuthService } from '../../core/services/auth.service';
import { LogoComponent } from '../../shared/brand/logo.component';
import { IconComponent } from '../../shared/icon.component';

/** Potwierdzenie adresu e-mail z linku (/verify-email?token=…). Działa także bez zalogowania. */
@Component({
  selector: 'vc-verify-email-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [LogoComponent, IconComponent, RouterLink],
  styleUrl: './login.page.scss',
  template: `
    <div class="auth">
      <main class="form-side">
        <div class="form-wrap">
          <div class="brand"><vc-logo [size]="40" /></div>
          @switch (state()) {
            @case ('pending') {
              <h1>Potwierdzanie adresu…</h1>
              <p class="lead"><span class="spinner"></span></p>
            }
            @case ('ok') {
              <h1>Adres potwierdzony</h1>
              <p class="lead">Adres {{ email() }} jest potwierdzony. Możesz generować nagrania.</p>
              <a class="btn btn-primary btn-lg btn-block" routerLink="/">Przejdź do edytora</a>
            }
            @case ('error') {
              <h1>Nie udało się potwierdzić adresu</h1>
              <div class="callout callout-danger notice" role="alert"><vc-icon name="circle-alert" /><div class="callout-body">{{ error() }}</div></div>
              <p class="lead">Zaloguj się — na górze aplikacji znajdziesz przycisk do wysłania nowego linku.</p>
              <a class="btn btn-block" routerLink="/">Przejdź do aplikacji</a>
            }
          }
        </div>
      </main>
    </div>
  `,
})
export class VerifyEmailPage {
  private auth = inject(AuthService);
  protected state = signal<'pending' | 'ok' | 'error'>('pending');
  protected email = signal('');
  protected error = signal('');

  constructor() {
    const token = inject(ActivatedRoute).snapshot.queryParamMap.get('token');
    if (!token) {
      this.state.set('error');
      this.error.set('Link jest niepełny.');
      return;
    }
    this.auth.verifyEmail(token).then(
      (r) => {
        this.email.set(r.email);
        this.state.set('ok');
      },
      (e: ApiError) => {
        this.error.set(e.message);
        this.state.set('error');
      },
    );
  }
}
