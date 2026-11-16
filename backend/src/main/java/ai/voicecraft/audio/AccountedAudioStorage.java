package ai.voicecraft.audio;

import ai.voicecraft.security.CurrentUser;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Magazyn z ewidencją miejsca: każdy zapis jest przypisywany zalogowanemu użytkownikowi (także w wątkach
 * kolejki, które działają „jako” użytkownik), a usunięcie pliku zwalnia miejsce. Pliki zapisane bez kontekstu
 * użytkownika (np. próbki głosów biblioteki) nie liczą się do niczyjego limitu.
 */
public class AccountedAudioStorage implements AudioStorage {
    private static final Logger log = LoggerFactory.getLogger(AccountedAudioStorage.class);
    private final AudioStorage delegate;
    private final StoredFileRepository files;

    public AccountedAudioStorage(AudioStorage delegate, StoredFileRepository files) {
        this.delegate = delegate;
        this.files = files;
    }

    @Override
    public String save(String prefix, byte[] data) {
        return saveFor(CurrentUser.idOrNull(), prefix, data);
    }

    @Override
    public String saveFor(String ownerId, String prefix, byte[] data) {
        String key = delegate.save(prefix, data);
        try {
            files.save(StoredFile.of(key, ownerId, data.length));
        } catch (RuntimeException e) {
            log.warn("Nie zapisano ewidencji pliku {}: {}", key, e.getMessage());
        }
        return key;
    }

    @Override
    public byte[] read(String key) {
        return delegate.read(key);
    }

    @Override
    public boolean exists(String key) {
        return delegate.exists(key);
    }

    @Override
    public void delete(String key) {
        delegate.delete(key);
        files.deleteByKey(key);
    }

    @Override
    public void forEachObject(Consumer<StoredObject> action) {
        delegate.forEachObject(action);
    }
}
