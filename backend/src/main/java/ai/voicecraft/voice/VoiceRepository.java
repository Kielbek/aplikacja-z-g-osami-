package ai.voicecraft.voice;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface VoiceRepository extends JpaRepository<Voice, String> {
    /** Biblioteka (wspólna) + głosy własne użytkownika. */
    @Query("select v from Voice v where v.ownerId is null or v.ownerId = ?1")
    List<Voice> findVisible(String userId);

    long countByOwnerId(String ownerId);

    List<Voice> findAllByOwnerId(String ownerId);

    /** Inny głos o tym samym nagraniu wzorcowym (klucz z katalogu), który już ma plik referencyjny. */
    java.util.Optional<Voice> findFirstByReferenceKeyAndReferenceFileIsNotNullAndIdNot(String referenceKey, String id);

    @Query("select v.sampleFile from Voice v where v.sampleFile is not null")
    List<String> allSampleFiles();

    @Query("select v.referenceFile from Voice v where v.referenceFile is not null")
    List<String> allReferenceFiles();
}
