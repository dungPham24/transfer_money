package commonlib.transfer_money.api;

import commonlib.transfer_money.api.dto.TransferRequest;
import commonlib.transfer_money.api.dto.TransferResponse;
import commonlib.transfer_money.api.exception.ApiError;
import commonlib.transfer_money.application.port.in.TransferFundsUseCase;
import commonlib.transfer_money.domain.model.Transfer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/transfers")
@Tag(name = "Transfers", description = "Idempotent, concurrency-safe fund transfers between wallets")
public class TransferController {

    private final TransferFundsUseCase transferFundsUseCase;

    public TransferController(TransferFundsUseCase transferFundsUseCase) {
        this.transferFundsUseCase = transferFundsUseCase;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "Transfer funds between wallets",
            description = """
                    Atomically debits the source wallet and credits the destination wallet.
                    Writes two balanced ledger entries (DEBIT + CREDIT) and updates both cached balances
                    in a single database transaction.

                    **Idempotency:** include a client-generated `Idempotency-Key` header with every request.
                    Re-sending the same key with the same payload returns the original result without
                    moving money again. Re-sending with a *different* payload returns 409.

                    **Concurrency:** wallets are locked with `SELECT FOR UPDATE` in a fixed UUID order
                    (lower UUID first) to prevent deadlocks. A database `CHECK (balance >= 0)` constraint
                    is the hard stop against overdraft.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Transfer completed successfully"),
            @ApiResponse(responseCode = "400",
                         description = "Validation error (missing/invalid fields) or source == destination wallet",
                         content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404",
                         description = "Source or destination wallet does not exist",
                         content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409",
                         description = "Idempotency key already used with a different payload",
                         content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "422",
                         description = "Source wallet has insufficient funds",
                         content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public TransferResponse transfer(
            @Parameter(description = "Client-generated unique key per transfer attempt. "
                    + "Retrying with the same key and same payload returns the original result.",
                    required = true, example = "550e8400-e29b-41d4-a716-446655440000")
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody TransferRequest request) {

        Transfer transfer = transferFundsUseCase.transfer(
                idempotencyKey,
                request.sourceWalletId(),
                request.destWalletId(),
                request.amount(),
                request.currency()
        );
        return toResponse(transfer);
    }

    private TransferResponse toResponse(Transfer t) {
        return new TransferResponse(t.getId(), t.getIdempotencyKey(), t.getSourceWalletId(),
                t.getDestWalletId(), t.getAmount(), t.getCurrency(),
                t.getStatus(), t.getCreatedAt(), t.getCompletedAt());
    }
}