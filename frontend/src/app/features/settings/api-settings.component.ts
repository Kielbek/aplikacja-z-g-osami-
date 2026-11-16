import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ApiService } from '../../core/api/api.service';
import { ApiOverview } from '../../core/api/models';
import { ConfirmService } from '../../core/services/confirm.service';
import { ToastService } from '../../core/services/toast.service';
import { formatDate, formatNumber } from '../../core/util/format';
import { CodeBlockComponent } from '../../shared/code-block.component';
import { IconComponent } from '../../shared/icon.component';
import { quickStart } from '../docs/api-snippets';

/** Ustawienia → API: klucze, sekret webhooków, limity planu i szybki start. */
@Component({
  selector: 'vc-api-settings',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, IconComponent, CodeBlockComponent],
  templateUrl: './api-settings.component.html',
  styleUrl: './api-settings.component.scss',
})
export class ApiSettingsComponent {
  private api = inject(ApiService);
  private toast = inject(ToastService);
  private confirm = inject(ConfirmService);

  protected overview = signal<ApiOverview | null>(null);
  protected loadError = signal(false);
  protected name = signal('');
  protected creating = signal(false);
  /** Pełny klucz — tylko zaraz po utworzeniu. */
  protected created = signal<{ name: string; secret: string } | null>(null);
  protected copiedKey = signal(false);
  protected secret = signal<string | null>(null);
  protected secretVisible = signal(false);

  protected fmtNum = formatNumber;
  protected fmtDate = formatDate;
  protected activeKeys = computed(() => this.overview()?.keys.filter((k) => !k.revokedAt) ?? []);
  protected revokedKeys = computed(() => this.overview()?.keys.filter((k) => !!k.revokedAt) ?? []);
  protected samples = computed(() => quickStart(this.created()?.secret));
  protected maskedSecret = computed(() => {
    const s = this.secret();
    if (!s) return '';
    return this.secretVisible() ? s : s.slice(0, 10) + '•'.repeat(18);
  });

  constructor() {
    void this.load();
  }

  async load() {
    try {
      this.overview.set(await this.api.apiOverview());
      this.loadError.set(false);
      if (this.overview()!.enabled && this.secret() === null) {
        this.secret.set((await this.api.webhookSecret()).secret);
      }
    } catch {
      this.loadError.set(true);
    }
  }

  async create(e?: Event) {
    e?.preventDefault();
    this.creating.set(true);
    try {
      const name = this.name().trim() || 'Klucz API';
      const r = await this.api.createApiKey(name);
      this.created.set({ name: r.key.name, secret: r.secret });
      this.copiedKey.set(false);
      this.name.set('');
      await this.load();
    } catch (err) {
      this.toast.apiError('Nie utworzono klucza', err);
    } finally {
      this.creating.set(false);
    }
  }

  async copy(text: string, what: 'key' | 'secret') {
    try {
      await navigator.clipboard.writeText(text);
      if (what === 'key') this.copiedKey.set(true);
      this.toast.success(what === 'key' ? 'Skopiowano klucz API' : 'Skopiowano sekret webhooków');
    } catch {
      this.toast.show('Nie udało się skopiować — zaznacz tekst i skopiuj ręcznie.');
    }
  }

  async revoke(id: string, name: string) {
    const ok = await this.confirm.ask({
      title: `Unieważnić klucz „${name}”?`,
      message: 'Programy korzystające z tego klucza od razu stracą dostęp do API. Tej operacji nie można cofnąć.',
      confirmLabel: 'Unieważnij',
      danger: true,
    });
    if (!ok) return;
    try {
      await this.api.revokeApiKey(id);
      await this.load();
      this.toast.success('Klucz unieważniony');
    } catch (err) {
      this.toast.apiError('Nie unieważniono klucza', err);
    }
  }

  async rotateSecret() {
    const ok = await this.confirm.ask({
      title: 'Wygenerować nowy sekret webhooków?',
      message: 'Dotychczasowy sekret przestanie działać — zaktualizuj weryfikację podpisu na swoim serwerze.',
      confirmLabel: 'Wygeneruj nowy',
      danger: true,
    });
    if (!ok) return;
    try {
      this.secret.set((await this.api.rotateWebhookSecret()).secret);
      this.secretVisible.set(true);
    } catch (err) {
      this.toast.apiError('Nie zmieniono sekretu', err);
    }
  }
}
