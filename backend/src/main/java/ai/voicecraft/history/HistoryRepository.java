package ai.voicecraft.history;

import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface HistoryRepository extends JpaRepository<GenerationRecord, Long> {
    List<GenerationRecord> findAllByUserIdAndCreatedAtAfterOrderByCreatedAtDesc(String userId, Instant after, Pageable page);

    java.util.Optional<GenerationRecord> findByIdAndUserId(Long id, String userId);

    boolean existsByUserIdAndAudioFile(String userId, String audioFile);

    List<GenerationRecord> findTop30ByUserIdAndSegmentIdAndStatusAndCreatedAtAfterOrderByCreatedAtDesc(
            String userId, String segmentId, String status, Instant after);

    @Transactional
    void deleteByUserId(String userId);

    @Query("select coalesce(sum(r.characters), 0) from GenerationRecord r where r.userId = ?1 and r.status = 'success' and r.createdAt >= ?2 and r.type <> 'export'")
    long sumCharactersSince(String userId, Instant since);

    @Query("select coalesce(sum(r.durationMs), 0) from GenerationRecord r where r.userId = ?1 and r.status = 'success' and r.createdAt >= ?2 and r.type <> 'export'")
    long sumDurationSince(String userId, Instant since);

    @Query("select count(r) from GenerationRecord r where r.userId = ?1 and r.status = 'success' and r.createdAt >= ?2 and r.type <> 'export'")
    long countSince(String userId, Instant since);
}
