import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { ApiError } from '../../core/api/models';
import { AuthConfig, AuthService } from '../../core/services/auth.service';
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
  private route = inject(ActivatedRoute);
  protected config = signal<AuthConfig | null>(null);

  protected mode = signal<Mode>('login');
  protected name = signal('');
  protected email = signal('');
  protected password = signal('');
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
    void this.auth.loadConfig().then((c) => this.config.set(c));
    if (this.route.snapshot.queryParamMap.get('expired')) {
      this.notice.set({ tone: 'info', text: 'Sesja wygasła. Zaloguj się ponownie.' });
    }
  }

  useDemoAccount() {
    const demo = this.config()?.demoAccount;
    if (!demo) return;
    this.setMode('login');
    this.email.set(demo.email);
    this.password.set(demo.password);
  }

  /** Pełne przeładowanie po zalogowaniu — aplikacja startuje z danymi nowego użytkownika. */
  private enterApp() {
    const target = this.route.snapshot.queryParamMap.get('returnUrl');
    window.location.assign(target && target.startsWith('/') && !target.startsWith('//') ? target : '/');
  }

  setMode(m: Mode) {
    this.mode.set(m);
    this.submitted.set(false);
    this.notice.set(null);
    if (m === 'register') this.password.set('');
  }

  async submit(e: Event) {
    e.preventDefault();
    this.submitted.set(true);
    this.notice.set(null);
    if (this.emailError() || this.passwordError() || this.nameError() || this.termsError()) return;
    this.loading.set(true);
    try {
      const email = this.email().trim();
      if (this.mode() === 'reset') {
        await this.auth.requestPasswordReset(email);
        this.notice.set({
          tone: 'success',
          text: `Jeśli konto ${email} istnieje, wysłaliśmy na nie link do ustawienia nowego hasła. Link jest ważny przez godzinę.`,
        });
        return;
      }
      if (this.mode() === 'register') await this.auth.register(this.name().trim(), email, this.password());
      else await this.auth.login(email, this.password(), this.remember());
      this.enterApp();
    } catch (e) {
      this.notice.set({ tone: 'danger', text: (e as ApiError).message });
    } finally {
      this.loading.set(false);
    }
  }

  async signInWithGoogle() {
    if (this.googleLoading()) return;
    this.notice.set(null);
    this.googleLoading.set(true);
    try {
      await this.google.signIn(this.remember());
      this.enterApp();
    } catch (e) {
      const err = e as GoogleAuthError;
      if (err.kind !== 'cancelled') this.notice.set({ tone: err.kind === 'not-configured' ? 'info' : 'danger', text: err.message });
    } finally {
      this.googleLoading.set(false);
    }
  }
}
