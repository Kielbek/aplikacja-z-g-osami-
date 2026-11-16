import { Injectable, computed, inject, signal } from '@angular/core';
import { ApiService } from '../api/api.service';
import { ProjectDto, ProjectFolder } from '../api/models';

/** Lista projektów (sidebar, widok projektów). Edytor zarządza własnym stanem projektu. */
@Injectable({ providedIn: 'root' })
export class ProjectsStore {
  private api = inject(ApiService);

  readonly projects = signal<ProjectDto[]>([]);
  readonly folders = signal<ProjectFolder[]>([]);
  readonly loaded = signal(false);
  readonly error = signal<string | null>(null);

  /**
   * Generowanie rozpoczęte w tej karcie (natychmiast, zanim serwer to potwierdzi).
   * Licznik na projekt — kilka fragmentów może się generować równolegle.
   */
  private readonly localBusy = signal<Record<string, number>>({});
  private pollTimer: ReturnType<typeof setTimeout> | null = null;
  /** Odświeżanie listy, dopóki serwer zgłasza trwające generowanie (także po przeładowaniu strony). */
  static readonly POLL_MS = 3000;

  /** Identyfikatory projektów, w których trwa generowanie (lokalnie lub według serwera). */
  readonly generatingIds = computed(() => {
    const ids = new Set(this.projects().filter((p) => p.generating).map((p) => p.id));
    for (const [id, n] of Object.entries(this.localBusy())) if (n > 0) ids.add(id);
    return ids;
  });

  async load() {
    try {
      const [projects, folders] = await Promise.all([this.api.projects(), this.api.folders()]);
      this.projects.set(projects);
      this.folders.set(folders);
      this.error.set(null);
      this.schedulePoll();
    } catch (e) {
      this.error.set((e as { message: string }).message);
    } finally {
      this.loaded.set(true);
    }
  }

  /** Odświeża same projekty (np. aktualne statusy po zakończeniu generowania). */
  async refreshProjects() {
    try {
      this.projects.set(await this.api.projects());
      this.schedulePoll();
    } catch {
      /* spróbujemy przy następnym odświeżeniu */
    }
  }

  /**
   * Oznacza początek/koniec generowania w projekcie. Projekt od razu trafia na górę „Ostatnich projektów”
   * ze spinnerem; po zakończeniu lista jest odświeżana (nowy status i czas nagrania).
   */
  markGenerating(p: ProjectDto, on: boolean) {
    if (on) this.touch(p);
    this.localBusy.update((m) => {
      const n = Math.max(0, (m[p.id] ?? 0) + (on ? 1 : -1));
      const next = { ...m };
      if (n) next[p.id] = n;
      else delete next[p.id];
      return next;
    });
    if (!on && !this.localBusy()[p.id]) void this.refreshProjects();
  }

  /** Edytor zamknięty: dalszy stan (np. trwające zadanie w tle) śledzimy już z serwera. */
  releaseLocal(projectId: string) {
    if (!this.localBusy()[projectId]) return;
    this.localBusy.update((m) => {
      const next = { ...m };
      delete next[projectId];
      return next;
    });
    void this.refreshProjects();
  }

  private schedulePoll() {
    if (this.pollTimer || !this.projects().some((p) => p.generating)) return;
    this.pollTimer = setTimeout(() => {
      this.pollTimer = null;
      void this.refreshProjects();
    }, ProjectsStore.POLL_MS);
  }

  /** Aktualizuje wpis po zmianach w edytorze (bez pełnego przeładowania). */
  touch(p: ProjectDto) {
    const prev = this.projects().find((x) => x.id === p.id);
    const summary = { ...p, segments: [], generating: p.generating ?? prev?.generating ?? false };
    this.projects.update((list) => [summary, ...list.filter((x) => x.id !== p.id)]);
  }

  remove(id: string) {
    this.projects.update((list) => list.filter((p) => p.id !== id));
  }

  /** Przeniesienie projektu do folderu (null = bez folderu) — bez zmiany kolejności na liście. */
  async move(projectId: string, folderId: string | null) {
    const p = this.projects().find((x) => x.id === projectId);
    if (!p || (p.folderId ?? null) === folderId) return;
    this.projects.update((list) => list.map((x) => (x.id === projectId ? { ...x, folderId } : x)));
    try {
      await this.api.moveProject(projectId, folderId);
    } catch (e) {
      this.projects.update((list) => list.map((x) => (x.id === projectId ? { ...x, folderId: p.folderId ?? null } : x)));
      throw e;
    }
  }

  async createFolder(name: string) {
    const f = await this.api.createFolder(name);
    this.folders.update((l) => [...l, f].sort((a, b) => a.name.localeCompare(b.name, 'pl')));
    return f;
  }

  async renameFolder(id: string, name: string) {
    const f = await this.api.renameFolder(id, name);
    this.folders.update((l) => l.map((x) => (x.id === id ? f : x)).sort((a, b) => a.name.localeCompare(b.name, 'pl')));
  }

  /** Usuwa folder; jego projekty wracają do „Wszystkich projektów”. */
  async deleteFolder(id: string) {
    await this.api.deleteFolder(id);
    this.folders.update((l) => l.filter((x) => x.id !== id));
    this.projects.update((list) => list.map((x) => (x.folderId === id ? { ...x, folderId: null } : x)));
  }
}
