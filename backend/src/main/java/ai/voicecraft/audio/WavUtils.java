package ai.voicecraft.audio;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;

/** Łączenie fragmentów WAV w jedno nagranie (z pauzami) — bez ponownej syntezy. */
public final class WavUtils {
    private WavUtils() {}

    public static byte[] concat(List<byte[]> wavs, int pauseMs) {
        if (wavs.isEmpty()) throw new IllegalArgumentException("Brak fragmentów do połączenia.");
        try {
            AudioFormat format = null;
            var pcm = new ByteArrayOutputStream();
            for (int i = 0; i < wavs.size(); i++) {
                try (AudioInputStream in = AudioSystem.getAudioInputStream(new ByteArrayInputStream(wavs.get(i)))) {
                    if (format == null) {
                        format = in.getFormat();
                    } else if (!sameFormat(format, in.getFormat())) {
                        throw new IllegalArgumentException(
                                "Fragmenty mają różne formaty audio (np. wygenerowano je różnymi silnikami). Wygeneruj je ponownie.");
                    }
                    in.transferTo(pcm);
                }
                if (i < wavs.size() - 1 && pauseMs > 0) {
                    int frames = (int) (format.getFrameRate() * pauseMs / 1000.0);
                    pcm.write(new byte[frames * format.getFrameSize()]);
                }
            }
            byte[] data = pcm.toByteArray();
            var stream = new AudioInputStream(new ByteArrayInputStream(data), format, data.length / format.getFrameSize());
            var out = new ByteArrayOutputStream();
            AudioSystem.write(stream, AudioFileFormat.Type.WAVE, out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (UnsupportedAudioFileException e) {
            throw new IllegalArgumentException("Nieobsługiwany format pliku audio.", e);
        }
    }

    /**
     * Składa fragment z części: nagrań WAV (byte[]) i pauz (Integer, ms). Pauzy przed pierwszym
     * nagraniem są dodawane po ustaleniu formatu.
     */
    public static byte[] joinParts(List<Object> parts) {
        AudioFormat format = null;
        var pcm = new ByteArrayOutputStream();
        int leadingPauseMs = 0;
        try {
            for (Object part : parts) {
                if (part instanceof Integer ms) {
                    if (format == null) leadingPauseMs += ms;
                    else pcm.write(silence(format, ms));
                    continue;
                }
                try (AudioInputStream in = AudioSystem.getAudioInputStream(new ByteArrayInputStream((byte[]) part))) {
                    if (format == null) {
                        format = in.getFormat();
                        if (leadingPauseMs > 0) pcm.write(silence(format, leadingPauseMs));
                    } else if (!sameFormat(format, in.getFormat())) {
                        throw new IllegalArgumentException("Części fragmentu mają różne formaty audio.");
                    }
                    in.transferTo(pcm);
                }
            }
            if (format == null) throw new IllegalArgumentException("Fragment zawiera tylko pauzy — dodaj tekst.");
            return write(pcm.toByteArray(), format);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (UnsupportedAudioFileException e) {
            throw new IllegalArgumentException("Nieobsługiwany format pliku audio.", e);
        }
    }

    public static long durationMs(byte[] wav) {
        try (AudioInputStream in = AudioSystem.getAudioInputStream(new ByteArrayInputStream(wav))) {
            return Math.round(in.getFrameLength() * 1000.0 / in.getFormat().getFrameRate());
        } catch (IOException | UnsupportedAudioFileException e) {
            throw new IllegalArgumentException("Nieobsługiwany format pliku audio.", e);
        }
    }

    /** Próbki mono [-1, 1] z 16-bitowego PCM (kanały uśredniane). */
    public record Pcm(float[] samples, AudioFormat format) {}

    public static Pcm decode(byte[] wav) {
        try (AudioInputStream in = AudioSystem.getAudioInputStream(new ByteArrayInputStream(wav))) {
            AudioFormat f = in.getFormat();
            if (f.getSampleSizeInBits() != 16 || f.getEncoding() != AudioFormat.Encoding.PCM_SIGNED) {
                throw new IllegalArgumentException("Wymagany 16-bitowy PCM.");
            }
            byte[] data = in.readAllBytes();
            int ch = f.getChannels();
            int frames = data.length / f.getFrameSize();
            float[] out = new float[frames];
            for (int i = 0; i < frames; i++) {
                float sum = 0;
                for (int c = 0; c < ch; c++) {
                    int idx = (i * ch + c) * 2;
                    int v = f.isBigEndian() ? (data[idx] << 8) | (data[idx + 1] & 0xff) : (data[idx + 1] << 8) | (data[idx] & 0xff);
                    sum += v / 32768f;
                }
                out[i] = sum / ch;
            }
            return new Pcm(out, f);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (UnsupportedAudioFileException e) {
            throw new IllegalArgumentException("Nieobsługiwany format pliku audio.", e);
        }
    }

    /** Zapis próbek mono jako 16-bitowy WAV. */
    public static byte[] encode(float[] samples, float sampleRate) {
        var format = new AudioFormat(sampleRate, 16, 1, true, false);
        byte[] data = new byte[samples.length * 2];
        for (int i = 0; i < samples.length; i++) {
            int v = Math.round(Math.max(-1f, Math.min(1f, samples[i])) * 32767f);
            data[2 * i] = (byte) v;
            data[2 * i + 1] = (byte) (v >> 8);
        }
        return write(data, format);
    }

    private static byte[] write(byte[] pcm, AudioFormat format) {
        try {
            var stream = new AudioInputStream(new ByteArrayInputStream(pcm), format, pcm.length / format.getFrameSize());
            var out = new ByteArrayOutputStream();
            AudioSystem.write(stream, AudioFileFormat.Type.WAVE, out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] silence(AudioFormat format, int ms) {
        int frames = (int) (format.getFrameRate() * ms / 1000.0);
        return new byte[frames * format.getFrameSize()];
    }

    private static boolean sameFormat(AudioFormat a, AudioFormat b) {
        return a.getSampleRate() == b.getSampleRate() && a.getChannels() == b.getChannels()
                && a.getSampleSizeInBits() == b.getSampleSizeInBits() && a.getEncoding().equals(b.getEncoding());
    }
}
