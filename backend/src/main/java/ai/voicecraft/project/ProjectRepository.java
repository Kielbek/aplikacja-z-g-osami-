package ai.voicecraft.project;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectRepository extends JpaRepository<Project, String> {
    List<Project> findAllByOrderByUpdatedAtDesc();
}
