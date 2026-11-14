# VoiceCraft AI

Aplikacja SaaS do generowania naturalnego głosu z tekstu (TTS). Po zalogowaniu użytkownik trafia od razu do edytora. Tekst dzieli się tam na fragmenty, które można generować, odsłuchiwać i poprawiać pojedynczo, a następnie połączyć w jedno nagranie MP3/WAV.

Silnikiem syntezy jest **VoxCPM2** (OpenBMB). Gdy model nie jest załadowany, aplikacja działa w **wyraźnie oznaczonym trybie demonstracyjnym**.

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
| **backend** | domena (projekty, fragmenty, głosy, historia, konto), wykrywanie zmian, łączenie fragmentów, eksport | szczegółach modelu (jeden klient: `EngineClient`) |
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

Otwórz http://localhost:4200. Konto demonstracyjne ma już wypełnione dane logowania.

Wszystko naraz w kontenerach:

```bash
docker compose up --build
```

## Podłączenie VoxCPM2

Na maszynie z GPU (CUDA):

```bash
cd tts-engine
pip install -r requirements-voxcpm.txt
ENGINE_MODE=voxcpm2 VOXCPM_MODEL_ID=openbmb/VoxCPM2 uvicorn app.main:app --host 0.0.0.0 --port 8808
```

Następnie wskaż adres backendowi: `ENGINE_URL=http://gpu-host:8808`.

- `ENGINE_MODE=auto` (domyślnie) próbuje załadować model, a gdy się nie uda, przechodzi w tryb demo. Powód jest widoczny w *Ustawienia → Silnik TTS*.
- `ENGINE_MODE=voxcpm2` wymaga modelu. Bez niego serwis nie wystartuje.
- Cała wiedza o bibliotece `voxcpm` siedzi w jednym pliku: `tts-engine/app/engines/voxcpm2.py`. Adapter sprawdza sygnaturę `VoxCPM.generate` i przekazuje tylko obsługiwane argumenty (`cfg_value`, `inference_timesteps`, `normalize`, `denoise`, `prompt_wav_path`, `prompt_text`…). Opis głosu i instrukcja stylu trafiają do modelu jako prefiks `(opis)tekst`, zgodnie z konwencją voice design VoxCPM2. Konwencję przełącza `VOXCPM_DESCRIPTION_FORMAT`. **Po aktualizacji modelu zweryfikuj ten plik z dokumentacją OpenBMB.**
- Fragmenty wygenerowane w trybie demo automatycznie dostają status *Nieaktualny* po podłączeniu prawdziwego modelu.

## Uczciwość trybu demonstracyjnego

- Syntezator demo **nie generuje mowy**. Tworzy sygnał zastępczy o rytmie mowy (sylaby, pauzy, intonacja), żeby dało się przetestować cały przepływ.
- Każda odpowiedź silnika niesie nagłówek `X-Engine-Mode`. Interfejs pokazuje baner trybu demo, odznaki **DEMO** przy fragmentach, próbkach, w odtwarzaczu i w historii. Eksportowane pliki dostają sufiks `_DEMO`.
- **Klonowanie** w trybie demo jest zablokowane: endpoint zwraca 503, a interfejs wyjaśnia dlaczego. Analiza nagrania (długość, SNR, clipping, głośność, cisza) działa naprawdę.
- Logowanie i płatności nie są podłączone. Widoki mówią o tym wprost, a odpowiednie przyciski są nieaktywne.

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

Zmiana planu (`PUT /api/account/plan`) działa w wersji demonstracyjnej bez płatności. Po integracji z operatorem płatności powinna być wywoływana z webhooka potwierdzającego zakup.

## Logowanie

- **Strona logowania** ma trzy tryby: logowanie, rejestracja (z miernikiem siły hasła) i reset hasła. Walidacja jest inline, a pole hasła ma przycisk pokaż/ukryj i opcję „Zapamiętaj mnie”.
- **Logowanie przez Google** działa w przepływie tokenu OAuth 2.0 Google Identity Services. Frontend pobiera token, a backend (`POST /api/auth/google`) weryfikuje go w Google: sprawdza, czy został wydany dla naszego klienta (`aud`) i czy e-mail jest zweryfikowany. Konfiguracja:
  1. W Google Cloud Console utwórz identyfikator klienta OAuth typu „Aplikacja internetowa”.
  2. Dodaj autoryzowane źródło JavaScript, np. `http://localhost:4200`.
  3. Uruchom backend z `GOOGLE_CLIENT_ID=<id>.apps.googleusercontent.com`.

  Bez tej zmiennej przycisk wyjaśnia, że logowanie przez Google nie jest skonfigurowane.
- Logowanie e-mailem jest nadal lokalne (wersja demonstracyjna).

## Logo

Propozycje znaku są w `frontend/src/app/shared/brand/logo-marks.ts`, a ich pliki SVG w `frontend/public/brand/`:

| | Wariant | Idea |
|---|---|---|
| A | **Fala V** (domyślny) | pięć pasków korektora układa się w literę V |
| B | Kursor w głos | kursor tekstowy przechodzący w falę |
| C | Dymek | dymek rozmowy z falą |
| D | Sygnał | okrąg przecięty sinusoidą |

Zmiana logo w całej aplikacji: ustaw `BRAND_LOGO` w `logo-marks.ts` i podmień `public/favicon.svg` na odpowiedni plik z `public/brand/`.

## Kluczowe zachowanie edytora

- Każdy fragment ma odcisk: SHA-256 z tekstu, głosu, języka, stylu, emocji, tempa, ekspresji, CFG, kroków, normalizacji, odszumiania, ziarna oraz trybu silnika. Zmiana tekstu jednego zdania oznacza jako *Nieaktualny* tylko ten fragment. Zmiana parametrów projektu oznacza wszystkie.
- Przycisk **„Generuj zmienione (n)”** generuje wyłącznie nowe i nieaktualne fragmenty. Można też zaznaczyć wybrane fragmenty albo ponowić pojedynczy (`Ctrl+Enter`).
- Łączenie fragmentów w jedno nagranie odbywa się w backendzie bez ponownej syntezy (`WavUtils.concat`), z konfigurowalną pauzą.
- Każda generacja zapisuje nowy, niezmienny plik. Historia pozwala odsłuchać także zastąpione wersje.
- Skróty: `Enter` dzieli fragment w miejscu kursora, `Backspace` na początku łączy go z poprzednim, `Shift+Enter` wstawia nową linię, `Ctrl+Shift+Enter` generuje zmienione, `Ctrl+S` zapisuje, `Spacja` to play/pauza.
- Wklejenie dłuższego tekstu dzieli go automatycznie na akapity, a zbyt długie akapity na grupy zdań (do ~400 znaków).

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
| PUT | `/api/projects/{id}/segments` | zapis listy fragmentów `[{id, text}]` |
| POST | `/api/projects/{id}/segments/{sid}/generate` | generowanie jednego fragmentu |
| GET | `/api/projects/{id}/segments/{sid}/audio` | WAV fragmentu |
| GET | `/api/projects/{id}/audio` | połączone nagranie (WAV) |
| GET | `/api/projects/{id}/export?format=mp3\|wav` | eksport (409, jeśli są niewygenerowane fragmenty) |
| GET | `/api/voices`, `/api/voices/{id}`, `/api/voices/{id}/sample` | biblioteka głosów, próbki |
| PUT | `/api/voices/{id}/favorite` | ulubione |
| POST | `/api/voices/design/preview`, `/api/voices/design` | projektowanie głosu |
| POST | `/api/voices/clone/analyze`, `/api/voices/clone` | analiza nagrania, klonowanie |
| GET/DELETE | `/api/history` | historia generowania |
| GET/PATCH | `/api/account`, GET `/api/account/usage` | profil, preferencje, eksport, wykorzystanie limitów |
| GET/POST | `/api/auth/config`, `/api/auth/google` | konfiguracja logowania, weryfikacja tokenu Google |
| GET/PUT | `/api/account/plans`, `/api/account/plan` | plany z limitami, zmiana planu (demo) |

## Testy

```bash
cd backend && mvn test
```

```bash
cd tts-engine && pip install -r requirements-dev.txt && ENGINE_MODE=demo pytest
```

```bash
cd frontend && npx ng build
```

## Dalsze kroki

- Uwierzytelnianie: Spring Security + OAuth2/JWT. Teraz jest jeden lokalny użytkownik.
- PostgreSQL zamiast H2 i magazyn obiektów (S3) dla plików audio.
- Kolejka zadań (np. Redis) i strumieniowanie postępu przez SSE przy długich projektach.
- Integracja płatności (np. Stripe) wywołująca zmianę planu z webhooka.
