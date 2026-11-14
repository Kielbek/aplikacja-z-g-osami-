import { Injectable, signal } from '@angular/core';

const KEY = 'vc.session';

export interface Session {
  email: string;
  name?: string;
  picture?: string;
  provider: 'password' | 'google';
}

/**
 * Sesja użytkownika. Logowanie e-mailem jest w wersji demonstracyjnej lokalne;
 * logowanie przez Google jest weryfikowane w backendzie (POST /api/auth/google).
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  readonly session = signal<Session | null>(this.read());

  isLoggedIn() {
    return this.session() !== null;
  }

  login(session: Session, remember = true) {
    try {
      (remember ? localStorage : sessionStorage).setItem(KEY, JSON.stringify(session));
    } catch {
      /* tryb prywatny */
    }
    this.session.set(session);
  }

  logout() {
    try {
      localStorage.removeItem(KEY);
      sessionStorage.removeItem(KEY);
    } catch {
      /* ignoruj */
    }
    this.session.set(null);
  }

  private read(): Session | null {
    try {
      const raw = localStorage.getItem(KEY) ?? sessionStorage.getItem(KEY);
      return raw ? JSON.parse(raw) : null;
    } catch {
      return null;
    }
  }
}
