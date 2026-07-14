package commonlib.transfer_money.domain.model;

import commonlib.transfer_money.domain.exception.InsufficientFundsException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Wallet domain entity. No Spring or JPA imports — pure business logic.
 * balance is a cached value kept in sync with ledger_entries within the same DB transaction.
 */
public class Wallet {

    private final UUID id;
    private final String ownerName;
    private final String currency;
    private BigDecimal balance;
    private final Instant createdAt;
    private Instant updatedAt;

    public Wallet(UUID id, String ownerName, String currency, BigDecimal balance,
                  Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.ownerName = ownerName;
        this.currency = currency;
        this.balance = balance;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static Wallet create(String ownerName, String currency) {
        Instant now = Instant.now();
        return new Wallet(UUID.randomUUID(), ownerName, currency, BigDecimal.ZERO, now, now);
    }

    /** Reduces balance; throws if insufficient. DB CHECK constraint is the final safety net. */
    public void debit(BigDecimal amount) {
        if (balance.compareTo(amount) < 0) {
            throw new InsufficientFundsException(id, balance, amount);
        }
        balance = balance.subtract(amount);
        updatedAt = Instant.now();
    }

    public void credit(BigDecimal amount) {
        balance = balance.add(amount);
        updatedAt = Instant.now();
    }

    public UUID getId()          { return id; }
    public String getOwnerName() { return ownerName; }
    public String getCurrency()  { return currency; }
    public BigDecimal getBalance() { return balance; }
    public Instant getCreatedAt()  { return createdAt; }
    public Instant getUpdatedAt()  { return updatedAt; }
}