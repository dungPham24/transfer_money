package commonlib.transfer_money.infrastructure.persistence.adapter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import commonlib.transfer_money.application.port.out.OutboxEventRepository;
import commonlib.transfer_money.domain.model.OutboxEvent;
import commonlib.transfer_money.infrastructure.persistence.entity.OutboxEventJpaEntity;
import commonlib.transfer_money.infrastructure.persistence.repository.OutboxEventJpaRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class OutboxEventPersistenceAdapter implements OutboxEventRepository {

    private static final TypeReference<Map<String, String>> MAP_TYPE = new TypeReference<>() {};

    private final OutboxEventJpaRepository jpaRepository;
    private final ObjectMapper objectMapper;

    public OutboxEventPersistenceAdapter(OutboxEventJpaRepository jpaRepository, ObjectMapper objectMapper) {
        this.jpaRepository = jpaRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public void save(OutboxEvent event) {
        jpaRepository.save(toEntity(event));
    }

    @Override
    public List<OutboxEvent> findUnpublished(int limit) {
        return jpaRepository.findUnpublished(limit).stream().map(this::toDomain).toList();
    }

    @Override
    public void markPublished(UUID eventId) {
        jpaRepository.markPublished(eventId, Instant.now());
    }

    private OutboxEventJpaEntity toEntity(OutboxEvent e) {
        try {
            String payloadJson = objectMapper.writeValueAsString(e.getPayload());
            return new OutboxEventJpaEntity(e.getId(), e.getEventType(), e.getAggregateId(),
                    payloadJson, e.getCreatedAt(), e.getPublishedAt());
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize outbox event payload", ex);
        }
    }

    private OutboxEvent toDomain(OutboxEventJpaEntity e) {
        try {
            Map<String, String> payload = objectMapper.readValue(e.getPayload(), MAP_TYPE);
            return new OutboxEvent(e.getId(), e.getEventType(), e.getAggregateId(),
                    payload, e.getCreatedAt(), e.getPublishedAt());
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to deserialize outbox event payload", ex);
        }
    }
}