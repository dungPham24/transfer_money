package commonlib.transfer_money.infrastructure.persistence.adapter;

import commonlib.transfer_money.application.port.out.DepositRepository;
import commonlib.transfer_money.domain.model.Deposit;
import commonlib.transfer_money.infrastructure.persistence.entity.DepositJpaEntity;
import commonlib.transfer_money.infrastructure.persistence.repository.DepositJpaRepository;
import org.springframework.stereotype.Component;

@Component
public class DepositPersistenceAdapter implements DepositRepository {

    private final DepositJpaRepository jpaRepository;

    public DepositPersistenceAdapter(DepositJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Deposit save(Deposit deposit) {
        DepositJpaEntity entity = new DepositJpaEntity(deposit.getId(), deposit.getWalletId(),
                deposit.getAmount(), deposit.getCurrency(), deposit.getCreatedAt());
        DepositJpaEntity saved = jpaRepository.save(entity);
        return new Deposit(saved.getId(), saved.getWalletId(), saved.getAmount(),
                saved.getCurrency(), saved.getCreatedAt());
    }
}
