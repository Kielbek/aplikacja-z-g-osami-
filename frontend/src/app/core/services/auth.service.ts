import { Injectable, signal } from '@angular/core';

const KEY = 'vc.session';

/**
 * Sesja użytkownika. W wersji demonstracyjnej logowanie jest lokalne —
 * miejsce na integrację z prawdziwym dostawcą (np. Spring Security + OAuth2/JWT).
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  readonly session = signal<{ email: string } | null>(this.read());

  isLoggedIn() {
    return this.session() !== null;
  }

  login(email: string) {
    const s = { email };
    try {
      localStorage.setItem(KEY, JSON.stringify(s));
    } catch {
      /* tryb prywatny */
    }
    this.session.set(s);
  }

  logout() {
    try {
      localStorage.removeItem(KEY);
    } catch {
      /* ignoruj */
    }
    this.session.set(null);
  }

  private read(): { email: string } | null {
    try {
      const raw = localStorage.getItem(KEY);
      return raw ? JSON.parse(raw) : null;
    } catch {
      return null;
    }
  }
}
