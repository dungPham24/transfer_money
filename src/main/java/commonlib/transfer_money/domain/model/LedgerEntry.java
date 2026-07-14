package commonlib.transfer_money.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Immutable double-entry ledger record.
 * Every transfer produces exactly one DEBIT (source) and one CREDIT (destination).
 * Invariant: SUM(CREDIT) - SUM(DEBIT) per wallet == wallet.balance
 */
public class LedgerEntry {

    public enum EntryType { DEBIT, CREDIT }

    private final UUID id;
    private final UUID transferId;
    private final UUID walletId;
    private final EntryType entryType;
    private final BigDecimal amount;
    private final Instant createdAt;

    public LedgerEntry(UUID id, UUID transferId, UUID walletId, EntryType entryType,
                       BigDecimal amount, Instant createdAt) {
        this.id = id;
        this.transferId = transferId;
        this.walletId = walletId;
        this.entryType = entryType;
        this.amount = amount;
        this.createdAt = createdAt;
    }

    public static LedgerEntry debit(UUID transferId, UUID walletId, BigDecimal amount) {
        return new LedgerEntry(UUID.randomUUID(), transferId, walletId,
                EntryType.DEBIT, amount, Instant.now());
    }

    public static LedgerEntry credit(UUID transferId, UUID walletId, BigDecimal amount) {
        return new LedgerEntry(UUID.randomUUID(), transferId, walletId,
                EntryType.CREDIT, amount, Instant.now());
    }

    public UUID getId()            { return id; }
    public UUID getTransferId()    { return transferId; }
    public UUID getWalletId()      { return walletId; }
    public EntryType getEntryType(){ return entryType; }
    public BigDecimal getAmount()  { return amount; }
    public Instant getCreatedAt()  { return createdAt; }
}