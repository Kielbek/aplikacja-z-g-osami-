import { ChangeDetectionStrategy, Component, ElementRef, computed, inject, signal } from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { map } from 'rxjs';
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
import { VoiceAvatarComponent } from '../../shared/voice-avatar.component';

type SortKey = 'name' | 'createdAt' | 'updatedAt' | 'duration';

const VIEW_KEY = 'vc.projectsView';

function readView(): 'files' | 'list' {
  try {
    return localStorage.getItem(VIEW_KEY) === 'list' ? 'list' : 'files';
  } catch {
    return 'files';
  }
}

@Component({
  selector: 'vc-projects-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [IconComponent, DropdownComponent, RouterLink, NgTemplateOutlet, VoiceAvatarComponent],
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
  private host: ElementRef<HTMLElement> = inject(ElementRef);
  private route = inject(ActivatedRoute);

  // ------------------------------------------------------------ foldery i widok

  /** Widok: foldery i pliki (jak eksplorator) albo jedna lista wszystkich projektów. */
  protected view = signal<'files' | 'list'>(readView());
  private folderParam = toSignal(this.route.queryParamMap.pipe(map((q) => q.get('folder'))), { initialValue: null });
  protected currentFolder = computed(() => {
    const id = this.folderParam();
    return id ? (this.store.folders().find((f) => f.id === id) ?? null) : null;
  });
  protected folderCounts = computed(() => {
    const out: Record<string, number> = {};
    for (const p of this.store.projects()) if (p.folderId) out[p.folderId] = (out[p.folderId] ?? 0) + 1;
    return out;
  });
  protected folderName = (id: string) => this.store.folders().find((f) => f.id === id)?.name ?? null;

  /** Kafelki projektów: bieżący folder (lub projekty bez folderu); przy wyszukiwaniu — wszystkie pasujące. */
  protected fileRows = computed(() => {
    const folder = this.currentFolder()?.id ?? null;
    const searching = !!this.query().trim();
    const folderIds = new Set(this.store.folders().map((f) => f.id));
    return this.rows().filter((p) => {
      if (searching) return true;
      const own = p.folderId && folderIds.has(p.folderId) ? p.folderId : null;
      return own === folder;
    });
  });

  setView(v: 'files' | 'list') {
    this.view.set(v);
    try {
      localStorage.setItem(VIEW_KEY, v);
    } catch {
      /* brak dostępu do pamięci przeglądarki */
    }
    if (v === 'list' && this.currentFolder()) this.openFolder(null);
  }

  openFolder(id: string | null) {
    void this.router.navigate(['/projects'], { queryParams: id ? { folder: id } : {} });
  }

  protected newFolderOpen = signal(false);
  protected newFolderName = signal('');

  openNewFolder() {
    this.newFolderName.set('');
    this.newFolderOpen.set(true);
    setTimeout(() => this.host.nativeElement.querySelector<HTMLInputElement>('.new-folder input')?.focus());
  }

  async saveNewFolder(e: Event) {
    e.preventDefault();
    const name = this.newFolderName().trim();
    if (!name) return;
    try {
      await this.store.createFolder(name);
      this.newFolderOpen.set(false);
      this.toast.success(`Utworzono folder „${name}”`, 'Przeciągnij na niego projekty albo użyj menu „Przenieś do”.');
    } catch (err) {
      this.toast.apiError('Nie udało się utworzyć folderu', err);
    }
  }

  protected renaming = signal<string | null>(null);
  protected renameValue = signal('');

  startRename(id: string, name: string) {
    this.renameValue.set(name);
    this.renaming.set(id);
    setTimeout(() => {
      const input = this.host.nativeElement.querySelector<HTMLInputElement>('.rename input');
      input?.focus();
      input?.select();
    });
  }

  async saveRename(e: Event, id: string) {
    e.preventDefault();
    if (this.renaming() !== id) return;
    const name = this.renameValue().trim();
    this.renaming.set(null);
    if (!name || name === this.folderName(id)) return;
    try {
      await this.store.renameFolder(id, name);
    } catch (err) {
      this.toast.apiError('Nie udało się zmienić nazwy', err);
    }
  }

  async removeFolder(id: string, name: string) {
    const n = this.folderCounts()[id] ?? 0;
    const ok = await this.confirm.ask({
      title: 'Usunąć folder?',
      message: n
        ? `Folder „${name}” zostanie usunięty. ${pluralN(n, 'projekt', 'projekty', 'projektów')} z tego folderu nie zostanie usuniętych — wrócą do „Wszystkich projektów”.`
        : `Folder „${name}” jest pusty i zostanie usunięty.`,
      confirmLabel: 'Usuń folder',
      danger: true,
    });
    if (!ok) return;
    try {
      await this.store.deleteFolder(id);
      if (this.currentFolder()?.id === id) this.openFolder(null);
      this.toast.show('Folder został usunięty.');
    } catch (err) {
      this.toast.apiError('Nie udało się usunąć folderu', err);
    }
  }

  async move(p: ProjectDto, folderId: string | null) {
    try {
      await this.store.move(p.id, folderId);
      this.toast.success(folderId ? `Przeniesiono do „${this.folderName(folderId)}”` : 'Wyjęto z folderu', p.name);
    } catch (err) {
      this.toast.apiError('Nie udało się przenieść projektu', err);
    }
  }

  // Przeciąganie projektu na folder (HTML5 drag and drop)
  protected dragging = signal<string | null>(null);
  protected dropTarget = signal<string | null>(null);

  onDragStart(e: DragEvent, id: string) {
    this.dragging.set(id);
    e.dataTransfer?.setData('text/plain', id);
    if (e.dataTransfer) e.dataTransfer.effectAllowed = 'move';
  }

  onDragOver(e: DragEvent, target: string) {
    if (!this.dragging()) return;
    e.preventDefault();
    if (e.dataTransfer) e.dataTransfer.dropEffect = 'move';
    this.dropTarget.set(target);
  }

  onDrop(e: DragEvent, folderId: string | null) {
    e.preventDefault();
    const id = this.dragging() ?? e.dataTransfer?.getData('text/plain');
    this.dragging.set(null);
    this.dropTarget.set(null);
    const p = this.store.projects().find((x) => x.id === id);
    if (p) void this.move(p, folderId);
  }

  private barsCache = new Map<string, number[]>();
  protected bars(id: string): number[] {
    let b = this.barsCache.get(id);
    if (!b) {
      let h = 0;
      for (const c of id) h = (h * 31 + c.charCodeAt(0)) >>> 0;
      const seed = (h % 1000) / 100;
      b = Array.from({ length: 30 }, (_, i) => 4 + Math.abs(Math.sin(i * 0.6 + seed) * Math.sin(i * 0.21 + seed * 2)) * 32);
      this.barsCache.set(id, b);
    }
    return b;
  }

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
      const p = await this.api.createProject({ folderId: this.currentFolder()?.id ?? null });
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
      const p = await this.api.createProject({ name: imported.title, text: imported.segments.join('\n\n'), folderId: this.currentFolder()?.id ?? null });
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
