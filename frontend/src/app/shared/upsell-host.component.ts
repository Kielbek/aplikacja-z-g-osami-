import { ChangeDetectionStrategy, Component, HostListener, computed, effect, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { ApiService } from '../core/api/api.service';
import { BillingConfig, CreditPack, Plan } from '../core/api/models';
import { AccountStore } from '../core/services/account.store';
import { ToastService } from '../core/services/toast.service';
import { UpsellService } from '../core/services/upsell.service';
import { formatDate, formatNumber } from '../core/util/format';
import { IconComponent } from './icon.component';

const price = (minor: number) =>
  (minor / 100).toLocaleString('pl-PL', { style: 'currency', currency: 'PLN', minimumFractionDigits: minor % 100 ? 2 : 0 });

/**
 * Okno „Za mało znaków”: jednorazowy pakiet znaków (bez subskrypcji) albo wyższy plan.
 * Polecany pakiet to najmniejszy, który pokrywa brakującą liczbę znaków.
 */
@Component({
  selector: 'vc-upsell-host',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [IconComponent],
  styles: `
    .scrim { position: fixed; inset: 0; z-index: 90; display: grid; place-items: center; padding: 16px; background: var(--scrim); animation: vc-fade-in 140ms var(--ease); }
    .dialog { width: min(680px, 100%); max-height: calc(100dvh - 32px); overflow-y: auto; background: var(--surface-raised); border-radius: var(--radius-xl); box-shadow: var(--shadow-lg); }
    .head { display: flex; gap: 14px; padding: 22px 22px 0; }
    .icon { display: grid; place-items: center; width: 42px; height: 42px; flex-shrink: 0; border-radius: 12px; background: var(--accent-soft); }
    h2 { font-family: var(--font-display); font-size: 21px; font-weight: 700; letter-spacing: -0.02em; margin: 0 0 4px; }
    .lead { margin: 0; color: var(--text-2); font-size: var(--text-sm); line-height: 1.5; }
    .x { margin-left: auto; align-self: flex-start; }
    .stats { display: grid; grid-template-columns: repeat(3, 1fr); gap: 1px; margin: 18px 22px 0; border: 1px solid var(--border); border-radius: 12px; overflow: hidden; background: var(--border); }
    .stats div { display: flex; flex-direction: column; gap: 2px; padding: 10px 14px; background: var(--surface); }
    .stats span { font-size: 11px; font-weight: 600; text-transform: uppercase; letter-spacing: 0.05em; color: var(--text-3); }
    .stats b { font-size: 18px; font-variant-numeric: tabular-nums; }
    .stats .miss b { color: var(--danger); }
    .cols { display: grid; grid-template-columns: 1fr 1fr; gap: 18px; padding: 20px 22px; }
    .col h3 { margin: 0 0 2px; font-size: 14px; }
    .col p.sub { margin: 0 0 12px; font-size: 12.5px; color: var(--text-3); }
    .opt { position: relative; display: flex; align-items: center; gap: 12px; padding: 11px 12px; border: 1px solid var(--border); border-radius: 12px; background: var(--surface); }
    .opt + .opt { margin-top: 8px; }
    .opt.best { border-color: var(--text); box-shadow: 0 0 0 1px var(--text); }
    .opt-main { flex: 1; min-width: 0; display: flex; flex-direction: column; }
    .opt-main b { font-size: 14px; }
    .opt-main small { font-size: 12px; color: var(--text-3); }
    .opt-price { font-weight: 600; font-variant-numeric: tabular-nums; white-space: nowrap; }
    .tag { position: absolute; top: -9px; left: 12px; padding: 1px 8px; border-radius: 999px; font-size: 10.5px; font-weight: 600; background: var(--text); color: var(--bg); }
    .tag.intro { left: auto; right: 12px; background: var(--success-soft); color: var(--success); }
    .foot { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 14px 22px 18px; border-top: 1px solid var(--border-subtle); font-size: 12.5px; color: var(--text-3); }
    .note { margin: 14px 22px 0; font-size: 12.5px; line-height: 1.5; color: var(--text-2); }
    .demo { margin: 12px 22px 0; padding: 8px 12px; border-radius: 10px; font-size: 12.5px; background: var(--demo-soft, var(--accent-soft)); color: var(--demo-text, var(--text-2)); }
    @media (max-width: 640px) {
      .cols { grid-template-columns: 1fr; }
      .stats b { font-size: 15px; }
      .head, .cols { padding-left: 16px; padding-right: 16px; }
      .stats, .demo { margin-left: 16px; margin-right: 16px; }
    }
  `,
  template: `
    @if (upsell.request(); as r) {
      <div class="scrim" (click)="upsell.close()">
        <div class="dialog" role="dialog" aria-modal="true" aria-labelledby="upsell-title" (click)="$event.stopPropagation()">
          <div class="head">
            <div class="icon"><vc-icon name="gauge" [size]="20" /></div>
            <div>
              <h2 id="upsell-title">Za mało znaków na to nagranie</h2>
              <p class="lead">Dokup jednorazowy pakiet znaków albo przejdź na wyższy plan — generowanie ruszy od razu, bez utraty tego, co już masz.</p>
            </div>
            <button class="btn btn-ghost btn-icon btn-sm x" (click)="upsell.close()" aria-label="Zamknij"><vc-icon name="x" /></button>
          </div>

          @if (r.needed !== undefined) {
            <div class="stats">
              <div><span>Potrzeba</span><b>{{ fmt(r.needed) }}</b></div>
              <div><span>Dostępne</span><b>{{ fmt(r.available ?? 0) }}</b></div>
              <div class="miss"><span>Brakuje</span><b>{{ fmt(shortfall()) }}</b></div>
            </div>
          }

          @if (minutesOut()) {
            <p class="note">Wykorzystano miesięczny limit minut audio planu ({{ account.usage()?.minuteLimit }} min) — dlatego dostępnych znaków z planu jest 0. Znaki z pakietu działają niezależnie od limitu minut.</p>
          }
          @if (billing() && !billing()!.stripeEnabled) {
            <p class="demo">Tryb deweloperski: pakiet zostanie dopisany bez opłaty, a plan zmieni się bez płatności.</p>
          }

          <div class="cols">
            <div class="col">
              <h3>Jednorazowo — pakiet znaków</h3>
              <p class="sub">Bez subskrypcji. Znaki nie wygasają.</p>
              @for (p of packs(); track p.id) {
                <div class="opt" [class.best]="p.id === bestPack()?.id">
                  @if (p.id === bestPack()?.id) { <span class="tag">Wystarczy na ten tekst</span> }
                  <div class="opt-main"><b>{{ fmt(p.characters) }} znaków</b><small>{{ p.name }} · {{ perK(p.priceMinor, p.characters) }} / 1000 zn.</small></div>
                  <span class="opt-price">{{ money(p.priceMinor) }}</span>
                  <button class="btn btn-sm" [class.btn-primary]="p.id === bestPack()?.id" [disabled]="busy() !== null" (click)="buy(p)">
                    @if (busy() === p.id) { <span class="spinner"></span> } Kup
                  </button>
                </div>
              }
            </div>
            <div class="col">
              <h3>Subskrypcja — więcej co miesiąc</h3>
              <p class="sub">Taniej za znak przy regularnym generowaniu.</p>
              @for (pl of upgrades(); track pl.id) {
                <div class="opt" [class.best]="pl.id === bestPlan()?.id && !bestPack()">
                  @if (introPct()) { <span class="tag intro">1. miesiąc −{{ introPct() }}%</span> }
                  <div class="opt-main"><b>{{ pl.name }}</b><small>{{ fmt(pl.monthlyCharacters) }} znaków / mies.</small></div>
                  <span class="opt-price">{{ money(pl.monthlyPriceMinor) }}<small> / mies.</small></span>
                  <button class="btn btn-sm" (click)="choosePlan()">Wybierz</button>
                </div>
              } @empty {
                <p class="sub">Masz już najwyższy plan — dokup pakiet znaków.</p>
              }
            </div>
          </div>

          <div class="foot">
            <span>@if (resetOn()) { Limit planu odnowi się {{ resetOn() }}. }</span>
            <button class="btn btn-sm btn-ghost" (click)="upsell.close()">Nie teraz</button>
          </div>
        </div>
      </div>
    }
  `,
})
export class UpsellHostComponent {
  protected upsell = inject(UpsellService);
  private api = inject(ApiService);
  protected account = inject(AccountStore);
  private toast = inject(ToastService);
  private router = inject(Router);

  protected packs = signal<CreditPack[]>([]);
  protected billing = signal<BillingConfig | null>(null);
  protected busy = signal<string | null>(null);
  protected fmt = formatNumber;
  protected money = price;

  protected shortfall = computed(() => {
    const r = this.upsell.request();
    return r?.needed === undefined ? 0 : Math.max(0, r.needed - (r.available ?? 0));
  });
  /** Najmniejszy pakiet pokrywający brak (albo największy, gdy żaden nie wystarcza). */
  protected bestPack = computed(() => {
    const need = this.shortfall();
    const list = [...this.packs()].sort((a, b) => a.characters - b.characters);
    if (!need || !list.length) return null;
    return list.find((p) => p.characters >= need) ?? list[list.length - 1];
  });
  protected upgrades = computed<Plan[]>(() => {
    const plans = this.account.plans();
    const current = this.account.plan();
    const idx = current ? plans.findIndex((p) => p.id === current.id) : -1;
    return plans.slice(idx + 1).filter((p) => p.monthlyPriceMinor > 0).slice(0, 2);
  });
  protected bestPlan = computed(() => {
    const need = this.upsell.request()?.needed ?? 0;
    return this.upgrades().find((p) => p.monthlyCharacters >= need) ?? this.upgrades()[0] ?? null;
  });
  protected minutesOut = computed(() => {
    const u = this.account.usage();
    return !!u && u.minutesUsed >= u.minuteLimit;
  });
  protected introPct = computed(() => (this.billing()?.introEligible ? this.billing()!.introPercentOff : 0));
  protected resetOn = computed(() => {
    const d = this.account.usage()?.limitsResetOn;
    return d ? formatDate(d) : '';
  });

  constructor() {
    // Dane do oferty pobieramy przy pierwszym otwarciu okna
    effect(() => {
      if (!this.upsell.request()) return;
      if (!this.packs().length) void this.api.billingPacks().then((p) => this.packs.set(p)).catch(() => null);
      void this.api.billingConfig().then((b) => this.billing.set(b)).catch(() => null);
      void this.account.refreshUsage();
    });
  }

  protected perK(priceMinor: number, chars: number) {
    return (priceMinor / 100 / (chars / 1000)).toLocaleString('pl-PL', { style: 'currency', currency: 'PLN', minimumFractionDigits: 2 });
  }

  async buy(p: CreditPack) {
    const retry = this.upsell.request()?.retry;
    this.busy.set(p.id);
    try {
      const r = await this.api.buyPack(p.id);
      if (r.url) {
        window.location.assign(r.url);
        return;
      }
      await this.account.refreshUsage();
      this.upsell.close();
      this.toast.success(`Dodano ${formatNumber(p.characters)} znaków`, retry ? 'Wznawiam generowanie.' : undefined);
      retry?.();
    } catch (e) {
      this.toast.apiError('Nie udało się kupić pakietu', e);
    } finally {
      this.busy.set(null);
    }
  }

  choosePlan() {
    this.upsell.close();
    void this.router.navigate(['/settings/subscription']);
  }

  @HostListener('document:keydown.escape')
  onEsc() {
    if (this.upsell.request()) this.upsell.close();
  }
}
