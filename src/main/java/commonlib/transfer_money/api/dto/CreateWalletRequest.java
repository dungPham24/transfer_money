package commonlib.transfer_money.api.dto;

import commonlib.transfer_money.api.validation.ValidCurrency;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "Request body to create a new wallet")
public record CreateWalletRequest(

        @Schema(description = "Full name of the wallet owner", example = "Alice")
        @NotBlank @Size(max = 255)
        String ownerName,

        @Schema(description = "ISO 4217 currency code — validated against java.util.Currency, not just regex",
                example = "USD")
        @NotBlank @ValidCurrency
        String currency

) {}