package commonlib.transfer_money.infrastructure.persistence.repository;

import commonlib.transfer_money.infrastructure.persistence.entity.FxRateJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface FxRateJpaRepository extends JpaRepository<FxRateJpaEntity, FxRateJpaEntity.FxRateId> {

    @Query("SELECT r FROM FxRateJpaEntity r WHERE r.fromCurrency = :from AND r.toCurrency = :to ORDER BY r.effectiveAt DESC LIMIT 1")
    Optional<FxRateJpaEntity> findLatest(@Param("from") String from, @Param("to") String to);
}