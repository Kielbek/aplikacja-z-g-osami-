import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { NavigationEnd, Router, RouterOutlet } from '@angular/router';
import { filter } from 'rxjs';
import { AccountStore } from '../core/services/account.store';
import { AuthService } from '../core/services/auth.service';
import { ToastService } from '../core/services/toast.service';
import { EngineStatusService } from '../core/services/engine-status.service';
import { ProjectsStore } from '../core/services/projects.store';
import { VoicesStore } from '../core/services/voices.store';
import { LogoComponent } from '../shared/brand/logo.component';
import { IconComponent } from '../shared/icon.component';
import { PlayerBarComponent } from './player-bar.component';
import { SidebarComponent } from './sidebar.component';

@Component({
  selector: 'vc-shell',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterOutlet, SidebarComponent, PlayerBarComponent, IconComponent, LogoComponent],
  styleUrl: './shell.component.scss',
  template: `
    <div class="shell" [class.nav-open]="navOpen()">
      <vc-sidebar class="sidebar" (navigate)="navOpen.set(false)" />
      <div class="scrim" (click)="navOpen.set(false)"></div>
      <div class="main">
        <header class="mobile-bar">
          <button class="btn btn-ghost btn-icon" aria-label="Menu" (click)="navOpen.set(true)"><vc-icon name="menu" [size]="18" /></button>
          <vc-logo [size]="22" [wordmark]="true" />
          <span style="width:34px"></span>
        </header>
        @if (needsVerification()) {
          <div class="verify-bar" role="status">
            <vc-icon name="info" [size]="15" />
            <span>Potwierdź adres <strong>{{ auth.user()?.email }}</strong>, aby generować nagrania. Link wysłaliśmy e-mailem.</span>
            <button class="btn btn-sm" (click)="resend()" [disabled]="sending()">
              @if (sending()) { <span class="spinner"></span> } Wyślij ponownie
            </button>
          </div>
        }
        <main class="content"><router-outlet /></main>
        <vc-player-bar class="player" />
      </div>
    </div>
  `,
})
export class ShellComponent {
  protected navOpen = signal(false);
  protected auth = inject(AuthService);
  private toast = inject(ToastService);
  private verificationRequired = signal(false);
  protected sending = signal(false);
  protected needsVerification = computed(() => this.verificationRequired() && this.auth.user()?.emailVerified === false);

  async resend() {
    this.sending.set(true);
    try {
      await this.auth.resendVerification();
      this.toast.success('Wysłaliśmy nowy link potwierdzający.');
    } catch (e) {
      this.toast.apiError('Nie udało się wysłać linku', e);
    } finally {
      this.sending.set(false);
    }
  }

  constructor() {
    void this.auth.loadConfig().then((c) => this.verificationRequired.set(!!c.emailVerificationRequired));
    inject(EngineStatusService).start();
    void inject(AccountStore).load();
    void inject(VoicesStore).load();
    void inject(ProjectsStore).load();
    inject(Router)
      .events.pipe(filter((e) => e instanceof NavigationEnd))
      .subscribe(() => this.navOpen.set(false));
  }
}
