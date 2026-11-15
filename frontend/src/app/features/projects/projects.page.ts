import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { ApiService } from '../../core/api/api.service';
import { ProjectDto } from '../../core/api/models';
import { AccountStore } from '../../core/services/account.store';
import { ConfirmService } from '../../core/services/confirm.service';
import { ProjectsStore } from '../../core/services/projects.store';
import { ToastService } from '../../core/services/toast.service';
import { formatDate, formatDuration, formatRelative, pluralN } from '../../core/util/format';
import { PROJECT_STATUS } from '../../core/util/labels';
import { DropdownComponent } from '../../shared/dropdown.component';
import { IconComponent } from '../../shared/icon.component';

type SortKey = 'name' | 'createdAt' | 'updatedAt' | 'duration';

@Component({
  selector: 'vc-projects-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [IconComponent, DropdownComponent, RouterLink],
  styleUrl: './projects.page.scss',
  templateUrl: './projects.page.html',
})
export class ProjectsPage {
  private api = inject(ApiService);
  private router = inject(Router);
  private toast = inject(ToastService);
  private confirm = inject(ConfirmService);
  protected account = inject(AccountStore);
  protected store = inject(ProjectsStore);

  protected query = signal('');
  protected statusFilter = signal<'all' | 'ready' | 'pending'>('all');
  protected sort = signal<{ key: SortKey; dir: 1 | -1 }>({ key: 'updatedAt', dir: -1 });
  protected creating = signal(false);

  protected PROJECT_STATUS = PROJECT_STATUS;
  protected formatDate = formatDate;
  protected formatRelative = formatRelative;
  protected formatDuration = formatDuration;
  protected pluralN = pluralN;

  protected rows = computed(() => {
    const q = this.query().trim().toLowerCase();
    const f = this.statusFilter();
    const { key, dir } = this.sort();
    return this.store
      .projects()
      .filter((p) => !q || p.name.toLowerCase().includes(q) || (p.voice?.name ?? '').toLowerCase().includes(q))
      .filter((p) => f === 'all' || (f === 'ready' ? p.stats.status === 'ready' : p.stats.status !== 'ready'))
      .sort((a, b) => {
        const va = key === 'name' ? a.name.toLowerCase() : key === 'duration' ? a.stats.durationMs : a[key];
        const vb = key === 'name' ? b.name.toLowerCase() : key === 'duration' ? b.stats.durationMs : b[key];
        return (va < vb ? -1 : va > vb ? 1 : 0) * dir;
      });
  });

  protected counts = computed(() => {
    const all = this.store.projects();
    return { all: all.length, ready: all.filter((p) => p.stats.status === 'ready').length, pending: all.filter((p) => p.stats.status !== 'ready').length };
  });

  constructor() {
    void this.store.load();
  }

  sortBy(key: SortKey) {
    this.sort.update((s) => (s.key === key ? { key, dir: (s.dir * -1) as 1 | -1 } : { key, dir: key === 'name' ? 1 : -1 }));
  }

  open(p: ProjectDto) {
    void this.router.navigate(['/editor', p.id]);
  }

  async create() {
    this.creating.set(true);
    try {
      const p = await this.api.createProject({});
      this.store.touch(p);
      void this.account.refreshUsage();
      await this.router.navigate(['/editor', p.id]);
    } catch (e) {
      this.toast.apiError('Nie udało się utworzyć projektu', e);
    } finally {
      this.creating.set(false);
    }
  }

  protected importing = signal(false);

  /** Import pliku jako nowy projekt (podział na fragmenty po stronie serwera). */
  async importFile(e: Event) {
    const input = e.target as HTMLInputElement;
    const file = input.files?.[0];
    input.value = '';
    if (!file) return;
    this.importing.set(true);
    try {
      const imported = await this.api.importText(file);
      const p = await this.api.createProject({ name: imported.title, text: imported.segments.join('\n\n') });
      this.store.touch(p);
      void this.account.refreshUsage();
      this.toast.success(`Utworzono projekt z pliku ${file.name}`, `${imported.segments.length} fragm. · ${imported.characters.toLocaleString('pl-PL')} znaków`);
      await this.router.navigate(['/editor', p.id]);
    } catch (err) {
      this.toast.apiError('Nie udało się zaimportować pliku', err);
    } finally {
      this.importing.set(false);
    }
  }

  async duplicate(p: ProjectDto) {
    try {
      const copy = await this.api.duplicateProject(p.id);
      this.store.touch(copy);
      void this.account.refreshUsage();
      this.toast.success('Utworzono kopię projektu', copy.name);
    } catch (e) {
      this.toast.apiError('Nie udało się zduplikować projektu', e);
    }
  }

  async remove(p: ProjectDto) {
    if (this.account.account()?.confirmBeforeDelete !== false) {
      const ok = await this.confirm.ask({
        title: 'Usunąć projekt?',
        message: `„${p.name}” zostanie trwale usunięty wraz z wygenerowanymi fragmentami. Tej operacji nie można cofnąć.`,
        confirmLabel: 'Usuń projekt',
        danger: true,
      });
      if (!ok) return;
    }
    try {
      await this.api.deleteProject(p.id);
      this.store.remove(p.id);
      void this.account.refreshUsage();
      this.toast.show('Projekt został usunięty.');
    } catch (e) {
      this.toast.apiError('Nie udało się usunąć projektu', e);
    }
  }
}
