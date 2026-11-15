# Głosy biblioteki — nagrania wzorcowe i próbki

Katalog głosów (21 głosów, 5 kategorii) jest w `backend/src/main/resources/voices/catalog.json`.

## `prompts/` — nagrania wzorcowe (Master Prompt)

Każdy głos ma jedno nagranie referencyjne z trybu Voice Design, np. `prompts/agnieszka_warm.wav`
(ścieżki jak w polu `reference` katalogu). Kilka głosów może dzielić jeden plik — np. „Ciepły Aksamit”
i „Ciepły Aksamit (Relaks)” różnią się tylko instrukcją sterującą.

- Plik jest wczytywany przy starcie backendu. Podmiana pliku = nowa barwa: referencja w silniku
  i wygenerowane próbki są tworzone od nowa.
- Brak pliku nie jest błędem: przy pierwszym użyciu z VoxCPM2 nagranie powstaje z opisu
  (`designPrompt`) ze stałym seedem i jest zapisywane w magazynie aplikacji.
- Wymagania: WAV mono, 10–30 s czystej mowy, 24 kHz lub więcej.

## `samples/` — gotowe próbki odsłuchowe (opcja B)

`samples/{voice_id}/{lang}.wav`, np. `samples/voice_deep_trailer/it.wav` — krótkie zdanie powitalne
w każdym z 30 języków. Taki plik jest odtwarzany natychmiast, bez wywołania silnika.
Gdy pliku nie ma, próbka jest generowana przy pierwszym odsłuchu i zapamiętywana (opcja A).

Komplet próbek (21 głosów × 30 języków) wygeneruje backend na maszynie z VoxCPM2:

```bash
PREGENERATE_SAMPLES=true docker compose up -d backend
docker compose logs -f backend   # postęp: „Próbki głosu … gotowe”
```

Istniejące pliki są pomijane, więc generowanie można przerwać i wznowić. Pliki `.wav` nie są
commitowane do repozytorium (`.gitignore`) — skopiuj katalog na serwer razem z aplikacją.
