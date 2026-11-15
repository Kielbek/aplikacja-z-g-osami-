package ai.voicecraft.voice;

import jakarta.persistence.*;

@Entity
@Table(name = "voice_favorites", uniqueConstraints = @UniqueConstraint(columnNames = {"userId", "voiceId"}))
public class VoiceFavorite {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(nullable = false)
    public String userId;

    @Column(nullable = false)
    public String voiceId;

    public VoiceFavorite() {}

    public VoiceFavorite(String userId, String voiceId) {
        this.userId = userId;
        this.voiceId = voiceId;
    }
}
