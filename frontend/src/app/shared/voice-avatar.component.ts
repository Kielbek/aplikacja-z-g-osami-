import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { avatarStyle, initials } from '../core/util/labels';
import { portraitUrl } from './voice-portraits';

@Component({
  selector: 'vc-voice-avatar',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    :host { display: inline-flex; }
    img.avatar {
      object-fit: cover;
      background: #eef0f4;
      padding: 0;
    }
  `,
  template: `
    @if (src(); as s) {
      <img class="avatar" [src]="s" alt="" [style]="sizeStyle()" loading="lazy" decoding="async" draggable="false" />
    } @else {
      <span class="avatar" [style]="style()">{{ letters() }}</span>
    }
  `,
})
export class VoiceAvatarComponent {
  readonly id = input.required<string>();
  readonly name = input.required<string>();
  readonly size = input(36);
  protected src = computed(() => portraitUrl(this.id(), this.size()));
  protected letters = computed(() => initials(this.name()));
  protected sizeStyle = computed(() => ({ '--size': `${this.size()}px` }));
  protected style = computed(() => ({ ...avatarStyle(this.id()), '--size': `${this.size()}px` }));
}
