package commonlib.transfer_money.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Money entering the system from outside the ledger (bank transfer, card top-up). */
public class Deposit {

    private final UUID id;
    private final UUID walletId;
    private final BigDecimal amount;
    private final String currency;
    private final Instant createdAt;

    public Deposit(UUID id, UUID walletId, BigDecimal amount, String currency, Instant createdAt) {
        this.id = id;
        this.walletId = walletId;
        this.amount = amount;
        this.currency = currency;
        this.createdAt = createdAt;
    }

    public static Deposit create(UUID walletId, BigDecimal amount, String currency) {
        return new Deposit(UUID.randomUUID(), walletId, amount, currency, Instant.now());
    }

    public UUID getId()             { return id; }
    public UUID getWalletId()       { return walletId; }
    public BigDecimal getAmount()   { return amount; }
    public String getCurrency()     { return currency; }
    public Instant getCreatedAt()   { return createdAt; }
}
