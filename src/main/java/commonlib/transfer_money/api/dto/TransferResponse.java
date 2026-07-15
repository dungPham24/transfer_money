package commonlib.transfer_money.api.dto;

import commonlib.transfer_money.domain.model.TransferStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "Result of a transfer request. Identical for the original call and any idempotent replay.")
public record TransferResponse(

        @Schema(description = "Transfer UUID", example = "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d")
        UUID id,

        @Schema(description = "Client-supplied idempotency key echoed back",
                example = "550e8400-e29b-41d4-a716-446655440000")
        String idempotencyKey,

        @Schema(description = "Source (debited) wallet UUID", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
        UUID sourceWalletId,

        @Schema(description = "Destination (credited) wallet UUID", example = "7c9e6679-7425-40de-944b-e07fc1f90ae7")
        UUID destWalletId,

        @Schema(description = "Transferred amount in source currency", example = "100.00")
        BigDecimal amount,

        @Schema(description = "ISO 4217 source currency code", example = "USD")
        String currency,

        @Schema(description = "Transfer lifecycle status",
                example = "COMPLETED", allowableValues = {"PENDING", "COMPLETED", "FAILED"})
        TransferStatus status,

        @Schema(description = "When the transfer record was created (ISO 8601 UTC)",
                example = "2026-07-14T10:30:00Z")
        Instant createdAt,

        @Schema(description = "When the transfer completed (null if still PENDING)",
                example = "2026-07-14T10:30:00.123Z", nullable = true)
        Instant completedAt,

        @Schema(description = "Exchange rate applied for cross-currency transfers (null for same-currency)",
                example = "0.92", nullable = true)
        BigDecimal exchangeRate,

        @Schema(description = "Amount credited to destination wallet in its own currency (null for same-currency)",
                example = "46.00", nullable = true)
        BigDecimal destAmount

) {}