import { Injectable, inject, signal } from '@angular/core';
import { ApiService } from '../api/api.service';
import { Account, Usage } from '../api/models';
import { ThemeService } from './theme.service';

@Injectable({ providedIn: 'root' })
export class AccountStore {
  private api = inject(ApiService);
  private theme = inject(ThemeService);

  readonly account = signal<Account | null>(null);
  readonly usage = signal<Usage | null>(null);

  async load() {
    try {
      const a = await this.api.account();
      this.account.set(a);
      this.theme.apply(a.theme);
    } catch {
      /* backend niedostępny — interfejs pokaże stan offline */
    }
    await this.refreshUsage();
  }

  async refreshUsage() {
    try {
      this.usage.set(await this.api.usage());
    } catch {
      /* ignoruj */
    }
  }

  async update(patch: Partial<Account>) {
    const a = await this.api.updateAccount(patch);
    this.account.set(a);
    if (patch.theme) this.theme.apply(a.theme);
    return a;
  }
}
