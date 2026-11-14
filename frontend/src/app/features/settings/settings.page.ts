import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { ApiError, Account } from '../../core/api/models';
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
  protected charPct = computed(() => {
    const u = this.store.usage();
    return u ? Math.min(100, (u.charactersUsed / u.characterLimit) * 100) : 0;
  });
  protected minPct = computed(() => {
    const u = this.store.usage();
    return u ? Math.min(100, (u.minutesUsed / u.minuteLimit) * 100) : 0;
  });
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
      this.toast.error('Nie zapisano ustawienia', (e as ApiError).message);
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
      this.toast.error('Nie zapisano profilu', (e as ApiError).message);
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
