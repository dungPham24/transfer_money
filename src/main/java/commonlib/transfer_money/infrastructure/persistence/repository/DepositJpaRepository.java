package commonlib.transfer_money.infrastructure.persistence.repository;

import commonlib.transfer_money.infrastructure.persistence.entity.DepositJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface DepositJpaRepository extends JpaRepository<DepositJpaEntity, UUID> {
}
