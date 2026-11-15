import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { ApiError } from '../../core/api/models';
import { AuthService } from '../../core/services/auth.service';
import { LogoComponent } from '../../shared/brand/logo.component';
import { IconComponent } from '../../shared/icon.component';

/** Ustawienie nowego hasła z linku wysłanego e-mailem (/reset-password?token=…). */
@Component({
  selector: 'vc-reset-password-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [LogoComponent, IconComponent, RouterLink],
  styleUrl: './login.page.scss',
  template: `
    <div class="auth">
      <main class="form-side">
        <div class="form-wrap">
          <div class="brand"><vc-logo [size]="40" /></div>
          <h1>Ustaw nowe hasło</h1>
          <p class="lead">Po zapisaniu zalogujemy Cię automatycznie, a pozostałe urządzenia zostaną wylogowane.</p>

          @if (!token) {
            <div class="callout callout-danger notice"><vc-icon name="circle-alert" /><div class="callout-body">Link jest niepełny. Poproś o nowy link do zmiany hasła.</div></div>
            <a class="btn btn-block" routerLink="/login">Wróć do logowania</a>
          } @else {
            @if (error(); as e) {
              <div class="callout callout-danger notice" role="alert"><vc-icon name="circle-alert" /><div class="callout-body">{{ e }}</div></div>
            }
            <form (submit)="submit($event)" novalidate>
              <div class="field">
                <label for="np">Nowe hasło</label>
                <input id="np" class="input" type="password" autocomplete="new-password" [class.is-invalid]="submitted() && !!pwError()"
                  [value]="password()" (input)="password.set($any($event.target).value)" />
              </div>
              <div class="field">
                <label for="np2">Powtórz hasło</label>
                <input id="np2" class="input" type="password" autocomplete="new-password" [class.is-invalid]="submitted() && !!pwError()"
                  [value]="repeat()" (input)="repeat.set($any($event.target).value)" />
                @if (submitted() && pwError(); as e) { <span class="field-error">{{ e }}</span> }
              </div>
              <button class="btn btn-primary btn-lg btn-block submit" type="submit" [disabled]="loading()">
                @if (loading()) { <span class="spinner"></span> } Zapisz hasło
              </button>
            </form>
            <p class="alt"><a routerLink="/login">Wróć do logowania</a></p>
          }
        </div>
      </main>
    </div>
  `,
})
export class ResetPasswordPage {
  private auth = inject(AuthService);
  protected token = inject(ActivatedRoute).snapshot.queryParamMap.get('token');
  protected password = signal('');
  protected repeat = signal('');
  protected submitted = signal(false);
  protected loading = signal(false);
  protected error = signal<string | null>(null);

  protected pwError = computed(() => {
    if (this.password().length < 8) return 'Hasło musi mieć co najmniej 8 znaków.';
    if (this.password() !== this.repeat()) return 'Hasła nie są identyczne.';
    return null;
  });

  async submit(e: Event) {
    e.preventDefault();
    this.submitted.set(true);
    if (this.pwError() || !this.token) return;
    this.loading.set(true);
    this.error.set(null);
    try {
      await this.auth.confirmPasswordReset(this.token, this.password());
      window.location.assign('/');
    } catch (err) {
      this.error.set((err as ApiError).message);
    } finally {
      this.loading.set(false);
    }
  }
}
