package ai.voicecraft.project;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "segments")
public class Segment {
    @Id
    public String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    public Project project;

    public int position;

    @Column(length = 5000, nullable = false)
    public String text = "";

    /** Plik ostatniej udanej generacji. */
    public String audioFile;
    public long durationMs;
    /** Odcisk (tekst + parametry + tryb silnika) z chwili generacji. */
    @Column(length = 64)
    public String generatedHash;
    public String engineMode;
    public Instant generatedAt;

    @Column(length = 1000)
    public String error;
}
