import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { ToastService } from '../core/services/toast.service';
import { IconComponent } from './icon.component';

@Component({
  selector: 'vc-toast-host',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [IconComponent],
  styles: `
    :host {
      position: fixed; right: 20px; bottom: calc(var(--player-h) + 16px); z-index: 100;
      display: flex; flex-direction: column; gap: 8px; width: min(380px, calc(100vw - 32px));
      pointer-events: none;
    }
    .toast {
      pointer-events: auto; display: flex; gap: 10px; align-items: flex-start;
      padding: 12px 12px 12px 14px; border-radius: var(--radius-lg);
      background: var(--graphite); color: var(--text-inverse); box-shadow: var(--shadow-lg);
      font-size: var(--text-sm); animation: vc-fade-in 180ms var(--ease);
    }
    .toast vc-icon { margin-top: 2px; }
    .body { flex: 1; min-width: 0; }
    .detail { opacity: .72; font-size: var(--text-xs); margin-top: 2px; line-height: 1.45; }
    .success vc-icon.lead { color: #7fd1a8; }
    .danger vc-icon.lead { color: #f0938b; }
    .demo vc-icon.lead { color: #e7c27a; }
    .x { all: unset; cursor: pointer; opacity: .6; padding: 2px; border-radius: 4px; }
    .x:hover { opacity: 1; }
  `,
  template: `
    @for (t of toasts.toasts(); track t.id) {
      <div class="toast" [class]="t.tone" role="status">
        <vc-icon class="lead" [name]="t.tone === 'danger' ? 'circle-alert' : t.tone === 'demo' ? 'flask' : t.tone === 'success' ? 'circle-check' : 'info'" />
        <div class="body">
          <div>{{ t.message }}</div>
          @if (t.detail) { <div class="detail">{{ t.detail }}</div> }
        </div>
        <button class="x" aria-label="Zamknij" (click)="toasts.dismiss(t.id)"><vc-icon name="x" [size]="14" /></button>
      </div>
    }
  `,
})
export class ToastHostComponent {
  protected toasts = inject(ToastService);
}
