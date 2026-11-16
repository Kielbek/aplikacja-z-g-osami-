import { ChangeDetectionStrategy, Component, HostListener, computed, effect, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { ApiService } from '../core/api/api.service';
import { BillingConfig, BillingInterval, CreditPack, Plan } from '../core/api/models';
import { AccountStore } from '../core/services/account.store';
import { ToastService } from '../core/services/toast.service';
import { UpsellService } from '../core/services/upsell.service';
import { formatBytes, formatDate, formatNumber } from '../core/util/format';
import { IconComponent } from './icon.component';

const money = (minor: number) =>
  (minor / 100).toLocaleString('pl-PL', { style: 'currency', currency: 'PLN', minimumFractionDigits: minor % 100 ? 2 : 0 });

/**
 * Okno „Skończyły się znaki”: karty wyższych planów (miesięcznie / rocznie) z wyborem od razu w oknie
 * oraz jednorazowe pakiety znaków. Po dokupieniu znaków lub zmianie planu przerwana akcja rusza sama.
 */
@Component({
  selector: 'vc-upsell-host',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [IconComponent],
  styles: `
    .scrim { position: fixed; inset: 0; z-index: 90; display: grid; place-items: center; padding: 16px; background: var(--scrim); animation: vc-fade-in 140ms var(--ease); }
    .dialog { position: relative; width: min(780px, 100%); max-height: calc(100dvh - 32px); overflow-y: auto; background: var(--surface-raised); border-radius: 22px; box-shadow: var(--shadow-lg); padding: 30px 28px 22px; animation: dlg-in 180ms var(--ease); }
    @keyframes dlg-in { from { opacity: 0; transform: translateY(8px) scale(0.99); } }
    .x { position: absolute; top: 14px; right: 14px; }
    header { text-align: center; }
    h2 { font-family: var(--font-display); font-size: 25px; font-weight: 700; letter-spacing: -0.025em; margin: 0 0 6px; }
    .sub { margin: 0 auto; max-width: 520px; color: var(--text-3); font-size: var(--text-sm); line-height: 1.55; }
    .sub b { color: var(--text); font-variant-numeric: tabular-nums; }
    .note { margin: 10px auto 0; max-width: 560px; font-size: 12.5px; color: var(--text-3); }
    .toggle { display: flex; justify-content: center; margin: 18px 0 20px; }
    .seg { display: inline-flex; padding: 4px; gap: 2px; border-radius: 12px; background: var(--bg-sunken); border: 1px solid var(--border-subtle); }
    .seg button { display: inline-flex; align-items: center; gap: 8px; height: 34px; padding: 0 16px; border: 0; border-radius: 9px; background: transparent; color: var(--text-2); font: inherit; font-size: 14px; font-weight: 500; cursor: pointer; }
    .seg button.on { background: var(--surface); color: var(--text); font-weight: 600; box-shadow: 0 1px 2px rgba(0,0,0,.08), 0 0 0 1px var(--border); }
    .save { padding: 2px 7px; border-radius: 999px; font-size: 11px; font-weight: 600; background: var(--text); color: var(--bg); }
    .plans { display: grid; grid-auto-flow: column; grid-auto-columns: minmax(210px, 1fr); gap: 12px; overflow-x: auto; padding: 12px 2px 6px; scroll-snap-type: x mandatory; }
    .card { position: relative; display: flex; flex-direction: column; padding: 20px 18px 18px; border: 1px solid var(--border); border-radius: 16px; background: var(--bg-sunken); scroll-snap-align: start; }
    .card.best { background: var(--surface); border: 2px solid var(--text); padding: 19px 17px 17px; }
    .badge-top { position: absolute; top: -11px; left: 50%; transform: translateX(-50%); padding: 3px 10px; border-radius: 999px; background: var(--text); color: var(--bg); font-size: 11px; font-weight: 700; letter-spacing: 0.04em; text-transform: uppercase; white-space: nowrap; }
    .card h3 { margin: 0 0 6px; font-size: 16px; }
    .price { display: flex; align-items: baseline; gap: 4px; margin-bottom: 2px; }
    .price b { font-family: var(--font-display); font-size: 26px; letter-spacing: -0.02em; }
    .price small, .billed { font-size: 12.5px; color: var(--text-3); }
    .billed { min-height: 18px; }
    .intro { display: inline-block; margin-top: 6px; padding: 2px 8px; border-radius: 999px; font-size: 11.5px; font-weight: 600; background: var(--success-soft); color: var(--success); align-self: flex-start; }
    ul { list-style: none; margin: 14px 0 18px; padding: 0; display: flex; flex-direction: column; gap: 9px; flex: 1; }
    li { display: flex; gap: 8px; font-size: 13px; line-height: 1.4; color: var(--text-2); }
    li vc-icon { flex-shrink: 0; margin-top: 2px; color: var(--text); }
    .card .btn { width: 100%; justify-content: center; height: 40px; }
    .packs { margin-top: 16px; padding: 14px 16px; border-radius: 14px; border: 1px dashed var(--border); }
    .packs-head { display: flex; justify-content: space-between; align-items: baseline; gap: 10px; margin-bottom: 10px; flex-wrap: wrap; }
    .packs-head b { font-size: 14px; }
    .packs-head span { font-size: 12.5px; color: var(--text-3); }
    .pack-row { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px; }
    .pack { position: relative; display: flex; align-items: center; justify-content: space-between; gap: 8px; padding: 9px 10px 9px 12px; border-radius: 11px; border: 1px solid var(--border); background: var(--surface); font-size: 13px; }
    .pack.fit { border-color: var(--text); }
    .pack .tag { position: absolute; top: -9px; left: 10px; padding: 1px 7px; border-radius: 999px; font-size: 10.5px; font-weight: 600; background: var(--text); color: var(--bg); }
    .pack span { display: flex; flex-direction: column; }
    .pack small { color: var(--text-3); font-size: 12px; }
    .foot { display: flex; justify-content: center; gap: 18px; margin-top: 16px; font-size: 13px; color: var(--text-3); flex-wrap: wrap; }
    .foot button { border: 0; background: none; padding: 0; font: inherit; color: var(--text); text-decoration: underline; text-underline-offset: 3px; cursor: pointer; font-weight: 500; }
    .demo { margin: 12px auto 0; max-width: 560px; padding: 7px 12px; border-radius: 10px; font-size: 12.5px; text-align: center; background: var(--accent-soft); color: var(--text-2); }
    @media (max-width: 640px) {
      .dialog { padding: 26px 16px 18px; border-radius: 18px; }
      h2 { font-size: 21px; }
      .plans { grid-auto-columns: 78%; }
      .pack-row { grid-template-columns: 1fr; }
    }
  `,
  template: `
    @if (upsell.request(); as r) {
      <div class="scrim" (click)="upsell.close()">
        <div class="dialog" role="dialog" aria-modal="true" aria-labelledby="upsell-title" (click)="$event.stopPropagation()">
          <button class="btn btn-ghost btn-icon btn-sm x" (click)="upsell.close()" aria-label="Zamknij"><vc-icon name="x" /></button>
          <header>
            <h2 id="upsell-title">{{ (r.available ?? 0) > 0 ? 'Za mało znaków na ten tekst' : 'Skończyły się znaki' }}</h2>
            <p class="sub">
              @if (r.needed !== undefined) {
                Ten tekst wymaga <b>{{ fmt(r.needed) }}</b> znaków, a dostępnych masz <b>{{ fmt(r.available ?? 0) }}</b>.
              }
              Przejdź na wyższy plan albo dokup znaki, żeby dalej generować mowę bez przerwy.
            </p>
            @if (minutesOut()) {
              <p class="note">Wykorzystano miesięczny limit {{ account.usage()?.minuteLimit }} minut audio planu — znaki z pakietów działają niezależnie od niego.</p>
            }
            @if (billing() && !billing()!.stripeEnabled) {
              <p class="demo">Tryb deweloperski: plan zmieni się, a pakiet zostanie dopisany bez płatności.</p>
            }
          </header>

          @if (upgrades().length) {
            <div class="toggle">
              <div class="seg" role="tablist" aria-label="Okres rozliczenia">
                <button role="tab" [class.on]="interval() === 'month'" [attr.aria-selected]="interval() === 'month'" (click)="interval.set('month')">Miesięcznie</button>
                <button role="tab" [class.on]="interval() === 'year'" [attr.aria-selected]="interval() === 'year'" (click)="interval.set('year')">
                  Rocznie <span class="save">Oszczędź {{ yearlySave() }}%</span>
                </button>
              </div>
            </div>

            <div class="plans">
              @for (pl of upgrades(); track pl.id) {
                <div class="card" [class.best]="pl.id === bestPlan()?.id">
                  @if (pl.id === bestPlan()?.id) { <span class="badge-top">{{ fits(pl) ? 'Wystarczy na ten tekst' : 'Najpopularniejszy' }}</span> }
                  <h3>{{ pl.name }}</h3>
                  <div class="price"><b>{{ priceOf(pl) }}</b><small>/ mies.</small></div>
                  <span class="billed">{{ interval() === 'year' ? money(pl.yearlyPriceMinor) + ' płatne rocznie' : 'płatne co miesiąc' }}</span>
                  @if (introPct() && interval() === 'month') { <span class="intro">1. miesiąc za {{ money(introPrice(pl)) }}</span> }
                  <ul>
                    <li><vc-icon name="check" [size]="14" />{{ fmt(pl.monthlyCharacters) }} znaków miesięcznie</li>
                    <li><vc-icon name="check" [size]="14" />{{ bytes(pl.storageMb * 1e6) }} na nagrania · bez limitu projektów</li>
                    <li><vc-icon name="check" [size]="14" />{{ pl.voiceCloning ? 'Klonowanie głosu' : 'Projektowanie głosu z opisu' }}</li>
                    <li><vc-icon name="check" [size]="14" />{{ fmt(pl.maxCustomVoices) }} własnych głosów · MP3 i WAV</li>
                    @if (pl.apiAccess) { <li><vc-icon name="check" [size]="14" />Dostęp przez API</li> }
                  </ul>
                  <button class="btn" [class.btn-primary]="pl.id === bestPlan()?.id" [disabled]="busy() !== null" (click)="choosePlan(pl)">
                    @if (busy() === pl.id) { <span class="spinner"></span> } Wybierz
                  </button>
                </div>
              }
            </div>
          }

          <div class="packs">
            <div class="packs-head"><b>Albo dokup znaki jednorazowo</b><span>Bez subskrypcji · znaki nie wygasają</span></div>
            <div class="pack-row">
              @for (p of packs(); track p.id) {
                <div class="pack" [class.fit]="p.id === bestPack()?.id">
                  @if (p.id === bestPack()?.id) { <span class="tag">Wystarczy</span> }
                  <span><b>{{ fmt(p.characters) }} znaków</b><small>{{ money(p.priceMinor) }}</small></span>
                  <button class="btn btn-sm" [class.btn-primary]="p.id === bestPack()?.id && !upgrades().length" [disabled]="busy() !== null" (click)="buy(p)">
                    @if (busy() === p.id) { <span class="spinner"></span> } Kup
                  </button>
                </div>
              }
            </div>
          </div>

          <div class="foot">
            <button (click)="seeAll()">Zobacz wszystkie plany</button>
            @if (resetOn()) { <span>Limit planu odnowi się {{ resetOn() }}</span> }
          </div>
        </div>
      </div>
    }
  `,
})
export class UpsellHostComponent {
  protected upsell = inject(UpsellService);
  protected account = inject(AccountStore);
  private api = inject(ApiService);
  private toast = inject(ToastService);
  private router = inject(Router);

  protected packs = signal<CreditPack[]>([]);
  protected billing = signal<BillingConfig | null>(null);
  protected busy = signal<string | null>(null);
  protected interval = signal<BillingInterval>('month');
  protected fmt = formatNumber;
  protected money = money;
  protected bytes = formatBytes;

  private shortfall = computed(() => {
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
  /** Plany wyższe od bieżącego. */
  protected upgrades = computed<Plan[]>(() => {
    const plans = this.account.plans();
    const current = this.account.plan();
    const idx = current ? plans.findIndex((p) => p.id === current.id) : -1;
    return plans.slice(idx + 1).filter((p) => p.monthlyPriceMinor > 0);
  });
  /** Najtańszy plan, w którym tekst się mieści; inaczej Pro (najczęściej wybierany) lub pierwszy wyższy. */
  protected bestPlan = computed(() => {
    const list = this.upgrades();
    return list.find((p) => this.fits(p)) ?? list.find((p) => p.id === 'PRO') ?? list[0] ?? null;
  });
  protected introPct = computed(() => (this.billing()?.introEligible ? this.billing()!.introPercentOff : 0));
  protected yearlySave = computed(() => Math.round((1 - (this.billing()?.yearlyMonthsCharged ?? 10) / 12) * 100));
  protected minutesOut = computed(() => {
    const u = this.account.usage();
    return !!u && u.minutesUsed >= u.minuteLimit;
  });
  protected resetOn = computed(() => {
    const d = this.account.usage()?.limitsResetOn;
    return d ? formatDate(d) : '';
  });

  constructor() {
    effect(() => {
      if (!this.upsell.request()) return;
      if (!this.packs().length) void this.api.billingPacks().then((p) => this.packs.set(p)).catch(() => null);
      void this.api.billingConfig().then((b) => this.billing.set(b)).catch(() => null);
      void this.account.refreshUsage();
    });
  }

  protected fits(pl: Plan) {
    const need = this.upsell.request()?.needed;
    return need !== undefined && need > 0 && pl.monthlyCharacters >= need;
  }

  protected priceOf(pl: Plan) {
    return money(this.interval() === 'year' ? Math.round(pl.yearlyPriceMinor / 12) : pl.monthlyPriceMinor);
  }

  protected introPrice(pl: Plan) {
    return Math.round((pl.monthlyPriceMinor * (100 - this.introPct())) / 100);
  }

  /** Wybór planu: płatność Stripe (przekierowanie) albo — w trybie deweloperskim — zmiana od razu i wznowienie akcji. */
  async choosePlan(pl: Plan) {
    const retry = this.upsell.request()?.retry;
    const b = this.billing();
    this.busy.set(pl.id);
    try {
      if (b?.stripeEnabled) {
        const { url } = await this.api.billingCheckout(pl.id, this.interval());
        window.location.assign(url);
        return;
      }
      await this.account.changePlan(pl.id, this.interval());
      this.upsell.close();
      this.toast.success(`Plan ${pl.name} aktywny`, retry ? 'Wznawiam generowanie.' : undefined);
      retry?.();
    } catch (e) {
      this.toast.apiError('Nie udało się zmienić planu', e);
    } finally {
      this.busy.set(null);
    }
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

  seeAll() {
    this.upsell.close();
    void this.router.navigate(['/settings/subscription']);
  }

  @HostListener('document:keydown.escape')
  onEsc() {
    if (this.upsell.request()) this.upsell.close();
  }
}
