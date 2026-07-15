package commonlib.transfer_money.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Immutable double-entry ledger record.
 * Every transfer produces exactly one DEBIT (source) and one CREDIT (destination); every
 * deposit produces exactly one CREDIT with no matching DEBIT (money entering from outside
 * the ledger). Exactly one of transferId / depositId is set, never both, never neither.
 * For same-currency transfers: both amounts are equal, both currencies are the same.
 * For cross-currency transfers: DEBIT amount is in source currency, CREDIT amount is in dest currency.
 * Invariant per wallet: SUM(CREDIT in wallet.currency) - SUM(DEBIT in wallet.currency) == wallet.balance
 */
public class LedgerEntry {

    public enum EntryType { DEBIT, CREDIT }

    private final UUID id;
    private final UUID transferId;
    private final UUID depositId;
    private final UUID walletId;
    private final EntryType entryType;
    private final BigDecimal amount;
    private final String currency;
    private final Instant createdAt;

    public LedgerEntry(UUID id, UUID transferId, UUID depositId, UUID walletId, EntryType entryType,
                       BigDecimal amount, String currency, Instant createdAt) {
        this.id = id;
        this.transferId = transferId;
        this.depositId = depositId;
        this.walletId = walletId;
        this.entryType = entryType;
        this.amount = amount;
        this.currency = currency;
        this.createdAt = createdAt;
    }

    public static LedgerEntry debit(UUID transferId, UUID walletId, BigDecimal amount, String currency) {
        return new LedgerEntry(UUID.randomUUID(), transferId, null, walletId,
                EntryType.DEBIT, amount, currency, Instant.now());
    }

    public static LedgerEntry credit(UUID transferId, UUID walletId, BigDecimal amount, String currency) {
        return new LedgerEntry(UUID.randomUUID(), transferId, null, walletId,
                EntryType.CREDIT, amount, currency, Instant.now());
    }

    public static LedgerEntry deposit(UUID depositId, UUID walletId, BigDecimal amount, String currency) {
        return new LedgerEntry(UUID.randomUUID(), null, depositId, walletId,
                EntryType.CREDIT, amount, currency, Instant.now());
    }

    public UUID getId()             { return id; }
    public UUID getTransferId()     { return transferId; }
    public UUID getDepositId()      { return depositId; }
    public UUID getWalletId()       { return walletId; }
    public EntryType getEntryType() { return entryType; }
    public BigDecimal getAmount()   { return amount; }
    public String getCurrency()     { return currency; }
    public Instant getCreatedAt()   { return createdAt; }
}
