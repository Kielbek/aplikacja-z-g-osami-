import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { AuthService } from '../../core/services/auth.service';
import { GoogleAuthError, GoogleAuthService } from '../../core/services/google-auth.service';
import { LogoComponent } from '../../shared/brand/logo.component';
import { IconComponent } from '../../shared/icon.component';

type Mode = 'login' | 'register' | 'reset';

@Component({
  selector: 'vc-login-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [IconComponent, LogoComponent],
  styleUrl: './login.page.scss',
  templateUrl: './login.page.html',
})
export class LoginPage {
  private auth = inject(AuthService);
  private google = inject(GoogleAuthService);
  private router = inject(Router);

  protected mode = signal<Mode>('login');
  protected name = signal('');
  protected email = signal('marta@studio-glos.pl');
  protected password = signal('demo-haslo');
  protected showPassword = signal(false);
  protected remember = signal(true);
  protected acceptTerms = signal(false);
  protected submitted = signal(false);
  protected loading = signal(false);
  protected googleLoading = signal(false);
  protected notice = signal<{ tone: 'info' | 'danger' | 'success'; text: string } | null>(null);
  protected year = new Date().getFullYear();

  protected emailError = computed(() => {
    if (!this.submitted()) return null;
    if (!this.email().trim()) return 'Podaj adres e-mail.';
    return /^\S+@\S+\.\S+$/.test(this.email().trim()) ? null : 'Adres e-mail ma niepoprawny format.';
  });
  protected passwordError = computed(() => {
    if (!this.submitted() || this.mode() === 'reset') return null;
    if (!this.password()) return 'Podaj hasło.';
    if (this.mode() === 'register' && this.password().length < 8) return 'Hasło musi mieć co najmniej 8 znaków.';
    return null;
  });
  protected nameError = computed(() => (this.submitted() && this.mode() === 'register' && !this.name().trim() ? 'Podaj imię i nazwisko.' : null));
  protected termsError = computed(() =>
    this.submitted() && this.mode() === 'register' && !this.acceptTerms() ? 'Zaakceptuj regulamin, aby utworzyć konto.' : null,
  );
  protected passwordStrength = computed(() => {
    const p = this.password();
    let score = 0;
    if (p.length >= 8) score++;
    if (p.length >= 12) score++;
    if (/[A-Z]/.test(p) && /[a-z]/.test(p)) score++;
    if (/\d/.test(p) || /[^A-Za-z0-9]/.test(p)) score++;
    return Math.min(score, 4);
  });
  protected strengthLabel = computed(() => ['Bardzo słabe', 'Słabe', 'Średnie', 'Dobre', 'Silne'][this.passwordStrength()]);

  constructor() {
    void this.google.loadConfig();
  }

  setMode(m: Mode) {
    this.mode.set(m);
    this.submitted.set(false);
    this.notice.set(null);
    if (m === 'register') {
      this.password.set('');
      this.email.set('');
    }
  }

  async submit(e: Event) {
    e.preventDefault();
    this.submitted.set(true);
    this.notice.set(null);
    if (this.emailError() || this.passwordError() || this.nameError() || this.termsError()) return;
    this.loading.set(true);
    await new Promise((r) => setTimeout(r, 450));
    this.loading.set(false);
    if (this.mode() === 'reset') {
      this.notice.set({
        tone: 'info',
        text: `Jeśli konto ${this.email().trim()} istnieje, wyślemy na nie link do zmiany hasła. W wersji demonstracyjnej wiadomości e-mail nie są wysyłane.`,
      });
      return;
    }
    this.auth.login(
      { email: this.email().trim(), name: this.mode() === 'register' ? this.name().trim() : undefined, provider: 'password' },
      this.remember(),
    );
    void this.router.navigate(['/']);
  }

  async signInWithGoogle() {
    if (this.googleLoading()) return;
    this.notice.set(null);
    this.googleLoading.set(true);
    try {
      const session = await this.google.signIn();
      this.auth.login(session, this.remember());
      void this.router.navigate(['/']);
    } catch (e) {
      const err = e as GoogleAuthError;
      if (err.kind !== 'cancelled') this.notice.set({ tone: err.kind === 'not-configured' ? 'info' : 'danger', text: err.message });
    } finally {
      this.googleLoading.set(false);
    }
  }
}
