import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { ThemeService } from './core/services/theme.service';
import { ConfirmHostComponent } from './shared/confirm-host.component';
import { ToastHostComponent } from './shared/toast-host.component';
import { UpsellHostComponent } from './shared/upsell-host.component';

@Component({
  selector: 'app-root',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterOutlet, ToastHostComponent, ConfirmHostComponent, UpsellHostComponent],
  template: `<router-outlet /><vc-toast-host /><vc-confirm-host /><vc-upsell-host />`,
})
export class App {
  constructor() {
    inject(ThemeService);
  }
}
