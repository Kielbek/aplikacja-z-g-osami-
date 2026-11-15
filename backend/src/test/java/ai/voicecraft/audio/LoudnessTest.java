package ai.voicecraft.audio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class LoudnessTest {
    private static float[] sine(double freq, double amplitude, double seconds, float fs) {
        float[] x = new float[(int) (seconds * fs)];
        for (int i = 0; i < x.length; i++) x[i] = (float) (amplitude * Math.sin(2 * Math.PI * freq * i / fs));
        return x;
    }

    @Test
    void fullScaleSineAt1kHzIsAboutMinus3Lufs() {
        // Wartość referencyjna BS.1770: sinus 1 kHz 0 dBFS (mono) ≈ −3,01 LUFS
        assertThat(Loudness.integrated(sine(1000, 1.0, 5, 48000), 48000)).isCloseTo(-3.01, within(0.15));
        assertThat(Loudness.integrated(sine(1000, 1.0, 5, 24000), 24000)).isCloseTo(-3.01, within(0.2));
    }

    @Test
    void minus20dBSineIsTwentyLuQuieter() {
        assertThat(Loudness.integrated(sine(1000, 0.1, 5, 48000), 48000)).isCloseTo(-23.01, within(0.15));
    }

    @Test
    void silenceIsGatedOut() {
        assertThat(Loudness.integrated(new float[48000 * 2], 48000)).isInfinite();
    }

    @Test
    void normalizesToTargetWithinPeakCeiling() {
        float fs = 48000;
        var r = Loudness.normalize(sine(1000, 0.05, 5, fs), fs, -16, -1);
        assertThat(Loudness.integrated(r.samples(), fs)).isCloseTo(-16, within(0.2));
        // Głośny sygnał: limit szczytu −1 dBFS ma pierwszeństwo przed celem LUFS
        var loud = Loudness.normalize(sine(1000, 0.9, 5, fs), fs, -1, -1);
        float peak = 0;
        for (float v : loud.samples()) peak = Math.max(peak, Math.abs(v));
        assertThat(20 * Math.log10(peak)).isLessThanOrEqualTo(-0.99);
    }
}
