package ai.voicecraft.audio;

import ai.voicecraft.config.AppProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Pliki audio na dysku. Każda generacja tworzy nowy, niezmienny plik (historia może go odtworzyć). */
@Component
public class AudioStorage {
    private final Path root;

    public AudioStorage(AppProperties props) {
        this.root = props.dataDir().resolve("audio").toAbsolutePath();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public String save(String prefix, byte[] data) {
        String name = prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16) + ".wav";
        try {
            Files.write(root.resolve(name), data);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return name;
    }

    public byte[] read(String name) {
        try {
            return Files.readAllBytes(resolve(name));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public boolean exists(String name) {
        return name != null && Files.exists(resolve(name));
    }

    private Path resolve(String name) {
        Path p = root.resolve(name).normalize();
        if (!p.startsWith(root)) throw new IllegalArgumentException("Niepoprawna ścieżka pliku.");
        return p;
    }
}
