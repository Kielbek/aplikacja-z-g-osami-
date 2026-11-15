package ai.voicecraft.project;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectFolderRepository extends JpaRepository<ProjectFolder, String> {
    List<ProjectFolder> findAllByOwnerIdOrderByNameAsc(String ownerId);

    Optional<ProjectFolder> findByIdAndOwnerId(String id, String ownerId);

    long countByOwnerId(String ownerId);
}
