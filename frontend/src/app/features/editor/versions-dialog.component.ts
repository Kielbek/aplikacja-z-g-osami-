import { ChangeDetectionStrategy, Component, HostListener, computed, inject, input } from '@angular/core';
import { formatDuration, formatRelative } from '../../core/util/format';
import { IconComponent } from '../../shared/icon.component';
import { PlayButtonComponent } from '../../shared/play-button.component';
import { EditorStore } from './editor.store';

/** Wersje nagrania fragmentu: odsłuch i przywrócenie wybranej. */
@Component({
  selector: 'vc-versions-dialog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [IconComponent, PlayButtonComponent],
  styles: `
    /* Host nie uczestniczy w układzie siatki edytora */
    :host { display: contents; }
    .scrim { position: fixed; inset: 0; z-index: 90; background: var(--scrim); display: grid; place-items: center; padding: 16px; animation: vc-fade-in 140ms var(--ease); }
    .dialog { width: min(560px, 100%); max-height: min(640px, 90vh); display: flex; flex-direction: column; background: var(--surface-raised); border-radius: var(--radius-xl); box-shadow: var(--shadow-lg); }
    .head { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; padding: 20px 20px 12px; }
    h2 { font-size: var(--text-md); margin-bottom: 4px; }
    .head p { font-size: var(--text-sm); color: var(--text-3); }
    .list { overflow-y: auto; padding: 0 12px 16px; display: flex; flex-direction: column; gap: 6px; }
    .ver { display: grid; grid-template-columns: auto minmax(0, 1fr) auto; gap: 12px; align-items: center; padding: 10px 12px; border-radius: var(--radius-md); border: 1px solid var(--border-subtle); }
    .ver.current { border-color: color-mix(in srgb, var(--accent) 40%, transparent); background: var(--accent-soft); }
    .ver vc-play-button { --pb: 30px; }
    .info { min-width: 0; display: flex; flex-direction: column; gap: 3px; }
    .row1 { display: flex; align-items: center; gap: 8px; font-size: var(--text-sm); font-weight: 550; flex-wrap: wrap; }
    .meta { font-size: var(--text-xs); color: var(--text-3); }
    .txt { font-size: var(--text-xs); color: var(--text-3); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
    .empty-state { padding: 24px; text-align: center; color: var(--text-3); font-size: var(--text-sm); }
  `,
  template: `
    <div class="scrim" (click)="store.closeVersions()">
      <div class="dialog" role="dialog" aria-modal="true" aria-label="Wersje nagrania" (click)="$event.stopPropagation()">
        <div class="head">
          <div>
            <h2>Wersje nagrania — fragment {{ index() + 1 }}</h2>
            <p>Każde wygenerowanie zapisuje osobną wersję. Odsłuchaj i przywróć tę, która brzmi najlepiej.</p>
          </div>
          <button class="btn btn-ghost btn-icon btn-sm" aria-label="Zamknij" (click)="store.closeVersions()"><vc-icon name="x" /></button>
        </div>
        <div class="list">
          @if (store.versions()?.loading) {
            <div class="empty-state"><span class="spinner"></span></div>
          } @else {
            @for (v of store.versions()?.list ?? []; track v.id; let i = $index) {
              <div class="ver" [class.current]="v.current">
                <vc-play-button [track]="store.versionTrack(segmentId(), v)" [iconSize]="11" />
                <div class="info">
                  <div class="row1">
                    Wersja {{ (store.versions()?.list?.length ?? 0) - i }}
                    @if (v.current) { <span class="badge badge-accent">Aktualnie używana</span> }
                    @if (!v.matchesCurrentText) { <span class="badge badge-warning" title="Wersja powstała dla innego tekstu, głosu lub ustawień">Inny tekst lub ustawienia</span> }
                    @if (v.engineMode === 'demo') { <span class="badge badge-demo">Demo</span> }
                  </div>
                  <div class="meta">{{ rel(v.createdAt) }} · {{ dur(v.durationMs) }}@if (v.voiceName) { · {{ v.voiceName }} }</div>
                  @if (!v.matchesCurrentText) { <div class="txt" [title]="v.text">„{{ v.text }}”</div> }
                </div>
                @if (!v.current) {
                  <button class="btn btn-sm" (click)="store.restoreVersion(segmentId(), v.id)">Przywróć</button>
                }
              </div>
            } @empty {
              <div class="empty-state">Brak zapisanych wersji tego fragmentu w okresie przechowywania historii.</div>
            }
          }
        </div>
      </div>
    </div>
  `,
})
export class VersionsDialogComponent {
  protected store = inject(EditorStore);
  readonly segmentId = input.required<string>();
  protected index = computed(() => this.store.segments().findIndex((s) => s.id === this.segmentId()));
  protected rel = formatRelative;
  protected dur = formatDuration;

  @HostListener('document:keydown.escape')
  esc() {
    this.store.closeVersions();
  }
}
