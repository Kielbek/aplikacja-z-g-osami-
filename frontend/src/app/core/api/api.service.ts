import { HttpClient, HttpErrorResponse, HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, catchError, firstValueFrom, throwError } from 'rxjs';
import {
  Account,
  ApiError,
  AudioAnalysis,
  DesignPreview,
  EngineStatus,
  HistoryItem,
  ProjectDto,
  SegmentDto,
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
    return { status: err.status, message };
  }
  return { status: -1, message: err instanceof Error ? err.message : 'Nieznany błąd.' };
}

export interface AudioBlob {
  blob: Blob;
  engineMode: string | null;
  filename: string | null;
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
  saveSegments(id: string, segments: { id: string; text: string }[]) {
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
  createClone(file: File, data: { name: string; transcript: string; language: string; consent: boolean }) {
    const form = new FormData();
    form.append('file', file);
    form.append('name', data.name);
    form.append('transcript', data.transcript);
    form.append('language', data.language);
    form.append('consent', String(data.consent));
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

  // --- Audio ---------------------------------------------------------------
  /** Pobiera plik audio wraz z informacją, który tryb silnika go wygenerował. */
  async audio(url: string): Promise<AudioBlob> {
    try {
      const res: HttpResponse<Blob> = await firstValueFrom(
        this.http.get(url, { observe: 'response', responseType: 'blob' }),
      );
      const cd = res.headers.get('Content-Disposition');
      const match = cd ? /filename="?([^";]+)"?/.exec(cd) : null;
      return { blob: res.body!, engineMode: res.headers.get('X-Engine-Mode'), filename: match ? match[1] : null };
    } catch (e) {
      // Przy responseType=blob treść błędu jest Blobem — odczytaj komunikat backendu
      if (e instanceof HttpErrorResponse && e.error instanceof Blob) {
        let message: string | null = null;
        try {
          message = String(JSON.parse(await e.error.text()).message ?? '');
        } catch {
          message = null;
        }
        if (message) throw { status: e.status, message } satisfies ApiError;
      }
      throw toApiError(e);
    }
  }
}
