package ai.voicecraft.jobs;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JobRepository extends JpaRepository<GenerationJob, String> {
    Optional<GenerationJob> findFirstByProjectIdAndUserIdAndStatusInOrderByCreatedAtDesc(String projectId, String userId,
                                                                                      Collection<JobStatus> statuses);

    List<GenerationJob> findAllByStatusIn(Collection<JobStatus> statuses);

    List<GenerationJob> findAllByUserIdAndStatusIn(String userId, Collection<JobStatus> statuses);

    Optional<GenerationJob> findByIdAndUserId(String id, String userId);

    @org.springframework.transaction.annotation.Transactional
    void deleteByUserId(String userId);
}
