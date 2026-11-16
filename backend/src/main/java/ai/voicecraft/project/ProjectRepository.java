package ai.voicecraft.project;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectRepository extends JpaRepository<Project, String> {
    List<Project> findAllByOwnerIdOrderByUpdatedAtDesc(String ownerId);

    Optional<Project> findByIdAndOwnerId(String id, String ownerId);

    long countByOwnerId(String ownerId);

    /** Puste projekty (bez tekstu, nigdy niegenerowane), nieruszane od podanej chwili. */
    @org.springframework.data.jpa.repository.Query("select p from Project p where p.lastGeneratedAt is null and p.updatedAt < ?1"
            + " and not exists (select s from Segment s where s.project = p and length(trim(s.text)) > 0)")
    List<Project> findEmptyBefore(java.time.Instant before);

    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("update Project p set p.folderId = null where p.folderId = ?1 and p.ownerId = ?2")
    int clearFolder(String folderId, String ownerId);

    @org.springframework.data.jpa.repository.Query("select distinct s.audioFile from Segment s where s.audioFile is not null")
    List<String> allSegmentAudioFiles();

    /** Plik nagrania fragmentu i właściciel projektu (uzupełnienie ewidencji miejsca). */
    @org.springframework.data.jpa.repository.Query("select s.audioFile, p.ownerId from Project p join p.segments s where s.audioFile is not null")
    List<Object[]> segmentAudioOwners();

    /**
     * Wygaszenie nagrań fragmentów starszych niż {@code before} w projektach użytkowników danego planu
     * (tekst zostaje — fragment wraca do stanu „do wygenerowania”, a plik usuwa sprzątanie osieroconych plików).
     */
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("update Segment s set s.audioFile = null, s.generatedHash = null, s.durationMs = 0 "
            + "where s.audioFile is not null and s.generatedAt < ?2 and s.project.ownerId in "
            + "(select a.userId from Account a where a.planId = ?1)")
    int expireAudio(ai.voicecraft.account.SubscriptionPlan plan, java.time.Instant before);
}
