package ai.voicecraft.user;

import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface EmailVerificationTokenRepository extends JpaRepository<EmailVerificationToken, String> {
    @Transactional
    void deleteByUserId(String userId);

    @Transactional
    @Modifying
    @Query("delete from EmailVerificationToken t where t.expiresAt < ?1")
    int deleteExpired(Instant now);
}
