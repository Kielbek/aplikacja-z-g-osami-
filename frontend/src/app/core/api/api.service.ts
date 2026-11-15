import { HttpClient, HttpErrorResponse, HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, catchError, firstValueFrom, throwError } from 'rxjs';
import {
  Account,
  ApiError,
  BillingConfig,
  ConsentText,
  VoiceConsent,
  GenerationJob,
  ImportedText,
  PronunciationEntry,
  SegmentDto,
  SegmentVersion,
  Plan,
  PlanId,
  AudioAnalysis,
  DesignPreview,
  EngineStatus,
  HistoryItem,
  ProjectDto,
  Usage,
  Voice,
  VoiceSettings,
} from './models';

export const API = '/api';

/** Normalizuje błąd HTTP do { status, message } z komunikatem backendu. */
export function toApiError(err: unknown): ApiError {
  if (err instanceof HttpErrorResponse) {
    if (err.status === 0) return { status: 0, message: 'Brak połączenia z serwerem aplikacji (backend).' };
    const body = err.error;
    const message = body && typeof body === 'object' && 'message' in body ? String(body.message) : err.message;
    const code = body && typeof body === 'object' && 'code' in body ? String(body.code) : undefined;
    return { status: err.status, message, code };
  }
  return { status: -1, message: err instanceof Error ? err.message : 'Nieznany błąd.' };
}

export interface AudioBlob {
  blob: Blob;
  engineMode: string | null;
  filename: string | null;
  /** Głośność eksportu po normalizacji (LUFS), jeśli włączona. */
  loudness: string | null;
}

/**
 * Jedyny punkt komunikacji frontendu z backendem.
 * Komponenty nie wiedzą nic o silniku TTS — operują na projektach, fragmentach i głosach.
 */
@Injectable({ providedIn: 'root' })
export class ApiService {
  private http = inject(HttpClient);

  private p<T>(o: Observable<T>): Promise<T> {
    return firstValueFrom(o.pipe(catchError((e) => throwError(() => toApiError(e)))));
  }

  // --- Silnik --------------------------------------------------------------
  engineStatus(refresh = false) {
    return this.p(this.http.get<EngineStatus>(`${API}/engine/status`, { params: { refresh } }));
  }

  // --- Projekty ------------------------------------------------------------
  projects() {
    return this.p(this.http.get<ProjectDto[]>(`${API}/projects`));
  }
  project(id: string) {
    return this.p(this.http.get<ProjectDto>(`${API}/projects/${id}`));
  }
  createProject(body: { name?: string; text?: string } = {}) {
    return this.p(this.http.post<ProjectDto>(`${API}/projects`, body));
  }
  updateProject(id: string, body: { name?: string; settings?: VoiceSettings }) {
    return this.p(this.http.patch<ProjectDto>(`${API}/projects/${id}`, body));
  }
  deleteProject(id: string) {
    return this.p(this.http.delete<void>(`${API}/projects/${id}`));
  }
  duplicateProject(id: string) {
    return this.p(this.http.post<ProjectDto>(`${API}/projects/${id}/duplicate`, {}));
  }
  saveSegments(id: string, segments: { id: string; text: string; voiceId: string | null }[]) {
    return this.p(this.http.put<ProjectDto>(`${API}/projects/${id}/segments`, segments));
  }
  generateSegment(projectId: string, segmentId: string) {
    return this.p(this.http.post<SegmentDto>(`${API}/projects/${projectId}/segments/${segmentId}/generate`, {}));
  }
  segmentAudioUrl(projectId: string, segmentId: string, version: string | null) {
    return `${API}/projects/${projectId}/segments/${segmentId}/audio?v=${version ?? ''}`;
  }
  projectAudioUrl(projectId: string, version: string) {
    return `${API}/projects/${projectId}/audio?v=${encodeURIComponent(version)}`;
  }
  // --- Wersje fragmentu ------------------------------------------------------
  segmentVersions(projectId: string, segmentId: string) {
    return this.p(this.http.get<SegmentVersion[]>(`${API}/projects/${projectId}/segments/${segmentId}/versions`));
  }
  segmentVersionAudioUrl(projectId: string, segmentId: string, versionId: number) {
    return `${API}/projects/${projectId}/segments/${segmentId}/versions/${versionId}/audio`;
  }
  restoreSegmentVersion(projectId: string, segmentId: string, versionId: number) {
    return this.p(this.http.post<SegmentDto>(`${API}/projects/${projectId}/segments/${segmentId}/versions/${versionId}/restore`, {}));
  }

  // --- Napisy i import -----------------------------------------------------------
  exportSubtitles(projectId: string, format: 'srt' | 'vtt') {
    return this.audio(`${API}/projects/${projectId}/subtitles?format=${format}`);
  }
  importText(file: File) {
    const form = new FormData();
    form.append('file', file);
    return this.p(this.http.post<ImportedText>(`${API}/import/text`, form));
  }

  // --- Słownik wymowy -------------------------------------------------------------
  pronunciation(projectId?: string) {
    return this.p(this.http.get<PronunciationEntry[]>(`${API}/pronunciation`, { params: projectId ? { projectId } : {} }));
  }
  addPronunciation(entry: Omit<PronunciationEntry, 'id'>) {
    return this.p(this.http.post<PronunciationEntry>(`${API}/pronunciation`, entry));
  }
  updatePronunciation(id: string, entry: Omit<PronunciationEntry, 'id' | 'projectId'>) {
    return this.p(this.http.put<PronunciationEntry>(`${API}/pronunciation/${id}`, entry));
  }
  deletePronunciation(id: string) {
    return this.p(this.http.delete<void>(`${API}/pronunciation/${id}`));
  }

  // --- Kolejka generowania (zadania w tle + SSE) -----------------------------
  startJob(projectId: string, segmentIds?: string[]) {
    return this.p(this.http.post<GenerationJob>(`${API}/projects/${projectId}/jobs`, segmentIds ? { segmentIds } : {}));
  }
  activeJob(projectId: string) {
    return this.p(this.http.get<GenerationJob | null>(`${API}/projects/${projectId}/jobs/active`));
  }
  cancelJob(jobId: string) {
    return this.p(this.http.post<GenerationJob>(`${API}/jobs/${jobId}/cancel`, {}));
  }
  projectEventsUrl(projectId: string) {
    return `${API}/projects/${projectId}/events`;
  }

  // --- Płatności ---------------------------------------------------------------
  billingConfig() {
    return this.p(this.http.get<BillingConfig>(`${API}/billing/config`));
  }
  billingCheckout(planId: string) {
    return this.p(this.http.post<{ url: string }>(`${API}/billing/checkout`, { planId }));
  }
  billingPortal() {
    return this.p(this.http.post<{ url: string }>(`${API}/billing/portal`, {}));
  }

  exportProject(id: string, format: 'mp3' | 'wav') {
    return this.audio(`${API}/projects/${id}/export?format=${format}`);
  }

  // --- Głosy ---------------------------------------------------------------
  voices() {
    return this.p(this.http.get<Voice[]>(`${API}/voices`));
  }
  voice(id: string) {
    return this.p(this.http.get<Voice>(`${API}/voices/${id}`));
  }
  setFavorite(id: string, favorite: boolean) {
    return this.p(this.http.put<Voice>(`${API}/voices/${id}/favorite`, { favorite }));
  }
  updateVoice(id: string, body: { name?: string; description?: string }) {
    return this.p(this.http.patch<Voice>(`${API}/voices/${id}`, body));
  }
  deleteVoice(id: string) {
    return this.p(this.http.delete<void>(`${API}/voices/${id}`));
  }
  voiceSampleUrl(id: string) {
    return `${API}/voices/${id}/sample`;
  }
  designPreview(body: { description: string; text: string; language: string; expressiveness?: number; seed?: number }) {
    return this.p(this.http.post<DesignPreview>(`${API}/voices/design/preview`, body));
  }
  designPreviewUrl(previewId: string) {
    return `${API}/voices/design/preview/${previewId}`;
  }
  saveDesignedVoice(body: {
    name: string;
    description: string;
    gender?: string | null;
    age?: number | null;
    categories: string[];
    traits: string[];
    language: string;
    previewId?: string | null;
    sampleText?: string;
    engineMode?: string | null;
  }) {
    return this.p(this.http.post<Voice>(`${API}/voices/design`, body));
  }
  analyzeReference(file: File) {
    const form = new FormData();
    form.append('file', file);
    return this.p(this.http.post<AudioAnalysis>(`${API}/voices/clone/analyze`, form));
  }
  cloningConsent() {
    return this.p(this.http.get<ConsentText>(`${API}/voices/clone/consent`));
  }
  voiceConsent(id: string) {
    return this.p(this.http.get<VoiceConsent | null>(`${API}/voices/${id}/consent`));
  }
  createClone(file: File, data: { name: string; transcript: string; language: string; consent: boolean; consentVersion: string | null }) {
    const form = new FormData();
    form.append('file', file);
    form.append('name', data.name);
    form.append('transcript', data.transcript);
    form.append('language', data.language);
    form.append('consent', String(data.consent));
    if (data.consentVersion) form.append('consentVersion', data.consentVersion);
    return this.p(this.http.post<Voice>(`${API}/voices/clone`, form));
  }

  // --- Historia ------------------------------------------------------------
  history() {
    return this.p(this.http.get<HistoryItem[]>(`${API}/history`));
  }
  historyAudioUrl(id: number) {
    return `${API}/history/${id}/audio`;
  }
  clearHistory() {
    return this.p(this.http.delete<void>(`${API}/history`));
  }

  // --- Konto ---------------------------------------------------------------
  account() {
    return this.p(this.http.get<Account>(`${API}/account`));
  }
  updateAccount(patch: Partial<Account>) {
    return this.p(this.http.patch<Account>(`${API}/account`, patch));
  }
  usage() {
    return this.p(this.http.get<Usage>(`${API}/account/usage`));
  }
  /** Eksport wszystkich danych konta (RODO) — archiwum ZIP. */
  exportAccountData() {
    return this.audio(`${API}/account/export`);
  }
  plans() {
    return this.p(this.http.get<Plan[]>(`${API}/account/plans`));
  }
  changePlan(planId: PlanId) {
    return this.p(this.http.put<Account>(`${API}/account/plan`, { planId }));
  }

  // --- Audio ---------------------------------------------------------------
  /** Pobiera plik audio wraz z informacją, który tryb silnika go wygenerował. */
  async audio(url: string): Promise<AudioBlob> {
    try {
      const res: HttpResponse<Blob> = await firstValueFrom(
        this.http.get(url, { observe: 'response', responseType: 'blob' }),
      );
      const cd = res.headers.get('Content-Disposition');
      const match = cd ? /filename="?([^";]+)"?/.exec(cd) : null;
      return {
        blob: res.body!,
        engineMode: res.headers.get('X-Engine-Mode'),
        filename: match ? decodeURIComponent(match[1]) : null,
        loudness: res.headers.get('X-Loudness-LUFS') || null,
      };
    } catch (e) {
      // Przy responseType=blob treść błędu jest Blobem — odczytaj komunikat backendu
      if (e instanceof HttpErrorResponse && e.error instanceof Blob) {
        let body: { message?: string; code?: string } | null = null;
        try {
          body = JSON.parse(await e.error.text());
        } catch {
          body = null;
        }
        if (body?.message) throw { status: e.status, message: body.message, code: body.code } satisfies ApiError;
      }
      throw toApiError(e);
    }
  }
}
