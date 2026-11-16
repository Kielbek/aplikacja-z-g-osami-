import { Injectable, computed, inject, signal } from '@angular/core';
import { ApiService } from '../api/api.service';
import { Account, BillingInterval, Plan, PlanId, Usage } from '../api/models';
import { ThemeService } from './theme.service';

/** Konto, plan subskrypcji i wykorzystanie limitów. */
@Injectable({ providedIn: 'root' })
export class AccountStore {
  private api = inject(ApiService);
  private theme = inject(ThemeService);

  readonly account = signal<Account | null>(null);
  readonly usage = signal<Usage | null>(null);
  readonly plans = signal<Plan[]>([]);

  /** Bieżący plan z jego limitami. */
  readonly plan = computed<Plan | null>(
    () => this.plans().find((p) => p.id === this.account()?.planId) ?? this.usage()?.plan ?? null,
  );

  readonly charactersLeft = computed(() => {
    const u = this.usage();
    if (!u) return Infinity;
    const plan = u.minutesUsed >= u.minuteLimit ? 0 : Math.max(0, u.characterLimit - u.charactersUsed);
    return Math.max(0, plan + (u.extraCharacters ?? 0) - (u.reservedCharacters ?? 0));
  });
  /** Limit planu wyczerpany i brak dokupionych znaków. */
  readonly quotaExhausted = computed(() => {
    const u = this.usage();
    return !!u && (u.charactersUsed >= u.characterLimit || u.minutesUsed >= u.minuteLimit) && !(u.extraCharacters > 0);
  });
  readonly projectLimitReached = computed(() => {
    const u = this.usage();
    return !!u && u.projectLimit >= 0 && u.projectsUsed >= u.projectLimit;
  });
  readonly customVoiceLimitReached = computed(() => {
    const u = this.usage();
    return !!u && u.customVoicesUsed >= u.customVoiceLimit;
  });

  async load() {
    try {
      const [a, plans] = await Promise.all([this.api.account(), this.api.plans()]);
      this.account.set(a);
      this.plans.set(plans);
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

  async changePlan(id: PlanId, interval: BillingInterval = 'month') {
    const a = await this.api.changePlan(id, interval);
    this.account.set(a);
    await this.refreshUsage();
    return a;
  }

  /** Najniższy plan spełniający warunek — do komunikatów „dostępne od planu …”. */
  lowestPlan(predicate: (p: Plan) => boolean): Plan | undefined {
    return this.plans().find(predicate);
  }
}
