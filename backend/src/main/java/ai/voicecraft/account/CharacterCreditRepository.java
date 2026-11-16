package ai.voicecraft.account;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CharacterCreditRepository extends JpaRepository<CharacterCredit, Long> {
    @Query("select coalesce(sum(c.characters), 0) from CharacterCredit c where c.userId = ?1")
    long sumCharacters(String userId);

    boolean existsByReference(String reference);

    List<CharacterCredit> findAllByUserIdOrderByCreatedAtDesc(String userId);
}
