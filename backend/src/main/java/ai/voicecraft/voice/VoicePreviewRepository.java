package ai.voicecraft.voice;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface VoicePreviewRepository extends JpaRepository<VoicePreview, VoicePreview.Key> {
    @Transactional
    void deleteByVoiceId(String voiceId);

    @Query("select p.audioFile from VoicePreview p")
    List<String> allAudioFiles();
}
