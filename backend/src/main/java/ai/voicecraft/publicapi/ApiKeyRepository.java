package ai.voicecraft.publicapi;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

public interface ApiKeyRepository extends JpaRepository<ApiKey, String> {
    Optional<ApiKey> findByKeyHash(String keyHash);

    List<ApiKey> findAllByUserIdOrderByCreatedAtDesc(String userId);

    Optional<ApiKey> findByIdAndUserId(String id, String userId);

    long countByUserIdAndRevokedAtIsNull(String userId);

    @Transactional
    void deleteByUserId(String userId);
}
