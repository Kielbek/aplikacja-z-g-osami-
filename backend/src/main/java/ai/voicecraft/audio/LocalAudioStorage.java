package ai.voicecraft.audio;

import ai.voicecraft.common.ApiException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Pliki w katalogu danych (środowisko deweloperskie / pojedynczy serwer). */
public class LocalAudioStorage implements AudioStorage {
    private final Path root;

    public LocalAudioStorage(Path root) {
        this.root = root.toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.root);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public String save(String prefix, byte[] data) {
        String key = AudioStorage.newKey(prefix);
        try {
            Files.write(resolve(key), data);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return key;
    }

    @Override
    public byte[] read(String key) {
        try {
            return Files.readAllBytes(resolve(key));
        } catch (IOException e) {
            throw ApiException.notFound("Plik audio");
        }
    }

    @Override
    public boolean exists(String key) {
        return AudioStorage.validKey(key) && Files.exists(resolve(key));
    }

    @Override
    public void delete(String key) {
        try {
            if (AudioStorage.validKey(key)) Files.deleteIfExists(resolve(key));
        } catch (IOException ignored) {
            // brak pliku nie jest błędem
        }
    }

    @Override
    public void forEachObject(java.util.function.Consumer<StoredObject> action) {
        try (var files = Files.list(root)) {
            files.filter(Files::isRegularFile)
                    .filter(f -> AudioStorage.validKey(f.getFileName().toString()))
                    .forEach(f -> {
                        try {
                            action.accept(new StoredObject(f.getFileName().toString(),
                                    Files.getLastModifiedTime(f).toInstant(), Files.size(f)));
                        } catch (IOException ignored) {
                            // plik usunięty w międzyczasie
                        }
                    });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Path resolve(String key) {
        if (!AudioStorage.validKey(key)) throw new IllegalArgumentException("Niepoprawny klucz pliku.");
        Path p = root.resolve(key).normalize();
        if (!p.startsWith(root)) throw new IllegalArgumentException("Niepoprawny klucz pliku.");
        return p;
    }
}
