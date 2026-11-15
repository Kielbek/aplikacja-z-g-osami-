# VoiceCraft AI

Aplikacja SaaS do generowania naturalnego głosu z tekstu (TTS). Po zalogowaniu użytkownik trafia od razu do edytora. Tekst dzieli się tam na fragmenty, które można generować, odsłuchiwać i poprawiać pojedynczo, a następnie połączyć w jedno nagranie MP3/WAV.

Silnikiem syntezy jest **VoxCPM2** (OpenBMB). Gdy model nie jest załadowany, aplikacja działa w **wyraźnie oznaczonym trybie demonstracyjnym**.

Aplikacja obsługuje wielu użytkowników. Logowanie odbywa się hasłem lub przez Google, a sesje są przechowywane w ciasteczku httpOnly. Generowanie działa w tle, z postępem na żywo przez SSE. Dane są w PostgreSQL z migracjami Flyway, nagrania w S3, a subskrypcje obsługuje Stripe.

```
┌────────────────────┐   /api    ┌──────────────────────┐   HTTP   ┌───────────────────────────┐
│ Angular 22         │ ────────▶ │ Spring Boot 4        │ ───────▶ │ Python / FastAPI          │
│ frontend/  :4200   │           │ backend/  :8080      │          │ tts-engine/  :8808        │
│ UI, odtwarzacz,    │           │ projekty, fragmenty, │          │ VoxCPM2 (GPU) lub         │
│ stan edytora       │           │ głosy, historia,     │          │ syntezator DEMO;          │
│                    │           │ limity, łączenie WAV │          │ analiza nagrań, MP3       │
└────────────────────┘           └──────────────────────┘          └───────────────────────────┘
```

| Warstwa | Odpowiada za | Nie wie o |
|---|---|---|
| **frontend** | widoki, edycję, autozapis, odtwarzanie, waveform | silniku TTS (rozmawia tylko z `/api`) |
| **backend** | użytkownicy i sesje, domena (projekty, fragmenty, głosy, historia, konto), kolejka generowania, limity planów, płatności, łączenie fragmentów, eksport | szczegółach modelu (jeden klient: `EngineClient`) |
| **tts-engine** | synteza, projektowanie głosu, klonowanie, analiza audio, kodowanie MP3 | projektach i użytkownikach |

## Szybki start (tryb demonstracyjny, bez GPU)

Wymagania: Node 22+, Java 21, Maven, Python 3.11+.

```bash
# 1. Silnik TTS
cd tts-engine && python -m venv .venv && . .venv/bin/activate
pip install -r requirements.txt
ENGINE_MODE=demo uvicorn app.main:app --port 8808
```

```bash
# 2. Backend
cd backend && mvn spring-boot:run
```

```bash
# 3. Frontend (proxy /api → :8080)
cd frontend && npm install && npm start
```

Otwórz http://localhost:4200. Lokalnie powstaje konto demonstracyjne **marta@studio-glos.pl / demo-haslo** (plan Pro, przykładowe projekty). Na stronie logowania można je wypełnić jednym kliknięciem. Można też założyć własne konto, które startuje z planem Free.

Lokalnie backend używa H2 (plik w `./data`). Do pracy z PostgreSQL ustaw `DATABASE_URL`, `DATABASE_USER` i `DATABASE_PASSWORD`. Schemat tworzy Flyway przy starcie.

Wszystko naraz w kontenerach (PostgreSQL, MinIO jako S3, silnik, backend w profilu `prod`, frontend):

```bash
cp .env.example .env
```

```bash
docker compose up --build
```

W pliku `.env` uzupełnij co najmniej `POSTGRES_PASSWORD`, `JWT_SECRET` (min. 32 znaki), `S3_ACCESS_KEY` i `S3_SECRET_KEY`.

## Podłączenie VoxCPM2

Na maszynie z GPU (CUDA):

```bash
cd tts-engine
pip install -r requirements-voxcpm.txt
ENGINE_MODE=voxcpm2 VOXCPM_MODEL_ID=openbmb/VoxCPM2 uvicorn app.main:app --host 0.0.0.0 --port 8808
```

Następnie wskaż adres backendowi: `ENGINE_URL=http://gpu-host:8808`.

- **Weryfikacja z biblioteką:** adapter został sprawdzony z kodem źródłowym `voxcpm` 2.0.3 i ma testy na atrapie o identycznej sygnaturze (`tts-engine/tests/test_voxcpm_adapter.py`). Weryfikacja wykryła i usunęła dwa błędy. Pierwszy: `VoxCPM.generate(*args, **kwargs)` ukrywa parametry, więc są odczytywane z `_generate`. Drugi: klonowanie bez transkrypcji używa tylko `reference_wav_path`, a para `prompt_wav_path`/`prompt_text` jest przekazywana razem. Lista języków odpowiada 30 językom VoxCPM2: polski jest, czeskiego i ukraińskiego nie ma. **Uruchomienia z prawdziwymi wagami na GPU nie dało się przeprowadzić w środowisku deweloperskim.**
- Wymagania VoxCPM2: Python 3.10–3.12, PyTorch ≥ 2.5, CUDA ≥ 12. Częstotliwość wyjściowa to 48 kHz.
- `ENGINE_MODE=auto` (domyślnie) próbuje załadować model, a gdy się nie uda, przechodzi w tryb demo. Powód jest widoczny w *Ustawienia → Silnik TTS*.
- `ENGINE_MODE=voxcpm2` wymaga modelu. Bez niego serwis nie wystartuje.
- Cała wiedza o bibliotece `voxcpm` siedzi w jednym pliku: `tts-engine/app/engines/voxcpm2.py`. Adapter sprawdza sygnaturę `VoxCPM.generate` i przekazuje tylko obsługiwane argumenty (`cfg_value`, `inference_timesteps`, `normalize`, `denoise`, `prompt_wav_path`, `prompt_text`…). Opis głosu i instrukcja stylu trafiają do modelu jako prefiks `(opis)tekst`, zgodnie z konwencją voice design VoxCPM2. Konwencję przełącza `VOXCPM_DESCRIPTION_FORMAT`. **Po aktualizacji modelu zweryfikuj ten plik z dokumentacją OpenBMB.**
- Fragmenty wygenerowane w trybie demo automatycznie dostają status *Nieaktualny* po podłączeniu prawdziwego modelu.

## Uczciwość trybu demonstracyjnego

- Syntezator demo **nie generuje mowy**. Tworzy sygnał zastępczy o rytmie mowy (sylaby, pauzy, intonacja), żeby dało się przetestować cały przepływ.
- Każda odpowiedź silnika niesie nagłówek `X-Engine-Mode`. Interfejs pokazuje baner trybu demo, odznaki **DEMO** przy fragmentach, próbkach, w odtwarzaczu i w historii. Eksportowane pliki dostają sufiks `_DEMO`.
- **Klonowanie** w trybie demo jest zablokowane: endpoint zwraca 503, a interfejs wyjaśnia dlaczego. Analiza nagrania (długość, SNR, clipping, głośność, cisza) działa naprawdę.
- Do testowania kolejki można spowolnić syntezę demo: `DEMO_LATENCY_MS=2500`.

## Plany subskrypcji i limity

Limity zależą od planu. Są zdefiniowane w jednym miejscu, `backend/.../account/SubscriptionPlan.java`, i **egzekwowane w backendzie** (`AccountService`). Interfejs pobiera je z `GET /api/account/plans`, pokazuje wykorzystanie i z góry blokuje niedostępne opcje. Przekroczenie limitu zwraca HTTP 402 z `code: "PLAN_LIMIT"`, a interfejs pokazuje wtedy komunikat z przejściem do *Ustawienia → Subskrypcja*.

| Limit | Free | Starter | Pro | Business |
|---|---|---|---|---|
| Znaki miesięcznie | 10 000 | 50 000 | 200 000 | 1 000 000 |
| Minuty audio miesięcznie | 10 | 60 | 240 | 1 200 |
| Projekty | 3 | 20 | 100 | bez limitu |
| Znaki w jednym projekcie | 5 000 | 20 000 | 100 000 | 500 000 |
| Własne głosy (zaprojektowane + sklonowane) | 1 | 5 | 20 | 100 |
| Klonowanie głosu | — | — | ✓ | ✓ |
| Ustawienia zaawansowane (CFG, kroki, ziarno…) | — | ✓ | ✓ | ✓ |
| Eksport | MP3 | MP3 + WAV | MP3 + WAV | MP3 + WAV |
| Maks. jakość MP3 | 128 kb/s | 192 kb/s | 320 kb/s | 320 kb/s |
| Historia generowania | 7 dni | 30 dni | 90 dni | 365 dni |

Gdzie limity są sprawdzane:

- **Generowanie fragmentu i próbki głosu:** miesięczne znaki i minuty, a także limit znaków w projekcie.
- **Tworzenie i duplikowanie projektu:** liczba projektów.
- **Zapis głosu zaprojektowanego lub sklonowanego:** liczba własnych głosów. Klonowanie wymaga planu Pro lub wyższego.
- **Eksport i ustawienia eksportu:** dozwolone formaty i maksymalny bitrate.
- **Zmiana ustawień zaawansowanych projektu:** odrzucana w planie Free. Generowanie w tym planie używa wartości domyślnych.
- **Lista historii:** pokazuje tylko wpisy z okresu przechowywania danego planu.

Po obniżeniu planu istniejące dane zostają nienaruszone. Blokowane są tylko nowe działania ponad limit, a ustawienia eksportu są automatycznie dopasowywane do nowego planu.

W produkcji plan zmienia się wyłącznie przez Stripe (zob. „Płatności”). `PUT /api/account/plan` (zmiana bez płatności) działa tylko przy `DEMO_PLAN_SWITCH=true`, czyli w lokalnym developmencie.

## Konta, logowanie i bezpieczeństwo

- **Konta:** wielu użytkowników. Projekty, własne głosy, ulubione, historia, zadania i subskrypcja należą do konkretnego użytkownika. Biblioteka głosów jest wspólna. Próba otwarcia cudzego zasobu zwraca 404, nieodróżnialne od braku zasobu.
- **Sesja:** JWT (HS256) w ciasteczku `vc_session` z flagami httpOnly i SameSite=Lax, w produkcji także Secure. JavaScript nie ma dostępu do tokenu. Sesja trwa 14 dni z „Zapamiętaj mnie”, bez tej opcji kończy się z zamknięciem przeglądarki. Token zawiera wersję sesji, więc zmiana hasła, reset hasła i „Wyloguj ze wszystkich innych urządzeń” natychmiast unieważniają pozostałe sesje.
- **Hasła:** bcrypt, minimum 8 znaków. Limit 5 nieudanych prób na konto i IP oraz 30 na IP w ciągu 15 minut (HTTP 429). Odpowiedź przy nieistniejącym koncie trwa tyle samo co przy błędnym haśle.
- **Ochrona CSRF:** ciasteczko SameSite=Lax oraz wymagany nagłówek `X-Requested-With` przy każdym żądaniu zmieniającym dane. Wyjątkiem jest webhook Stripe, uwierzytelniany podpisem.
- **Reset hasła:** jednorazowy link ważny przez godzinę. W bazie przechowywany jest wyłącznie skrót SHA-256 tokenu, a odpowiedź nie zdradza, czy konto istnieje. E-maile wysyłane są przez SMTP (`SPRING_MAIL_HOST` i powiązane zmienne). Bez SMTP link trafia do logów serwera, co nadaje się tylko do lokalnego developmentu.
- **Logowanie przez Google:** przepływ tokenu Google Identity Services. Backend weryfikuje token w Google: czy wydano go dla naszego klienta (`aud`) i czy e-mail jest zweryfikowany. Konto e-mail z tym samym adresem zostaje połączone z Google. Konfiguracja:
  1. W Google Cloud Console utwórz identyfikator klienta OAuth typu „Aplikacja internetowa” i dodaj źródło JavaScript, np. `http://localhost:4200`.
  2. Ustaw `GOOGLE_CLIENT_ID=<id>.apps.googleusercontent.com`.
- **Usunięcie konta:** wymaga hasła i trwale usuwa projekty, głosy, historię oraz zadania. Przy aktywnej subskrypcji trzeba ją najpierw anulować.
- **Konto demo i darmowa zmiana planu** są włączone tylko lokalnie. W profilu `prod` są domyślnie wyłączone (`SEED_DEMO_DATA`, `DEMO_PLAN_SWITCH`).

## Generowanie w tle

„Generuj zmienione” tworzy w backendzie zadanie (`generation_jobs`). Fragmenty są generowane po kolei w puli wątków, a liczba równoległych zadań jest ustawiana przez `voicecraft.jobs.concurrency` (domyślnie 2).

- Postęp i gotowe fragmenty trafiają do przeglądarki przez Server-Sent Events (`GET /api/projects/{id}/events`).
- **Zamknięcie karty nie przerywa generowania.** Po ponownym otwarciu projektu edytor podłącza się do trwającego zadania.
- Po restarcie serwera niedokończone zadania są wznawiane od miejsca przerwania.
- Zadanie można zatrzymać. Przekroczenie limitu planu lub niedostępność silnika kończy zadanie z komunikatem.
- Kanał zdarzeń działa w pamięci jednej instancji. Przy kilku instancjach backendu potrzebny jest Redis Pub/Sub lub sticky sessions.

## Baza danych i magazyn plików

- **Baza:** PostgreSQL 14+ w produkcji. Schemat jest zarządzany przez Flyway (`backend/src/main/resources/db/migration`), a Hibernate tylko go waliduje (`ddl-auto: validate`). Ta sama migracja działa na H2 w lokalnym developmencie. Sprawdzone na PostgreSQL 16.
- **Nagrania:** `STORAGE_TYPE=local` (katalog danych) albo `STORAGE_TYPE=s3`. S3 działa z AWS, MinIO, Cloudflare R2 i Backblaze B2. Ustawia się je przez `S3_BUCKET`, `S3_REGION`, `S3_ENDPOINT`, `S3_ACCESS_KEY`, `S3_SECRET_KEY` i `S3_PATH_STYLE`. Bucket jest tworzony automatycznie, jeśli nie istnieje.

## Płatności (Stripe)

1. W Stripe utwórz produkty i ceny cykliczne dla planów Starter, Pro i Business, a ich identyfikatory wpisz w `STRIPE_PRICE_STARTER`, `STRIPE_PRICE_PRO` i `STRIPE_PRICE_BUSINESS`.
2. Ustaw `STRIPE_SECRET_KEY`.
3. Dodaj endpoint webhooka `https://<twoja-domena>/api/billing/webhook` ze zdarzeniami `checkout.session.completed` i `customer.subscription.created|updated|deleted`, a jego sekret wpisz w `STRIPE_WEBHOOK_SECRET`.
4. Włącz Customer Portal, przez który użytkownicy zmieniają plan, metodę płatności, pobierają faktury i anulują subskrypcję.

Jak to działa:

- Wybór planu przekierowuje do Stripe Checkout. **Plan zmienia się wyłącznie po zweryfikowanym webhooku**, a powrót z Checkout niczego sam nie aktywuje.
- Webhook sprawdza podpis HMAC z tolerancją 5 minut i ignoruje powtórzone zdarzenia.
- Anulowanie lub nieopłacenie subskrypcji przywraca plan Free. Przy statusie `past_due` plan zostaje na czas ponawiania płatności.
- Do testów lokalnych możesz przekierować zdarzenia poleceniem `stripe listen --forward-to localhost:8080/api/billing/webhook`.

## Logo

Propozycje znaku są w `frontend/src/app/shared/brand/logo-marks.ts`, a ich pliki SVG w `frontend/public/brand/`:

| | Wariant | Idea |
|---|---|---|
| A | **Fala V** (domyślny) | pięć pasków korektora układa się w literę V |
| B | Kursor w głos | kursor tekstowy przechodzący w falę |
| C | Dymek | dymek rozmowy z falą |
| D | Sygnał | okrąg przecięty sinusoidą |

Zmiana logo w całej aplikacji: ustaw `BRAND_LOGO` w `logo-marks.ts` i podmień `public/favicon.svg` na odpowiedni plik z `public/brand/`.

## Edytor

**Generowanie tylko tego, co się zmieniło**

- Każdy fragment ma odcisk: SHA-256 z tekstu po zastosowaniu słownika wymowy, parametrów projektu, głosu fragmentu i trybu silnika.
- Zmiana jednego zdania, głosu jednego fragmentu albo hasła w słowniku oznacza jako *Nieaktualny* tylko fragmenty, których to dotyczy.
- „Generuj zmienione (n)” generuje wyłącznie nowe i nieaktualne fragmenty (w tle, patrz „Generowanie w tle”).
- Łączenie fragmentów odbywa się bez ponownej syntezy, z konfigurowalną pauzą między fragmentami.

**Wiele głosów w projekcie (dialogi)**

- Głos projektu jest domyślny. Każdy fragment może mieć własny głos: chip w wierszu fragmentu albo zbiorczo dla zaznaczonych fragmentów.
- Gdy projekt ma kilka głosów, fragmenty dostają kolorowy znacznik mówcy.

**Pauzy**

- Znacznik `[pauza 1.5s]` albo `[pauza 500ms]` w tekście (przycisk „Pauza” wstawia go w miejscu kursora) jest zamieniany na ciszę o dokładnej długości, od 50 ms do 10 s.
- Fragment z pauzami jest syntezowany w częściach i składany w jedno nagranie.
- Znaczniki nie wliczają się do limitu znaków.

**Słownik wymowy**

- Hasło → wymowa (np. „VoxCPM” → „woks ce pe em”), zamieniane przed syntezą.
- Dopasowanie obejmuje całe słowa, bez względu na wielkość liter (opcjonalnie z jej uwzględnieniem). Dłuższe hasła mają pierwszeństwo, a znaczniki pauz nie są zmieniane.
- Wpisy mogą dotyczyć jednego projektu (panel „Głos i styl”) albo wszystkich projektów (*Ustawienia → Słownik wymowy*).

**Cofnij i ponów**

- Historia obejmuje tekst, podział i łączenie fragmentów, kolejność, głosy, pauzy oraz import.
- Kolejne znaki wpisane w jednym fragmencie w krótkim czasie są łączone w jeden krok cofania.

**Kolejność**

- Fragmenty można przeciągać za uchwyt (Angular CDK, działa także na ekranach dotykowych).

**Wersje nagrania**

- Każde wygenerowanie zapisuje osobny plik. W menu fragmentu „Wersje nagrania” można odsłuchać i przywrócić wcześniejszą wersję, np. lepsze ujęcie.
- Wersja nagrana dla innego tekstu lub ustawień jest wyraźnie oznaczona, a po przywróceniu fragment ma status *Nieaktualny*.

**Import**

- Obsługiwane formaty: TXT (UTF-8 lub Windows-1250), MD, DOCX i PDF z warstwą tekstową, do 10 MB.
- Tekst jest dzielony na fragmenty: akapity, a zbyt długie akapity na grupy zdań do ok. 400 znaków.
- Import do bieżącego projektu (przycisk „Importuj”) albo jako nowy projekt (*Projekty → Importuj z pliku*).
- Parser DOCX blokuje DTD i encje zewnętrzne (ochrona przed XXE).

**Napisy**

- Eksport SRT albo WebVTT. Czasy wynikają z rzeczywistych długości nagrań fragmentów i pauz.
- Napis ma najwyżej 2 linie po 42 znaki.

**Normalizacja głośności**

- Eksport jest mierzony według ITU-R BS.1770-4 (K-weighting, bramkowanie −70 LUFS i −10 LU) i doprowadzany do wybranej wartości: −14, −16 (domyślnie), −19 albo −23 LUFS, lub bez normalizacji.
- Szczyt jest ograniczony do −1 dBFS.

**Skróty klawiszowe**

| Skrót | Działanie |
|---|---|
| `Enter` | dzieli fragment w miejscu kursora |
| `Backspace` na początku fragmentu | łączy go z poprzednim |
| `Shift+Enter` | nowa linia |
| `Ctrl+Enter` | generuje fragment |
| `Ctrl+Shift+Enter` | generuje zmienione |
| `Ctrl+Z` / `Ctrl+Shift+Z` / `Ctrl+Y` | cofnij / ponów |
| `Ctrl+S` | zapisz |
| `Spacja` | play/pauza |

## System wizualny

Tokeny są w `frontend/src/styles/_tokens.scss`, kontrolki w `_controls.scss`, wzorce układu w `_patterns.scss`.

- **Kolory:** złamana biel `#FAF9F7` (tło), ciepłe szarości (powierzchnie, obramowania), grafit `#1F2430` (tekst, główne akcje), kobalt `#2C4BA3` (akcent: zaznaczenia, postęp, fokus). Kolory semantyczne są stonowane, a tryb demo ma osobną, ciepłą paletę. Dostępny jest też pełny motyw ciemny.
- **Typografia:** Inter Variable w interfejsie, Source Serif 4 tylko w tytułach stron i projektów. Skala 11–32 px, cyfry tabelaryczne dla czasów i liczników.
- **Siatka 4 px**, promienie 4–16 px, cienie o niskim kontraście, animacje 120–220 ms (wyłączane przy `prefers-reduced-motion`).
- **Układ:** sidebar 248 px, edytor o szerokości czytelnej kolumny (760 px), panel ustawień głosu 320 px, odtwarzacz 72 px. Poniżej 1280 px panel głosu staje się wysuwaną szufladą, poniżej 1024 px sidebar chowa się pod przyciskiem menu.

## Struktura

```
frontend/src/app/
  core/api/            ApiService (jedyny klient HTTP), modele
  core/services/       odtwarzacz (Web Audio + waveform), stan silnika, konto, głosy, projekty, toasty, motyw
  core/util/           dzielenie tekstu, formatowanie, etykiety
  shared/              logo (brand/), lista vc-select, menu, ikony, waveform, przycisk odtwarzania, awatar, banery, dialogi
  layout/              shell, sidebar, dolny odtwarzacz
  features/editor/     EditorStore (stan, autozapis, kolejka generowania), wiersz fragmentu, panel głosu, wybór głosu
  features/…           projects, voices (+ szczegóły), voice-design, voice-clone, history, settings, login
backend/src/main/java/ai/voicecraft/
  engine/              EngineClient + DTO kontraktu z silnikiem
  project/             Project, Segment, VoiceSettings, ProjectService, GenerationService
  voice/ history/ account/ audio/ config/ common/
tts-engine/app/
  engines/base.py      interfejs silnika
  engines/voxcpm2.py   adapter VoxCPM2
  engines/demo.py      syntezator demonstracyjny
  audio.py             WAV/MP3, analiza nagrań referencyjnych
```

## API backendu (skrót)

| Metoda | Ścieżka | Opis |
|---|---|---|
| GET | `/api/engine/status` | tryb silnika, możliwości, komunikat |
| GET/POST | `/api/projects` | lista / nowy projekt |
| GET/PATCH/DELETE | `/api/projects/{id}` | projekt, nazwa i ustawienia głosu |
| POST | `/api/projects/{id}/duplicate` | kopia projektu |
| PUT | `/api/projects/{id}/segments` | zapis listy fragmentów `[{id, text, voiceId}]` |
| POST | `/api/projects/{id}/segments/{sid}/generate` | generowanie jednego fragmentu |
| GET | `/api/projects/{id}/segments/{sid}/audio` | WAV fragmentu |
| GET | `/api/projects/{id}/audio` | połączone nagranie (WAV) |
| GET | `/api/projects/{id}/export?format=mp3\|wav` | eksport z normalizacją LUFS (409, jeśli są niewygenerowane fragmenty) |
| GET | `/api/projects/{id}/subtitles?format=srt\|vtt` | napisy |
| GET/POST | `/api/projects/{id}/segments/{sid}/versions`, `…/versions/{vid}/audio`, `…/versions/{vid}/restore` | wersje nagrania fragmentu |
| GET/POST/PUT/DELETE | `/api/pronunciation` | słownik wymowy (globalny i projektu) |
| POST | `/api/import/text` | tekst z pliku TXT/MD/DOCX/PDF i proponowany podział |
| GET | `/api/voices`, `/api/voices/{id}`, `/api/voices/{id}/sample` | biblioteka głosów, próbki |
| PUT | `/api/voices/{id}/favorite` | ulubione |
| POST | `/api/voices/design/preview`, `/api/voices/design` | projektowanie głosu |
| POST | `/api/voices/clone/analyze`, `/api/voices/clone` | analiza nagrania, klonowanie |
| GET/DELETE | `/api/history` | historia generowania |
| GET/PATCH | `/api/account`, GET `/api/account/usage` | profil, preferencje, eksport, wykorzystanie limitów |
| POST | `/api/auth/register`, `/login`, `/logout`, `/logout-all`, `/google` | konto i sesja (ciasteczko httpOnly) |
| GET | `/api/auth/me`, `/api/auth/config` | bieżący użytkownik, konfiguracja logowania |
| POST | `/api/auth/password`, `/password-reset`, `/password-reset/confirm`, `/delete-account` | hasło i usunięcie konta |
| POST/GET | `/api/projects/{id}/jobs`, `/jobs/active`, `/api/jobs/{id}/cancel` | kolejka generowania |
| GET (SSE) | `/api/projects/{id}/events` | zdarzenia: `job`, `segment`, `segment-error` |
| GET/POST | `/api/billing/config`, `/checkout`, `/portal`, `/webhook` | subskrypcje Stripe |
| GET/PUT | `/api/account/plans`, `/api/account/plan` | plany z limitami; zmiana planu bez płatności (tylko gdy `DEMO_PLAN_SWITCH=true`) |

## Testy

```bash
cd backend && mvn test
```

Backend ma 44 testy:

- integracyjne: logowanie, sesje, izolacja danych, CSRF, limit prób, webhook Stripe;
- funkcje edytora z atrapą silnika: głosy fragmentów, pauzy, słownik, wersje, napisy, eksport −16 LUFS, import;
- jednostkowe: pauzy i słownik, LUFS (sinus 1 kHz 0 dBFS = −3,01 LUFS), napisy, ekstrakcja DOCX/PDF/TXT.

```bash
cd tts-engine && pip install -r requirements-dev.txt && ENGINE_MODE=demo pytest
```

```bash
cd frontend && npx ng build
```

## Dalsze kroki

- Uruchomienie VoxCPM2 z prawdziwymi wagami na GPU i odsłuch jakości (adapter jest zweryfikowany z kodem biblioteki).
- Redis dla kanału zdarzeń i limitu logowań przy wielu instancjach backendu.
- Weryfikacja adresu e-mail przy rejestracji i opcjonalne 2FA.
- Testy end-to-end frontendu w CI (Playwright).
- OCR dla skanowanych PDF-ów i podgląd odsłuchu pojedynczego wpisu słownika.
