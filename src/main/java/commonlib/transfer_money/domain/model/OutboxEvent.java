package commonlib.transfer_money.domain.model;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public class OutboxEvent {

    private final UUID id;
    private final String eventType;
    private final UUID aggregateId;
    private final Map<String, String> payload;
    private final Instant createdAt;
    private final Instant publishedAt;

    public OutboxEvent(UUID id, String eventType, UUID aggregateId,
                       Map<String, String> payload, Instant createdAt, Instant publishedAt) {
        this.id = id;
        this.eventType = eventType;
        this.aggregateId = aggregateId;
        this.payload = payload;
        this.createdAt = createdAt;
        this.publishedAt = publishedAt;
    }

    public static OutboxEvent transferCompleted(Transfer transfer) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("transferId",      transfer.getId().toString());
        payload.put("sourceWalletId",  transfer.getSourceWalletId().toString());
        payload.put("destWalletId",    transfer.getDestWalletId().toString());
        payload.put("amount",          transfer.getAmount().toPlainString());
        payload.put("currency",        transfer.getCurrency());
        payload.put("completedAt",     transfer.getCompletedAt().toString());
        return new OutboxEvent(UUID.randomUUID(), "TRANSFER_COMPLETED",
                transfer.getId(), payload, Instant.now(), null);
    }

    public UUID getId()                      { return id; }
    public String getEventType()             { return eventType; }
    public UUID getAggregateId()             { return aggregateId; }
    public Map<String, String> getPayload()  { return payload; }
    public Instant getCreatedAt()            { return createdAt; }
    public Instant getPublishedAt()          { return publishedAt; }
}