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

    @Query("SELECT e FROM OutboxEventJpaEntity e WHERE e.publishedAt IS NULL ORDER BY e.createdAt ASC LIMIT :limit")
    List<OutboxEventJpaEntity> findUnpublished(@Param("limit") int limit);

    @Modifying
    @Query("UPDATE OutboxEventJpaEntity e SET e.publishedAt = :now WHERE e.id = :id")
    void markPublished(@Param("id") UUID id, @Param("now") Instant now);
}