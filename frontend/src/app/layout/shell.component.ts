import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { NavigationEnd, Router, RouterOutlet } from '@angular/router';
import { filter } from 'rxjs';
import { AccountStore } from '../core/services/account.store';
import { EngineStatusService } from '../core/services/engine-status.service';
import { ProjectsStore } from '../core/services/projects.store';
import { VoicesStore } from '../core/services/voices.store';
import { IconComponent } from '../shared/icon.component';
import { PlayerBarComponent } from './player-bar.component';
import { SidebarComponent } from './sidebar.component';

@Component({
  selector: 'vc-shell',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterOutlet, SidebarComponent, PlayerBarComponent, IconComponent],
  styleUrl: './shell.component.scss',
  template: `
    <div class="shell" [class.nav-open]="navOpen()">
      <vc-sidebar class="sidebar" (navigate)="navOpen.set(false)" />
      <div class="scrim" (click)="navOpen.set(false)"></div>
      <div class="main">
        <header class="mobile-bar">
          <button class="btn btn-ghost btn-icon" aria-label="Menu" (click)="navOpen.set(true)"><vc-icon name="menu" [size]="18" /></button>
          <span class="mobile-brand">VoiceCraft <span>AI</span></span>
          <span style="width:34px"></span>
        </header>
        <main class="content"><router-outlet /></main>
        <vc-player-bar class="player" />
      </div>
    </div>
  `,
})
export class ShellComponent {
  protected navOpen = signal(false);

  constructor() {
    inject(EngineStatusService).start();
    void inject(AccountStore).load();
    void inject(VoicesStore).load();
    void inject(ProjectsStore).load();
    inject(Router)
      .events.pipe(filter((e) => e instanceof NavigationEnd))
      .subscribe(() => this.navOpen.set(false));
  }
}
