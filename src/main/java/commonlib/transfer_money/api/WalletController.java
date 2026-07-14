package commonlib.transfer_money.api;

import commonlib.transfer_money.api.dto.CreateWalletRequest;
import commonlib.transfer_money.api.dto.PagedResponse;
import commonlib.transfer_money.api.dto.TransactionHistoryResponse;
import commonlib.transfer_money.api.dto.WalletResponse;
import commonlib.transfer_money.api.exception.ApiError;
import commonlib.transfer_money.application.PageResult;
import commonlib.transfer_money.application.port.in.CreateWalletUseCase;
import commonlib.transfer_money.application.port.in.GetWalletUseCase;
import commonlib.transfer_money.domain.model.LedgerEntry;
import commonlib.transfer_money.domain.model.Wallet;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/wallets")
@Tag(name = "Wallets", description = "Create wallets and query balance / transaction history")
@Validated
public class WalletController {

    private final CreateWalletUseCase createWalletUseCase;
    private final GetWalletUseCase    getWalletUseCase;

    public WalletController(CreateWalletUseCase createWalletUseCase,
                             GetWalletUseCase getWalletUseCase) {
        this.createWalletUseCase = createWalletUseCase;
        this.getWalletUseCase    = getWalletUseCase;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a new wallet",
               description = "Creates a wallet with zero balance. `currency` must be a valid ISO 4217 code.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Wallet created successfully"),
            @ApiResponse(responseCode = "400", description = "Validation failed — blank owner name or invalid currency code",
                         content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public WalletResponse create(@Valid @RequestBody CreateWalletRequest request) {
        return toResponse(createWalletUseCase.createWallet(request.ownerName(), request.currency()));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get wallet by ID",
               description = "Returns the wallet with its current cached balance.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Wallet found"),
            @ApiResponse(responseCode = "404", description = "No wallet exists with the given ID",
                         content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public WalletResponse get(@PathVariable UUID id) {
        return toResponse(getWalletUseCase.getWallet(id));
    }

    @GetMapping("/{id}/transactions")
    @Operation(summary = "Get paginated ledger history",
               description = "Returns ledger entries for the wallet, newest first. "
                       + "Each transfer produces exactly one DEBIT on the source and one CREDIT on the destination.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Transaction history returned"),
            @ApiResponse(responseCode = "400", description = "Invalid pagination parameters (page < 0 or size > 100)",
                         content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "Wallet not found",
                         content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public PagedResponse<TransactionHistoryResponse> history(
            @PathVariable UUID id,
            @Parameter(description = "Zero-based page number")
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @Parameter(description = "Page size — maximum 100")
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {

        PageResult<LedgerEntry> result = getWalletUseCase.getTransactionHistory(id, page, size);
        return new PagedResponse<>(
                result.content().stream()
                        .map(e -> new TransactionHistoryResponse(
                                e.getId(), e.getTransferId(), e.getEntryType(),
                                e.getAmount(), e.getCreatedAt()))
                        .toList(),
                result.totalElements(),
                result.totalPages(),
                result.page(),
                result.size()
        );
    }

    private WalletResponse toResponse(Wallet w) {
        return new WalletResponse(w.getId(), w.getOwnerName(), w.getCurrency(),
                w.getBalance(), w.getCreatedAt());
    }
}