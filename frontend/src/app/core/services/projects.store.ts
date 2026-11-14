import { Injectable, inject, signal } from '@angular/core';
import { ApiService } from '../api/api.service';
import { ProjectDto } from '../api/models';

/** Lista projektów (sidebar, widok projektów). Edytor zarządza własnym stanem projektu. */
@Injectable({ providedIn: 'root' })
export class ProjectsStore {
  private api = inject(ApiService);

  readonly projects = signal<ProjectDto[]>([]);
  readonly loaded = signal(false);
  readonly error = signal<string | null>(null);

  async load() {
    try {
      this.projects.set(await this.api.projects());
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
}
