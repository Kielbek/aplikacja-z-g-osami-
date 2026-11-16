package ai.voicecraft.audio;

import java.util.UUID;

/**
 * Magazyn plików audio. Każda generacja zapisuje nowy, niezmienny obiekt — klucze są losowe,
 * więc historia może odtworzyć wcześniejsze wersje. Implementacje: lokalny dysk lub S3.
 */
public interface AudioStorage {
    /** Zapisuje dane i zwraca klucz obiektu. */
    String save(String prefix, byte[] data);

    /**
     * Zapis przypisany wskazanemu właścicielowi w ewidencji miejsca (null = plik wspólny, np. biblioteki).
     * Domyślnie bez ewidencji.
     */
    default String saveFor(String ownerId, String prefix, byte[] data) {
        return save(prefix, data);
    }

    byte[] read(String key);

    boolean exists(String key);

    void delete(String key);

    /** Obiekt w magazynie (do sprzątania osieroconych plików). */
    record StoredObject(String key, java.time.Instant lastModified, long size) {}

    /** Przegląda wszystkie pliki audio aplikacji w magazynie. */
    void forEachObject(java.util.function.Consumer<StoredObject> action);

    static String newKey(String prefix) {
        if (!prefix.matches("[a-z]+")) throw new IllegalArgumentException("Niepoprawny prefiks pliku.");
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16) + ".wav";
    }

    /** Klucze mają stały format — blokuje dostęp do dowolnych ścieżek. */
    static boolean validKey(String key) {
        return key != null && key.matches("[a-z]+_[a-f0-9]{16}\\.wav");
    }
}
