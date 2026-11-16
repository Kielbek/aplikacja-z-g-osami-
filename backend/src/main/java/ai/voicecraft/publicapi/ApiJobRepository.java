package ai.voicecraft.publicapi;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface ApiJobRepository extends JpaRepository<ApiJob, String> {
    Optional<ApiJob> findByIdAndUserId(String id, String userId);

    Optional<ApiJob> findByUserIdAndIdempotencyKey(String userId, String idempotencyKey);

    long countByUserIdAndStatusIn(String userId, Collection<ApiJob.Status> statuses);

    List<ApiJob> findAllByStatusInOrderByCreatedAtAsc(Collection<ApiJob.Status> statuses);

    @Query("select j.audioFile from ApiJob j where j.audioFile is not null")
    List<String> allAudioFiles();

    @Query("select j from ApiJob j where j.createdAt < ?1")
    List<ApiJob> findCreatedBefore(Instant before);

    @Transactional
    @Modifying
    @Query("delete from ApiJob j where j.userId = ?1")
    void deleteByUserId(String userId);
}
