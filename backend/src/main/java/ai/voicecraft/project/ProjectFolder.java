package ai.voicecraft.project;

import jakarta.persistence.*;
import java.time.Instant;

/** Folder porządkujący projekty użytkownika. Jeden poziom — folder nie zawiera innych folderów. */
@Entity
@Table(name = "project_folders")
public class ProjectFolder {
    @Id
    public String id;

    @Column(nullable = false)
    public String ownerId;

    @Column(nullable = false, length = 80)
    public String name;

    public Instant createdAt = Instant.now();
}
