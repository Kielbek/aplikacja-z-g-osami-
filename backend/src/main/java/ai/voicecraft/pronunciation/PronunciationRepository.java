package ai.voicecraft.pronunciation;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface PronunciationRepository extends JpaRepository<PronunciationEntry, String> {
    /** Wpisy globalne użytkownika oraz wpisy danego projektu. */
    @Query("select e from PronunciationEntry e where e.ownerId = ?1 and (e.projectId is null or e.projectId = ?2) order by e.term")
    List<PronunciationEntry> findApplicable(String ownerId, String projectId);

    @Query("select e from PronunciationEntry e where e.ownerId = ?1 order by e.term")
    List<PronunciationEntry> findAllOf(String ownerId);

    Optional<PronunciationEntry> findByIdAndOwnerId(String id, String ownerId);

    long countByOwnerId(String ownerId);

    @Transactional
    void deleteByOwnerId(String ownerId);
}
