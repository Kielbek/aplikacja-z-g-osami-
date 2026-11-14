import { Injectable, computed, inject, signal } from '@angular/core';
import { ApiService } from '../api/api.service';
import { Voice } from '../api/models';

@Injectable({ providedIn: 'root' })
export class VoicesStore {
  private api = inject(ApiService);

  readonly voices = signal<Voice[]>([]);
  readonly loaded = signal(false);
  readonly byId = computed(() => new Map(this.voices().map((v) => [v.id, v])));
  readonly favorites = computed(() => this.voices().filter((v) => v.favorite));

  async load(force = false) {
    if (this.loaded() && !force) return;
    try {
      this.voices.set(await this.api.voices());
      this.loaded.set(true);
    } catch {
      /* obsłużone w widokach */
    }
  }

  get(id: string | null | undefined): Voice | undefined {
    return id ? this.byId().get(id) : undefined;
  }

  async toggleFavorite(v: Voice) {
    const updated = await this.api.setFavorite(v.id, !v.favorite);
    this.upsert(updated);
    return updated;
  }

  upsert(v: Voice) {
    this.voices.update((list) => (list.some((x) => x.id === v.id) ? list.map((x) => (x.id === v.id ? v : x)) : [v, ...list]));
  }

  remove(id: string) {
    this.voices.update((list) => list.filter((v) => v.id !== id));
  }
}
