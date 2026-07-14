package commonlib.transfer_money.api.dto;

import commonlib.transfer_money.domain.model.LedgerEntry.EntryType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransactionHistoryResponse(
        UUID id,
        UUID transferId,
        EntryType entryType,
        BigDecimal amount,
        Instant createdAt
) {}