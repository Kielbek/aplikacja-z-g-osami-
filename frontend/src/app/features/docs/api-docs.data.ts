import { CodeSample } from '../../shared/code-block.component';
import { apiBase } from './api-snippets';

/** Pole w tabeli parametrów lub odpowiedzi. Opis obsługuje `kod` i **pogrubienie**. */
export interface Field {
  name: string;
  type: string;
  required?: boolean;
  desc: string;
}

export interface Endpoint {
  id: string;
  method: 'GET' | 'POST';
  path: string;
  title: string;
  summary: string;
  pathParams?: Field[];
  query?: Field[];
  headers?: Field[];
  body?: Field[];
  responseNote: string;
  responseFields?: Field[];
  request: CodeSample[];
  response: CodeSample[];
  errors: string[];
}

const KEY = 'nrv_live_TWÓJ_KLUCZ';

export function endpoints(base = apiBase()): Endpoint[] {
  const auth = `-H "Authorization: Bearer ${KEY}"`;
  const speechBody: Field[] = [
    { name: 'text', type: 'string', required: true, desc: 'Tekst do przeczytania. Pauzy: `[pauza 1s]`, `[pauza 500ms]`. Słownik wymowy z Twojego konta jest stosowany automatycznie.' },
    { name: 'voice_id', type: 'string', required: true, desc: 'Identyfikator głosu z `GET /v1/voices` — z biblioteki lub Twój własny.' },
    { name: 'language', type: 'string', desc: 'Kod języka, np. `pl`, `en`, `de`, `ja` (30 języków). Domyślnie pierwszy język głosu. Głos mówi z naturalnym akcentem wybranego języka.' },
    { name: 'format', type: 'enum', desc: '`mp3` (domyślnie, jakość wg planu do 320 kb/s) lub `wav`.' },
    { name: 'style', type: 'string', desc: 'Sposób mówienia opisany słowami, np. „spokojna narracja, wyraźna dykcja”. Do 300 znaków.' },
    { name: 'emotion', type: 'string', desc: '`Neutral`, `Happy`, `Whisper`, `Calm`, `Sad`, `Excited`, `Serious` lub `Menacing`.' },
    { name: 'speed', type: 'number', desc: 'Tempo od `0.5` do `2.0`. Domyślnie `1.0`.' },
    { name: 'expressiveness', type: 'number', desc: 'Ekspresja od `0` do `1`. Domyślnie `0.5`.' },
    { name: 'seed', type: 'integer', desc: 'Ziarno losowości — ten sam tekst i ziarno dają powtarzalny wynik.' },
  ];
  const jobFields: Field[] = [
    { name: 'id', type: 'string', desc: 'Identyfikator zadania, np. `job_3f2a…`.' },
    { name: 'status', type: 'enum', desc: '`queued` → `processing` → `succeeded` lub `failed`.' },
    { name: 'characters', type: 'integer', desc: 'Znaki, które zostaną (lub zostały) naliczone.' },
    { name: 'duration_ms', type: 'integer', desc: 'Długość nagrania — gdy `succeeded`.' },
    { name: 'audio_url', type: 'string', desc: 'Ścieżka do pobrania nagrania — gdy `succeeded`. Wymaga klucza API.' },
    { name: 'error', type: 'object', desc: '`code` i `message` — gdy `failed`.' },
    { name: 'expires_at', type: 'datetime', desc: 'Po tym czasie zadanie i nagranie są usuwane (7 dni od utworzenia).' },
    { name: 'webhook', type: 'object', desc: '`url`, `status` (`pending`, `delivered`, `failed`) i liczba prób — gdy podano `callback_url`.' },
  ];
  const jobJson = `{
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
  "demo": false,
  "webhook": {
    "url": "https://twoja-domena.pl/webhooks/narrivy",
    "status": "delivered",
    "attempts": 1
  }
}`;
  const voiceJson = `{
  "voice_id": "voice_warm_velvet",
  "object": "voice",
  "name": "Ciepły Aksamit",
  "type": "library",
  "description": "Ciepły, aksamitny głos kobiecy. Do audiobooków, powieści i opowieści.",
  "gender": "female",
  "age": 34,
  "languages": ["pl", "en", "de", "es", "fr", "…"],
  "categories": ["audiobooks"],
  "default_instruction": null
}`;
  return [
    {
      id: 'synteza',
      method: 'POST',
      path: '/v1/text-to-speech',
      title: 'Synteza mowy',
      summary: 'Zamienia tekst (do **5 000 znaków**) na nagranie i zwraca plik audio w odpowiedzi. Tekst jest dzielony po zdaniach i składany w jedno nagranie. Dłuższe teksty wysyłaj jako zadanie w tle.',
      body: speechBody,
      responseNote: '`200 OK` — plik `audio/mpeg` lub `audio/wav` z oznaczeniem treści AI w metadanych.',
      responseFields: [
        { name: 'X-Characters', type: 'nagłówek', desc: 'Naliczone znaki (bez znaczników pauz).' },
        { name: 'X-Duration-Ms', type: 'nagłówek', desc: 'Długość nagrania w milisekundach.' },
        { name: 'X-Engine-Mode', type: 'nagłówek', desc: '`voxcpm2`, `piper` lub `demo` (sygnał zastępczy, gdy model nie jest załadowany).' },
        { name: 'X-Request-Id', type: 'nagłówek', desc: 'Identyfikator żądania do zgłoszeń.' },
      ],
      request: [
        { label: 'curl', code: `curl -X POST ${base}/v1/text-to-speech \\
  ${auth} \\
  -H "Content-Type: application/json" \\
  -d '{
    "text": "Dzień dobry! Tak brzmi Narrivy.",
    "voice_id": "voice_warm_velvet",
    "language": "pl",
    "format": "mp3",
    "emotion": "Happy"
  }' \\
  --output nagranie.mp3` },
        { label: 'Python', code: `import requests

res = requests.post(
    "${base}/v1/text-to-speech",
    headers={"Authorization": "Bearer ${KEY}"},
    json={
        "text": "Dzień dobry! Tak brzmi Narrivy.",
        "voice_id": "voice_warm_velvet",
        "format": "mp3",
        "emotion": "Happy",
    },
    timeout=180,
)
res.raise_for_status()
open("nagranie.mp3", "wb").write(res.content)` },
        { label: 'Node.js', code: `const res = await fetch("${base}/v1/text-to-speech", {
  method: "POST",
  headers: { Authorization: "Bearer ${KEY}", "Content-Type": "application/json" },
  body: JSON.stringify({ text: "Dzień dobry! Tak brzmi Narrivy.", voice_id: "voice_warm_velvet", format: "mp3" }),
});
if (!res.ok) throw new Error((await res.json()).error.message);
const audio = Buffer.from(await res.arrayBuffer());` },
      ],
      response: [{ label: 'HTTP', code: `HTTP/1.1 200 OK
Content-Type: audio/mpeg
X-Characters: 31
X-Duration-Ms: 2840
X-Engine-Mode: voxcpm2
X-Request-Id: req_8b90d2cbbf0d474fb7a51866
X-RateLimit-Limit: 60
X-RateLimit-Remaining: 59

<dane binarne MP3>` }],
      errors: ['invalid_request', 'voice_not_found', 'text_too_long', 'quota_exceeded', 'concurrency_limit_exceeded', 'engine_busy'],
    },
    {
      id: 'zadanie-utworz',
      method: 'POST',
      path: '/v1/text-to-speech/jobs',
      title: 'Utwórz zadanie',
      summary: 'Synteza w tle dla długich tekstów — audiobooków, kursów, artykułów — do limitu znaków w projekcie z Twojego planu (**Pro: 200 000**, **Business: 500 000**). Odpowiedź przychodzi od razu; nagranie pobierzesz, gdy zadanie się zakończy.',
      headers: [{ name: 'Idempotency-Key', type: 'string', desc: 'Unikalny klucz operacji (np. UUID lub identyfikator rozdziału). Ponowione żądanie z tym samym kluczem zwraca istniejące zadanie zamiast tworzyć drugie.' }],
      body: [...speechBody, { name: 'callback_url', type: 'string', desc: 'Adres `https://`, na który wyślemy [webhook](#webhooki) po zakończeniu zadania.' }],
      responseNote: '`202 Accepted` — obiekt zadania i nagłówek `Location`.',
      responseFields: jobFields,
      request: [
        { label: 'curl', code: `curl -X POST ${base}/v1/text-to-speech/jobs \\
  ${auth} \\
  -H "Content-Type: application/json" \\
  -H "Idempotency-Key: ksiazka-12-rozdzial-1" \\
  -d '{
    "text": "Rozdział pierwszy. …",
    "voice_id": "voice_warm_narrator",
    "callback_url": "https://twoja-domena.pl/webhooks/narrivy"
  }'` },
        { label: 'Python', code: `import time, requests

API = "${base}/v1"
H = {"Authorization": "Bearer ${KEY}"}

job = requests.post(
    f"{API}/text-to-speech/jobs",
    headers={**H, "Idempotency-Key": "ksiazka-12-rozdzial-1"},
    json={"text": open("rozdzial-1.txt").read(), "voice_id": "voice_warm_narrator"},
).json()

while job["status"] in ("queued", "processing"):
    time.sleep(5)
    job = requests.get(f"{API}/text-to-speech/jobs/{job['id']}", headers=H).json()

if job["status"] == "failed":
    raise RuntimeError(job["error"]["message"])
audio = requests.get(f"{API}{job['audio_url'][3:]}", headers=H).content` },
      ],
      response: [{ label: 'JSON', code: `{
  "id": "job_3f2a9c0e7b1d4e5f6a7b8c9d",
  "object": "text_to_speech_job",
  "status": "queued",
  "voice_id": "voice_warm_narrator",
  "language": "pl",
  "format": "mp3",
  "characters": 48210,
  "created_at": "2026-11-16T09:12:03Z",
  "started_at": null,
  "finished_at": null,
  "expires_at": "2026-11-23T09:12:03Z",
  "webhook": {
    "url": "https://twoja-domena.pl/webhooks/narrivy",
    "status": "pending",
    "attempts": 0
  }
}` }],
      errors: ['invalid_request', 'voice_not_found', 'text_too_long', 'too_many_pending_jobs'],
    },
    {
      id: 'zadanie-stan',
      method: 'GET',
      path: '/v1/text-to-speech/jobs/{job_id}',
      title: 'Stan zadania',
      summary: 'Zwraca aktualny stan zadania. Odpytuj co kilka sekund albo — lepiej — podaj `callback_url` i czekaj na webhook.',
      pathParams: [{ name: 'job_id', type: 'string', required: true, desc: 'Identyfikator zadania.' }],
      responseNote: '`200 OK` — obiekt zadania.',
      responseFields: jobFields,
      request: [{ label: 'curl', code: `curl ${base}/v1/text-to-speech/jobs/job_3f2a9c0e7b1d4e5f6a7b8c9d \\
  ${auth}` }],
      response: [{ label: 'JSON', code: jobJson }],
      errors: ['job_not_found'],
    },
    {
      id: 'zadanie-nagranie',
      method: 'GET',
      path: '/v1/text-to-speech/jobs/{job_id}/audio',
      title: 'Nagranie zadania',
      summary: 'Pobiera gotowe nagranie. Dostępne przez 7 dni od utworzenia zadania.',
      pathParams: [{ name: 'job_id', type: 'string', required: true, desc: 'Identyfikator zadania.' }],
      responseNote: '`200 OK` — plik `audio/mpeg` lub `audio/wav`. `409 job_not_ready`, jeśli zadanie jeszcze trwa.',
      request: [{ label: 'curl', code: `curl ${base}/v1/text-to-speech/jobs/job_3f2a9c0e7b1d4e5f6a7b8c9d/audio \\
  ${auth} \\
  --output rozdzial-1.mp3` }],
      response: [{ label: 'HTTP', code: `HTTP/1.1 200 OK
Content-Type: audio/mpeg
Content-Disposition: attachment; filename="job_3f2a9c0e7b1d4e5f6a7b8c9d.mp3"
X-Duration-Ms: 3412000

<dane binarne MP3>` }],
      errors: ['job_not_found', 'job_not_ready'],
    },
    {
      id: 'glosy-lista',
      method: 'GET',
      path: '/v1/voices',
      title: 'Lista głosów',
      summary: 'Głosy z biblioteki Narrivy oraz Twoje własne — zaprojektowane i sklonowane. Własne głosy są na początku listy.',
      query: [
        { name: 'language', type: 'string', desc: 'Tylko głosy obsługujące język, np. `pl`.' },
        { name: 'category', type: 'enum', desc: '`audiobooks`, `social_media`, `media_business`, `gaming_animation` lub `wellness_relax`.' },
      ],
      responseNote: '`200 OK` — `{"object": "list", "data": [...]}`.',
      responseFields: [
        { name: 'voice_id', type: 'string', desc: 'Identyfikator do pola `voice_id` w syntezie.' },
        { name: 'type', type: 'enum', desc: '`library`, `designed` lub `cloned`.' },
        { name: 'languages', type: 'string[]', desc: 'Obsługiwane języki.' },
        { name: 'categories', type: 'string[]', desc: 'Kategorie zastosowań.' },
        { name: 'default_instruction', type: 'string | null', desc: 'Domyślny sposób mówienia, gdy nie podasz `style` ani `emotion`.' },
      ],
      request: [{ label: 'curl', code: `curl "${base}/v1/voices?language=pl&category=audiobooks" \\
  ${auth}` }],
      response: [{ label: 'JSON', code: `{
  "object": "list",
  "data": [
${voiceJson.split('\n').map((l) => '    ' + l).join('\n')}
  ]
}` }],
      errors: [],
    },
    {
      id: 'glosy-szczegoly',
      method: 'GET',
      path: '/v1/voices/{voice_id}',
      title: 'Szczegóły głosu',
      summary: 'Jeden głos z biblioteki lub Twój własny.',
      pathParams: [{ name: 'voice_id', type: 'string', required: true, desc: 'Identyfikator głosu.' }],
      responseNote: '`200 OK` — obiekt głosu.',
      request: [{ label: 'curl', code: `curl ${base}/v1/voices/voice_warm_velvet \\
  ${auth}` }],
      response: [{ label: 'JSON', code: voiceJson }],
      errors: ['voice_not_found'],
    },
    {
      id: 'zuzycie',
      method: 'GET',
      path: '/v1/usage',
      title: 'Zużycie i limity',
      summary: 'Zużycie znaków w bieżącym okresie rozliczeniowym, saldo dokupionych pakietów i limity Twojego planu.',
      responseNote: '`200 OK` — obiekt zużycia.',
      responseFields: [
        { name: 'characters_used', type: 'integer', desc: 'Znaki zużyte z limitu planu w bieżącym okresie (edytor i API razem).' },
        { name: 'character_limit', type: 'integer', desc: 'Miesięczny limit planu.' },
        { name: 'extra_characters', type: 'integer', desc: 'Saldo dokupionych pakietów (nie wygasają, używane po wyczerpaniu limitu planu).' },
        { name: 'characters_remaining', type: 'integer', desc: 'Ile znaków możesz jeszcze wygenerować.' },
        { name: 'period_end', type: 'date', desc: 'Dzień odnowienia limitów.' },
        { name: 'rate_limits', type: 'object', desc: 'Limity żądań, równoległości i długości tekstu.' },
      ],
      request: [{ label: 'curl', code: `curl ${base}/v1/usage \\
  ${auth}` }],
      response: [{ label: 'JSON', code: `{
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
    "max_job_characters": 200000
  }
}` }],
      errors: [],
    },
  ];
}

export const ERRORS: { http: number; code: string; desc: string; retry?: boolean }[] = [
  { http: 400, code: 'invalid_request', desc: 'Brakujące lub niepoprawne pole — np. literówka w nazwie pola, nieobsługiwany format, `speed` poza zakresem. Komunikat wskazuje pole.' },
  { http: 400, code: 'invalid_json', desc: 'Treść nie jest poprawnym JSON-em albo pole ma zły typ.' },
  { http: 401, code: 'missing_api_key', desc: 'Brak nagłówka z kluczem.' },
  { http: 401, code: 'invalid_api_key', desc: 'Klucz jest nieprawidłowy lub unieważniony.' },
  { http: 402, code: 'quota_exceeded', desc: 'Wyczerpany miesięczny limit znaków planu i dokupionych pakietów.' },
  { http: 402, code: 'storage_full', desc: 'Brak miejsca na nagrania w Twoim planie — usuń niepotrzebne projekty lub historię albo zmień plan.' },
  { http: 403, code: 'plan_required', desc: 'Plan bez dostępu do API — wymagany Pro lub Business.' },
  { http: 403, code: 'email_not_verified', desc: 'Adres e-mail konta nie jest potwierdzony.' },
  { http: 404, code: 'voice_not_found', desc: 'Nieznany głos albo głos innego użytkownika.' },
  { http: 404, code: 'job_not_found', desc: 'Zadanie nie istnieje lub wygasło (po 7 dniach).' },
  { http: 409, code: 'job_not_ready', desc: 'Nagranie zadania nie jest jeszcze gotowe.' },
  { http: 413, code: 'text_too_long', desc: 'Tekst przekracza limit wywołania: 5 000 znaków synchronicznie, w zadaniach — limit planu.' },
  { http: 429, code: 'rate_limit_exceeded', desc: 'Przekroczony limit żądań na minutę.', retry: true },
  { http: 429, code: 'concurrency_limit_exceeded', desc: 'Za dużo równoległych syntez synchronicznych.', retry: true },
  { http: 429, code: 'too_many_pending_jobs', desc: 'Ponad 100 zadań czeka w kolejce.', retry: true },
  { http: 500, code: 'internal_error', desc: 'Nieoczekiwany błąd. Podaj `request_id`, kontaktując się z pomocą.', retry: true },
  { http: 503, code: 'engine_busy', desc: 'Silnik jest chwilowo zajęty innymi nagraniami.', retry: true },
  { http: 503, code: 'engine_unavailable', desc: 'Silnik TTS jest niedostępny, np. podczas prac technicznych.', retry: true },
];

/** Minimalny Markdown dla opisów: `kod`, **pogrubienie**, [link](#kotwica). Treść jest najpierw escapowana. */
export function md(text: string): string {
  return text
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/`([^`]+)`/g, '<code>$1</code>')
    .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
    .replace(/\[([^\]]+)\]\((#[a-z0-9-]+)\)/g, '<a href="$2">$1</a>');
}
