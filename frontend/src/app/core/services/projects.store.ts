import { Injectable, inject, signal } from '@angular/core';
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

  async load() {
    try {
      const [projects, folders] = await Promise.all([this.api.projects(), this.api.folders()]);
      this.projects.set(projects);
      this.folders.set(folders);
      this.error.set(null);
    } catch (e) {
      this.error.set((e as { message: string }).message);
    } finally {
      this.loaded.set(true);
    }
  }

  /** Aktualizuje wpis po zmianach w edytorze (bez pełnego przeładowania). */
  touch(p: ProjectDto) {
    const summary = { ...p, segments: [] };
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
