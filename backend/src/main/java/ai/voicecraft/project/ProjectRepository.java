package ai.voicecraft.project;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectRepository extends JpaRepository<Project, String> {
    List<Project> findAllByOwnerIdOrderByUpdatedAtDesc(String ownerId);

    Optional<Project> findByIdAndOwnerId(String id, String ownerId);

    long countByOwnerId(String ownerId);

    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("update Project p set p.folderId = null where p.folderId = ?1 and p.ownerId = ?2")
    int clearFolder(String folderId, String ownerId);

    @org.springframework.data.jpa.repository.Query("select distinct s.audioFile from Segment s where s.audioFile is not null")
    List<String> allSegmentAudioFiles();
}
