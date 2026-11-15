package ai.voicecraft.voice;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VoiceConsentRepository extends JpaRepository<VoiceConsent, String> {
    Optional<VoiceConsent> findFirstByVoiceIdAndUserIdOrderByCreatedAtDesc(String voiceId, String userId);

    List<VoiceConsent> findAllByUserIdOrderByCreatedAtDesc(String userId);
}
