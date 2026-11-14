package ai.voicecraft.audio;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import javax.sound.sampled.*;
import org.junit.jupiter.api.Test;

class WavUtilsTest {

    private static byte[] wav(int frames, float rate) throws Exception {
        var format = new AudioFormat(rate, 16, 1, true, false);
        byte[] pcm = new byte[frames * 2];
        var out = new ByteArrayOutputStream();
        AudioSystem.write(new AudioInputStream(new ByteArrayInputStream(pcm), format, frames), AudioFileFormat.Type.WAVE, out);
        return out.toByteArray();
    }

    private static long frames(byte[] wav) throws Exception {
        try (var in = AudioSystem.getAudioInputStream(new ByteArrayInputStream(wav))) {
            return in.getFrameLength();
        }
    }

    @Test
    void concatenatesWithPauses() throws Exception {
        byte[] merged = WavUtils.concat(List.of(wav(24000, 24000), wav(12000, 24000)), 500);
        assertEquals(24000 + 12000 + 12000, frames(merged));
    }

    @Test
    void rejectsMixedSampleRates() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> WavUtils.concat(List.of(wav(100, 24000), wav(100, 48000)), 0));
    }
}
