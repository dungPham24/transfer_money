package commonlib.transfer_money.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;

@Schema(description = "Request body to deposit funds into a wallet from outside the ledger "
        + "(e.g. bank transfer, card top-up). Demo/seeding utility — see WalletService.deposit().")
public record DepositRequest(

        @Schema(description = "Amount to deposit. Must be > 0, up to 15 integer digits and 4 decimal places.",
                example = "500.00")
        @NotNull @DecimalMin("0.0001") @Digits(integer = 15, fraction = 4)
        BigDecimal amount,

        @Schema(description = "ISO 4217 currency code — must match the wallet's own currency", example = "USD")
        @NotBlank @Pattern(regexp = "[A-Z]{3}", message = "must be a 3-letter ISO 4217 currency code")
        String currency

) {}
