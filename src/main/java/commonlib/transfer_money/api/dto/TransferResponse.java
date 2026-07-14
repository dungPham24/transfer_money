package commonlib.transfer_money.api.dto;

import commonlib.transfer_money.domain.model.TransferStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransferResponse(
        UUID id,
        String idempotencyKey,
        UUID sourceWalletId,
        UUID destWalletId,
        BigDecimal amount,
        String currency,
        TransferStatus status,
        Instant createdAt,
        Instant completedAt
) {}