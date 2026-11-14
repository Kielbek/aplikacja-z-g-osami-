import { ChangeDetectionStrategy, Component, HostListener, inject } from '@angular/core';
import { PlayerService } from '../core/services/player.service';
import { formatSeconds } from '../core/util/format';
import { IconComponent } from '../shared/icon.component';
import { WaveformComponent } from '../shared/waveform.component';

@Component({
  selector: 'vc-player-bar',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [IconComponent, WaveformComponent],
  styleUrl: './player-bar.component.scss',
  template: `
    <div class="bar" [class.is-empty]="!player.track()">
      <div class="info">
        @if (player.track(); as t) {
          <div class="title-row">
            <span class="title" [title]="t.title">{{ t.title }}</span>
            @if (player.isDemo()) { <span class="badge badge-demo" title="Nagranie wygenerowane w trybie demonstracyjnym — sygnał zastępczy, nie mowa">Demo</span> }
          </div>
          @if (t.subtitle) { <div class="subtitle" [title]="t.subtitle">{{ t.subtitle }}</div> }
        } @else {
          <div class="subtitle">Nic nie jest odtwarzane</div>
        }
      </div>

      <div class="transport">
        <button class="btn btn-ghost btn-icon btn-sm hide-sm" aria-label="Cofnij 5 sekund" title="Cofnij 5 s" [disabled]="player.status() !== 'ready'" (click)="player.skip(-5)">
          <vc-icon name="skip-back" [size]="15" />
        </button>
        <button class="play" [disabled]="player.status() !== 'ready'" (click)="player.toggle()" [attr.aria-label]="player.playing() ? 'Pauza' : 'Odtwórz'" title="Odtwórz / pauza (spacja)">
          @if (player.status() === 'loading') {
            <span class="spinner"></span>
          } @else if (player.playing()) {
            <vc-icon name="pause" [size]="14" />
          } @else {
            <vc-icon name="play" [size]="14" style="margin-left:2px" />
          }
        </button>
        <button class="btn btn-ghost btn-icon btn-sm hide-sm" aria-label="Do przodu 5 sekund" title="Do przodu 5 s" [disabled]="player.status() !== 'ready'" (click)="player.skip(5)">
          <vc-icon name="skip-forward" [size]="15" />
        </button>
      </div>

      <div class="timeline">
        <span class="time tabular">{{ fmt(player.currentTime()) }}</span>
        <vc-waveform class="wave" [peaks]="player.peaks()" [progress]="player.progress()" [markers]="player.track()?.markers ?? []" (seek)="player.seek($event)" />
        <span class="time tabular end">{{ fmt(player.duration()) }}</span>
      </div>

      <div class="tools">
        <div class="volume hide-md">
          <button class="btn btn-ghost btn-icon btn-sm" (click)="player.toggleMute()" [attr.aria-label]="player.muted() ? 'Włącz dźwięk' : 'Wycisz'">
            <vc-icon [name]="player.muted() || player.volume() === 0 ? 'volume-x' : 'volume'" [size]="15" />
          </button>
          <input class="range" type="range" min="0" max="1" step="0.01" aria-label="Głośność"
            [value]="player.muted() ? 0 : player.volume()" [style.--fill.%]="(player.muted() ? 0 : player.volume()) * 100"
            (input)="player.setVolume(+$any($event.target).value)" />
        </div>
        <button class="btn btn-ghost btn-icon btn-sm" aria-label="Pobierz WAV" title="Pobierz to nagranie (WAV)" [disabled]="player.status() !== 'ready'" (click)="player.download()">
          <vc-icon name="download" [size]="15" />
        </button>
        @if (player.track()) {
          <button class="btn btn-ghost btn-icon btn-sm hide-sm" aria-label="Zamknij odtwarzacz" title="Zamknij" (click)="player.close()">
            <vc-icon name="x" [size]="15" />
          </button>
        }
      </div>
    </div>
  `,
})
export class PlayerBarComponent {
  protected player = inject(PlayerService);
  protected fmt = formatSeconds;

  @HostListener('document:keydown', ['$event'])
  onKey(e: KeyboardEvent) {
    const el = e.target as HTMLElement;
    if (el.closest('input, textarea, select, [contenteditable], button')) return;
    if (e.code === 'Space') {
      e.preventDefault();
      this.player.toggle();
    }
  }
}
