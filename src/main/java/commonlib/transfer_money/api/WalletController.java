package commonlib.transfer_money.api;

import commonlib.transfer_money.api.dto.CreateWalletRequest;
import commonlib.transfer_money.api.dto.TransactionHistoryResponse;
import commonlib.transfer_money.api.dto.WalletResponse;
import commonlib.transfer_money.application.port.in.CreateWalletUseCase;
import commonlib.transfer_money.application.port.in.GetWalletUseCase;
import commonlib.transfer_money.domain.model.Wallet;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/wallets")
@Tag(name = "Wallets")
public class WalletController {

    private final CreateWalletUseCase createWalletUseCase;
    private final GetWalletUseCase getWalletUseCase;

    public WalletController(CreateWalletUseCase createWalletUseCase,
                             GetWalletUseCase getWalletUseCase) {
        this.createWalletUseCase = createWalletUseCase;
        this.getWalletUseCase = getWalletUseCase;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a new wallet")
    public WalletResponse create(@Valid @RequestBody CreateWalletRequest request) {
        return toResponse(createWalletUseCase.createWallet(request.ownerName(), request.currency()));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get wallet balance")
    public WalletResponse get(@PathVariable UUID id) {
        return toResponse(getWalletUseCase.getWallet(id));
    }

    @GetMapping("/{id}/transactions")
    @Operation(summary = "Get transaction history (ledger entries) for a wallet")
    public List<TransactionHistoryResponse> history(@PathVariable UUID id) {
        return getWalletUseCase.getTransactionHistory(id).stream()
                .map(e -> new TransactionHistoryResponse(
                        e.getId(), e.getTransferId(), e.getEntryType(), e.getAmount(), e.getCreatedAt()))
                .toList();
    }

    private WalletResponse toResponse(Wallet w) {
        return new WalletResponse(w.getId(), w.getOwnerName(), w.getCurrency(),
                w.getBalance(), w.getCreatedAt());
    }
}