package ai.voicecraft.importer;

import ai.voicecraft.project.SpeechText;
import ai.voicecraft.project.TextSplitter;
import ai.voicecraft.security.CurrentUser;
import java.io.IOException;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** Import tekstu z pliku. Zwraca tekst i proponowany podział na fragmenty — projekt tworzy klient. */
@RestController
@RequestMapping("/api/import")
public class ImportController {
    static final long MAX_FILE = 10L * 1024 * 1024;
    static final int MAX_CHARS = 500_000;

    public record ImportedText(String title, String text, int characters, List<String> segments) {}

    @PostMapping(value = "/text", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ImportedText importText(@RequestPart("file") MultipartFile file) throws IOException {
        CurrentUser.id();
        if (file.isEmpty()) throw new IllegalArgumentException("Plik jest pusty.");
        if (file.getSize() > MAX_FILE) throw new IllegalArgumentException("Plik jest za duży (maks. 10 MB).");
        String text = TextExtractor.extract(file.getOriginalFilename(), file.getBytes());
        if (text.isBlank()) throw new IllegalArgumentException("Nie znaleziono tekstu w pliku. Skanowane PDF wymagają rozpoznawania tekstu (OCR).");
        if (text.length() > MAX_CHARS) throw new IllegalArgumentException("Tekst jest za długi (maks. 500 000 znaków). Podziel plik na mniejsze części.");
        String name = file.getOriginalFilename() == null ? "Import" : file.getOriginalFilename().replaceAll("\\.[^.]+$", "").replace('_', ' ').trim();
        return new ImportedText(name.isEmpty() ? "Import" : name, text, SpeechText.spokenCharacters(text),
                TextSplitter.segments(text, TextSplitter.MAX_SEGMENT));
    }
}
