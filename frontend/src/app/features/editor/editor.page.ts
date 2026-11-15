import { ChangeDetectionStrategy, Component, HostListener, computed, effect, inject, input, signal, viewChild } from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { RouterLink } from '@angular/router';
import { AccountStore } from '../../core/services/account.store';
import { EngineStatusService } from '../../core/services/engine-status.service';
import { PlayerService } from '../../core/services/player.service';
import { formatDuration, pluralN } from '../../core/util/format';
import { DropdownComponent } from '../../shared/dropdown.component';
import { EngineBannerComponent } from '../../shared/engine-banner.component';
import { IconComponent } from '../../shared/icon.component';
import { EditorStore } from './editor.store';
import { CdkDrag, CdkDragPlaceholder, CdkDropList } from '@angular/cdk/drag-drop';
import { SegmentRowComponent } from './segment-row.component';
import { VersionsDialogComponent } from './versions-dialog.component';
import { VoicePanelComponent } from './voice-panel.component';
import { VoicePickerComponent } from './voice-picker.component';
import { NarratorEditorComponent } from './narrator-editor.component';
import { StarterPrompt, StarterPromptsComponent } from './starter-prompts.component';

@Component({
  selector: 'vc-editor-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [EditorStore],
  imports: [
    RouterLink,
    IconComponent,
    DropdownComponent,
    EngineBannerComponent,
    SegmentRowComponent,
    VoicePanelComponent,
    VoicePickerComponent,
    VersionsDialogComponent,
    CdkDropList,
    CdkDrag,
    CdkDragPlaceholder,
    NgTemplateOutlet,
    NarratorEditorComponent,
    StarterPromptsComponent,
  ],
  styleUrl: './editor.page.scss',
  templateUrl: './editor.page.html',
})
export class EditorPage {
  protected store = inject(EditorStore);
  protected engine = inject(EngineStatusService);
  protected player = inject(PlayerService);
  protected account = inject(AccountStore);
  protected wavAllowed = computed(() => this.account.plan()?.exportFormats.includes('wav') ?? true);
  protected wavPlan = computed(() => this.account.lowestPlan((p) => p.exportFormats.includes('wav'))?.name ?? '');
  protected charPct = computed(() => (this.store.characters() / this.store.projectCharLimit()) * 100);

  /** Parametr trasy /editor/:id */
  readonly id = input.required<string>();

  protected inspectorOpen = signal(false);
  protected pauseOptions = [
    { ms: 500, label: 'Krótka — 0,5 s', token: '0.5s' },
    { ms: 1000, label: 'Średnia — 1 s', token: '1s' },
    { ms: 2000, label: 'Długa — 2 s', token: '2s' },
    { ms: 3000, label: 'Bardzo długa — 3 s', token: '3s' },
  ];
  protected fmt = formatDuration;
  protected pluralN = pluralN;

  protected projectTrackId = computed(() => `project:${this.store.project()?.id}`);
  protected playingAll = computed(() => this.player.isPlaying(this.projectTrackId()));
  protected loadingAll = computed(() => this.player.isLoading(this.projectTrackId()));

  protected generateLabel = computed(() => {
    const n = this.store.pendingIds().length;
    if (n === 0) return this.store.allReady() ? 'Wszystko aktualne' : 'Generuj';
    const total = this.store.nonEmpty().length;
    return n === total ? `Generuj całość` : `Generuj zmienione (${n})`;
  });

  protected batchPct = computed(() => {
    const b = this.store.batch();
    return b ? (b.done / b.total) * 100 : 0;
  });

  protected saveLabel = computed(() => {
    switch (this.store.saveState()) {
      case 'saving':
        return 'Zapisywanie…';
      case 'dirty':
        return 'Niezapisane zmiany';
      case 'error':
        return 'Błąd zapisu';
      default:
        return 'Zapisano';
    }
  });

  constructor() {
    effect(() => void this.store.load(this.id()));
  }

  protected playAll() {
    const t = this.store.projectTrack();
    if (t) void this.player.play(t);
  }

  protected onImport(e: Event) {
    const input = e.target as HTMLInputElement;
    const file = input.files?.[0];
    if (file) void this.store.importFile(file);
    input.value = '';
  }

  private narrator = viewChild(NarratorEditorComponent);

  protected insertPause(p: { ms: number; token: string }) {
    const n = this.narrator();
    if (this.store.viewMode() === 'narrator' && n) n.insertPause(p.token);
    else this.store.insertPause(p.ms);
  }

  /** Podpowiedź startowa: tekst dla jednego lektora albo gotowa rozmowa w widoku dialogu. */
  protected useStarter(p: StarterPrompt) {
    if (p.dialog) {
      this.store.setViewMode('dialog');
      this.store.startDialog(p.dialog);
    } else if (p.text) {
      this.store.setFullText(p.text);
      if (this.store.viewMode() === 'narrator') queueMicrotask(() => this.narrator()?.focus());
    }
  }

  protected generateSelected() {
    void this.store.generateMany(this.store.selectedIds());
  }

  @HostListener('window:beforeunload', ['$event'])
  onUnload(e: BeforeUnloadEvent) {
    if (this.store.saveState() === 'dirty' || this.store.saveState() === 'saving') {
      void this.store.flush();
      e.preventDefault();
    }
  }

  @HostListener('document:keydown', ['$event'])
  onKey(e: KeyboardEvent) {
    if ((e.ctrlKey || e.metaKey) && e.shiftKey && e.key === 'Enter') {
      e.preventDefault();
      void this.store.generateMany();
    }
    const target = e.target as HTMLElement;
    const inField = !!target.closest('input, textarea, select, [contenteditable]');
    if ((e.ctrlKey || e.metaKey) && !inField && ['z', 'y'].includes(e.key.toLowerCase())) {
      e.preventDefault();
      if (e.key.toLowerCase() === 'y' || e.shiftKey) this.store.redo();
      else this.store.undo();
    }
    if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 's') {
      e.preventDefault();
      void this.store.flush();
    }
  }
}
