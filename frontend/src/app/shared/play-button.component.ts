import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { PlayerService, PlayerTrack } from '../core/services/player.service';
import { IconComponent } from './icon.component';

/** Przycisk odtwarzania powiązany z globalnym odtwarzaczem. */
@Component({
  selector: 'vc-play-button',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [IconComponent],
  styles: `
    :host { display: inline-flex; }
    .pb {
      width: var(--pb, 32px); height: var(--pb, 32px); border-radius: 50%;
      display: grid; place-items: center; border: 1px solid var(--border);
      background: var(--surface); color: var(--text); box-shadow: var(--shadow-xs);
      transition: all var(--dur-fast) var(--ease);
    }
    .pb:hover:not(:disabled) { border-color: var(--border-strong); background: var(--surface-hover); }
    .pb.is-on { background: var(--graphite); border-color: var(--graphite); color: var(--text-inverse); }
    .pb:disabled { opacity: .4; }
    .pb.solid { background: var(--graphite); border-color: var(--graphite); color: var(--text-inverse); }
    .pb.solid:hover:not(:disabled) { background: var(--graphite-hover); }
  `,
  template: `
    <button
      type="button"
      class="pb"
      [class.is-on]="playing()"
      [class.solid]="solid()"
      [disabled]="disabled() || !track()"
      [attr.aria-label]="playing() ? 'Pauza' : label()"
      [attr.title]="playing() ? 'Pauza' : label()"
      (click)="$event.stopPropagation(); onClick()"
    >
      @if (loading()) {
        <span class="spinner"></span>
      } @else if (playing()) {
        <vc-icon name="pause" [size]="iconSize()" />
      } @else {
        <vc-icon name="play" [size]="iconSize()" style="margin-left:1px" />
      }
    </button>
  `,
})
export class PlayButtonComponent {
  private player = inject(PlayerService);
  readonly track = input<PlayerTrack | null>(null);
  readonly disabled = input(false);
  readonly solid = input(false);
  readonly iconSize = input(13);
  readonly label = input('Odtwórz');

  protected playing = computed(() => {
    const t = this.track();
    return !!t && this.player.isPlaying(t.id) && this.player.track()?.url === t.url;
  });
  protected loading = computed(() => {
    const t = this.track();
    return !!t && this.player.isLoading(t.id);
  });

  onClick() {
    const t = this.track();
    if (t) void this.player.play(t);
  }
}
