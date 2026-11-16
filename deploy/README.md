# Wdrożenie Narrivy (produkcja)

Dwa serwery:

| | Co działa | Przykład |
|---|---|---|
| **Serwer aplikacji** | Caddy (HTTPS), frontend, backend, PostgreSQL | Hetzner Cloud CPX21–CPX31 (ok. 10–20 €/mies.), Ubuntu 24.04 |
| **Serwer GPU** | silnik mowy VoxCPM2 | Hetzner GEX (stały) **albo** RunPod (na godziny) |

Nagrania trafiają do **Cloudflare R2** (instrukcja w głównym README, sekcja „Cloudflare R2”).

## Jak silnik jest zabezpieczony

Z silnika może korzystać wyłącznie Twój backend:

1. **Klucz** — każde żądanie wymaga `Authorization: Bearer <ENGINE_API_KEY>` (porównanie odporne na ataki czasowe).
   W produkcji (`ENGINE_REQUIRE_KEY=true`) silnik **nie wystartuje** bez klucza lub z kluczem krótszym niż 32 znaki.
2. **Sieć** — silnik słucha tylko na `127.0.0.1` (albo na adresie sieci prywatnej). Z internetu prowadzi do niego
   wyłącznie Caddy, który **wpuszcza tylko adres IP serwera aplikacji** (inne adresy dostają 403).
3. **Szyfrowanie** — połączenie backend → silnik idzie przez HTTPS (certyfikat Let's Encrypt) albo przez sieć prywatną.
4. **Dodatkowo** — wyłączona dokumentacja `/docs`, limit rozmiaru żądania (30 MB), ostrzeżenia w logu backendu,
   gdy silnik jest skonfigurowany bez klucza lub bez szyfrowania.

## Krok 1. Sekrety

Na swoim komputerze:

```bash
openssl rand -hex 32        # ENGINE_API_KEY — ten sam na obu serwerach
openssl rand -base64 48     # JWT_SECRET
openssl rand -base64 24     # POSTGRES_PASSWORD
```

## Krok 2. Serwer aplikacji (Hetzner Cloud)

1. Utwórz serwer Ubuntu 24.04 z kluczem SSH. W DNS domeny dodaj rekord **A**: `app.twoja-domena.pl` → IP serwera.
2. Na serwerze:
   ```bash
   git clone <adres-repozytorium> aplikacja && cd aplikacja
   bash deploy/server-setup.sh app
   cp deploy/app/.env.example deploy/app/.env && nano deploy/app/.env
   ```
   Uzupełnij: `DOMAIN`, `ACME_EMAIL`, sekrety z kroku 1, `ENGINE_URL` (krok 3), dane R2 i SMTP.
3. Uruchom (i każdą kolejną aktualizację):
   ```bash
   bash deploy/deploy.sh app
   ```
   Po minucie aplikacja działa pod `https://app.twoja-domena.pl` z ważnym certyfikatem.
4. Kopie bazy co noc: `crontab -e` → `15 3 * * * /root/aplikacja/deploy/app/backup.sh >> /var/log/narrivy-backup.log 2>&1`

## Krok 3a. Silnik na Hetznerze (serwer GPU, stała opłata)

1. Zamów serwer GPU (seria GEX) z Ubuntu 24.04. W DNS dodaj rekord **A**: `engine.twoja-domena.pl` → IP serwera GPU.
2. Na serwerze GPU:
   ```bash
   git clone <adres-repozytorium> aplikacja && cd aplikacja
   bash deploy/server-setup.sh engine && reboot
   # po restarcie:
   nvidia-smi                                   # powinna być widoczna karta
   cp deploy/engine/.env.example deploy/engine/.env && nano deploy/engine/.env
   ```
   Uzupełnij: `ENGINE_API_KEY`, `ENGINE_DOMAIN=engine.twoja-domena.pl`, `APP_SERVER_IP` (publiczne IP serwera aplikacji), `ACME_EMAIL`.
3. `bash deploy/deploy.sh engine` — pierwsze uruchomienie buduje obraz i pobiera model (kilka GB, kilka–kilkanaście minut).
4. Na serwerze aplikacji w `deploy/app/.env`: `ENGINE_URL=https://engine.twoja-domena.pl`, potem `bash deploy/deploy.sh app`.
5. W panelu Hetznera (Robot → Firewall) dla serwera GPU zezwól na 22 (SSH) oraz 80/443 — 443 najlepiej tylko z IP serwera aplikacji
   (port 80 jest potrzebny do wystawienia certyfikatu).

Wariant z siecią prywatną (vSwitch / Cloud Network): ustaw `ENGINE_BIND=<prywatny IP serwera GPU>` w `deploy/engine/.env`
(Caddy nie jest wtedy uruchamiany), a w aplikacji `ENGINE_URL=http://<prywatny IP>:8808`.

## Krok 3b. Silnik na RunPod (na godziny — testy, początek)

1. RunPod → **Secure Cloud** (nie Community), region w UE, GPU RTX 4090, szablon *PyTorch* (CUDA 12), dysk ≥ 30 GB,
   w *Expose HTTP Ports* wpisz `8808`.
2. W terminalu poda:
   ```bash
   git clone <adres-repozytorium> aplikacja && cd aplikacja
   ENGINE_API_KEY=<klucz z kroku 1> bash tts-engine/scripts/run-gpu.sh
   ```
3. W aplikacji: `ENGINE_URL=https://<ID_PODA>-8808.proxy.runpod.net` i ten sam `ENGINE_API_KEY`, potem `bash deploy/deploy.sh app`.

RunPod przyjmuje ruch przez własne proxy HTTPS — ochroną jest tu klucz (silnik bez niego nie wystartuje).
Pamiętaj, że pod naliczany jest za każdą godzinę działania — zatrzymuj go, gdy nie testujesz.

## Krok 3c. Silnik na Modal (darmowa miesięczna pula — testy)

Modal uruchamia kartę tylko na czas generowania i nalicza sekundy pracy z miesięcznej puli planu Starter (ok. 30 $).

1. Załóż konto na modal.com (plan **Starter**).
2. Na swoim komputerze, w katalogu repozytorium:
   ```bash
   pip install modal && modal setup
   modal secret create narrivy-engine ENGINE_API_KEY=$(openssl rand -hex 32)
   modal deploy deploy/modal/narrivy_engine.py
   ```
   Modal wypisze adres `https://…modal.run`. Klucz podejrzysz w panelu Modal → *Secrets → narrivy-engine*.
3. W `.env` aplikacji: `ENGINE_URL=<adres z modal.run>`, `ENGINE_API_KEY=<klucz>`, **`ENGINE_HEALTH_TTL=30m`**, potem `docker compose up -d backend`.

Jak oszczędzać pulę: karta wyłącza się po 2 minutach bez żądań (`SCALEDOWN_S` w pliku), aplikacja nie odpytuje silnika z kart
przeglądarki w tle, a `ENGINE_HEALTH_TTL=30m` sprawia, że samo sprawdzanie stanu rzadko budzi kartę. Pierwsze generowanie
po przerwie trwa dłużej (start kontenera i wczytanie modelu; przy pierwszym uruchomieniu także pobranie modelu) —
jeśli zakończy się błędem, po prostu spróbuj ponownie po chwili. Zużycie sprawdzisz w panelu Modal → *Usage*.

## Krok 4. Sprawdzenie

- `https://app.twoja-domena.pl` — rejestracja, e-mail z potwierdzeniem, wygenerowanie fragmentu.
- Ustawienia → Silnik TTS: tryb **voxcpm2** (nie *demo*).
- Na serwerze aplikacji: `cd deploy/app && docker compose logs backend | grep -i "silnik\|magazyn"` — bez ostrzeżeń o kluczu i szyfrowaniu.
- Z innego komputera: `curl https://engine.twoja-domena.pl/health` → **403** (silnik niedostępny dla nikogo poza aplikacją).

## Płatności Stripe (gdy będziesz gotowy)

Webhook: `https://app.twoja-domena.pl/api/billing/webhook` ze zdarzeniami opisanymi w głównym README (sekcja „Płatności”).
