import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { ApiService } from '../../core/api/api.service';
import { ApiError, BillingConfig, CreditPack, LoudnessTarget, PronunciationEntry } from '../../core/api/models';
import { ConfirmService } from '../../core/services/confirm.service';
import { Account, Plan } from '../../core/api/models';
import { AccountStore } from '../../core/services/account.store';
import { AuthService } from '../../core/services/auth.service';
import { EngineStatusService } from '../../core/services/engine-status.service';
import { ToastService } from '../../core/services/toast.service';
import { VoicesStore } from '../../core/services/voices.store';
import { formatDate, formatNumber } from '../../core/util/format';
import { categoryLabel, initials, languageLabel, languageOptions } from '../../core/util/labels';
import { SelectComponent, SelectOption } from '../../shared/select.component';
import { IconComponent } from '../../shared/icon.component';

type Tab = 'profile' | 'account' | 'subscription' | 'export' | 'dictionary' | 'preferences' | 'engine';

const TABS: { id: Tab; label: string; icon: string }[] = [
  { id: 'profile', label: 'Profil', icon: 'user' },
  { id: 'account', label: 'Konto i bezpieczeństwo', icon: 'shield' },
  { id: 'subscription', label: 'Subskrypcja i limity', icon: 'gauge' },
  { id: 'export', label: 'Eksport', icon: 'download' },
  { id: 'dictionary', label: 'Słownik wymowy', icon: 'book' },
  { id: 'preferences', label: 'Preferencje', icon: 'settings' },
  { id: 'engine', label: 'Silnik TTS', icon: 'plug' },
];

@Component({
  selector: 'vc-settings-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, IconComponent, SelectComponent],
  styleUrl: './settings.page.scss',
  templateUrl: './settings.page.html',
})
export class SettingsPage {
  protected store = inject(AccountStore);
  protected engine = inject(EngineStatusService);
  protected voices = inject(VoicesStore);
  private toast = inject(ToastService);
  protected auth = inject(AuthService);
  private router = inject(Router);
  private route = inject(ActivatedRoute);
  private api = inject(ApiService);
  private confirm = inject(ConfirmService);

  protected billing = signal<BillingConfig | null>(null);
  protected packs = signal<CreditPack[]>([]);
  protected buying = signal<string | null>(null);
  protected canBuyPacks = computed(() => !!this.billing() && (this.billing()!.stripeEnabled || this.billing()!.demoPlanSwitch));
  protected dict = signal<PronunciationEntry[]>([]);
  protected dictTerm = signal('');
  protected dictReplacement = signal('');
  protected dictCase = signal(false);
  protected dictEditing = signal<string | null>(null);
  protected globalDict = computed(() => this.dict().filter((e) => !e.projectId));
  protected projectDictCount = computed(() => this.dict().filter((e) => e.projectId).length);
  protected loudnessOptions: SelectOption<LoudnessTarget>[] = [
    { value: 'off', label: 'Bez normalizacji', hint: 'Głośność jak z silnika' },
    { value: '-14', label: '−14 LUFS', hint: 'Spotify, YouTube, platformy streamingowe' },
    { value: '-16', label: '−16 LUFS', hint: 'Podcasty (zalecane)' },
    { value: '-19', label: '−19 LUFS', hint: 'Audiobooki i treści mówione' },
    { value: '-23', label: '−23 LUFS', hint: 'Radio i telewizja (EBU R128)' },
  ];
  protected pwCurrent = signal('');
  protected pwNew = signal('');
  protected pwRepeat = signal('');
  protected pwSaving = signal(false);
  protected pwError = signal<string | null>(null);
  protected deletePw = signal('');
  protected deleteConfirm = signal(false);
  protected deleting = signal(false);

  readonly tab = input<Tab>('profile');
  protected TABS = TABS;
  protected draft = signal<Account | null>(null);
  protected saving = signal(false);

  protected fmtNum = formatNumber;
  protected fmtDate = formatDate;
  protected languageLabel = languageLabel;

  protected initials = computed(() => initials(this.draft()?.fullName ?? ''));
  protected dirty = computed(() => {
    const a = this.store.account();
    const d = this.draft();
    return !!a && !!d && JSON.stringify(a) !== JSON.stringify(d);
  });
  protected changing = signal(false);

  protected meters = computed(() => {
    const u = this.store.usage();
    if (!u) return [];
    const pct = (used: number, limit: number) => (limit < 0 ? 0 : limit === 0 ? 100 : Math.min(100, (used / limit) * 100));
    const lim = (n: number) => (n < 0 ? 'bez limitu' : formatNumber(n));
    return [
      { label: 'Znaki w miesiącu', used: formatNumber(u.charactersUsed), limit: lim(u.characterLimit), pct: pct(u.charactersUsed, u.characterLimit), hint: (u.extraCharacters ?? 0) > 0 ? `Limit planu wykorzystany — generowanie korzysta teraz z dodatkowych znaków (${formatNumber(u.extraCharacters)}).` : 'Generowanie wstrzymane — dokup pakiet znaków, poczekaj na odnowienie limitu lub zmień plan.' },
      { label: 'Minuty audio w miesiącu', used: u.minutesUsed.toFixed(1), limit: lim(u.minuteLimit), pct: pct(u.minutesUsed, u.minuteLimit), hint: 'Generowanie wstrzymane do odnowienia limitu lub zmiany planu.' },
      { label: 'Projekty', used: formatNumber(u.projectsUsed), limit: lim(u.projectLimit), pct: pct(u.projectsUsed, u.projectLimit), hint: 'Nie można tworzyć nowych projektów — usuń nieużywane lub zmień plan.' },
      { label: 'Własne głosy', used: formatNumber(u.customVoicesUsed), limit: lim(u.customVoiceLimit), pct: pct(u.customVoicesUsed, u.customVoiceLimit), hint: 'Nie można zapisać kolejnych głosów — usuń nieużywane lub zmień plan.' },
    ];
  });

  protected planRows: { label: string; kind: (p: Plan) => string }[] = [
    { label: 'Znaki miesięcznie', kind: (p) => formatNumber(p.monthlyCharacters) },
    { label: 'Minuty audio miesięcznie', kind: (p) => formatNumber(p.monthlyMinutes) },
    { label: 'Projekty', kind: (p) => (p.maxProjects < 0 ? 'bez limitu' : formatNumber(p.maxProjects)) },
    { label: 'Znaki w projekcie', kind: (p) => formatNumber(p.maxCharactersPerProject) },
    { label: 'Własne głosy', kind: (p) => formatNumber(p.maxCustomVoices) },
    { label: 'Projektowanie głosu', kind: () => 'yes' },
    { label: 'Klonowanie głosu', kind: (p) => (p.voiceCloning ? 'yes' : 'no') },
    { label: 'Ustawienia zaawansowane', kind: (p) => (p.advancedControls ? 'yes' : 'no') },
    { label: 'Eksport', kind: (p) => p.exportFormats.map((f) => f.toUpperCase()).join(' + ') },
    { label: 'Jakość MP3', kind: (p) => `do ${p.maxMp3Bitrate} kb/s` },
    { label: 'Historia generowania', kind: (p) => `${p.historyRetentionDays} dni` },
  ];
  protected fileNamePreview = computed(() => {
    const p = this.draft()?.fileNamePattern || '{project}';
    return p.replace('{project}', 'podcast-odcinek-12').replace('{date}', new Date().toISOString().slice(0, 10)) + '.' + (this.draft()?.exportFormat ?? 'mp3');
  });
  protected languageOpts = computed(() => languageOptions(this.engine.languages()));
  protected voiceOpts = computed<SelectOption[]>(() =>
    [...this.voices.voices()]
      .sort((a, b) => (a.languages[0] === 'pl' ? 0 : 1) - (b.languages[0] === 'pl' ? 0 : 1) || languageLabel(a.languages[0] ?? '').localeCompare(languageLabel(b.languages[0] ?? ''), 'pl') || a.name.localeCompare(b.name, 'pl'))
      .map((v) => ({
        value: v.id,
        label: v.name,
        hint: v.categories.map(categoryLabel).join(' · ') || v.description,
        group: languageLabel(v.languages[0] ?? ''),
      })),
  );
  protected bitrateOptions = computed<SelectOption<number>[]>(() => {
    const max = this.store.plan()?.maxMp3Bitrate ?? 320;
    const hints: Record<number, string> = { 128: 'Mniejszy plik, mowa', 192: 'Zalecane', 256: 'Wysoka jakość', 320: 'Maksymalna jakość' };
    return [128, 192, 256, 320].map((b) => ({
      value: b,
      label: `${b} kb/s`,
      hint: hints[b],
      disabled: b > max,
      badge: b > max ? (this.store.lowestPlan((p) => p.maxMp3Bitrate >= b)?.name ?? '') + '+' : undefined,
    }));
  });

  constructor() {
    effect(() => {
      const a = this.store.account();
      if (a && !this.dirty()) this.draft.set({ ...a });
    });
    void this.store.refreshUsage();
    void this.voices.load();
    void this.api.billingConfig().then((b) => this.billing.set(b)).catch(() => null);
    void this.api.billingPacks().then((p) => this.packs.set(p)).catch(() => null);
    void this.loadDict();
    this.handleCheckoutReturn();
  }

  /** Powrót ze Stripe Checkout: plan aktywuje webhook, więc odświeżamy kilka razy. */
  private handleCheckoutReturn() {
    const status = this.route.snapshot.queryParamMap.get('checkout');
    if (status === 'success' && this.route.snapshot.queryParamMap.get('pack')) {
      this.toast.success('Płatność przyjęta', 'Dodatkowe znaki pojawią się na koncie w ciągu kilku sekund.');
      [2000, 5000, 10000].forEach((ms) => setTimeout(() => void this.store.refreshUsage(), ms));
    } else if (status === 'success') {
      this.toast.success('Płatność przyjęta', 'Nowy plan zostanie aktywowany w ciągu kilku sekund.');
      [2000, 5000, 10000].forEach((ms) => setTimeout(() => void this.store.load(), ms));
    } else if (status === 'cancelled') {
      this.toast.show('Płatność anulowana — nic nie zostało pobrane.');
    }
    if (status) void this.router.navigate([], { queryParams: {}, replaceUrl: true });
  }

  async loadDict() {
    try {
      this.dict.set(await this.api.pronunciation());
    } catch {
      /* ignoruj */
    }
  }

  editDict(e: PronunciationEntry) {
    this.dictEditing.set(e.id);
    this.dictTerm.set(e.term);
    this.dictReplacement.set(e.replacement);
    this.dictCase.set(e.caseSensitive);
  }

  resetDict() {
    this.dictEditing.set(null);
    this.dictTerm.set('');
    this.dictReplacement.set('');
    this.dictCase.set(false);
  }

  async saveDict(e?: Event) {
    e?.preventDefault();
    const term = this.dictTerm().trim();
    const replacement = this.dictReplacement().trim();
    if (!term || !replacement) return;
    try {
      const id = this.dictEditing();
      if (id) await this.api.updatePronunciation(id, { term, replacement, caseSensitive: this.dictCase() });
      else await this.api.addPronunciation({ projectId: null, term, replacement, caseSensitive: this.dictCase() });
      this.resetDict();
      await this.loadDict();
    } catch (err) {
      this.toast.apiError('Nie zapisano wpisu', err);
    }
  }

  async deleteDict(id: string) {
    try {
      await this.api.deletePronunciation(id);
      if (this.dictEditing() === id) this.resetDict();
      await this.loadDict();
    } catch (err) {
      this.toast.apiError('Nie usunięto wpisu', err);
    }
  }

  protected price(minor: number) {
    return (minor / 100).toLocaleString('pl-PL', { style: 'currency', currency: 'PLN', minimumFractionDigits: minor % 100 ? 2 : 0 });
  }

  protected perThousand(p: CreditPack) {
    return (p.priceMinor / 100 / (p.characters / 1000)).toLocaleString('pl-PL', { style: 'currency', currency: 'PLN', minimumFractionDigits: 2 });
  }

  /** Orientacyjnie ~900 znaków na minutę mowy. */
  protected approxMinutes(chars: number) {
    return formatNumber(Math.round(chars / 900));
  }

  async buyPack(p: CreditPack) {
    this.buying.set(p.id);
    try {
      const r = await this.api.buyPack(p.id);
      if (r.url) {
        window.location.assign(r.url);
        return;
      }
      await this.store.refreshUsage();
      this.toast.success('Dodano znaki', `${formatNumber(p.characters)} znaków dopisano do konta (tryb deweloperski, bez opłaty).`);
    } catch (e) {
      this.toast.apiError('Nie udało się kupić pakietu', e);
    }
    this.buying.set(null);
  }

  canChoose(p: Plan) {
    const b = this.billing();
    if (!b) return false;
    if (b.stripeEnabled) return p.id !== 'FREE' || !!b.hasSubscription;
    return b.demoPlanSwitch;
  }

  async choosePlan(p: Plan) {
    const b = this.billing();
    if (b?.stripeEnabled) {
      this.changing.set(true);
      try {
        // Obniżenie do Free = anulowanie subskrypcji w panelu Stripe
        const { url } = p.id === 'FREE' ? await this.api.billingPortal() : await this.api.billingCheckout(p.id);
        window.location.assign(url);
      } catch (e) {
        this.toast.apiError('Nie udało się otworzyć płatności', e);
        this.changing.set(false);
      }
      return;
    }
    await this.changePlan(p);
  }

  async openPortal() {
    this.changing.set(true);
    try {
      window.location.assign((await this.api.billingPortal()).url);
    } catch (e) {
      this.toast.apiError('Nie udało się otworzyć panelu płatności', e);
      this.changing.set(false);
    }
  }

  async changePassword(e: Event) {
    e.preventDefault();
    this.pwError.set(null);
    if (this.pwNew().length < 8) return this.pwError.set('Nowe hasło musi mieć co najmniej 8 znaków.');
    if (this.pwNew() !== this.pwRepeat()) return this.pwError.set('Hasła nie są identyczne.');
    this.pwSaving.set(true);
    try {
      await this.auth.changePassword(this.pwCurrent(), this.pwNew());
      this.pwCurrent.set('');
      this.pwNew.set('');
      this.pwRepeat.set('');
      this.toast.success('Hasło zostało zmienione', 'Pozostałe urządzenia zostały wylogowane.');
    } catch (err) {
      this.pwError.set((err as ApiError).message);
    } finally {
      this.pwSaving.set(false);
    }
  }

  protected exporting = signal(false);

  /** Eksport danych konta (RODO art. 15 i 20). */
  async exportData() {
    this.exporting.set(true);
    try {
      const res = await this.api.exportAccountData();
      const a = document.createElement('a');
      const url = URL.createObjectURL(res.blob);
      a.href = url;
      a.download = res.filename ?? 'voicecraft-dane.zip';
      a.click();
      setTimeout(() => URL.revokeObjectURL(url), 2000);
      this.toast.success('Pobrano archiwum z danymi konta');
    } catch (e) {
      this.toast.apiError('Nie udało się przygotować eksportu', e);
    } finally {
      this.exporting.set(false);
    }
  }

  async logoutEverywhere() {
    const ok = await this.confirm.ask({
      title: 'Wylogować z innych urządzeń?',
      message: 'Wszystkie inne sesje zostaną natychmiast zakończone. Ta przeglądarka pozostanie zalogowana.',
      confirmLabel: 'Wyloguj z innych urządzeń',
    });
    if (!ok) return;
    try {
      await this.auth.logoutEverywhere();
      this.toast.success('Wylogowano z pozostałych urządzeń');
    } catch (e) {
      this.toast.apiError('Nie udało się zakończyć sesji', e);
    }
  }

  async deleteAccount() {
    const ok = await this.confirm.ask({
      title: 'Usunąć konto na zawsze?',
      message: 'Projekty, nagrania, własne głosy i historia zostaną trwale usunięte.',
      confirmLabel: 'Usuń konto',
      danger: true,
    });
    if (!ok) return;
    this.deleting.set(true);
    try {
      await this.auth.deleteAccount(this.deletePw());
    } catch (e) {
      this.toast.apiError('Nie udało się usunąć konta', e);
      this.deleting.set(false);
    }
  }

  set<K extends keyof Account>(key: K, value: Account[K]) {
    this.draft.update((d) => (d ? { ...d, [key]: value } : d));
  }

  /** Preferencje i eksport zapisujemy od razu; pola profilu — przyciskiem. */
  async setNow<K extends keyof Account>(key: K, value: Account[K]) {
    this.set(key, value);
    try {
      await this.store.update({ [key]: value } as Partial<Account>);
    } catch (e) {
      this.toast.apiError('Nie zapisano ustawienia', e);
    }
  }

  allows(format: 'mp3' | 'wav') {
    return this.store.plan()?.exportFormats.includes(format) ?? true;
  }

  lowestName(format: 'mp3' | 'wav') {
    return this.store.lowestPlan((p) => p.exportFormats.includes(format))?.name ?? '';
  }

  planRank(p: Plan | null) {
    return p ? this.store.plans().findIndex((x) => x.id === p.id) : -1;
  }

  async changePlan(p: Plan) {
    this.changing.set(true);
    try {
      await this.store.changePlan(p.id);
      this.draft.set({ ...this.store.account()! });
      this.toast.success(`Zmieniono plan na ${p.name}`, 'Nowe limity obowiązują od razu.');
    } catch (e) {
      this.toast.apiError('Nie udało się zmienić planu', e);
    } finally {
      this.changing.set(false);
    }
  }

  reset() {
    const a = this.store.account();
    if (a) this.draft.set({ ...a });
  }

  async saveProfile() {
    const d = this.draft();
    if (!d) return;
    if (!/^\S+@\S+\.\S+$/.test(d.email)) {
      this.toast.error('Niepoprawny adres e-mail');
      return;
    }
    this.saving.set(true);
    try {
      const emailChanged = this.auth.user()?.email !== d.email.trim().toLowerCase();
      await this.store.update({ fullName: d.fullName, email: d.email, company: d.company, role: d.role });
      if (emailChanged) {
        await this.auth.refresh();
        this.toast.success('Zapisano profil', 'Potwierdź nowy adres — link wysłaliśmy e-mailem.');
      } else {
        this.toast.success('Zapisano profil');
      }
    } catch (e) {
      this.toast.apiError('Nie zapisano profilu', e);
    } finally {
      this.saving.set(false);
    }
  }

  logout() {
    void this.auth.logout();
  }

  copy(text: string) {
    void navigator.clipboard?.writeText(text).then(() => this.toast.show('Skopiowano do schowka'));
  }
}
