import { Injectable, OnDestroy, computed, inject, signal } from '@angular/core';
import { ApiService } from '../../core/api/api.service';
import { moveItemInArray } from '@angular/cdk/drag-drop';
import {
  ApiError,
  GenerationJob,
  ProjectDto,
  PronunciationEntry,
  SegmentDto,
  SegmentStatus,
  SegmentVersion,
  VoiceSettings,
} from '../../core/api/models';
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
  jobId: string;
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
export class EditorStore implements OnDestroy {
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
  private events: EventSource | null = null;
  private jobIds: string[] = [];

  // Historia zmian (cofnij / ponów)
  private undoStack: EditorSegment[][] = [];
  private redoStack: EditorSegment[][] = [];
  private lastSnapshot = { key: '', at: 0 };
  readonly canUndo = signal(false);
  readonly canRedo = signal(false);

  /** Ostatnia pozycja kursora — tam trafia wstawiana pauza. */
  caret: { id: string; pos: number } | null = null;

  readonly dictionary = signal<PronunciationEntry[]>([]);
  readonly versions = signal<{ segmentId: string; list: SegmentVersion[]; loading: boolean } | null>(null);

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
  /** Limit znaków w projekcie wg planu. */
  readonly projectCharLimit = computed(() => this.accountStore.plan()?.maxCharactersPerProject ?? Infinity);
  readonly overProjectLimit = computed(() => this.characters() > this.projectCharLimit());
  /** Powód blokady generowania wynikający z planu (null = można generować). */
  readonly planBlock = computed<string | null>(() => {
    const plan = this.accountStore.plan();
    if (!plan) return null;
    if (this.accountStore.quotaExhausted()) {
      return `Wykorzystano miesięczny limit planu ${plan.name}. Dokup pakiet znaków (bez subskrypcji), poczekaj na odnowienie limitów lub zmień plan.`;
    }
    if (this.overProjectLimit()) {
      return `Projekt przekracza limit planu ${plan.name}: ${this.projectCharLimit().toLocaleString('pl-PL')} znaków w jednym projekcie. Skróć tekst, podziel go na kilka projektów lub zmień plan.`;
    }
    return null;
  });

  /** Głosy użyte w projekcie (projekt + nadpisania fragmentów) — do oznaczeń mówców. */
  readonly voicesInUse = computed(() => {
    const ids = new Set<string>();
    const projectVoice = this.settings()?.voiceId;
    if (projectVoice) ids.add(projectVoice);
    for (const s of this.segments()) if (s.voiceId) ids.add(s.voiceId);
    return [...ids];
  });

  readonly selectedIds = computed(() => this.segments().filter((s) => this.selection().has(s.id)).map((s) => s.id));

  /** Klucz wersji połączonego nagrania — zmienia się po każdej generacji. */
  readonly mergedVersion = computed(() =>
    this.nonEmpty()
      .map((s) => s.audioVersion ?? '')
      .join('.') + `:${this.accountStore.account()?.pauseBetweenSegmentsMs ?? 0}`,
  );

  // ------------------------------------------------------------ ładowanie

  async load(id: string) {
    if (this.project() && this.project()!.id !== id) void this.discardIfEmpty();
    this.reset();
    try {
      const p = await this.api.project(id);
      this.project.set(p);
      this.name.set(p.name);
      this.settings.set({ ...p.settings });
      this.segments.set(p.segments.map((s) => ({ ...s, generating: false, queued: false })));
      if (!p.segments.length) {
        this.addSegment();
        this.undoStack = [];
        this.syncHistoryFlags();
      }
      this.connectEvents(id);
      void this.loadDictionary();
      // Podłączenie do zadania, które trwa w tle (np. po odświeżeniu strony)
      const job = await this.api.activeJob(id).catch(() => null);
      if (job) this.applyJob(job);
    } catch (e) {
      this.loadError.set((e as ApiError).message);
    }
  }

  ngOnDestroy() {
    this.events?.close();
    const p = this.project();
    if (p) this.projectsStore.releaseLocal(p.id);
    void this.discardIfEmpty();
  }

  /**
   * Pusty projekt (bez żadnego tekstu i nigdy niegenerowany) nie jest zachowywany —
   * usuwany przy wyjściu z edytora. Zabezpieczenie po stronie serwera: nocne sprzątanie.
   */
  private async discardIfEmpty() {
    const p = this.project();
    if (!p || p.lastGeneratedAt || this.batch()) return;
    if (this.segments().some((s) => s.text.trim().length > 0)) return;
    if (this.segTimer) clearTimeout(this.segTimer);
    if (this.settingsTimer) clearTimeout(this.settingsTimer);
    this.segDirty = false;
    this.settingsDirty = false;
    try {
      if (this.saving) await this.saving;
      await this.api.deleteProject(p.id);
      this.projectsStore.remove(p.id);
      void this.accountStore.refreshUsage();
    } catch {
      /* projekt usunie nocne sprzątanie */
    }
  }

  // ------------------------------------------------------------ zdarzenia z backendu (SSE)

  private connectEvents(projectId: string) {
    this.events?.close();
    const es = new EventSource(this.api.projectEventsUrl(projectId), { withCredentials: true });
    es.addEventListener('job', (e) => this.applyJob(JSON.parse((e as MessageEvent).data)));
    es.addEventListener('segment', (e) => this.applySegment(JSON.parse((e as MessageEvent).data)));
    es.addEventListener('segment-error', (e) => {
      const { segmentId, message } = JSON.parse((e as MessageEvent).data) as { segmentId: string; message: string };
      this.patchSeg(segmentId, { generating: false, queued: false, status: 'error', error: message });
    });
    // Po zerwaniu połączenia przeglądarka łączy się ponownie — dociągamy aktualny stan zadania
    let dropped = false;
    es.onerror = () => (dropped = true);
    es.onopen = () => {
      if (!dropped) return;
      dropped = false;
      void this.api.activeJob(projectId).then((job) => (job ? this.applyJob(job) : this.endBatch())).catch(() => null);
    };
    this.events = es;
  }

  private applySegment(seg: SegmentDto) {
    const current = this.segments().find((s) => s.id === seg.id);
    if (!current) return;
    // Tekst zmieniony w trakcie generowania — zachowaj tekst użytkownika, nagranie oznacz jako nieaktualne
    const patch = current.text !== seg.text ? { ...seg, text: current.text, status: 'stale' as const } : seg;
    this.patchSeg(seg.id, { ...patch, generating: false, queued: false });
    void this.accountStore.refreshUsage();
  }

  private applyJob(job: GenerationJob) {
    if (job.status === 'QUEUED' || job.status === 'RUNNING') {
      const p = this.project();
      if (p && !this.batch()) this.projectsStore.markGenerating(p, true);
      this.batch.set({ jobId: job.id, total: job.total, done: job.done, failed: job.failed, cancelRequested: this.batch()?.cancelRequested ?? false });
      this.segments.update((list) =>
        list.map((s) => {
          const generating = s.id === job.currentSegmentId;
          const queued = !generating && (s.queued || this.jobIds.includes(s.id));
          return s.generating !== generating || s.queued !== queued ? { ...s, generating, queued } : s;
        }),
      );
      return;
    }
    // Zadanie zakończone
    const wasRunning = this.batch()?.jobId === job.id;
    const finished = this.project();
    if (this.batch() && finished) this.projectsStore.markGenerating(finished, false);
    this.batch.set(null);
    this.jobIds = [];
    this.segments.update((list) => list.map((s) => (s.queued || s.generating ? { ...s, queued: false, generating: false } : s)));
    if (!wasRunning) return;
    this.clearSelection();
    void this.accountStore.refreshUsage();
    const ok = job.done - job.failed;
    if (job.status === 'CANCELLED') this.toast.show(`Zatrzymano generowanie (${ok} z ${job.total}).`);
    else if (job.failed) {
      this.toast.apiError(`Nie wygenerowano ${job.failed} z ${job.total} fragmentów`, {
        status: 0,
        message: job.lastError ?? '',
        code: job.lastErrorCode ?? undefined,
      });
    } else {
      this.toast.success(`Wygenerowano ${job.total} ${job.total === 1 ? 'fragment' : 'fragmentów'}`);
      if (this.allReady() && (this.accountStore.account()?.playAfterGenerate ?? true)) {
        const t = this.projectTrack();
        if (t) void this.player.play(t, { force: true });
      }
    }
  }

  /** Zadanie zniknęło (np. zakończyło się podczas zerwanego połączenia) — zdejmij znacznik generowania. */
  private endBatch() {
    const p = this.project();
    if (this.batch() && p) this.projectsStore.markGenerating(p, false);
    this.batch.set(null);
  }

  private reset() {
    // Przejście do innego projektu w tym samym edytorze: dalszy stan poprzedniego śledzimy z serwera
    const prev = this.project();
    if (prev) this.projectsStore.releaseLocal(prev.id);
    this.undoStack = [];
    this.redoStack = [];
    this.syncHistoryFlags();
    this.versions.set(null);
    this.project.set(null);
    this.segments.set([]);
    this.loadError.set(null);
    this.selection.set(new Set());
    this.batch.set(null);
    this.saveState.set('saved');
  }

  // ------------------------------------------------------------ historia zmian

  /**
   * Zapamiętuje stan przed zmianą. Kolejne zmiany tego samego rodzaju w krótkim czasie
   * (np. pisanie w jednym fragmencie) są łączone w jeden krok cofania.
   */
  private snapshot(key: string, coalesce = false) {
    const now = Date.now();
    if (coalesce && key === this.lastSnapshot.key && now - this.lastSnapshot.at < 1500) {
      this.lastSnapshot.at = now;
      return;
    }
    this.undoStack.push(this.segments());
    if (this.undoStack.length > 100) this.undoStack.shift();
    this.redoStack = [];
    this.lastSnapshot = { key, at: now };
    this.syncHistoryFlags();
  }

  undo() {
    if (this.batch() || !this.undoStack.length) return;
    this.redoStack.push(this.segments());
    this.restore(this.undoStack.pop()!);
  }

  redo() {
    if (this.batch() || !this.redoStack.length) return;
    this.undoStack.push(this.segments());
    this.restore(this.redoStack.pop()!);
  }

  private restore(state: EditorSegment[]) {
    this.segments.set(state.map((s) => ({ ...s, generating: false, queued: false })));
    this.lastSnapshot = { key: '', at: 0 };
    this.syncHistoryFlags();
    this.markSegmentsDirty();
  }

  private syncHistoryFlags() {
    this.canUndo.set(this.undoStack.length > 0);
    this.canRedo.set(this.redoStack.length > 0);
  }

  // ------------------------------------------------------------ edycja tekstu

  setText(id: string, text: string) {
    this.snapshot('text:' + id, true);
    this.segments.update((list) =>
      list.map((s) => (s.id === id ? { ...s, text, status: localStatus(s, text), error: null } : s)),
    );
    this.markSegmentsDirty();
  }

  /** Dzieli fragment w miejscu kursora (Enter). */
  splitAt(id: string, caret: number) {
    this.snapshot('splitAt');
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
    this.snapshot('mergePair');
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
    this.snapshot('insertParagraphs');
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
    this.snapshot('addSegment');
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
    this.snapshot('duplicate');
    const list = [...this.segments()];
    const idx = list.findIndex((s) => s.id === id);
    if (idx < 0) return;
    list.splice(idx + 1, 0, blank(list[idx].text));
    this.segments.set(list);
    this.markSegmentsDirty();
  }

  remove(id: string) {
    this.snapshot('remove');
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
    this.snapshot('move');
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
    this.snapshot('splitIntoSentences');
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
    this.snapshot('resplitAll');
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

  /** Zmiana kolejności przeciąganiem. */
  reorder(from: number, to: number) {
    if (from === to) return;
    this.snapshot('reorder');
    const list = [...this.segments()];
    moveItemInArray(list, from, to);
    this.segments.set(list);
    this.markSegmentsDirty();
  }

  /** Głos fragmentów (null = głos projektu). Nagrania tych fragmentów stają się nieaktualne. */
  setSegmentVoice(ids: string[], voiceId: string | null) {
    const projectVoice = this.settings()?.voiceId ?? null;
    const value = voiceId === projectVoice ? null : voiceId;
    if (!this.segments().some((s) => ids.includes(s.id) && s.voiceId !== value)) return;
    this.snapshot('voice');
    this.segments.update((list) =>
      list.map((s) =>
        ids.includes(s.id) && s.voiceId !== value
          ? { ...s, voiceId: value, status: s.audioVersion && s.text.trim() ? 'stale' : s.status }
          : s,
      ),
    );
    this.markSegmentsDirty();
  }

  /** Wstawia znacznik pauzy w miejscu kursora (lub na końcu ostatniego fragmentu). */
  insertPause(ms: number) {
    const list = this.segments();
    const target = (this.caret && list.find((s) => s.id === this.caret!.id)) || list[list.length - 1];
    if (!target) return;
    const pos = this.caret?.id === target.id ? Math.min(this.caret.pos, target.text.length) : target.text.length;
    const label = ms >= 1000 ? `${(ms / 1000).toString().replace('.', ',')}s` : `${ms}ms`;
    const before = target.text.slice(0, pos).replace(/\s+$/, '');
    const after = target.text.slice(pos).replace(/^\s+/, '');
    const token = `[pauza ${label.replace(',', '.')}]`;
    const text = [before, token, after].filter(Boolean).join(' ');
    this.snapshot('pause');
    this.segments.update((l) => l.map((s) => (s.id === target.id ? { ...s, text, status: localStatus(s, text) } : s)));
    const caretPos = (before ? before.length + 1 : 0) + token.length;
    this.focusRequest.set({ id: target.id, caret: caretPos });
    this.markSegmentsDirty();
  }

  // ------------------------------------------------------------ tryb „Jeden lektor”

  /** Widok edytora: jeden lektor (cały tekst w jednym polu) lub dialog (fragmenty z głosami). */
  readonly viewMode = signal<'narrator' | 'dialog'>(readViewMode());

  setViewMode(mode: 'narrator' | 'dialog') {
    this.viewMode.set(mode);
    try {
      localStorage.setItem(VIEW_MODE_KEY, mode);
    } catch {
      /* brak dostępu do pamięci przeglądarki */
    }
  }

  /** Cały tekst projektu (akapity = fragmenty), jak w polu trybu „Jeden lektor”. */
  readonly fullText = computed(() => this.segments().map((s) => s.text).join('\n\n'));

  /** Fragmenty z własnym głosem (w trybie „Jeden lektor” cały tekst powinien czytać głos projektu). */
  readonly voiceOverrides = computed(() => this.segments().filter((s) => !!s.voiceId).length);

  /**
   * Zastępuje cały tekst (tryb „Jeden lektor”). Akapity stają się fragmentami; fragmenty o niezmienionym
   * tekście zachowują nagranie, a zmieniony akapit zachowuje identyfikator (wersje, własny głos).
   */
  setFullText(text: string) {
    const paras = text
      .split(/\n[ \t]*\n+/)
      .map((p) => p.replace(/^\n+|\s+$/g, ''))
      .filter((p) => p.trim().length > 0);
    const old = this.segments();
    if (paras.length === old.filter((s) => s.text.trim()).length && paras.every((p, i) => p === old.filter((s) => s.text.trim())[i]?.text)) {
      return;
    }
    this.snapshot('fulltext', true);
    const byText = new Map<string, EditorSegment[]>();
    for (const s of old) byText.set(s.text, [...(byText.get(s.text) ?? []), s]);
    const used = new Set<string>();
    const next = paras.map((p, i) => {
      const exact = byText.get(p)?.find((s) => !used.has(s.id));
      if (exact) {
        used.add(exact.id);
        return exact;
      }
      const sameSlot = old[i] && !used.has(old[i].id) && !paras.includes(old[i].text) ? old[i] : null;
      if (sameSlot) {
        used.add(sameSlot.id);
        return { ...sameSlot, text: p, status: localStatus(sameSlot, p), error: null };
      }
      return blank(p);
    });
    this.segments.set(next.length ? next : [blank('')]);
    this.markSegmentsDirty();
  }

  /** Wypełnia pusty projekt rozmową: kolejne kwestie z przypisanymi głosami. */
  startDialog(lines: { voiceId: string; text: string }[]) {
    this.snapshot('dialog');
    const projectVoice = this.settings()?.voiceId ?? null;
    this.segments.set(lines.map((l) => ({ ...blank(l.text), voiceId: l.voiceId === projectVoice ? null : l.voiceId })));
    this.markSegmentsDirty();
  }

  /** Dołącza fragmenty (np. z importowanego pliku). Pusty projekt jest zastępowany. */
  appendSegments(texts: string[]) {
    if (!texts.length) return;
    this.snapshot('append');
    const created = texts.map((t) => blank(t));
    this.segments.update((list) => (list.length === 1 && !list[0].text.trim() ? created : [...list, ...created]));
    this.markSegmentsDirty();
  }

  async importFile(file: File) {
    try {
      const res = await this.api.importText(file);
      this.appendSegments(res.segments);
      this.toast.success(`Zaimportowano ${res.segments.length} ${res.segments.length === 1 ? 'fragment' : 'fragmentów'}`, file.name);
    } catch (e) {
      this.toast.apiError('Nie udało się zaimportować pliku', e);
    }
  }

  // ------------------------------------------------------------ słownik wymowy

  async loadDictionary() {
    const p = this.project();
    if (!p) return;
    try {
      this.dictionary.set(await this.api.pronunciation(p.id));
    } catch {
      /* słownik jest opcjonalny */
    }
  }

  async addDictionaryEntry(term: string, replacement: string, global: boolean) {
    const p = this.project();
    if (!p) return false;
    try {
      await this.api.addPronunciation({ projectId: global ? null : p.id, term, replacement, caseSensitive: false });
      await this.afterDictionaryChange();
      return true;
    } catch (e) {
      this.toast.apiError('Nie dodano wpisu', e);
      return false;
    }
  }

  async updateDictionaryEntry(entry: PronunciationEntry) {
    try {
      await this.api.updatePronunciation(entry.id, { term: entry.term, replacement: entry.replacement, caseSensitive: entry.caseSensitive });
      await this.afterDictionaryChange();
    } catch (e) {
      this.toast.apiError('Nie zapisano wpisu', e);
    }
  }

  async deleteDictionaryEntry(id: string) {
    try {
      await this.api.deletePronunciation(id);
      await this.afterDictionaryChange();
    } catch (e) {
      this.toast.apiError('Nie usunięto wpisu', e);
    }
  }

  /** Zmiana słownika zmienia wymowę — odświeżamy statusy (nieaktualne będą tylko fragmenty z danym hasłem). */
  private async afterDictionaryChange() {
    await this.loadDictionary();
    await this.flush();
    const p = this.project();
    if (p) this.applyServer(await this.api.project(p.id));
  }

  // ------------------------------------------------------------ wersje fragmentu

  async openVersions(segmentId: string) {
    const p = this.project();
    if (!p) return;
    this.versions.set({ segmentId, list: [], loading: true });
    try {
      const list = await this.api.segmentVersions(p.id, segmentId);
      if (this.versions()?.segmentId === segmentId) this.versions.set({ segmentId, list, loading: false });
    } catch (e) {
      this.versions.set(null);
      this.toast.apiError('Nie udało się wczytać wersji', e);
    }
  }

  closeVersions() {
    this.versions.set(null);
  }

  versionTrack(segmentId: string, v: SegmentVersion): PlayerTrack | null {
    const p = this.project();
    if (!p) return null;
    const idx = this.segments().findIndex((s) => s.id === segmentId);
    return {
      id: `version:${v.id}`,
      title: `Fragment ${idx + 1} — wersja`,
      subtitle: new Date(v.createdAt).toLocaleString('pl-PL'),
      url: this.api.segmentVersionAudioUrl(p.id, segmentId, v.id),
    };
  }

  async restoreVersion(segmentId: string, versionId: number) {
    const p = this.project();
    if (!p) return;
    try {
      await this.flush();
      const seg = await this.api.restoreSegmentVersion(p.id, segmentId, versionId);
      this.patchSeg(segmentId, { ...seg, generating: false, queued: false });
      this.toast.success('Przywrócono wersję nagrania', seg.status === 'stale' ? 'Ta wersja powstała dla innego tekstu lub ustawień — fragment jest oznaczony jako nieaktualny.' : undefined);
      void this.openVersions(segmentId);
    } catch (e) {
      this.toast.apiError('Nie udało się przywrócić wersji', e);
    }
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
      this.toast.apiError('Nie zapisano nazwy projektu', e);
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
        const payload = this.segments().map((s) => ({ id: s.id, text: s.text, voiceId: s.voiceId ?? null }));
        res = await this.api.saveSegments(p.id, payload);
      }
      if (res) this.applyServer(res);
      this.saveState.set(this.segDirty || this.settingsDirty ? 'dirty' : 'saved');
    } catch (e) {
      this.saveState.set('error');
      this.toast.apiError('Nie udało się zapisać zmian', e);
      throw e;
    }
  }

  /** Scala odpowiedź serwera z lokalnym stanem (nie nadpisuje tekstu wpisanego w międzyczasie). */
  applyServer(res: ProjectDto) {
    this.project.set({ ...res, segments: [] });
    this.projectsStore.touch(res);
    const server = new Map(res.segments.map((s) => [s.id, s]));
    this.segments.update((list) =>
      list.map((s) => {
        const srv = server.get(s.id);
        if (!srv || srv.text !== s.text || (srv.voiceId ?? null) !== (s.voiceId ?? null)) return s;
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
    const current0 = this.project() ?? p;
    this.projectsStore.markGenerating(current0, true);
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
    } finally {
      this.projectsStore.markGenerating(current0, false);
    }
  }

  async generateOne(id: string) {
    try {
      await this.generate(id);
    } catch (e) {
      this.toast.apiError('Generowanie nie powiodło się', e);
    }
  }

  /**
   * Generuje wskazane fragmenty (domyślnie: tylko nowe i zmienione) w kolejce backendu.
   * Postęp przychodzi przez SSE; zamknięcie karty nie przerywa generowania.
   */
  async generateMany(ids: string[] = this.pendingIds()) {
    const p = this.project();
    const todo = ids.filter((id) => this.segments().some((s) => s.id === id && s.text.trim()));
    if (!p || !todo.length || this.batch()) return;
    try {
      await this.flush();
    } catch {
      return;
    }
    this.jobIds = todo;
    this.segments.update((list) => list.map((s) => (todo.includes(s.id) ? { ...s, queued: true, error: null } : s)));
    try {
      const job = await this.api.startJob(p.id, todo);
      this.jobIds = todo;
      this.applyJob(job);
    } catch (e) {
      this.jobIds = [];
      this.segments.update((list) => list.map((s) => (s.queued ? { ...s, queued: false } : s)));
      this.toast.apiError('Nie udało się uruchomić generowania', e);
    }
  }

  async cancelBatch() {
    const b = this.batch();
    if (!b) return;
    this.batch.set({ ...b, cancelRequested: true });
    try {
      await this.api.cancelJob(b.jobId);
    } catch (e) {
      this.toast.apiError('Nie udało się zatrzymać generowania', e);
    }
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
        this.toast.show(
          'Pobrano plik demonstracyjny',
          'demo',
          'Plik zawiera sygnał zastępczy, nie mowę. W nazwie pliku dodano oznaczenie _DEMO.' +
            (res.loudness ? ` Głośność znormalizowana do ${res.loudness.replace('.', ',')} LUFS.` : ''),
        );
      } else {
        this.toast.success(`Pobrano ${format.toUpperCase()}`, res.loudness ? `Głośność znormalizowana do ${res.loudness.replace('.', ',')} LUFS.` : undefined);
      }
    } catch (e) {
      this.toast.apiError('Eksport nie powiódł się', e);
    }
  }

  async exportSubtitles(format: 'srt' | 'vtt') {
    const p = this.project();
    if (!p) return;
    await this.flush();
    try {
      const res = await this.api.exportSubtitles(p.id, format);
      const a = document.createElement('a');
      const url = URL.createObjectURL(res.blob);
      a.href = url;
      a.download = res.filename ?? `${this.name()}.${format}`;
      a.click();
      setTimeout(() => URL.revokeObjectURL(url), 2000);
      this.toast.success(`Pobrano napisy ${format.toUpperCase()}`);
    } catch (e) {
      this.toast.apiError('Nie udało się wygenerować napisów', e);
    }
  }

  private patchSeg(id: string, patch: Partial<EditorSegment>) {
    this.segments.update((list) => list.map((s) => (s.id === id ? { ...s, ...patch } : s)));
  }
}

const VIEW_MODE_KEY = 'vc.editorView';

function readViewMode(): 'narrator' | 'dialog' {
  try {
    return localStorage.getItem(VIEW_MODE_KEY) === 'dialog' ? 'dialog' : 'narrator';
  } catch {
    return 'narrator';
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
    voiceId: null,
    generating: false,
    queued: false,
  };
}

function localStatus(s: SegmentDto, text: string): SegmentStatus {
  if (!text.trim()) return 'empty';
  if (text === s.text) return s.status;
  return s.audioVersion ? 'stale' : 'draft';
}
