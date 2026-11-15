package ai.voicecraft.audio;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Oznaczenie pliku jako treści wygenerowanej przez AI (AI Act, art. 50 ust. 2 — oznaczenie
 * w formacie nadającym się do odczytu maszynowego).
 *
 * <ul>
 *   <li>WAV — fragment LIST/INFO (ICMT, ISFT, ICRD, INAM, IGNR),</li>
 *   <li>MP3 — znacznik ID3v2.3 (TIT2, TSSE, COMM, TXXX:AI_GENERATED, TXXX:AI_GENERATOR).</li>
 * </ul>
 * Metadane można usunąć przy ponownym zapisie pliku — docelowo uzupełni je znak wodny w sygnale audio.
 */
public final class AiContentLabel {
    private AiContentLabel() {}

    public static final String SOFTWARE = "VoiceCraft AI";

    /** @param generator model, który wygenerował mowę (np. "VoxCPM2"), @param title nazwa projektu */
    public record Label(String title, String generator, LocalDate date) {
        public String comment() {
            return "Treść wygenerowana przez AI (syntetyczny głos). AI-generated speech. Generator: " + SOFTWARE
                    + " / " + generator + ", " + date + ".";
        }
    }

    public static Label of(String title, String engineMode) {
        String gen = "demo".equals(engineMode) ? "tryb demonstracyjny (sygnał zastępczy)" : "VoxCPM2";
        return new Label(title, gen, LocalDate.now());
    }

    // ------------------------------------------------------------------ WAV

    /** Wstawia (lub zastępuje) fragment LIST/INFO przed fragmentem danych. */
    public static byte[] wav(byte[] wav, Label label) {
        ByteBuffer in = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        if (wav.length < 12 || !"RIFF".equals(ascii(wav, 0)) || !"WAVE".equals(ascii(wav, 8))) {
            throw new IllegalArgumentException("To nie jest plik WAV.");
        }
        var out = new ByteArrayOutputStream(wav.length + 512);
        out.writeBytes(new byte[12]); // nagłówek RIFF — rozmiar uzupełniany na końcu
        byte[] info = infoChunk(label);
        boolean inserted = false;
        int pos = 12;
        while (pos + 8 <= wav.length) {
            String id = ascii(wav, pos);
            long size = Integer.toUnsignedLong(in.getInt(pos + 4));
            int total = (int) Math.min(8 + size + (size & 1), wav.length - pos);
            boolean isInfo = "LIST".equals(id) && pos + 12 <= wav.length && "INFO".equals(ascii(wav, pos + 8));
            if ("data".equals(id) && !inserted) {
                out.writeBytes(info);
                inserted = true;
            }
            if (!isInfo) out.write(wav, pos, total);
            pos += total;
        }
        if (!inserted) out.writeBytes(info);
        byte[] res = out.toByteArray();
        ByteBuffer b = ByteBuffer.wrap(res).order(ByteOrder.LITTLE_ENDIAN);
        b.put(0, "RIFF".getBytes(StandardCharsets.US_ASCII));
        b.putInt(4, res.length - 8);
        b.put(8, "WAVE".getBytes(StandardCharsets.US_ASCII));
        return res;
    }

    private static byte[] infoChunk(Label l) {
        var fields = new LinkedHashMap<String, String>();
        if (l.title() != null && !l.title().isBlank()) fields.put("INAM", l.title());
        fields.put("ICMT", l.comment());
        fields.put("ISFT", SOFTWARE + " (" + l.generator() + ")");
        fields.put("ICRD", l.date().toString());
        fields.put("IGNR", "AI-generated speech");
        var body = new ByteArrayOutputStream();
        body.writeBytes("INFO".getBytes(StandardCharsets.US_ASCII));
        for (Map.Entry<String, String> f : fields.entrySet()) {
            byte[] text = (f.getValue() + "\0").getBytes(StandardCharsets.UTF_8);
            body.writeBytes(f.getKey().getBytes(StandardCharsets.US_ASCII));
            body.writeBytes(le32(text.length));
            body.writeBytes(text);
            if ((text.length & 1) == 1) body.write(0);
        }
        byte[] b = body.toByteArray();
        var chunk = new ByteArrayOutputStream();
        chunk.writeBytes("LIST".getBytes(StandardCharsets.US_ASCII));
        chunk.writeBytes(le32(b.length));
        chunk.writeBytes(b);
        return chunk.toByteArray();
    }

    /** Odczyt pól LIST/INFO (testy, weryfikacja). */
    public static Map<String, String> readWavInfo(byte[] wav) {
        var out = new LinkedHashMap<String, String>();
        ByteBuffer in = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        int pos = 12;
        while (pos + 8 <= wav.length) {
            String id = ascii(wav, pos);
            int size = in.getInt(pos + 4);
            if ("LIST".equals(id) && "INFO".equals(ascii(wav, pos + 8))) {
                int p = pos + 12, end = pos + 8 + size;
                while (p + 8 <= end) {
                    String key = ascii(wav, p);
                    int len = in.getInt(p + 4);
                    String v = new String(wav, p + 8, len, StandardCharsets.UTF_8).replace("\0", "");
                    out.put(key, v);
                    p += 8 + len + (len & 1);
                }
            }
            pos += 8 + size + (size & 1);
        }
        return out;
    }

    // ------------------------------------------------------------------ MP3

    /** Dodaje znacznik ID3v2.3 na początku pliku MP3 (istniejący znacznik ID3v2 jest zastępowany). */
    public static byte[] mp3(byte[] mp3, Label label) {
        int skip = 0;
        if (mp3.length >= 10 && mp3[0] == 'I' && mp3[1] == 'D' && mp3[2] == '3') {
            skip = 10 + ((mp3[6] & 0x7f) << 21 | (mp3[7] & 0x7f) << 14 | (mp3[8] & 0x7f) << 7 | (mp3[9] & 0x7f));
        }
        var frames = new ByteArrayOutputStream();
        if (label.title() != null && !label.title().isBlank()) frames.writeBytes(textFrame("TIT2", label.title()));
        frames.writeBytes(textFrame("TSSE", SOFTWARE + " (" + label.generator() + ")"));
        frames.writeBytes(textFrame("TYER", String.valueOf(label.date().getYear())));
        frames.writeBytes(commentFrame(label.comment()));
        frames.writeBytes(txxx("AI_GENERATED", "true"));
        frames.writeBytes(txxx("AI_GENERATOR", SOFTWARE + " / " + label.generator()));
        byte[] body = frames.toByteArray();
        var out = new ByteArrayOutputStream(mp3.length + body.length + 10);
        out.writeBytes(new byte[] {'I', 'D', '3', 3, 0, 0});
        out.writeBytes(syncsafe(body.length));
        out.writeBytes(body);
        out.write(mp3, skip, mp3.length - skip);
        return out.toByteArray();
    }

    /** Odczyt ramek tekstowych ID3v2.3 (TXXX zwracane jako "TXXX:opis"). */
    public static Map<String, String> readId3(byte[] mp3) {
        var out = new LinkedHashMap<String, String>();
        if (mp3.length < 10 || mp3[0] != 'I' || mp3[1] != 'D' || mp3[2] != '3') return out;
        int size = (mp3[6] & 0x7f) << 21 | (mp3[7] & 0x7f) << 14 | (mp3[8] & 0x7f) << 7 | (mp3[9] & 0x7f);
        ByteBuffer b = ByteBuffer.wrap(mp3);
        int p = 10;
        while (p + 10 <= 10 + size) {
            String id = ascii(mp3, p);
            int len = b.getInt(p + 4);
            if (len <= 0 || id.charAt(0) == 0) break;
            byte[] data = java.util.Arrays.copyOfRange(mp3, p + 10, p + 10 + len);
            String text = decode(data, 1, data.length);
            if ("TXXX".equals(id)) {
                String[] parts = text.split("\0", 2);
                out.put("TXXX:" + parts[0], parts.length > 1 ? parts[1].replace("\0", "") : "");
            } else if ("COMM".equals(id)) {
                // kodowanie(1) + język(3) + opis zakończony zerem + tekst
                String rest = decode(data, 4, data.length);
                String[] parts = rest.split("\0", 2);
                out.put("COMM", parts.length > 1 ? parts[1].replace("\0", "") : parts[0]);
            } else {
                out.put(id, text.replace("\0", ""));
            }
            p += 10 + len;
        }
        return out;
    }

    private static String decode(byte[] data, int from, int to) {
        // Kodowanie 1 = UTF-16 z BOM; poszczególne łańcuchy rozdzielone podwójnym zerem
        var sb = new StringBuilder();
        int i = from;
        while (i + 1 < to) {
            int bomEnd = i;
            boolean le = true;
            if ((data[i] & 0xff) == 0xff && (data[i + 1] & 0xff) == 0xfe) { bomEnd = i + 2; }
            else if ((data[i] & 0xff) == 0xfe && (data[i + 1] & 0xff) == 0xff) { bomEnd = i + 2; le = false; }
            int j = bomEnd;
            while (j + 1 < to && !(data[j] == 0 && data[j + 1] == 0)) j += 2;
            sb.append(new String(data, bomEnd, j - bomEnd, le ? StandardCharsets.UTF_16LE : StandardCharsets.UTF_16BE));
            if (j + 1 < to) sb.append('\0');
            i = j + 2;
        }
        return sb.toString();
    }

    private static byte[] textFrame(String id, String text) {
        var d = new ByteArrayOutputStream();
        d.write(1);
        d.writeBytes(utf16(text));
        return frame(id, d.toByteArray());
    }

    private static byte[] txxx(String description, String value) {
        var d = new ByteArrayOutputStream();
        d.write(1);
        d.writeBytes(utf16(description));
        d.writeBytes(new byte[] {0, 0});
        d.writeBytes(utf16(value));
        return frame("TXXX", d.toByteArray());
    }

    private static byte[] commentFrame(String text) {
        var d = new ByteArrayOutputStream();
        d.write(1);
        d.writeBytes("pol".getBytes(StandardCharsets.US_ASCII));
        d.writeBytes(utf16("")); // pusty opis
        d.writeBytes(new byte[] {0, 0});
        d.writeBytes(utf16(text));
        return frame("COMM", d.toByteArray());
    }

    private static byte[] utf16(String s) {
        byte[] t = s.getBytes(StandardCharsets.UTF_16LE);
        byte[] r = new byte[t.length + 2];
        r[0] = (byte) 0xff;
        r[1] = (byte) 0xfe;
        System.arraycopy(t, 0, r, 2, t.length);
        return r;
    }

    private static byte[] frame(String id, byte[] data) {
        ByteBuffer b = ByteBuffer.allocate(10 + data.length);
        b.put(id.getBytes(StandardCharsets.US_ASCII)).putInt(data.length).putShort((short) 0).put(data);
        return b.array();
    }

    private static byte[] syncsafe(int n) {
        return new byte[] {(byte) (n >> 21 & 0x7f), (byte) (n >> 14 & 0x7f), (byte) (n >> 7 & 0x7f), (byte) (n & 0x7f)};
    }

    private static byte[] le32(int n) {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(n).array();
    }

    private static String ascii(byte[] b, int pos) {
        return new String(b, pos, 4, StandardCharsets.US_ASCII);
    }
}
