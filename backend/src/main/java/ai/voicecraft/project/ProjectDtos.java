package ai.voicecraft.project;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public final class ProjectDtos {
    private ProjectDtos() {}

    public record SegmentDto(String id, int position, String text, String status, long durationMs, String engineMode,
                             Instant generatedAt, String error, String audioVersion, String voiceId) {}

    public record Stats(int characters, int segmentCount, int readyCount, int staleCount, int draftCount, int errorCount,
                        long durationMs, String status) {}

    public record VoiceRef(String id, String name) {}

    public record ProjectDto(String id, String name, Instant createdAt, Instant updatedAt, Instant lastGeneratedAt,
                             VoiceSettings settings, VoiceRef voice, Stats stats, List<SegmentDto> segments) {}

    public record CreateProject(@Size(max = 200) String name, @Size(max = 100_000) String text) {}

    public record UpdateProject(@Size(min = 1, max = 200) String name, VoiceSettings settings) {}

    /** voiceId: głos fragmentu (null = głos projektu). */
    public record SegmentInput(@NotNull String id, @NotNull @Size(max = 5000) String text, String voiceId) {}
}
