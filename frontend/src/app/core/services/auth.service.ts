import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { API, toApiError } from '../api/api.service';

export interface SessionUser {
  id: string;
  email: string;
  name: string;
  picture: string | null;
  hasPassword: boolean;
  google: boolean;
}

export interface AuthConfig {
  googleEnabled: boolean;
  googleClientId: string | null;
  emailDelivery: boolean;
  demoAccount?: { email: string; password: string };
}

/**
 * Sesja użytkownika. Token sesji jest w ciasteczku httpOnly ustawianym przez backend —
 * JavaScript nie ma do niego dostępu (ochrona przed kradzieżą przez XSS).
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private http = inject(HttpClient);
  private loaded: Promise<SessionUser | null> | null = null;
  private config: Promise<AuthConfig> | null = null;

  readonly user = signal<SessionUser | null>(null);

  /** Sprawdza sesję w backendzie (raz na uruchomienie aplikacji). */
  ensureLoaded(): Promise<SessionUser | null> {
    this.loaded ??= firstValueFrom(this.http.get<SessionUser>(`${API}/auth/me`))
      .then((u) => {
        this.user.set(u);
        return u;
      })
      .catch(() => {
        this.user.set(null);
        return null;
      });
    return this.loaded;
  }

  loadConfig(): Promise<AuthConfig> {
    this.config ??= firstValueFrom(this.http.get<AuthConfig>(`${API}/auth/config`)).catch(() => ({
      googleEnabled: false,
      googleClientId: null,
      emailDelivery: false,
    }));
    return this.config;
  }

  login(email: string, password: string, remember: boolean) {
    return this.session(this.http.post<SessionUser>(`${API}/auth/login`, { email, password, remember }));
  }

  register(name: string, email: string, password: string) {
    return this.session(this.http.post<SessionUser>(`${API}/auth/register`, { name, email, password, remember: true }));
  }

  loginWithGoogleToken(accessToken: string, remember: boolean) {
    return this.session(this.http.post<SessionUser>(`${API}/auth/google`, { accessToken, remember }));
  }

  requestPasswordReset(email: string) {
    return this.call(this.http.post<{ sent: boolean }>(`${API}/auth/password-reset`, { email }));
  }

  confirmPasswordReset(token: string, password: string) {
    return this.session(this.http.post<SessionUser>(`${API}/auth/password-reset/confirm`, { token, password }));
  }

  changePassword(currentPassword: string, newPassword: string) {
    return this.session(this.http.post<SessionUser>(`${API}/auth/password`, { currentPassword, newPassword }));
  }

  logoutEverywhere() {
    return this.session(this.http.post<SessionUser>(`${API}/auth/logout-all`, {}));
  }

  async deleteAccount(password: string) {
    await this.call(this.http.post<void>(`${API}/auth/delete-account`, { password }));
    this.hardReset('/login');
  }

  async logout() {
    try {
      await firstValueFrom(this.http.post<void>(`${API}/auth/logout`, {}));
    } finally {
      this.hardReset('/login');
    }
  }

  /** Wywoływane po 401 — sesja wygasła lub została unieważniona. */
  markLoggedOut() {
    this.user.set(null);
    this.loaded = Promise.resolve(null);
  }

  /** Pełne przeładowanie czyści stan poprzedniego użytkownika (projekty, głosy, bufor audio). */
  private hardReset(path: string) {
    this.markLoggedOut();
    window.location.assign(path);
  }

  private async session(obs: ReturnType<HttpClient['post']>): Promise<SessionUser> {
    const u = (await this.call(obs)) as SessionUser;
    this.user.set(u);
    this.loaded = Promise.resolve(u);
    return u;
  }

  private async call<T>(obs: import('rxjs').Observable<T>): Promise<T> {
    try {
      return await firstValueFrom(obs);
    } catch (e) {
      throw toApiError(e);
    }
  }
}
