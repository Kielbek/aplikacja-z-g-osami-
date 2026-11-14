package ai.voicecraft.history;

import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface HistoryRepository extends JpaRepository<GenerationRecord, Long> {
    List<GenerationRecord> findAllByOrderByCreatedAtDesc(Pageable page);

    @Query("select coalesce(sum(r.characters), 0) from GenerationRecord r where r.status = 'success' and r.createdAt >= ?1 and r.type <> 'export'")
    long sumCharactersSince(Instant since);

    @Query("select coalesce(sum(r.durationMs), 0) from GenerationRecord r where r.status = 'success' and r.createdAt >= ?1 and r.type <> 'export'")
    long sumDurationSince(Instant since);

    @Query("select count(r) from GenerationRecord r where r.status = 'success' and r.createdAt >= ?1 and r.type <> 'export'")
    long countSince(Instant since);
}
