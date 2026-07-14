package commonlib.transfer_money.api.exception;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

@Schema(description = "Structured error response returned on all 4xx / 5xx status codes")
public record ApiError(

        @Schema(description = "Machine-readable error code",
                example = "WALLET_NOT_FOUND",
                allowableValues = {
                        "WALLET_NOT_FOUND",
                        "INSUFFICIENT_FUNDS",
                        "IDEMPOTENCY_CONFLICT",
                        "SAME_WALLET_TRANSFER",
                        "VALIDATION_ERROR",
                        "MISSING_HEADER",
                        "INTERNAL_ERROR"
                })
        String code,

        @Schema(description = "Human-readable description of the error",
                example = "Wallet not found: 3fa85f64-5717-4562-b3fc-2c963f66afa6")
        String message,

        @Schema(description = "UTC timestamp of when the error occurred (ISO 8601)",
                example = "2026-07-14T10:30:00Z")
        Instant timestamp,

        @Schema(description = "Field-level details — populated only for VALIDATION_ERROR",
                example = "[\"ownerName: must not be blank\", \"currency: must be a valid ISO 4217 currency code\"]")
        List<String> details

) {
    public static ApiError of(String code, String message) {
        return new ApiError(code, message, Instant.now(), List.of());
    }

    public static ApiError of(String code, String message, List<String> details) {
        return new ApiError(code, message, Instant.now(), details);
    }
}