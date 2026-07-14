package commonlib.transfer_money.api.dto;

import commonlib.transfer_money.api.validation.ValidCurrency;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateWalletRequest(
        @NotBlank @Size(max = 255) String ownerName,
        @NotBlank @ValidCurrency String currency   // full ISO 4217 check, not just regex
) {}