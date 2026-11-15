package ai.voicecraft.importer;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;

/**
 * Wyciąganie czystego tekstu z plików TXT, MD, DOCX i PDF. Akapity rozdzielone pustą linią
 * (tak jak oczekuje podział na fragmenty).
 */
public final class TextExtractor {
    static final long MAX_UNZIPPED = 30L * 1024 * 1024;

    private TextExtractor() {}

    public static String extract(String filename, byte[] data) {
        String ext = extension(filename);
        String text = switch (ext) {
            case "txt" -> decode(data);
            case "md", "markdown" -> stripMarkdown(decode(data));
            case "docx" -> docx(data);
            case "pdf" -> pdf(data);
            default -> throw new IllegalArgumentException("Nieobsługiwany format pliku. Użyj TXT, MD, DOCX lub PDF.");
        };
        return normalize(text);
    }

    static String extension(String filename) {
        if (filename == null) return "";
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** UTF-8 (z BOM lub bez); gdy plik nie jest poprawnym UTF-8 — Windows-1250 (polskie pliki z Notatnika). */
    static String decode(byte[] data) {
        int offset = data.length >= 3 && (data[0] & 0xff) == 0xEF && (data[1] & 0xff) == 0xBB && (data[2] & 0xff) == 0xBF ? 3 : 0;
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data, offset, data.length - offset)).toString();
        } catch (CharacterCodingException e) {
            return new String(data, Charset.forName("windows-1250"));
        }
    }

    static String stripMarkdown(String md) {
        return md.replaceAll("(?s)```.*?```", " ")
                .replaceAll("(?m)^[ \\t]{0,3}#{1,6}[ \\t]*", "")
                .replaceAll("(?m)^[ \\t]{0,3}>[ \\t]?", "")
                .replaceAll("(?m)^[ \\t]*[-*+][ \\t]+", "")
                .replaceAll("!\\[[^]]*]\\([^)]*\\)", "")
                .replaceAll("\\[([^]]+)]\\([^)]*\\)", "$1")
                .replaceAll("(\\*\\*|__|\\*|_|`)", "");
    }

    /** DOCX = ZIP z word/document.xml. Parser XML bez DTD i encji zewnętrznych (ochrona przed XXE). */
    static String docx(byte[] data) {
        try (var zip = new ZipInputStream(new ByteArrayInputStream(data))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if ("word/document.xml".equals(entry.getName())) return docxXml(limited(zip));
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Plik DOCX jest uszkodzony.");
        }
        throw new IllegalArgumentException("Plik nie jest poprawnym dokumentem DOCX.");
    }

    private static InputStream limited(InputStream in) throws IOException {
        byte[] buf = in.readNBytes((int) MAX_UNZIPPED + 1);
        if (buf.length > MAX_UNZIPPED) throw new IllegalArgumentException("Dokument jest za duży.");
        return new ByteArrayInputStream(buf);
    }

    private static String docxXml(InputStream xml) {
        XMLInputFactory f = XMLInputFactory.newFactory();
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        var out = new StringBuilder();
        try {
            XMLStreamReader r = f.createXMLStreamReader(xml);
            boolean inText = false;
            while (r.hasNext()) {
                int ev = r.next();
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    switch (r.getLocalName()) {
                        case "t" -> inText = true;
                        case "tab" -> out.append(' ');
                        case "br", "cr" -> out.append('\n');
                        default -> { }
                    }
                } else if (ev == XMLStreamConstants.END_ELEMENT) {
                    if ("t".equals(r.getLocalName())) inText = false;
                    if ("p".equals(r.getLocalName())) out.append("\n\n");
                } else if (ev == XMLStreamConstants.CHARACTERS && inText) {
                    out.append(r.getText());
                }
            }
        } catch (XMLStreamException e) {
            throw new IllegalArgumentException("Nie udało się odczytać treści dokumentu DOCX.");
        }
        return out.toString();
    }

    static String pdf(byte[] data) {
        try (PDDocument doc = Loader.loadPDF(data)) {
            var stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            stripper.setParagraphEnd("\n\n");
            String raw = stripper.getText(doc);
            return joinPdfLines(raw);
        } catch (InvalidPasswordException e) {
            throw new IllegalArgumentException("PDF jest zabezpieczony hasłem.");
        } catch (IOException e) {
            throw new IllegalArgumentException("Plik PDF jest uszkodzony lub nie zawiera tekstu.");
        }
    }

    /** Łączy linie łamane w PDF w zdania; usuwa podział wyrazów z dywizem na końcu linii. */
    static String joinPdfLines(String raw) {
        var paragraphs = raw.replace("\r\n", "\n").split("\n\\s*\n");
        var out = new StringBuilder();
        for (String p : paragraphs) {
            String joined = p.replaceAll("(\\p{L})-\\n(\\p{Ll})", "$1$2").replaceAll("\\s*\\n\\s*", " ").trim();
            if (!joined.isEmpty()) out.append(joined).append("\n\n");
        }
        return out.toString();
    }

    static String normalize(String text) {
        return text.replace("\r\n", "\n").replace(' ', ' ')
                .replaceAll("[\\t\\x0B\\f]+", " ")
                .replaceAll("[ ]{2,}", " ")
                .replaceAll("\\n[ ]+", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }
}
