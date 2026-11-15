import { Injectable, inject } from '@angular/core';
import { ApiError } from '../api/models';
import { AuthService, SessionUser } from './auth.service';

interface TokenResponse {
  access_token?: string;
  error?: string;
  error_description?: string;
}

interface GoogleOAuth2 {
  initTokenClient(cfg: {
    client_id: string;
    scope: string;
    callback: (r: TokenResponse) => void;
    error_callback?: (e: { type: string }) => void;
  }): { requestAccessToken(o?: { prompt?: string }): void };
}

declare global {
  interface Window {
    google?: { accounts: { oauth2: GoogleOAuth2 } };
  }
}

export class GoogleAuthError extends Error {
  constructor(message: string, readonly kind: 'not-configured' | 'cancelled' | 'failed') {
    super(message);
  }
}

/** Logowanie przez Google Identity Services; token jest weryfikowany i wymieniany na sesję w backendzie. */
@Injectable({ providedIn: 'root' })
export class GoogleAuthService {
  private auth = inject(AuthService);
  private script: Promise<void> | null = null;

  async signIn(remember: boolean): Promise<SessionUser> {
    const cfg = await this.auth.loadConfig();
    if (!cfg.googleEnabled || !cfg.googleClientId) {
      throw new GoogleAuthError(
        'Logowanie przez Google nie jest jeszcze skonfigurowane. Administrator musi ustawić GOOGLE_CLIENT_ID w backendzie.',
        'not-configured',
      );
    }
    await this.loadScript();
    const token = await new Promise<string>((resolve, reject) => {
      const client = window.google!.accounts.oauth2.initTokenClient({
        client_id: cfg.googleClientId!,
        scope: 'openid email profile',
        callback: (r) =>
          r.access_token ? resolve(r.access_token) : reject(new GoogleAuthError(r.error_description ?? 'Logowanie przerwane.', 'failed')),
        error_callback: (e) =>
          reject(new GoogleAuthError(e.type === 'popup_closed' ? 'Zamknięto okno logowania Google.' : 'Nie udało się otworzyć okna Google.', 'cancelled')),
      });
      client.requestAccessToken({ prompt: 'select_account' });
    });
    try {
      return await this.auth.loginWithGoogleToken(token, remember);
    } catch (e) {
      throw new GoogleAuthError((e as ApiError).message, 'failed');
    }
  }

  private loadScript(): Promise<void> {
    this.script ??= new Promise((resolve, reject) => {
      if (window.google?.accounts?.oauth2) return resolve();
      const s = document.createElement('script');
      s.src = 'https://accounts.google.com/gsi/client';
      s.async = true;
      s.onload = () => resolve();
      s.onerror = () => {
        this.script = null;
        reject(new GoogleAuthError('Nie udało się wczytać usług Google. Sprawdź połączenie z internetem.', 'failed'));
      };
      document.head.appendChild(s);
    });
    return this.script;
  }
}
