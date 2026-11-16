import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { AuthService } from '../../core/services/auth.service';
import { LogoComponent } from '../../shared/brand/logo.component';
import { CodeBlockComponent, CodeSample } from '../../shared/code-block.component';
import { apiBase, jobSamples, quickStart, webhookSamples } from './api-snippets';

interface Param { name: string; type: string; required?: boolean; desc: string }

/** Publiczna dokumentacja API (bez logowania). Źródło maszynowe: /v1/openapi.json. */
@Component({
  selector: 'vc-api-docs-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, LogoComponent, CodeBlockComponent],
  templateUrl: './api-docs.page.html',
  styleUrl: './api-docs.page.scss',
})
export class ApiDocsPage {
  protected auth = inject(AuthService);
  protected base = apiBase();
  protected quick = quickStart();
  protected jobs = jobSamples();
  protected webhooks = webhookSamples();

  protected toc = [
    { id: 'wprowadzenie', label: 'Wprowadzenie' },
    { id: 'uwierzytelnianie', label: 'Uwierzytelnianie' },
    { id: 'szybki-start', label: 'Szybki start' },
    { id: 'synteza', label: 'Synteza mowy' },
    { id: 'zadania', label: 'Zadania w tle' },
    { id: 'webhooki', label: 'Webhooki' },
    { id: 'glosy', label: 'Głosy' },
    { id: 'zuzycie', label: 'Zużycie' },
    { id: 'limity', label: 'Limity' },
    { id: 'bledy', label: 'Błędy' },
    { id: 'dobre-praktyki', label: 'Dobre praktyki' },
  ];

  protected speechParams: Param[] = [
    { name: 'text', type: 'string', required: true, desc: 'Tekst do przeczytania. Pauzy: [pauza 1s], [pauza 500ms]. Słownik wymowy z Twojego konta jest stosowany automatycznie.' },
    { name: 'voice_id', type: 'string', required: true, desc: 'Identyfikator głosu z GET /v1/voices (biblioteka lub Twój głos).' },
    { name: 'language', type: 'string', desc: 'Kod języka, np. pl, en, de, es, ja (30 języków). Domyślnie pierwszy język głosu. Głos mówi z naturalnym akcentem wybranego języka.' },
    { name: 'format', type: '"mp3" | "wav"', desc: 'Format pliku. Domyślnie mp3 (jakość wg planu, do 320 kb/s).' },
    { name: 'style', type: 'string', desc: 'Sposób mówienia opisany słowami, np. „spokojna narracja, wyraźna dykcja”. Do 300 znaków.' },
    { name: 'emotion', type: 'string', desc: 'Emocja: Neutral, Happy, Whisper, Calm, Sad, Excited, Serious, Menacing.' },
    { name: 'speed', type: 'number', desc: 'Tempo 0.5–2.0 (domyślnie 1.0).' },
    { name: 'expressiveness', type: 'number', desc: 'Ekspresja 0–1 (domyślnie 0.5).' },
    { name: 'seed', type: 'integer', desc: 'Ziarno losowości — ten sam tekst i ziarno dają powtarzalny wynik.' },
    { name: 'callback_url', type: 'string', desc: 'Tylko zadania w tle: adres https://, na który wyślemy webhook po zakończeniu.' },
  ];

  protected errors = [
    { http: 400, code: 'invalid_request', desc: 'Brakujące lub niepoprawne pole (np. literówka w nazwie pola, zły format, speed poza zakresem).' },
    { http: 400, code: 'invalid_json', desc: 'Treść nie jest poprawnym JSON-em.' },
    { http: 401, code: 'missing_api_key', desc: 'Brak nagłówka z kluczem.' },
    { http: 401, code: 'invalid_api_key', desc: 'Klucz nieprawidłowy lub unieważniony.' },
    { http: 402, code: 'quota_exceeded', desc: 'Wyczerpany miesięczny limit znaków planu i dokupionych pakietów.' },
    { http: 403, code: 'plan_required', desc: 'Plan bez dostępu do API (wymagany Pro lub Business).' },
    { http: 403, code: 'email_not_verified', desc: 'Adres e-mail konta nie jest potwierdzony.' },
    { http: 404, code: 'voice_not_found', desc: 'Nieznany głos (albo głos innego użytkownika).' },
    { http: 404, code: 'job_not_found', desc: 'Zadanie nie istnieje lub wygasło (po 7 dniach).' },
    { http: 409, code: 'job_not_ready', desc: 'Nagranie zadania nie jest jeszcze gotowe.' },
    { http: 413, code: 'text_too_long', desc: 'Tekst przekracza limit wywołania (5 000 znaków synchronicznie; zadania — limit planu).' },
    { http: 429, code: 'rate_limit_exceeded', desc: 'Przekroczony limit żądań na minutę. Ponów po czasie z nagłówka Retry-After.' },
    { http: 429, code: 'concurrency_limit_exceeded', desc: 'Za dużo równoległych syntez. Poczekaj na zakończenie trwających lub użyj zadań w tle.' },
    { http: 429, code: 'too_many_pending_jobs', desc: 'Ponad 100 zadań czeka w kolejce.' },
    { http: 503, code: 'engine_busy', desc: 'Silnik jest chwilowo zajęty. Ponów po Retry-After (zwykle 5 s).' },
    { http: 503, code: 'engine_unavailable', desc: 'Silnik TTS jest niedostępny (np. przerwa techniczna).' },
    { http: 500, code: 'internal_error', desc: 'Nieoczekiwany błąd — podaj request_id, kontaktując się z pomocą.' },
  ];

  protected errorExample: CodeSample[] = [{ label: 'JSON', code: `{
  "error": {
    "code": "quota_exceeded",
    "message": "Wykorzystano miesięczny limit znaków planu Pro (200 000)…",
    "request_id": "req_8f3a2c1b9d0e4f5a6b7c8d9e"
  }
}` }];

  protected jobExample: CodeSample[] = [{ label: 'JSON', code: `{
  "id": "job_3f2a9c0e7b1d4e5f6a7b8c9d",
  "object": "text_to_speech_job",
  "status": "succeeded",
  "voice_id": "voice_warm_narrator",
  "language": "pl",
  "format": "mp3",
  "characters": 48210,
  "created_at": "2026-11-16T09:12:03Z",
  "started_at": "2026-11-16T09:12:04Z",
  "finished_at": "2026-11-16T09:31:40Z",
  "expires_at": "2026-11-23T09:12:03Z",
  "duration_ms": 3412000,
  "audio_url": "/v1/text-to-speech/jobs/job_3f2a9c0e7b1d4e5f6a7b8c9d/audio",
  "webhook": { "url": "https://twoja-domena.pl/webhooks/narrivy", "status": "delivered", "attempts": 1 }
}` }];

  protected webhookExample: CodeSample[] = [{ label: 'HTTP', code: `POST /webhooks/narrivy HTTP/1.1
Content-Type: application/json
Narrivy-Signature: t=1763284300,v1=5f2b9c…

{
  "id": "evt_3f2a9c0e7b1d4e5f6a7b8c9d_0",
  "type": "job.succeeded",
  "created_at": "2026-11-16T09:31:40Z",
  "data": { "id": "job_3f2a…", "status": "succeeded", "audio_url": "/v1/text-to-speech/jobs/job_3f2a…/audio", … }
}` }];

  protected voicesExample: CodeSample[] = [{ label: 'JSON', code: `{
  "object": "list",
  "data": [
    {
      "voice_id": "voice_warm_velvet",
      "object": "voice",
      "name": "Ciepły Aksamit",
      "type": "library",
      "description": "Ciepły, aksamitny głos kobiecy. Do audiobooków, powieści i opowieści.",
      "gender": "female",
      "age": 34,
      "languages": ["pl", "en", "de", "…"],
      "categories": ["audiobooks"],
      "default_instruction": null
    }
  ]
}` }];

  protected usageExample: CodeSample[] = [{ label: 'JSON', code: `{
  "object": "usage",
  "plan": "pro",
  "period_start": "2026-11-01",
  "period_end": "2026-12-01",
  "characters_used": 48210,
  "character_limit": 200000,
  "extra_characters": 25000,
  "characters_remaining": 176790,
  "rate_limits": {
    "requests_per_minute": 60,
    "concurrent_requests": 2,
    "max_sync_characters": 5000,
    "max_job_characters": 100000
  }
}` }];
}
