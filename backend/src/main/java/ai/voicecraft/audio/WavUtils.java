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

    private static boolean sameFormat(AudioFormat a, AudioFormat b) {
        return a.getSampleRate() == b.getSampleRate() && a.getChannels() == b.getChannels()
                && a.getSampleSizeInBits() == b.getSampleSizeInBits() && a.getEncoding().equals(b.getEncoding());
    }
}
