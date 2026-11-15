package ai.voicecraft.usage;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface UsageRepository extends JpaRepository<UsageEvent, Long> {
    @Query("select coalesce(sum(e.characters), 0) from UsageEvent e where e.userId = ?1 and e.createdAt >= ?2")
    long sumCharactersSince(String userId, Instant since);

    @Query("select coalesce(sum(e.durationMs), 0) from UsageEvent e where e.userId = ?1 and e.createdAt >= ?2")
    long sumDurationSince(String userId, Instant since);

    @Query("select count(e) from UsageEvent e where e.userId = ?1 and e.createdAt >= ?2")
    long countSince(String userId, Instant since);

    List<UsageEvent> findAllByUserIdOrderByCreatedAtDesc(String userId);

    @Transactional
    void deleteByUserId(String userId);
}
