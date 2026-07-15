package commonlib.transfer_money.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public class Transfer {

    private final UUID id;
    private final String idempotencyKey;
    private final UUID sourceWalletId;
    private final UUID destWalletId;
    private final BigDecimal amount;
    private final String currency;
    private final BigDecimal destAmount;    // null for same-currency transfers
    private final BigDecimal exchangeRate;  // null for same-currency transfers
    private TransferStatus status;
    private final Instant createdAt;
    private Instant completedAt;

    public Transfer(UUID id, String idempotencyKey, UUID sourceWalletId, UUID destWalletId,
                    BigDecimal amount, String currency, BigDecimal destAmount, BigDecimal exchangeRate,
                    TransferStatus status, Instant createdAt, Instant completedAt) {
        this.id = id;
        this.idempotencyKey = idempotencyKey;
        this.sourceWalletId = sourceWalletId;
        this.destWalletId = destWalletId;
        this.amount = amount;
        this.currency = currency;
        this.destAmount = destAmount;
        this.exchangeRate = exchangeRate;
        this.status = status;
        this.createdAt = createdAt;
        this.completedAt = completedAt;
    }

    /** Same-currency transfer — destAmount and exchangeRate remain null. */
    public static Transfer create(String idempotencyKey, UUID sourceWalletId, UUID destWalletId,
                                  BigDecimal amount, String currency) {
        return new Transfer(UUID.randomUUID(), idempotencyKey, sourceWalletId, destWalletId,
                amount, currency, null, null, TransferStatus.PENDING, Instant.now(), null);
    }

    /** Cross-currency transfer — destAmount = amount × exchangeRate (already computed by caller). */
    public static Transfer create(String idempotencyKey, UUID sourceWalletId, UUID destWalletId,
                                  BigDecimal amount, String currency,
                                  BigDecimal destAmount, BigDecimal exchangeRate) {
        return new Transfer(UUID.randomUUID(), idempotencyKey, sourceWalletId, destWalletId,
                amount, currency, destAmount, exchangeRate, TransferStatus.PENDING, Instant.now(), null);
    }

    public void complete() {
        this.status = TransferStatus.COMPLETED;
        this.completedAt = Instant.now();
    }

    public void fail() {
        this.status = TransferStatus.FAILED;
        this.completedAt = Instant.now();
    }

    public UUID getId()                  { return id; }
    public String getIdempotencyKey()    { return idempotencyKey; }
    public UUID getSourceWalletId()      { return sourceWalletId; }
    public UUID getDestWalletId()        { return destWalletId; }
    public BigDecimal getAmount()        { return amount; }
    public String getCurrency()          { return currency; }
    public BigDecimal getDestAmount()    { return destAmount; }
    public BigDecimal getExchangeRate()  { return exchangeRate; }
    public TransferStatus getStatus()    { return status; }
    public Instant getCreatedAt()        { return createdAt; }
    public Instant getCompletedAt()      { return completedAt; }
}