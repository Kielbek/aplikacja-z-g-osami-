import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { avatarStyle, initials } from '../core/util/labels';

@Component({
  selector: 'vc-voice-avatar',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `:host { display: inline-flex; }`,
  template: `<span class="avatar" [style]="style()">{{ letters() }}</span>`,
})
export class VoiceAvatarComponent {
  readonly id = input.required<string>();
  readonly name = input.required<string>();
  readonly size = input(36);
  protected letters = computed(() => initials(this.name()));
  protected style = computed(() => ({ ...avatarStyle(this.id()), '--size': `${this.size()}px` }));
}
