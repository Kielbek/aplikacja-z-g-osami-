import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { PronunciationEntry } from '../../core/api/models';
import { IconComponent } from '../../shared/icon.component';
import { EditorStore } from './editor.store';

/** Słownik wymowy projektu (oraz podgląd wpisów globalnych) w panelu ustawień głosu. */
@Component({
  selector: 'vc-dictionary-panel',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [IconComponent, RouterLink],
  styles: `
    :host { display: flex; flex-direction: column; gap: 8px; }
    .entries { display: flex; flex-direction: column; border: 1px solid var(--border-subtle); border-radius: var(--radius-md); overflow: hidden; }
    .entry { display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 6px; align-items: center; padding: 7px 6px 7px 10px; border-bottom: 1px solid var(--border-subtle); cursor: pointer; }
    .entry:last-child { border-bottom: 0; }
    .entry:hover { background: var(--surface-hover); }
    .entry.editing { background: var(--accent-soft); }
    .pair { min-width: 0; display: flex; flex-direction: column; gap: 1px; font-size: var(--text-xs); }
    .term { font-weight: 600; color: var(--text); display: flex; align-items: center; gap: 6px; }
    .repl { color: var(--text-3); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .badge { height: 16px; font-size: 9.5px; padding: 0 5px; }
    .form { display: grid; grid-template-columns: 1fr 1fr; gap: 6px; }
    .form .input { min-height: 30px; font-size: var(--text-xs); padding: 5px 8px; }
    .actions { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
    .checkbox { font-size: var(--text-xs); }
    .hint { font-size: var(--text-xs); color: var(--text-3); line-height: 1.45; }
    .x { color: var(--text-4); }
    .x:hover { color: var(--danger); }
  `,
  template: `
    @if (entries().length) {
      <div class="entries">
        @for (e of entries(); track e.id) {
          <div class="entry" [class.editing]="editing()?.id === e.id" (click)="edit(e)" [title]="'Kliknij, aby edytować'">
            <div class="pair">
              <span class="term">{{ e.term }} @if (!e.projectId) { <span class="badge" title="Wpis globalny — działa we wszystkich projektach">globalny</span> }</span>
              <span class="repl">→ {{ e.replacement }}</span>
            </div>
            <button class="btn btn-ghost btn-icon btn-sm x" [attr.aria-label]="'Usuń ' + e.term" (click)="$event.stopPropagation(); store.deleteDictionaryEntry(e.id)"><vc-icon name="x" [size]="13" /></button>
          </div>
        }
      </div>
    }
    <div class="form">
      <input class="input" placeholder="Hasło, np. VoxCPM" maxlength="100" [value]="term()" (input)="term.set($any($event.target).value)" (keydown.enter)="save()" aria-label="Hasło" />
      <input class="input" placeholder="Wymowa, np. woks ce pe em" maxlength="300" [value]="replacement()" (input)="replacement.set($any($event.target).value)" (keydown.enter)="save()" aria-label="Wymowa" />
    </div>
    <div class="actions">
      @if (!editing()) {
        <label class="checkbox"><input type="checkbox" [checked]="global()" (change)="global.set($any($event.target).checked)" />We wszystkich projektach</label>
      } @else {
        <button class="btn btn-ghost btn-sm" (click)="cancel()">Anuluj</button>
      }
      <button class="btn btn-sm" [disabled]="!canSave() || saving()" (click)="save()">
        <vc-icon [name]="editing() ? 'check' : 'plus'" [size]="13" />{{ editing() ? 'Zapisz' : 'Dodaj' }}
      </button>
    </div>
    <p class="hint">Hasła są zamieniane przed syntezą (całe słowa, bez względu na wielkość liter). Nieaktualne staną się tylko fragmenty, które zawierają zmienione hasło. Wpisy globalne: <a routerLink="/settings/dictionary">Ustawienia</a>.</p>
  `,
})
export class DictionaryPanelComponent {
  protected store = inject(EditorStore);
  protected term = signal('');
  protected replacement = signal('');
  protected global = signal(false);
  protected saving = signal(false);
  protected editing = signal<PronunciationEntry | null>(null);

  protected entries = computed(() =>
    [...this.store.dictionary()].sort((a, b) => Number(!!a.projectId === false) - Number(!!b.projectId === false) || a.term.localeCompare(b.term, 'pl')),
  );
  protected canSave = computed(() => this.term().trim().length > 0 && this.replacement().trim().length > 0);

  edit(e: PronunciationEntry) {
    this.editing.set(e);
    this.term.set(e.term);
    this.replacement.set(e.replacement);
  }

  cancel() {
    this.editing.set(null);
    this.term.set('');
    this.replacement.set('');
  }

  async save() {
    if (!this.canSave()) return;
    this.saving.set(true);
    const editing = this.editing();
    if (editing) {
      await this.store.updateDictionaryEntry({ ...editing, term: this.term().trim(), replacement: this.replacement().trim() });
      this.cancel();
    } else if (await this.store.addDictionaryEntry(this.term().trim(), this.replacement().trim(), this.global())) {
      this.cancel();
    }
    this.saving.set(false);
  }
}
