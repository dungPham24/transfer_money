package commonlib.transfer_money.infrastructure.outbox;

import commonlib.transfer_money.application.port.out.OutboxEventRepository;
import commonlib.transfer_money.domain.model.OutboxEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
public class OutboxPoller {

    private static final Logger log = LoggerFactory.getLogger(OutboxPoller.class);
    private static final int BATCH_SIZE = 50;

    private final OutboxEventRepository outboxEventRepository;

    public OutboxPoller(OutboxEventRepository outboxEventRepository) {
        this.outboxEventRepository = outboxEventRepository;
    }

    // fixedDelay (not fixedRate) ensures the next poll only starts after the previous one completes,
    // preventing pile-up if publishing is slow.
    @Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms:5000}")
    @Transactional
    public void publishPendingEvents() {
        List<OutboxEvent> events = outboxEventRepository.findUnpublished(BATCH_SIZE);
        if (events.isEmpty()) return;

        for (OutboxEvent event : events) {
            // Simulate publishing to a message broker (Kafka, SQS, etc.)
            // In production, replace this log statement with the actual broker client call.
            // At-least-once guarantee: if this method crashes after the log but before markPublished,
            // the event stays unpublished and will be re-processed on the next poll cycle.
            log.atInfo()
                    .addKeyValue("eventType",    event.getEventType())
                    .addKeyValue("aggregateId",  event.getAggregateId())
                    .addKeyValue("payload",      event.getPayload())
                    .log("outbox.published");

            outboxEventRepository.markPublished(event.getId());
        }
    }
}