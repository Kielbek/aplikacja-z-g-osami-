import { Injectable, signal } from '@angular/core';
import { ApiError } from '../api/models';

export interface UpsellRequest {
  /** Ile znaków potrzeba do wygenerowania (jeśli wiadomo). */
  needed?: number;
  /** Ile znaków jest teraz dostępnych (plan + pakiety − rezerwacje). */
  available?: number;
  /** Ponowienie akcji po dokupieniu znaków (np. „Generuj całość”). */
  retry?: () => void;
}

/** Okno z ofertą (pakiet znaków albo wyższy plan), gdy brakuje znaków do generowania. */
@Injectable({ providedIn: 'root' })
export class UpsellService {
  readonly request = signal<UpsellRequest | null>(null);

  open(req: UpsellRequest = {}) {
    this.request.set(req);
  }

  close() {
    this.request.set(null);
  }

  /** Obsługuje błąd „za mało znaków” z API; zwraca true, gdy okno zostało pokazane. */
  handle(err: unknown, retry?: () => void): boolean {
    const e = err as ApiError;
    if (e?.code !== 'QUOTA_EXCEEDED') return false;
    this.open({ needed: e.details?.needed, available: e.details?.available, retry });
    return true;
  }
}
