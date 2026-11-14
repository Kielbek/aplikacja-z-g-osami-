import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { Account, Plan } from '../../core/api/models';
import { AccountStore } from '../../core/services/account.store';
import { AuthService } from '../../core/services/auth.service';
import { EngineStatusService } from '../../core/services/engine-status.service';
import { ToastService } from '../../core/services/toast.service';
import { VoicesStore } from '../../core/services/voices.store';
import { formatDate, formatNumber } from '../../core/util/format';
import { initials, languageLabel } from '../../core/util/labels';
import { IconComponent } from '../../shared/icon.component';

type Tab = 'profile' | 'account' | 'subscription' | 'export' | 'preferences' | 'engine';

const TABS: { id: Tab; label: string; icon: string }[] = [
  { id: 'profile', label: 'Profil', icon: 'user' },
  { id: 'account', label: 'Konto i bezpieczeństwo', icon: 'shield' },
  { id: 'subscription', label: 'Subskrypcja i limity', icon: 'gauge' },
  { id: 'export', label: 'Eksport', icon: 'download' },
  { id: 'preferences', label: 'Preferencje', icon: 'settings' },
  { id: 'engine', label: 'Silnik TTS', icon: 'plug' },
];

@Component({
  selector: 'vc-settings-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, IconComponent],
  styleUrl: './settings.page.scss',
  templateUrl: './settings.page.html',
})
export class SettingsPage {
  protected store = inject(AccountStore);
  protected engine = inject(EngineStatusService);
  protected voices = inject(VoicesStore);
  private toast = inject(ToastService);
  private auth = inject(AuthService);
  private router = inject(Router);

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
      { label: 'Znaki w miesiącu', used: formatNumber(u.charactersUsed), limit: lim(u.characterLimit), pct: pct(u.charactersUsed, u.characterLimit), hint: 'Generowanie wstrzymane do odnowienia limitu lub zmiany planu.' },
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
  protected sortedVoices = computed(() => [...this.voices.voices()].sort((a, b) => a.name.localeCompare(b.name)));

  constructor() {
    effect(() => {
      const a = this.store.account();
      if (a && !this.dirty()) this.draft.set({ ...a });
    });
    void this.store.refreshUsage();
    void this.voices.load();
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
      await this.store.update({ fullName: d.fullName, email: d.email, company: d.company, role: d.role });
      this.toast.success('Zapisano profil');
    } catch (e) {
      this.toast.apiError('Nie zapisano profilu', e);
    } finally {
      this.saving.set(false);
    }
  }

  logout() {
    this.auth.logout();
    void this.router.navigate(['/login']);
  }

  copy(text: string) {
    void navigator.clipboard?.writeText(text).then(() => this.toast.show('Skopiowano do schowka'));
  }
}
