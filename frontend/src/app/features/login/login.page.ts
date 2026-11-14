import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { AuthService } from '../../core/services/auth.service';
import { IconComponent } from '../../shared/icon.component';

@Component({
  selector: 'vc-login-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [IconComponent],
  styles: `
    :host { display: grid; place-items: center; min-height: 100dvh; padding: 24px; background: var(--bg); }
    .box { width: min(380px, 100%); animation: vc-fade-in 240ms var(--ease); }
    .logo { width: 36px; height: 36px; color: var(--graphite); margin-bottom: 28px; --logo-fg: var(--bg); }
    .logo svg { width: 100%; height: 100%; display: block; }
    h1 { font-family: var(--font-serif); font-weight: 500; font-size: 28px; letter-spacing: -0.02em; margin-bottom: 6px; }
    .lead { color: var(--text-3); font-size: var(--text-sm); margin-bottom: 28px; }
    form { display: flex; flex-direction: column; gap: 14px; }
    .input { height: 40px; font-size: var(--text-base); }
    .btn-lg { margin-top: 6px; }
    .note { margin-top: 20px; }
  `,
  template: `
    <div class="box">
      <div class="logo" aria-hidden="true">
        <svg viewBox="0 0 32 32"><rect width="32" height="32" rx="8" fill="currentColor" /><g stroke="var(--logo-fg)" stroke-width="2.4" stroke-linecap="round"><path d="M9 13v6" /><path d="M13.5 9v14" /><path d="M18 12v8" /><path d="M22.5 14.5v3" /></g></svg>
      </div>
      <h1>Zaloguj się</h1>
      <p class="lead">VoiceCraft AI — generowanie naturalnego głosu z tekstu.</p>
      <form (submit)="submit($event)">
        <div class="field">
          <label for="email">Adres e-mail</label>
          <input id="email" class="input" type="email" autocomplete="email" required [value]="email()" (input)="email.set($any($event.target).value)" />
        </div>
        <div class="field">
          <label for="password">Hasło</label>
          <input id="password" class="input" type="password" autocomplete="current-password" required [value]="password()" (input)="password.set($any($event.target).value)" />
        </div>
        @if (error()) { <span class="field-error">{{ error() }}</span> }
        <button class="btn btn-primary btn-lg btn-block" type="submit"><vc-icon name="login" />Zaloguj się</button>
      </form>
      <div class="callout note">
        <vc-icon name="info" />
        <div class="callout-body t-xs">Wersja demonstracyjna: logowanie działa lokalnie i nie jest jeszcze połączone z usługą uwierzytelniania. Dane konta są wypełnione przykładowo.</div>
      </div>
    </div>
  `,
})
export class LoginPage {
  private auth = inject(AuthService);
  private router = inject(Router);
  protected email = signal('marta@studio-glos.pl');
  protected password = signal('demo-haslo');
  protected error = signal<string | null>(null);

  submit(e: Event) {
    e.preventDefault();
    if (!/^\S+@\S+\.\S+$/.test(this.email())) return this.error.set('Podaj poprawny adres e-mail.');
    if (this.password().length < 4) return this.error.set('Hasło jest za krótkie.');
    this.auth.login(this.email());
    void this.router.navigate(['/']);
  }
}
