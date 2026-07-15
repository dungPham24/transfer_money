package commonlib.transfer_money.infrastructure.persistence.adapter;

import commonlib.transfer_money.application.port.out.WalletRepository;
import commonlib.transfer_money.domain.model.Wallet;
import commonlib.transfer_money.infrastructure.persistence.entity.WalletJpaEntity;
import commonlib.transfer_money.infrastructure.persistence.repository.WalletJpaRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

@Component
public class WalletPersistenceAdapter implements WalletRepository {

    private final WalletJpaRepository jpaRepository;

    public WalletPersistenceAdapter(WalletJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Wallet save(Wallet wallet) {
        return toDomain(jpaRepository.save(toEntity(wallet)));
    }

    @Override
    public Optional<Wallet> findById(UUID id) {
        return jpaRepository.findById(id).map(this::toDomain);
    }

    @Override
    public Optional<Wallet> findByIdForUpdate(UUID id) {
        return jpaRepository.findByIdForUpdate(id).map(this::toDomain);
    }

    private WalletJpaEntity toEntity(Wallet w) {
        return new WalletJpaEntity(w.getId(), w.getOwnerName(), w.getCurrency(),
                w.getBalance(), w.getCreatedAt(), w.getUpdatedAt());
    }

    private Wallet toDomain(WalletJpaEntity e) {
        return new Wallet(e.getId(), e.getOwnerName(), e.getCurrency(),
                e.getBalance(), e.getCreatedAt(), e.getUpdatedAt());
    }
}
