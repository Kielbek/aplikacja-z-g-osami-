import { ChangeDetectionStrategy, Component, computed, input, signal } from '@angular/core';
import { IconComponent } from './icon.component';

export interface CodeSample {
  label: string;
  code: string;
}

/** Blok kodu z zakładkami języków i przyciskiem „Kopiuj”. */
@Component({
  selector: 'vc-code-block',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [IconComponent],
  template: `
    <div class="code">
      <div class="bar">
        @if (samples().length > 1) {
          <div class="cb-tabs" role="tablist">
            @for (s of samples(); track s.label; let i = $index) {
              <button role="tab" [class.on]="i === active()" [attr.aria-selected]="i === active()" (click)="active.set(i)">{{ s.label }}</button>
            }
          </div>
        } @else {
          <span class="lang">{{ samples()[0]?.label }}</span>
        }
        <button class="copy" (click)="copy()" [attr.aria-label]="'Kopiuj ' + (current()?.label ?? '')">
          <vc-icon [name]="copied() ? 'check' : 'copy'" [size]="13" />{{ copied() ? 'Skopiowano' : 'Kopiuj' }}
        </button>
      </div>
      <pre><code>{{ current()?.code }}</code></pre>
    </div>
  `,
  styles: `
    :host { display: block; }
    .code { border-radius: 12px; overflow: hidden; background: #0f0f10; border: 1px solid #222; color: #e8e8e6; }
    .bar { display: flex; align-items: center; justify-content: space-between; gap: 8px; padding: 6px 8px 6px 10px; border-bottom: 1px solid #222; }
    .cb-tabs { display: flex; gap: 2px; border: 0; margin: 0; padding: 0; }
    .cb-tabs button, .copy {
      border: 0; background: transparent; color: #a3a3a0; font: inherit; font-size: 12.5px; padding: 5px 9px; border-radius: 7px; cursor: pointer;
    }
    .cb-tabs button.on { background: #222224; color: #fff; }
    .cb-tabs button:hover, .copy:hover { color: #fff; }
    .lang { font-size: 12px; color: #a3a3a0; padding-left: 2px; }
    .copy { display: inline-flex; align-items: center; gap: 6px; }
    pre { margin: 0; padding: 14px 16px; overflow-x: auto; font-size: 12.8px; line-height: 1.6; }
    code { font-family: ui-monospace, 'SF Mono', 'JetBrains Mono', Menlo, Consolas, monospace; white-space: pre; }
  `,
})
export class CodeBlockComponent {
  readonly samples = input.required<CodeSample[]>();
  protected active = signal(0);
  protected copied = signal(false);
  protected current = computed(() => this.samples()[Math.min(this.active(), this.samples().length - 1)]);

  async copy() {
    const code = this.current()?.code ?? '';
    try {
      await navigator.clipboard.writeText(code);
    } catch {
      return;
    }
    this.copied.set(true);
    setTimeout(() => this.copied.set(false), 1600);
  }
}
