package commonlib.transfer_money.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.util.UUID;

@Schema(description = "Request body to initiate a fund transfer between two wallets")
public record TransferRequest(

        @Schema(description = "UUID of the wallet to debit", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
        @NotNull
        UUID sourceWalletId,

        @Schema(description = "UUID of the wallet to credit", example = "7c9e6679-7425-40de-944b-e07fc1f90ae7")
        @NotNull
        UUID destWalletId,

        @Schema(description = "Amount to transfer. Must be > 0, up to 15 integer digits and 4 decimal places.",
                example = "100.00")
        @NotNull @DecimalMin("0.0001") @Digits(integer = 15, fraction = 4)
        BigDecimal amount,

        @Schema(description = "ISO 4217 currency code (3 uppercase letters)", example = "USD")
        @NotBlank @Pattern(regexp = "[A-Z]{3}", message = "must be a 3-letter ISO 4217 currency code")
        String currency

) {}