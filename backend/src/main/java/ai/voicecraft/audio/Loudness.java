package ai.voicecraft.audio;

/**
 * Głośność zintegrowana według ITU-R BS.1770-4 / EBU R128 (LUFS) i normalizacja do wartości docelowej.
 * K-weighting: filtr półkowy (+4 dB, 1,5 kHz) i górnoprzepustowy RLB (38 Hz); bloki 400 ms z nakładaniem 75%;
 * bramka absolutna −70 LUFS i względna −10 LU.
 */
public final class Loudness {
    private Loudness() {}

    public static final double ABSOLUTE_GATE = -70.0;

    /** Zintegrowana głośność w LUFS (Double.NEGATIVE_INFINITY dla ciszy). */
    public static double integrated(float[] x, float sampleRate) {
        double[] y = kWeight(x, sampleRate);
        int block = Math.round(0.4f * sampleRate);
        int step = Math.round(0.1f * sampleRate);
        if (y.length < block) block = step = Math.max(1, y.length);
        int n = (y.length - block) / step + 1;
        double[] z = new double[n];
        for (int j = 0; j < n; j++) {
            double sum = 0;
            for (int i = j * step; i < j * step + block; i++) sum += y[i] * y[i];
            z[j] = sum / block;
        }
        double absSum = 0;
        int absCount = 0;
        for (double v : z) {
            if (lufs(v) > ABSOLUTE_GATE) {
                absSum += v;
                absCount++;
            }
        }
        if (absCount == 0) return Double.NEGATIVE_INFINITY;
        double relGate = lufs(absSum / absCount) - 10.0;
        double sum = 0;
        int count = 0;
        for (double v : z) {
            double l = lufs(v);
            if (l > ABSOLUTE_GATE && l > relGate) {
                sum += v;
                count++;
            }
        }
        return count == 0 ? Double.NEGATIVE_INFINITY : lufs(sum / count);
    }

    public record Result(float[] samples, double measuredLufs, double outputLufs, double gainDb) {}

    /**
     * Normalizuje do targetLufs z ograniczeniem szczytu do ceilingDb (dBFS), aby uniknąć przesterowania.
     */
    public static Result normalize(float[] x, float sampleRate, double targetLufs, double ceilingDb) {
        double measured = integrated(x, sampleRate);
        if (Double.isInfinite(measured)) return new Result(x, measured, measured, 0);
        double gainDb = targetLufs - measured;
        float peak = 0;
        for (float v : x) peak = Math.max(peak, Math.abs(v));
        if (peak > 0) {
            double maxGainDb = ceilingDb - 20 * Math.log10(peak);
            gainDb = Math.min(gainDb, maxGainDb);
        }
        float g = (float) Math.pow(10, gainDb / 20);
        float[] out = new float[x.length];
        for (int i = 0; i < x.length; i++) out[i] = x[i] * g;
        return new Result(out, measured, measured + gainDb, gainDb);
    }

    private static double lufs(double meanSquare) {
        return meanSquare <= 0 ? Double.NEGATIVE_INFINITY : -0.691 + 10 * Math.log10(meanSquare);
    }

    static double[] kWeight(float[] x, float fs) {
        // Etap 1: filtr półkowy (współczynniki jak w pyloudnorm / BS.1770)
        double[] shelf = biquadHighShelf(fs, 1500.0, 4.0, 0.7071752369554193);
        // Etap 2: filtr górnoprzepustowy RLB
        double[] hp = biquadHighPass(fs, 38.0, 0.5003270373253953);
        double[] tmp = filter(toDouble(x), shelf);
        return filter(tmp, hp);
    }

    private static double[] toDouble(float[] x) {
        double[] d = new double[x.length];
        for (int i = 0; i < x.length; i++) d[i] = x[i];
        return d;
    }

    /** Zwraca {b0,b1,b2,a1,a2} znormalizowane przez a0. */
    private static double[] biquadHighShelf(double fs, double fc, double gainDb, double q) {
        double a = Math.pow(10, gainDb / 40);
        double w0 = 2 * Math.PI * fc / fs;
        double alpha = Math.sin(w0) / (2 * q);
        double cos = Math.cos(w0);
        double sq = 2 * Math.sqrt(a) * alpha;
        double b0 = a * ((a + 1) + (a - 1) * cos + sq);
        double b1 = -2 * a * ((a - 1) + (a + 1) * cos);
        double b2 = a * ((a + 1) + (a - 1) * cos - sq);
        double a0 = (a + 1) - (a - 1) * cos + sq;
        double a1 = 2 * ((a - 1) - (a + 1) * cos);
        double a2 = (a + 1) - (a - 1) * cos - sq;
        return new double[] {b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0};
    }

    private static double[] biquadHighPass(double fs, double fc, double q) {
        double w0 = 2 * Math.PI * fc / fs;
        double alpha = Math.sin(w0) / (2 * q);
        double cos = Math.cos(w0);
        double b0 = (1 + cos) / 2, b1 = -(1 + cos), b2 = (1 + cos) / 2;
        double a0 = 1 + alpha, a1 = -2 * cos, a2 = 1 - alpha;
        return new double[] {b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0};
    }

    private static double[] filter(double[] x, double[] c) {
        double[] y = new double[x.length];
        double x1 = 0, x2 = 0, y1 = 0, y2 = 0;
        for (int i = 0; i < x.length; i++) {
            double v = c[0] * x[i] + c[1] * x1 + c[2] * x2 - c[3] * y1 - c[4] * y2;
            x2 = x1;
            x1 = x[i];
            y2 = y1;
            y1 = v;
            y[i] = v;
        }
        return y;
    }
}
