package ai.voicecraft.usage;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface UsageRepository extends JpaRepository<UsageEvent, Long> {
    @Query("select coalesce(sum(e.characters), 0) from UsageEvent e where e.userId = ?1 and e.createdAt >= ?2")
    long sumCharactersSince(String userId, Instant since);

    /** Znaki zużyte z limitu planu (bez części pokrytej pakietami). */
    @Query("select coalesce(sum(e.characters - e.creditCharacters), 0) from UsageEvent e where e.userId = ?1 and e.createdAt >= ?2")
    long sumPlanCharactersSince(String userId, Instant since);

    @Query("select coalesce(sum(e.characters), 0) from UsageEvent e where e.userId = ?1 and e.kind = 'api' and e.createdAt >= ?2")
    long sumApiCharactersSince(String userId, Instant since);

    @Query("select e.apiKeyId, coalesce(sum(e.characters), 0), count(e) from UsageEvent e where e.userId = ?1 and e.kind = 'api' and e.createdAt >= ?2 group by e.apiKeyId")
    List<Object[]> apiUsageByKeySince(String userId, Instant since);

    /** Znaki pobrane z pakietów — od początku konta (pakiety nie wygasają). */
    @Query("select coalesce(sum(e.creditCharacters), 0) from UsageEvent e where e.userId = ?1")
    long sumCreditCharacters(String userId);

    @Query("select coalesce(sum(e.durationMs), 0) from UsageEvent e where e.userId = ?1 and e.createdAt >= ?2")
    long sumDurationSince(String userId, Instant since);

    @Query("select count(e) from UsageEvent e where e.userId = ?1 and e.createdAt >= ?2")
    long countSince(String userId, Instant since);

    List<UsageEvent> findAllByUserIdOrderByCreatedAtDesc(String userId);

    @Transactional
    void deleteByUserId(String userId);
}
