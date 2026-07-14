package commonlib.transfer_money.api.dto;

import commonlib.transfer_money.domain.model.LedgerEntry.EntryType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "One ledger entry in a wallet's transaction history. "
        + "Every transfer produces exactly one DEBIT on the source wallet "
        + "and one CREDIT on the destination wallet.")
public record TransactionHistoryResponse(

        @Schema(description = "Ledger entry UUID", example = "1f3e4d2c-5b6a-7890-abcd-ef1234567890")
        UUID id,

        @Schema(description = "UUID of the transfer this entry belongs to",
                example = "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d")
        UUID transferId,

        @Schema(description = "DEBIT = money left this wallet; CREDIT = money arrived in this wallet",
                example = "CREDIT", allowableValues = {"DEBIT", "CREDIT"})
        EntryType entryType,

        @Schema(description = "Entry amount — always positive regardless of entry type", example = "100.0000")
        BigDecimal amount,

        @Schema(description = "When this ledger entry was written (ISO 8601 UTC)", example = "2026-07-14T10:30:00Z")
        Instant createdAt

) {}