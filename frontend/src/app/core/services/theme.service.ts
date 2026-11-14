import { Injectable } from '@angular/core';

type Theme = 'light' | 'dark' | 'system';
const KEY = 'vc.theme';

@Injectable({ providedIn: 'root' })
export class ThemeService {
  private media = window.matchMedia('(prefers-color-scheme: dark)');
  private current: Theme = 'light';

  constructor() {
    let saved: Theme = 'light';
    try {
      saved = (localStorage.getItem(KEY) as Theme) || 'light';
    } catch {
      /* ignoruj */
    }
    this.apply(saved);
    this.media.addEventListener('change', () => this.current === 'system' && this.apply('system'));
  }

  apply(theme: Theme) {
    this.current = theme;
    try {
      localStorage.setItem(KEY, theme);
    } catch {
      /* ignoruj */
    }
    const dark = theme === 'dark' || (theme === 'system' && this.media.matches);
    document.documentElement.dataset['theme'] = dark ? 'dark' : 'light';
    document.querySelector('meta[name="theme-color"]')?.setAttribute('content', dark ? '#15171b' : '#faf9f7');
  }
}
