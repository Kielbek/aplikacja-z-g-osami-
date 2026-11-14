import { Injectable, signal } from '@angular/core';

export type ToastTone = 'default' | 'success' | 'danger' | 'demo';

export interface Toast {
  id: number;
  message: string;
  tone: ToastTone;
  detail?: string;
}

@Injectable({ providedIn: 'root' })
export class ToastService {
  readonly toasts = signal<Toast[]>([]);
  private seq = 0;

  show(message: string, tone: ToastTone = 'default', detail?: string, ttl = 4200) {
    const id = ++this.seq;
    this.toasts.update((t) => [...t.slice(-3), { id, message, tone, detail }]);
    setTimeout(() => this.dismiss(id), ttl);
  }

  success(message: string, detail?: string) {
    this.show(message, 'success', detail);
  }

  error(message: string, detail?: string) {
    this.show(message, 'danger', detail, 6500);
  }

  dismiss(id: number) {
    this.toasts.update((t) => t.filter((x) => x.id !== id));
  }
}
