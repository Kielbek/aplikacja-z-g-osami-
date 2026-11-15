package ai.voicecraft.user;

import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, String> {
    void deleteByUserId(String userId);

    @Transactional
    @Modifying
    @Query("delete from PasswordResetToken t where t.expiresAt < ?1")
    int deleteExpired(Instant now);
}
