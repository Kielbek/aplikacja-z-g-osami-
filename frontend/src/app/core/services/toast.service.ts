import { Injectable, signal } from '@angular/core';
import { ApiError } from '../api/models';

export type ToastTone = 'default' | 'success' | 'danger' | 'demo' | 'plan';

export interface Toast {
  id: number;
  message: string;
  tone: ToastTone;
  detail?: string;
  action?: { label: string; link: string };
}

@Injectable({ providedIn: 'root' })
export class ToastService {
  readonly toasts = signal<Toast[]>([]);
  private seq = 0;

  show(message: string, tone: ToastTone = 'default', detail?: string, ttl = 4200, action?: Toast['action']) {
    const id = ++this.seq;
    this.toasts.update((t) => [...t.slice(-3), { id, message, tone, detail, action }]);
    setTimeout(() => this.dismiss(id), ttl);
  }

  success(message: string, detail?: string) {
    this.show(message, 'success', detail);
  }

  error(message: string, detail?: string) {
    this.show(message, 'danger', detail, 6500);
  }

  /** Błąd z API; przy przekroczeniu limitu planu proponuje przejście do subskrypcji. */
  apiError(message: string, err: unknown) {
    const e = err as ApiError;
    if (e?.code === 'PLAN_LIMIT' || e?.code === 'QUOTA_EXCEEDED' || e?.code === 'STORAGE_FULL') {
      this.show('Limit planu', 'plan', e.message, 8000, { label: 'Zobacz plany', link: '/settings/subscription' });
    } else {
      this.error(message, e?.message);
    }
  }

  dismiss(id: number) {
    this.toasts.update((t) => t.filter((x) => x.id !== id));
  }
}
