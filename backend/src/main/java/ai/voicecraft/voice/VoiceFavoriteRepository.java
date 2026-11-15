package ai.voicecraft.voice;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

public interface VoiceFavoriteRepository extends JpaRepository<VoiceFavorite, Long> {
    List<VoiceFavorite> findAllByUserId(String userId);

    boolean existsByUserIdAndVoiceId(String userId, String voiceId);

    @Transactional
    void deleteByUserIdAndVoiceId(String userId, String voiceId);

    @Transactional
    void deleteByUserId(String userId);

    @Transactional
    void deleteByVoiceId(String voiceId);
}
