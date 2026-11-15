package ai.voicecraft.user;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, String> {
    Optional<User> findByEmailIgnoreCase(String email);

    Optional<User> findByGoogleSub(String sub);

    boolean existsByEmailIgnoreCase(String email);
}
