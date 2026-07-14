package commonlib.transfer_money.api;

import commonlib.transfer_money.api.dto.TransferRequest;
import commonlib.transfer_money.api.dto.TransferResponse;
import commonlib.transfer_money.application.port.in.TransferFundsUseCase;
import commonlib.transfer_money.domain.model.Transfer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/transfers")
@Tag(name = "Transfers")
public class TransferController {

    private final TransferFundsUseCase transferFundsUseCase;

    public TransferController(TransferFundsUseCase transferFundsUseCase) {
        this.transferFundsUseCase = transferFundsUseCase;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Transfer funds between wallets",
               description = "Idempotent — include a unique Idempotency-Key header per transfer attempt.")
    public TransferResponse transfer(
            @Parameter(description = "Client-generated unique key; retrying with the same key returns the original result")
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