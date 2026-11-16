package ai.voicecraft.audio;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface StoredFileRepository extends JpaRepository<StoredFile, String> {
    @Query("select coalesce(sum(f.bytes), 0) from StoredFile f where f.userId = ?1")
    long sumBytes(String userId);

    @Transactional
    @Modifying
    @Query("delete from StoredFile f where f.key = ?1")
    void deleteByKey(String key);
}
