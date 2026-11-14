import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { ApiService } from '../../core/api/api.service';
import { ApiError } from '../../core/api/models';
import { IconComponent } from '../../shared/icon.component';

/** Punkt startowy: otwiera ostatnio edytowany projekt lub tworzy nowy. */
@Component({
  selector: 'vc-start-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [IconComponent, RouterLink],
  template: `
    <div class="empty" style="height:100%;justify-content:center">
      @if (error(); as e) {
        <div class="empty-icon"><vc-icon name="plug" [size]="20" /></div>
        <h3>Nie można połączyć się z serwerem</h3>
        <p>{{ e }} Uruchom backend Spring Boot (port 8080) i odśwież stronę.</p>
        <div class="row"><button class="btn" (click)="start()">Spróbuj ponownie</button><a class="btn btn-ghost" routerLink="/settings/engine">Diagnostyka</a></div>
      } @else {
        <span class="spinner"></span>
      }
    </div>
  `,
})
export class StartPage {
  private api = inject(ApiService);
  private router = inject(Router);
  protected error = signal<string | null>(null);

  constructor() {
    void this.start();
  }

  async start() {
    this.error.set(null);
    try {
      const projects = await this.api.projects();
      const target = projects[0] ?? (await this.api.createProject({}));
      await this.router.navigate(['/editor', target.id], { replaceUrl: true });
    } catch (e) {
      this.error.set((e as ApiError).message);
    }
  }
}
