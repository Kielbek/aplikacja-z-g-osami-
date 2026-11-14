import { Injectable, computed, inject, signal } from '@angular/core';
import { ApiService } from '../api/api.service';
import { EngineStatus } from '../api/models';

/** Stan silnika TTS (VoxCPM2 / tryb demo / offline), odświeżany cyklicznie. */
@Injectable({ providedIn: 'root' })
export class EngineStatusService {
  private api = inject(ApiService);
  private timer: ReturnType<typeof setInterval> | null = null;

  readonly status = signal<EngineStatus | null>(null);
  readonly backendDown = signal(false);
  readonly loading = signal(false);

  readonly mode = computed(() => (this.backendDown() ? 'offline' : (this.status()?.mode ?? 'offline')));
  readonly isDemo = computed(() => this.mode() === 'demo');
  readonly isOffline = computed(() => this.mode() === 'offline');
  readonly isReal = computed(() => this.mode() === 'voxcpm2');
  readonly caps = computed(() => this.status()?.capabilities ?? null);
  readonly languages = computed(() => this.caps()?.languages ?? ['pl', 'en', 'de', 'fr', 'es', 'it', 'uk']);

  readonly label = computed(() => {
    switch (this.mode()) {
      case 'voxcpm2':
        return 'VoxCPM2 połączony';
      case 'demo':
        return 'Tryb demonstracyjny';
      default:
        return this.backendDown() ? 'Backend niedostępny' : 'Silnik offline';
    }
  });

  start() {
    if (this.timer) return;
    void this.refresh();
    this.timer = setInterval(() => void this.refresh(), 20000);
  }

  async refresh(force = false) {
    this.loading.set(true);
    try {
      this.status.set(await this.api.engineStatus(force));
      this.backendDown.set(false);
    } catch {
      this.backendDown.set(true);
      this.status.set(null);
    } finally {
      this.loading.set(false);
    }
  }
}
