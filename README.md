# Narrivy

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

Wszystko naraz w kontenerach (PostgreSQL, silnik, backend w profilu `prod`, frontend). Nagrania trafiają domyślnie na wolumen backendu; S3 włącza się przez `STORAGE_TYPE=s3` i zmienne `S3_*`:

```bash
cp .env.example .env
```

```bash
docker compose up --build
```

W pliku `.env` uzupełnij co najmniej `POSTGRES_PASSWORD` i `JWT_SECRET` (min. 32 znaki).

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

### Spójny głos: nagrania wzorcowe

Projektowanie głosu z samego opisu daje przy każdym wywołaniu nieco inną barwę, więc fragmenty jednego projektu mogłyby brzmieć jak różne osoby. Dlatego z prawdziwym modelem każdy głos ma **jedno nagranie wzorcowe**, a każdy fragment jest syntezowany przez klonowanie z tego nagrania (`reference_wav_path`, a przy znanej transkrypcji także `prompt_wav_path` + `prompt_text`). Logika jest w `VoiceAnchorService`.

| Głos | Nagranie wzorcowe |
|---|---|
| Z biblioteki | Generowane raz przy pierwszym użyciu, ze stałym seedem wynikającym z identyfikatora głosu. Ono staje się też próbką w bibliotece. |
| Zaprojektowany | Wariant wybrany podczas projektowania. Głos w projekcie brzmi dokładnie jak w podglądzie. |
| Sklonowany | Przesłane nagranie. |

- Nagranie jest przechowywane w magazynie aplikacji (`voices.reference_file`), nie tylko w silniku.
- Gdy silnik nie zna referencji (np. nowy kontener bez danych), zwraca `409 REFERENCE_MISSING:<id>`. Backend rejestruje wtedy nagranie ponownie pod tym samym identyfikatorem i powtarza syntezę.
- W trybie demo głos opisuje wyłącznie opis — tryb demo nie obsługuje nagrań referencyjnych.
- **Nie zostało to odsłuchane na prawdziwych wagach** (brak GPU w środowisku deweloperskim). Przetestowano przepływ z atrapą silnika i adapter z atrapą biblioteki o identycznej sygnaturze.

### VoxCPM2 na wynajętym GPU (aplikacja na własnym komputerze)

1. Wynajmij maszynę z GPU (np. RunPod lub Vast.ai, karta z ≥ 16 GB VRAM, obraz PyTorch + CUDA 12) i wystaw port HTTP 8808.
2. Skopiuj na nią katalog `tts-engine` i uruchom: `ENGINE_API_KEY=<długi-losowy-klucz> bash tts-engine/scripts/run-gpu.sh`.
3. W `.env` aplikacji ustaw `ENGINE_URL=https://<adres-maszyny>` i ten sam `ENGINE_API_KEY`, potem `docker compose up -d backend`.

Silnik z ustawionym `ENGINE_API_KEY` odrzuca żądania bez nagłówka `Authorization: Bearer <klucz>`, więc obcy nie skorzystają z wynajętego GPU.

## Biblioteka głosów i próbki w 30 językach

Biblioteka to 21 głosów w 5 kategoriach (Audiobooki i Opowieści, Social Media i Marketing, Media i Biznes, Gry i Animacje, Relaks i Medytacja). Źródłem jest `backend/src/main/resources/voices/catalog.json`: identyfikator, nazwa, persona, kategoria, plik wzorcowy (`reference`), opis Voice Design (`designPrompt`) i opcjonalna domyślna instrukcja (`instruction`, np. wariant „Relaks”). Przy starcie backend synchronizuje bibliotekę z katalogiem, a dawne głosy (Anna, Tomasz…) przepina na najbliższe nowe w projektach, fragmentach i ulubionych.

- **Jedno nagranie wzorcowe, 30 języków:** każdy głos ma jeden Master Prompt (`voices/prompts/…wav`). Synteza używa go jako `reference_wav_path` z `ultimate_cloning=false`: model zachowuje barwę, a akcent jest natywny dla języka docelowego. „Ultimate cloning” (dodatkowo `prompt_wav_path` + `prompt_text`) można włączyć w ustawieniach zaawansowanych projektu, gdy tekst jest w języku nagrania.
- **Emocja = `control_instruction`:** gotowe ustawienia (Neutral, Happy, Whisper, Calm…) i własny opis stylu trafiają do VoxCPM2 jako prefiks `(instrukcja)tekst`. Domyślna instrukcja głosu działa, gdy projekt nie ustawia stylu ani emocji.
- **Próbki w wybranym języku:** w bibliotece głosów wybierasz „Język próbek”, a przycisk odtwarzania każdego głosu mówi krótkie zdanie w tym języku. Źródło próbki, w tej kolejności:
  1. gotowy plik `voices/samples/{voice_id}/{lang}.wav` — odtwarzany natychmiast (opcja B);
  2. próbka wygenerowana wcześniej i zapamiętana;
  3. generowanie przy pierwszym odsłuchu (opcja A), wynik jest zapamiętywany.

  Komplet plików (21 × 30) generuje `PREGENERATE_SAMPLES=true` na maszynie z VoxCPM2 — zob. `voices/README.md`. Próbki nie zużywają limitu znaków.
- **API:** `GET /api/voices/categories`, `GET /api/voices/preview-languages` (30 języków ze zdaniem próbki), `GET /api/voices/{id}/preview?lang=it` (nagłówek `X-Preview-Source: static|cache|generated`).
- Zdania próbek w rzadszych językach (np. birmański, khmerski, laotański) warto sprawdzić z native speakerem (`PreviewTexts.java`).

## Bez GPU: silnik Piper

Do testów na komputerze bez karty graficznej służy lekki silnik [Piper](https://github.com/OHF-Voice/piper1-gpl). Działa na zwykłym procesorze i generuje **prawdziwą mowę**.

```bash
# .env
ENGINE_MODE=piper
docker compose up -d --build tts-engine
```

- Głosy (~60 MB każdy) pobierają się przy pierwszym użyciu z `huggingface.co/rhasspy/piper-voices` do wolumenu silnika i dalej działają offline. Można je pobrać przy starcie: `PIPER_PRELOAD=pl_PL-gosia-medium,pl_PL-darkman-medium`.
- Głosy z biblioteki Narrivy są mapowane na głos Piper według języka i płci (`tts-engine/app/engines/piper.py`). Po polsku są dwie barwy — kobieca i męska — więc np. Anna, Julia i Zofia brzmią tak samo.
- Piper **nie projektuje głosu z opisu i nie klonuje** — te funkcje są w interfejsie zablokowane z wyjaśnieniem. Działa tempo i ekspresja.
- W trybie `auto` kolejność to VoxCPM2 → Piper → demo.
- Licencja pakietu `piper-tts` to GPL-3.0 (działa jako osobny serwis). Głosy mają własne licencje — sprawdź je przed użyciem komercyjnym.
- Adapter jest zweryfikowany z API `piper-tts` 1.8 i ma testy na atrapie; w środowisku deweloperskim nie dało się pobrać modeli głosów.

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

Zużycie znaków i minut jest liczone z osobnej tabeli `usage_events`, której użytkownik nie może usunąć. „Wyczyść historię” usuwa wpisy i stare wersje nagrań, ale **nie zeruje limitu**. Migracja V3 przeniosła dotychczasowe zużycie z historii.

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
- **Potwierdzenie adresu e-mail:** po rejestracji wysyłany jest jednorazowy link ważny 48 godzin (w bazie tylko skrót SHA-256). Do potwierdzenia konto może się logować i edytować, ale nie generuje audio ani nie klonuje głosu (HTTP 403, `code: "EMAIL_NOT_VERIFIED"`). Na górze aplikacji jest baner z ponowną wysyłką (najwyżej raz na minutę). Zmiana adresu w ustawieniach wymaga ponownego potwierdzenia. Konta Google są potwierdzone przez Google. Wyłączenie: `REQUIRE_EMAIL_VERIFICATION=false`.
- **Rejestracja:** wymaga akceptacji regulaminu i polityki prywatności (zapisywane są data i wersja dokumentów). Z jednego adresu IP można założyć najwyżej 5 kont na godzinę.
- **Usunięcie konta:** wymaga hasła i trwale usuwa projekty, głosy, historię, zużycie oraz zadania. Przy aktywnej subskrypcji trzeba ją najpierw anulować. Rejestr zgód na klonowanie jest zachowywany (zob. niżej).
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
- **Nagrania:** `STORAGE_TYPE=local` (katalog danych) albo `STORAGE_TYPE=s3`. S3 działa z AWS, Cloudflare R2, Backblaze B2 i innymi usługami zgodnymi z S3 (MinIO nie publikuje już obrazów w Docker Hub, dlatego nie ma go w docker-compose). Ustawia się je przez `S3_BUCKET`, `S3_REGION`, `S3_ENDPOINT`, `S3_ACCESS_KEY`, `S3_SECRET_KEY` i `S3_PATH_STYLE`. Bucket jest tworzony automatycznie, jeśli nie istnieje.

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

## Projekty i foldery

Strona projektów ma dwa widoki (wybór jest zapamiętywany):

- **Foldery** — jak eksplorator plików: kafelki folderów i projektów. Projekt przenosisz, przeciągając go na folder (albo na kafelek „Wszystkie projekty”, żeby wyjąć go z folderu), lub z menu projektu → „Przenieś do”. Nowy projekt utworzony wewnątrz folderu od razu do niego trafia. Wyszukiwanie przeszukuje wszystkie foldery.
- **Lista** — wszystkie projekty w jednej tabeli z kolumną „Folder”.

Puste projekty nie są zachowywane: projekt bez żadnego tekstu (i nigdy niegenerowany) jest usuwany po wyjściu z edytora, a nocne sprzątanie usuwa takie projekty pozostawione dłużej niż dobę (np. po zamknięciu karty).

Foldery mają jeden poziom (bez folderów w folderach), najwyżej 100 na konto. Usunięcie folderu nie usuwa projektów — wracają do „Wszystkich projektów”. API: `GET/POST /api/folders`, `PATCH/DELETE /api/folders/{id}`, `PUT /api/projects/{id}/folder`.

## Zgodność z prawem i porządek danych

- **Oznaczanie treści AI (AI Act, art. 50 ust. 2):** eksportowane pliki i nagrania pobierane z historii mają oznaczenie w metadanych. WAV dostaje fragment `LIST/INFO` (`ICMT`, `ISFT`, `IGNR`, `ICRD`, `INAM`), a MP3 znacznik ID3v2.3 (`TXXX:AI_GENERATED=true`, `TXXX:AI_GENERATOR`, `COMM`, `TSSE`). Odpowiedź ma nagłówek `X-AI-Generated: true`, a menu eksportu o tym informuje. Metadane da się usunąć przy ponownym zapisie pliku — docelowo warto dodać znak wodny w sygnale audio (np. AudioSeal) lub manifest C2PA.
- **Rejestr zgód na klonowanie:** przy tworzeniu klonu zapisywane są treść i wersja oświadczenia, data, adres IP, przeglądarka, nazwa pliku i skrót SHA-256 nagrania (`voice_consents`). Treść oświadczenia pochodzi z backendu (`GET /api/voices/clone/consent`); nieaktualna wersja jest odrzucana (409). Zgoda jest widoczna w szczegółach głosu i zachowywana po usunięciu głosu lub konta (art. 17 ust. 3 lit. e RODO).
- **Regulamin i polityka prywatności** z informacją o cookies: publiczne strony `/terms` i `/privacy`. Aplikacja używa tylko niezbędnego ciasteczka sesji i localStorage (motyw, głośność), więc nie potrzebuje banera zgody. **Treść to szablon opisujący rzeczywiste działanie aplikacji — przed startem trzeba uzupełnić dane usługodawcy i dać ją do przeglądu prawnikowi.** Wersja dokumentów: `LegalDocuments.TERMS_VERSION` (backend) i `LEGAL_VERSION` (frontend).
- **Eksport danych (RODO art. 15 i 20):** *Ustawienia → Konto → Pobierz moje dane* zwraca archiwum ZIP: dane w JSON (konto, projekty, głosy, historia, zużycie, słownik, zgody) oraz nagrania fragmentów i nagrania wzorcowe własnych głosów.
- **Automatyczne sprzątanie** (`CleanupService`, codziennie o 3:30, `CLEANUP_CRON`):
  - usuwa historię starszą niż okres przechowywania planu właściciela;
  - usuwa pliki audio, do których nic już nie prowadzi (stare wersje fragmentów, usunięte projekty i głosy, niezapisane podglądy), z zapasem 24 godzin na pliki zapisywane w danej chwili;
  - usuwa wygasłe tokeny resetu hasła i potwierdzenia e-mail.

  Działa z dyskiem lokalnym i z S3.

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

- **Kolory:** monochrom. Tło jest czystą bielą `#FFFFFF`, sidebar i pola pomocnicze mają neutralną szarość `#F6F6F5`. Czerń `#0A0A0A` to kolor akcji: przyciski główne, zaznaczenia, suwaki, przełączniki, postęp i waveform. Kolor pojawia się tylko tam, gdzie niesie informację: statusy (zielony, bursztynowy, czerwony), tryb demo i znaczniki mówców.
- **Tryb ciemny:** odwrócony monochrom, czyli tło `#0F0F0F` i jasne przyciski. Komunikaty (toasty) są zawsze ciemne.
- **Typografia:** Onest Variable (pełne polskie znaki). Tytuły mają wagę 600 i ciaśniejsze odstępy (`--display-weight`, `--display-tracking`). Skala 11–32 px, cyfry tabelaryczne dla czasów i liczników. Linki w treści są podkreślone, bo nie odróżniają się kolorem.
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
| POST | `/api/voices/clone/analyze`, `/api/voices/clone` | analiza nagrania, klonowanie (z wersją oświadczenia o zgodzie) |
| GET | `/api/voices/clone/consent`, `/api/voices/{id}/consent` | treść oświadczenia; zapisana zgoda klonu |
| GET/DELETE | `/api/history` | historia generowania |
| GET/PATCH | `/api/account`, GET `/api/account/usage` | profil, preferencje, eksport, wykorzystanie limitów |
| GET | `/api/account/export` | eksport danych konta (ZIP) |
| POST | `/api/auth/verify-email`, `/api/auth/verify-email/resend` | potwierdzenie adresu e-mail |
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

Backend ma 55 testów:

- integracyjne: logowanie, sesje, izolacja danych, CSRF, limit prób, webhook Stripe;
- zgodność: licznik zużycia odporny na czyszczenie historii, nagrania wzorcowe i ich odtwarzanie po utracie w silniku, oznaczenie AI w eksporcie, rejestr zgód, eksport danych ZIP, sprzątanie, potwierdzenie e-mail, limit rejestracji;
- funkcje edytora z atrapą silnika: głosy fragmentów, pauzy, słownik, wersje, napisy, eksport −16 LUFS, import;
- jednostkowe: pauzy i słownik, LUFS (sinus 1 kHz 0 dBFS = −3,01 LUFS), napisy, ekstrakcja DOCX/PDF/TXT, metadane AI w WAV i MP3.

```bash
cd tts-engine && pip install -r requirements-dev.txt && ENGINE_MODE=demo pytest
```

```bash
cd frontend && npx ng build
```

## Dalsze kroki

- Uruchomienie VoxCPM2 z prawdziwymi wagami na GPU i odsłuch jakości, w tym spójności głosu z nagrań wzorcowych (adapter jest zweryfikowany z kodem biblioteki).
- Znak wodny w sygnale audio (oprócz metadanych) i przegląd prawny regulaminu oraz polityki prywatności.
- Redis dla kanału zdarzeń i limitu logowań przy wielu instancjach backendu.
- Opcjonalne 2FA.
- Testy end-to-end frontendu w CI (Playwright).
- OCR dla skanowanych PDF-ów i podgląd odsłuchu pojedynczego wpisu słownika.
