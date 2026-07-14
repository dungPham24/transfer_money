package commonlib.transfer_money.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "Wallet with its current cached balance")
public record WalletResponse(

        @Schema(description = "Wallet UUID (auto-generated)", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
        UUID id,

        @Schema(description = "Owner name", example = "Alice")
        String ownerName,

        @Schema(description = "ISO 4217 currency code", example = "USD")
        String currency,

        @Schema(description = "Current balance. Cached for performance; the double-entry ledger is the source of truth.",
                example = "250.0000")
        BigDecimal balance,

        @Schema(description = "Wallet creation timestamp (ISO 8601 UTC)", example = "2026-07-14T10:30:00Z")
        Instant createdAt

) {}