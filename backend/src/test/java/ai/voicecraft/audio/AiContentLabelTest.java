package ai.voicecraft.audio;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class AiContentLabelTest {
    final AiContentLabel.Label label = new AiContentLabel.Label("Audiobook — rozdział 1", "VoxCPM2", LocalDate.of(2026, 11, 15));

    @Test
    void wavGetsInfoChunkAndStaysPlayable() {
        float[] x = new float[24000];
        for (int i = 0; i < x.length; i++) x[i] = (float) (0.2 * Math.sin(i / 10.0));
        byte[] wav = WavUtils.encode(x, 24000);
        byte[] labeled = AiContentLabel.wav(wav, label);

        var info = AiContentLabel.readWavInfo(labeled);
        assertThat(info.get("ICMT")).contains("wygenerowana przez AI").contains("AI-generated").contains("VoxCPM2");
        assertThat(info.get("INAM")).isEqualTo("Audiobook — rozdział 1");
        assertThat(info.get("ISFT")).startsWith("Narrivy");
        // Dane audio bez zmian, plik nadal poprawny
        assertThat(WavUtils.durationMs(labeled)).isEqualTo(WavUtils.durationMs(wav));
        assertThat(WavUtils.decode(labeled).samples()).hasSize(x.length);

        // Ponowne oznaczenie zastępuje fragment INFO zamiast go dublować
        byte[] twice = AiContentLabel.wav(labeled, label);
        assertThat(twice.length).isEqualTo(labeled.length);
    }

    @Test
    void mp3GetsId3TagReplacingExisting() {
        byte[] frames = {(byte) 0xff, (byte) 0xfb, (byte) 0x90, 0x64, 1, 2, 3, 4};
        byte[] tagged = AiContentLabel.mp3(frames, label);
        var tags = AiContentLabel.readId3(tagged);
        assertThat(tags.get("TXXX:AI_GENERATED")).isEqualTo("true");
        assertThat(tags.get("TXXX:AI_GENERATOR")).contains("Narrivy");
        assertThat(tags.get("COMM")).contains("AI-generated speech");
        assertThat(tags.get("TIT2")).isEqualTo("Audiobook — rozdział 1");
        // Po znaczniku następują niezmienione ramki MP3
        assertThat(java.util.Arrays.copyOfRange(tagged, tagged.length - frames.length, tagged.length)).isEqualTo(frames);
        byte[] again = AiContentLabel.mp3(tagged, label);
        assertThat(again.length).isEqualTo(tagged.length);
    }
}
