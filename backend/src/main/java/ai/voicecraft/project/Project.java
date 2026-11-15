package ai.voicecraft.project;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "projects")
public class Project {
    @Id
    public String id;

    @Column(nullable = false)
    public String ownerId;

    @Column(nullable = false)
    public String name;

    public Instant createdAt = Instant.now();
    public Instant updatedAt = Instant.now();
    public Instant lastGeneratedAt;

    @Embedded
    public VoiceSettings settings = new VoiceSettings();

    @OneToMany(mappedBy = "project", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @OrderBy("position ASC")
    public List<Segment> segments = new ArrayList<>();
}
