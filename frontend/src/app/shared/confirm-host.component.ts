import { ChangeDetectionStrategy, Component, HostListener, inject } from '@angular/core';
import { ConfirmService } from '../core/services/confirm.service';

@Component({
  selector: 'vc-confirm-host',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    .scrim { position: fixed; inset: 0; background: var(--scrim); z-index: 90; display: grid; place-items: center; padding: 16px; animation: vc-fade-in 140ms var(--ease); }
    .dialog { width: min(420px, 100%); background: var(--surface-raised); border-radius: var(--radius-xl); box-shadow: var(--shadow-lg); padding: 22px; }
    h2 { font-size: var(--text-md); margin-bottom: 6px; }
    p { color: var(--text-2); font-size: var(--text-sm); line-height: 1.55; }
    .actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 20px; }
  `,
  template: `
    @if (confirm.pending(); as p) {
      <div class="scrim" (click)="confirm.close(false)">
        <div class="dialog" role="alertdialog" aria-modal="true" [attr.aria-label]="p.title" (click)="$event.stopPropagation()">
          <h2>{{ p.title }}</h2>
          @if (p.message) { <p>{{ p.message }}</p> }
          <div class="actions">
            <button class="btn" (click)="confirm.close(false)">{{ p.cancelLabel ?? 'Anuluj' }}</button>
            <button class="btn" [class.btn-danger-solid]="p.danger" [class.btn-primary]="!p.danger" (click)="confirm.close(true)" autofocus>
              {{ p.confirmLabel ?? 'Potwierdź' }}
            </button>
          </div>
        </div>
      </div>
    }
  `,
})
export class ConfirmHostComponent {
  protected confirm = inject(ConfirmService);

  @HostListener('document:keydown.escape')
  esc() {
    if (this.confirm.pending()) this.confirm.close(false);
  }
}
