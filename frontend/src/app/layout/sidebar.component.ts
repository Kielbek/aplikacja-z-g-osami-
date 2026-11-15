import { ChangeDetectionStrategy, Component, computed, inject, output, signal } from '@angular/core';
import { Router, RouterLink, RouterLinkActive } from '@angular/router';
import { ApiService } from '../core/api/api.service';
import { AccountStore } from '../core/services/account.store';
import { AuthService } from '../core/services/auth.service';
import { EngineStatusService } from '../core/services/engine-status.service';
import { ProjectsStore } from '../core/services/projects.store';
import { ToastService } from '../core/services/toast.service';
import { formatNumber } from '../core/util/format';
import { initials, PROJECT_STATUS } from '../core/util/labels';
import { DropdownComponent } from '../shared/dropdown.component';
import { LogoComponent } from '../shared/brand/logo.component';
import { IconComponent } from '../shared/icon.component';

@Component({
  selector: 'vc-sidebar',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, RouterLinkActive, IconComponent, DropdownComponent, LogoComponent],
  styleUrl: './sidebar.component.scss',
  templateUrl: './sidebar.component.html',
})
export class SidebarComponent {
  private api = inject(ApiService);
  private router = inject(Router);
  private toast = inject(ToastService);
  private auth = inject(AuthService);
  protected engine = inject(EngineStatusService);
  protected account = inject(AccountStore);
  protected projects = inject(ProjectsStore);

  readonly navigate = output<void>();
  protected creating = signal(false);
  protected PROJECT_STATUS = PROJECT_STATUS;

  protected recent = computed(() => this.projects.projects().slice(0, 5));
  protected initials = computed(() => initials(this.account.account()?.fullName ?? 'U'));
  protected usagePct = computed(() => {
    const u = this.account.usage();
    return u ? Math.min(100, (u.charactersUsed / u.characterLimit) * 100) : 0;
  });
  protected usageLabel = computed(() => {
    const u = this.account.usage();
    return u ? `${formatNumber(u.charactersUsed)} / ${formatNumber(u.characterLimit)}` : '—';
  });

  async newProject() {
    if (this.creating()) return;
    this.creating.set(true);
    try {
      const p = await this.api.createProject({});
      this.projects.touch(p);
      void this.account.refreshUsage();
      this.navigate.emit();
      await this.router.navigate(['/editor', p.id]);
    } catch (e) {
      this.toast.apiError('Nie udało się utworzyć projektu', e);
    } finally {
      this.creating.set(false);
    }
  }

  logout() {
    void this.auth.logout();
  }
}
