package commonlib.transfer_money.infrastructure.persistence.repository;

import commonlib.transfer_money.infrastructure.persistence.entity.OutboxEventJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OutboxEventJpaRepository extends JpaRepository<OutboxEventJpaEntity, UUID> {

    // Native + FOR UPDATE SKIP LOCKED: when multiple app instances run OutboxPoller concurrently,
    // each poll grabs a disjoint batch instead of all instances racing to publish the same rows.
    // JPQL has no SKIP LOCKED support, hence native SQL here (column names match the entity 1:1).
    @Query(value = "SELECT * FROM outbox_events WHERE published_at IS NULL " +
            "ORDER BY created_at ASC LIMIT :limit FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<OutboxEventJpaEntity> findUnpublished(@Param("limit") int limit);

    @Modifying
    @Query("UPDATE OutboxEventJpaEntity e SET e.publishedAt = :now WHERE e.id = :id")
    void markPublished(@Param("id") UUID id, @Param("now") Instant now);
}