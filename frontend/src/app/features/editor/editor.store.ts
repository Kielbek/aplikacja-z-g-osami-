import { Injectable, computed, inject, signal } from '@angular/core';
import { ApiService } from '../../core/api/api.service';
import { ApiError, ProjectDto, SegmentDto, SegmentStatus, VoiceSettings } from '../../core/api/models';
import { AccountStore } from '../../core/services/account.store';
import { PlayerService, PlayerTrack } from '../../core/services/player.service';
import { ProjectsStore } from '../../core/services/projects.store';
import { ToastService } from '../../core/services/toast.service';
import { estimateDurationMs } from '../../core/util/format';
import { splitIntoSegments, splitSentences, uuid } from '../../core/util/text';

export interface EditorSegment extends SegmentDto {
  generating: boolean;
  queued: boolean;
}

export interface BatchState {
  total: number;
  done: number;
  failed: number;
  cancelRequested: boolean;
}

const PENDING: SegmentStatus[] = ['draft', 'stale', 'error'];

/**
 * Stan edytora projektu. Odpowiada za edycję fragmentów, autozapis i generowanie.
 * Komponenty widoku tylko wywołują jego metody — nie rozmawiają z API bezpośrednio.
 */
@Injectable()
export class EditorStore {
  private api = inject(ApiService);
  private toast = inject(ToastService);
  private player = inject(PlayerService);
  private accountStore = inject(AccountStore);
  private projectsStore = inject(ProjectsStore);

  readonly project = signal<ProjectDto | null>(null);
  readonly segments = signal<EditorSegment[]>([]);
  readonly settings = signal<VoiceSettings | null>(null);
  readonly name = signal('');
  readonly loadError = signal<string | null>(null);
  readonly saveState = signal<'saved' | 'saving' | 'dirty' | 'error'>('saved');
  readonly selection = signal<ReadonlySet<string>>(new Set());
  readonly batch = signal<BatchState | null>(null);
  readonly focusRequest = signal<{ id: string; caret: number } | null>(null);

  private segTimer: ReturnType<typeof setTimeout> | null = null;
  private settingsTimer: ReturnType<typeof setTimeout> | null = null;
  private nameTimer: ReturnType<typeof setTimeout> | null = null;
  private saving: Promise<void> | null = null;
  private segDirty = false;
  private settingsDirty = false;

  // ------------------------------------------------------------ wartości pochodne

  readonly nonEmpty = computed(() => this.segments().filter((s) => s.text.trim().length > 0));
  readonly pendingIds = computed(() => this.nonEmpty().filter((s) => PENDING.includes(s.status)).map((s) => s.id));
  readonly allReady = computed(() => this.nonEmpty().length > 0 && this.nonEmpty().every((s) => s.status === 'ready'));
  readonly characters = computed(() => this.segments().reduce((n, s) => n + s.text.trim().length, 0));
  readonly readyDurationMs = computed(() =>
    this.segments().filter((s) => s.status === 'ready').reduce((n, s) => n + s.durationMs, 0),
  );
  readonly estimatedDurationMs = computed(() => estimateDurationMs(this.characters(), this.settings()?.speed ?? 1));
  readonly isGenerating = computed(() => this.batch() !== null || this.segments().some((s) => s.generating));
  readonly selectedIds = computed(() => this.segments().filter((s) => this.selection().has(s.id)).map((s) => s.id));

  /** Klucz wersji połączonego nagrania — zmienia się po każdej generacji. */
  readonly mergedVersion = computed(() =>
    this.nonEmpty()
      .map((s) => s.audioVersion ?? '')
      .join('.') + `:${this.accountStore.account()?.pauseBetweenSegmentsMs ?? 0}`,
  );

  // ------------------------------------------------------------ ładowanie

  async load(id: string) {
    this.reset();
    try {
      const p = await this.api.project(id);
      this.project.set(p);
      this.name.set(p.name);
      this.settings.set({ ...p.settings });
      this.segments.set(p.segments.map((s) => ({ ...s, generating: false, queued: false })));
      if (!p.segments.length) this.addSegment();
    } catch (e) {
      this.loadError.set((e as ApiError).message);
    }
  }

  private reset() {
    this.project.set(null);
    this.segments.set([]);
    this.loadError.set(null);
    this.selection.set(new Set());
    this.batch.set(null);
    this.saveState.set('saved');
  }

  // ------------------------------------------------------------ edycja tekstu

  setText(id: string, text: string) {
    this.segments.update((list) =>
      list.map((s) => (s.id === id ? { ...s, text, status: localStatus(s, text), error: null } : s)),
    );
    this.markSegmentsDirty();
  }

  /** Dzieli fragment w miejscu kursora (Enter). */
  splitAt(id: string, caret: number) {
    const list = this.segments();
    const idx = list.findIndex((s) => s.id === id);
    if (idx < 0) return;
    const seg = list[idx];
    const before = seg.text.slice(0, caret).trimEnd();
    const after = seg.text.slice(caret).trimStart();
    const created = blank(after);
    const next = [...list];
    next[idx] = { ...seg, text: before, status: localStatus(seg, before) };
    next.splice(idx + 1, 0, created);
    this.segments.set(next);
    this.focusRequest.set({ id: created.id, caret: 0 });
    this.markSegmentsDirty();
  }

  /** Łączy fragment z poprzednim (Backspace na początku). */
  mergeWithPrevious(id: string) {
    const list = this.segments();
    const idx = list.findIndex((s) => s.id === id);
    if (idx <= 0) return;
    this.mergePair(idx - 1);
  }

  mergeWithNext(id: string) {
    const idx = this.segments().findIndex((s) => s.id === id);
    if (idx < 0 || idx >= this.segments().length - 1) return;
    this.mergePair(idx);
  }

  private mergePair(firstIdx: number) {
    const list = [...this.segments()];
    const a = list[firstIdx];
    const b = list[firstIdx + 1];
    const joiner = a.text && b.text ? ' ' : '';
    const text = a.text.trimEnd() + joiner + b.text.trimStart();
    list[firstIdx] = { ...a, text, status: localStatus(a, text) };
    list.splice(firstIdx + 1, 1);
    this.segments.set(list);
    this.focusRequest.set({ id: a.id, caret: a.text.trimEnd().length + joiner.length });
    this.markSegmentsDirty();
  }

  /** Wstawia nowe fragmenty po wskazanym (np. przy wklejaniu wielu akapitów). */
  insertParagraphs(id: string, caret: number, selectionEnd: number, pasted: string) {
    const parts = splitIntoSegments(pasted);
    if (parts.length <= 1) return false;
    const list = [...this.segments()];
    const idx = list.findIndex((s) => s.id === id);
    if (idx < 0) return false;
    const seg = list[idx];
    const before = seg.text.slice(0, caret);
    const after = seg.text.slice(selectionEnd);
    const firstText = (before + parts[0]).trim();
    list[idx] = { ...seg, text: firstText, status: localStatus(seg, firstText) };
    const created = parts.slice(1).map((t, i) => blank(i === parts.length - 2 ? (t + ' ' + after).trim() : t));
    list.splice(idx + 1, 0, ...created);
    this.segments.set(list);
    const last = created[created.length - 1];
    this.focusRequest.set({ id: last.id, caret: last.text.length - after.trim().length });
    this.markSegmentsDirty();
    return true;
  }

  addSegment(afterId?: string) {
    const created = blank('');
    this.segments.update((list) => {
      if (!afterId) return [...list, created];
      const idx = list.findIndex((s) => s.id === afterId);
      const next = [...list];
      next.splice(idx + 1, 0, created);
      return next;
    });
    this.focusRequest.set({ id: created.id, caret: 0 });
    this.markSegmentsDirty();
  }

  duplicate(id: string) {
    const list = [...this.segments()];
    const idx = list.findIndex((s) => s.id === id);
    if (idx < 0) return;
    list.splice(idx + 1, 0, blank(list[idx].text));
    this.segments.set(list);
    this.markSegmentsDirty();
  }

  remove(id: string) {
    const list = this.segments();
    if (list.length === 1) {
      this.setText(id, '');
      return;
    }
    const idx = list.findIndex((s) => s.id === id);
    this.segments.set(list.filter((s) => s.id !== id));
    this.selection.update((sel) => {
      const n = new Set(sel);
      n.delete(id);
      return n;
    });
    const neighbour = this.segments()[Math.max(0, idx - 1)];
    if (neighbour) this.focusRequest.set({ id: neighbour.id, caret: neighbour.text.length });
    this.markSegmentsDirty();
  }

  move(id: string, delta: -1 | 1) {
    const list = [...this.segments()];
    const idx = list.findIndex((s) => s.id === id);
    const target = idx + delta;
    if (idx < 0 || target < 0 || target >= list.length) return;
    [list[idx], list[target]] = [list[target], list[idx]];
    this.segments.set(list);
    this.markSegmentsDirty();
  }

  /** Dzieli fragment na zdania. */
  splitIntoSentences(id: string) {
    const list = [...this.segments()];
    const idx = list.findIndex((s) => s.id === id);
    if (idx < 0) return;
    const parts = splitSentences(list[idx].text);
    if (parts.length < 2) {
      this.toast.show('Fragment zawiera tylko jedno zdanie.');
      return;
    }
    const seg = list[idx];
    list.splice(idx, 1, { ...seg, text: parts[0], status: localStatus(seg, parts[0]) }, ...parts.slice(1).map(blank));
    this.segments.set(list);
    this.markSegmentsDirty();
  }

  /** Ponowny podział całego tekstu (akapity, długie akapity po zdaniach). */
  resplitAll(maxChars = 400) {
    const text = this.segments().map((s) => s.text.trim()).filter(Boolean).join('\n\n');
    const parts = splitIntoSegments(text, maxChars);
    const old = this.segments();
    // Zachowaj fragmenty, których tekst się nie zmienił (ich audio pozostaje aktualne)
    const pool = new Map(old.map((s) => [s.text.trim(), s]));
    this.segments.set(
      parts.map((t) => {
        const keep = pool.get(t);
        if (keep) {
          pool.delete(t);
          return keep;
        }
        return blank(t);
      }),
    );
    this.markSegmentsDirty();
  }

  // ------------------------------------------------------------ zaznaczanie

  toggleSelect(id: string) {
    this.selection.update((sel) => {
      const n = new Set(sel);
      if (n.has(id)) n.delete(id);
      else n.add(id);
      return n;
    });
  }

  clearSelection() {
    this.selection.set(new Set());
  }

  selectPending() {
    this.selection.set(new Set(this.pendingIds()));
  }

  // ------------------------------------------------------------ ustawienia i nazwa

  updateSettings(patch: Partial<VoiceSettings>) {
    const current = this.settings();
    if (!current) return;
    const next = { ...current, ...patch };
    this.settings.set(next);
    // Optymistycznie: wszystko, co było gotowe, staje się nieaktualne (serwer potwierdzi)
    this.segments.update((list) => list.map((s) => (s.status === 'ready' ? { ...s, status: 'stale' } : s)));
    this.settingsDirty = true;
    this.saveState.set('dirty');
    if (this.settingsTimer) clearTimeout(this.settingsTimer);
    this.settingsTimer = setTimeout(() => void this.flush(), 450);
  }

  setName(name: string) {
    this.name.set(name);
    if (this.nameTimer) clearTimeout(this.nameTimer);
    this.saveState.set('dirty');
    this.nameTimer = setTimeout(() => void this.saveName(), 600);
  }

  private async saveName() {
    const p = this.project();
    const name = this.name().trim() || 'Projekt bez tytułu';
    if (!p) return;
    try {
      const res = await this.api.updateProject(p.id, { name });
      this.project.set({ ...res, segments: [] });
      this.projectsStore.touch(res);
      if (!this.segDirty && !this.settingsDirty) this.saveState.set('saved');
    } catch (e) {
      this.saveState.set('error');
      this.toast.error('Nie zapisano nazwy projektu', (e as ApiError).message);
    }
  }

  // ------------------------------------------------------------ zapis

  private markSegmentsDirty() {
    this.segDirty = true;
    this.saveState.set('dirty');
    if (this.segTimer) clearTimeout(this.segTimer);
    this.segTimer = setTimeout(() => void this.flush(), 700);
  }

  /** Zapisuje oczekujące zmiany. Wywoływane automatycznie oraz przed generowaniem. */
  async flush(): Promise<void> {
    if (this.saving) await this.saving;
    if (!this.segDirty && !this.settingsDirty) return;
    this.saving = this.doSave().finally(() => (this.saving = null));
    await this.saving;
  }

  private async doSave() {
    const p = this.project();
    if (!p) return;
    if (this.segTimer) clearTimeout(this.segTimer);
    if (this.settingsTimer) clearTimeout(this.settingsTimer);
    this.saveState.set('saving');
    try {
      let res: ProjectDto | null = null;
      if (this.settingsDirty) {
        this.settingsDirty = false;
        res = await this.api.updateProject(p.id, { settings: this.settings()! });
      }
      if (this.segDirty) {
        this.segDirty = false;
        const payload = this.segments().map((s) => ({ id: s.id, text: s.text }));
        res = await this.api.saveSegments(p.id, payload);
      }
      if (res) this.applyServer(res);
      this.saveState.set(this.segDirty || this.settingsDirty ? 'dirty' : 'saved');
    } catch (e) {
      this.saveState.set('error');
      this.toast.error('Nie udało się zapisać zmian', (e as ApiError).message);
      throw e;
    }
  }

  /** Scala odpowiedź serwera z lokalnym stanem (nie nadpisuje tekstu wpisanego w międzyczasie). */
  private applyServer(res: ProjectDto) {
    this.project.set({ ...res, segments: [] });
    this.projectsStore.touch(res);
    const server = new Map(res.segments.map((s) => [s.id, s]));
    this.segments.update((list) =>
      list.map((s) => {
        const srv = server.get(s.id);
        if (!srv || srv.text !== s.text) return s;
        return { ...srv, generating: s.generating, queued: s.queued };
      }),
    );
  }

  // ------------------------------------------------------------ generowanie

  segmentTrack(s: SegmentDto): PlayerTrack | null {
    const p = this.project();
    if (!p || !s.audioVersion) return null;
    const idx = this.segments().findIndex((x) => x.id === s.id);
    return {
      id: `seg:${s.id}`,
      title: `Fragment ${idx + 1}`,
      subtitle: this.name(),
      url: this.api.segmentAudioUrl(p.id, s.id, s.audioVersion),
      downloadName: `${this.name()}-fragment-${idx + 1}`,
    };
  }

  projectTrack(): PlayerTrack | null {
    const p = this.project();
    if (!p || !this.allReady()) return null;
    const segs = this.nonEmpty();
    const pause = (this.accountStore.account()?.pauseBetweenSegmentsMs ?? 0) / 1000;
    const total = segs.reduce((n, s) => n + s.durationMs / 1000, 0) + pause * (segs.length - 1);
    const markers: number[] = [];
    let acc = 0;
    segs.slice(0, -1).forEach((s) => {
      acc += s.durationMs / 1000 + pause;
      markers.push((acc - pause / 2) / total);
    });
    return {
      id: `project:${p.id}`,
      title: this.name(),
      subtitle: `Całe nagranie · ${segs.length} fragm.`,
      url: this.api.projectAudioUrl(p.id, this.mergedVersion()),
      markers,
      downloadName: this.name(),
    };
  }

  async generate(id: string, opts: { play?: boolean } = {}) {
    const p = this.project();
    if (!p) return;
    const seg = this.segments().find((s) => s.id === id);
    if (!seg || !seg.text.trim()) return;
    try {
      await this.flush();
    } catch {
      return;
    }
    this.patchSeg(id, { generating: true, queued: false, error: null });
    try {
      const res = await this.api.generateSegment(p.id, id);
      const current = this.segments().find((s) => s.id === id);
      // Jeśli tekst zmieniono w trakcie generowania — zachowaj tekst, oznacz jako nieaktualny
      this.patchSeg(id, current && current.text !== res.text ? { ...res, text: current.text, status: 'stale', generating: false } : { ...res, generating: false });
      void this.accountStore.refreshUsage();
      const play = opts.play ?? this.accountStore.account()?.playAfterGenerate ?? true;
      if (play) {
        const t = this.segmentTrack(res);
        if (t) void this.player.play(t, { force: true });
      }
      return res;
    } catch (e) {
      const err = e as ApiError;
      this.patchSeg(id, { generating: false, status: 'error', error: err.message });
      throw err;
    }
  }

  async generateOne(id: string) {
    try {
      await this.generate(id);
    } catch (e) {
      this.toast.error('Generowanie nie powiodło się', (e as ApiError).message);
    }
  }

  /** Generuje kolejno wskazane fragmenty (domyślnie: tylko nowe i zmienione). */
  async generateMany(ids: string[] = this.pendingIds()) {
    const todo = ids.filter((id) => this.segments().some((s) => s.id === id && s.text.trim()));
    if (!todo.length || this.batch()) return;
    this.segments.update((list) => list.map((s) => (todo.includes(s.id) ? { ...s, queued: true } : s)));
    this.batch.set({ total: todo.length, done: 0, failed: 0, cancelRequested: false });
    let lastError: string | null = null;
    for (const id of todo) {
      if (this.batch()?.cancelRequested) break;
      try {
        await this.generate(id, { play: false });
      } catch (e) {
        lastError = (e as ApiError).message;
        this.batch.update((b) => (b ? { ...b, failed: b.failed + 1 } : b));
        if ((e as ApiError).status === 503 || (e as ApiError).status === 402 || (e as ApiError).status === 0) break;
      }
      this.batch.update((b) => (b ? { ...b, done: b.done + 1 } : b));
    }
    const b = this.batch();
    this.segments.update((list) => list.map((s) => (s.queued ? { ...s, queued: false } : s)));
    this.batch.set(null);
    this.clearSelection();
    if (b?.cancelRequested) this.toast.show(`Zatrzymano generowanie (${b.done - b.failed} z ${b.total}).`);
    else if (b && b.failed) this.toast.error(`Nie wygenerowano ${b.failed} z ${b.total} fragmentów`, lastError ?? undefined);
    else if (b) {
      this.toast.success(`Wygenerowano ${b.total} ${b.total === 1 ? 'fragment' : 'fragmentów'}`);
      if (this.allReady() && (this.accountStore.account()?.playAfterGenerate ?? true)) {
        const t = this.projectTrack();
        if (t) void this.player.play(t, { force: true });
      }
    }
  }

  cancelBatch() {
    this.batch.update((b) => (b ? { ...b, cancelRequested: true } : b));
  }

  async export(format: 'mp3' | 'wav') {
    const p = this.project();
    if (!p) return;
    await this.flush();
    try {
      const res = await this.api.exportProject(p.id, format);
      const a = document.createElement('a');
      const url = URL.createObjectURL(res.blob);
      a.href = url;
      a.download = res.filename ?? `${this.name()}.${format}`;
      a.click();
      setTimeout(() => URL.revokeObjectURL(url), 2000);
      if (res.engineMode === 'demo') {
        this.toast.show('Pobrano plik demonstracyjny', 'demo', 'Plik zawiera sygnał zastępczy, nie mowę. W nazwie pliku dodano oznaczenie _DEMO.');
      } else {
        this.toast.success(`Pobrano ${format.toUpperCase()}`);
      }
    } catch (e) {
      this.toast.error('Eksport nie powiódł się', (e as ApiError).message);
    }
  }

  private patchSeg(id: string, patch: Partial<EditorSegment>) {
    this.segments.update((list) => list.map((s) => (s.id === id ? { ...s, ...patch } : s)));
  }
}

function blank(text: string): EditorSegment {
  return {
    id: uuid(),
    position: 0,
    text,
    status: text.trim() ? 'draft' : 'empty',
    durationMs: 0,
    engineMode: null,
    generatedAt: null,
    error: null,
    audioVersion: null,
    generating: false,
    queued: false,
  };
}

function localStatus(s: SegmentDto, text: string): SegmentStatus {
  if (!text.trim()) return 'empty';
  if (text === s.text) return s.status;
  return s.audioVersion ? 'stale' : 'draft';
}
