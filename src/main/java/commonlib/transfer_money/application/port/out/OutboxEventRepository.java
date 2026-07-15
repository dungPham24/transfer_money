package commonlib.transfer_money.application.port.out;

import commonlib.transfer_money.domain.model.OutboxEvent;

import java.util.List;
import java.util.UUID;

public interface OutboxEventRepository {
    void save(OutboxEvent event);
    List<OutboxEvent> findUnpublished(int limit);
    void markPublished(UUID eventId);
}