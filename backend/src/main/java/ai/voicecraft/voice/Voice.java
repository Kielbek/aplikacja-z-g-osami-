package ai.voicecraft.voice;

import ai.voicecraft.common.StringListConverter;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "voices")
public class Voice {
    @Id
    public String id;

    @Column(nullable = false)
    public String name;

    @Enumerated(EnumType.STRING)
    public VoiceKind kind = VoiceKind.LIBRARY;

    /** Właściciel głosu własnego; null = głos z biblioteki (wspólny). */
    public String ownerId;

    /** female | male | neutral */
    public String gender;

    public Integer age;

    /** Języki, w których głos brzmi najlepiej (kody ISO). */
    @Convert(converter = StringListConverter.class)
    public List<String> languages = new ArrayList<>();

    /** Kategorie charakteru: natural, professional, energetic, calm, narrative, advertising. */
    @Convert(converter = StringListConverter.class)
    public List<String> categories = new ArrayList<>();

    /** Krótkie cechy barwy, np. "ciepły", "niski". */
    @Convert(converter = StringListConverter.class)
    public List<String> traits = new ArrayList<>();

    @Column(length = 1000)
    public String description;

    /** Opis przekazywany do VoxCPM2 (voice design). */
    @Column(length = 1000)
    public String enginePrompt;

    /** Identyfikator nagrania referencyjnego w silniku (głosy klonowane). */
    public String referenceId;

    @Column(length = 1000)
    public String sampleText;

    public String sampleFile;
    public String sampleEngineMode;

    /** Wyliczane dla bieżącego użytkownika (tabela voice_favorites). */
    @Transient
    public boolean favorite;
    public int useCount;
    public Instant createdAt = Instant.now();
}
