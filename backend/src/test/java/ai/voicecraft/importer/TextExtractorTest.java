package ai.voicecraft.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

class TextExtractorTest {

    @Test
    void decodesUtf8AndWindows1250() {
        String pl = "Zażółć gęślą jaźń.";
        assertThat(TextExtractor.extract("a.txt", pl.getBytes(StandardCharsets.UTF_8))).isEqualTo(pl);
        assertThat(TextExtractor.extract("a.txt", pl.getBytes(Charset.forName("windows-1250")))).isEqualTo(pl);
    }

    @Test
    void stripsMarkdown() {
        String md = "# Tytuł\n\nTo jest **ważny** [link](https://x.pl) i `kod`.\n\n- punkt";
        assertThat(TextExtractor.extract("a.md", md.getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("Tytuł\n\nTo jest ważny link i kod.\n\npunkt");
    }

    @Test
    void readsDocxParagraphs() throws Exception {
        String xml = "<?xml version=\"1.0\"?><w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
                + "<w:p><w:r><w:t>Pierwszy </w:t></w:r><w:r><w:t>akapit.</w:t></w:r></w:p>"
                + "<w:p><w:r><w:t>Drugi akapit.</w:t></w:r></w:p></w:body></w:document>";
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write(xml.getBytes(StandardCharsets.UTF_8));
        }
        assertThat(TextExtractor.extract("dok.docx", bytes.toByteArray())).isEqualTo("Pierwszy akapit.\n\nDrugi akapit.");
    }

    @Test
    void rejectsDocxWithExternalEntities() throws Exception {
        String xml = "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><w:document xmlns:w=\"w\"><w:p><w:t>&e;</w:t></w:p></w:document>";
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write(xml.getBytes(StandardCharsets.UTF_8));
        }
        assertThatThrownBy(() -> TextExtractor.extract("x.docx", bytes.toByteArray())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void readsPdfText() throws Exception {
        var out = new ByteArrayOutputStream();
        try (var doc = new PDDocument()) {
            var page = new PDPage();
            doc.addPage(page);
            try (var cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(72, 700);
                cs.showText("Hello from a PDF document.");
                cs.endText();
            }
            doc.save(out);
        }
        assertThat(TextExtractor.extract("a.pdf", out.toByteArray())).isEqualTo("Hello from a PDF document.");
    }

    @Test
    void joinsPdfLinesAndHyphenation() {
        assertThat(TextExtractor.joinPdfLines("To jest zda-\nnie łamane\nw linii.\n\nNowy akapit.")).isEqualTo("To jest zdanie łamane w linii.\n\nNowy akapit.\n\n");
    }

    @Test
    void rejectsUnknownFormats() {
        assertThatThrownBy(() -> TextExtractor.extract("a.exe", new byte[] {1})).hasMessageContaining("Nieobsługiwany");
    }
}
