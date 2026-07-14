package commonlib.transfer_money.api.exception;

import commonlib.transfer_money.api.TransferController;
import commonlib.transfer_money.api.WalletController;
import commonlib.transfer_money.application.port.in.CreateWalletUseCase;
import commonlib.transfer_money.application.port.in.GetWalletUseCase;
import commonlib.transfer_money.application.port.in.TransferFundsUseCase;
import commonlib.transfer_money.domain.exception.IdempotencyConflictException;
import commonlib.transfer_money.domain.exception.InsufficientFundsException;
import commonlib.transfer_money.domain.exception.SameWalletTransferException;
import commonlib.transfer_money.domain.exception.ValidationException;
import commonlib.transfer_money.domain.exception.WalletNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Isolated @WebMvcTest slice — loads only the web layer (controllers + advice).
 * No Testcontainers, no Spring Data. Verifies that every domain exception maps
 * to the correct HTTP status, error code, and response shape.
 */
@WebMvcTest({WalletController.class, TransferController.class})
class GlobalExceptionHandlerTest {

    @Autowired MockMvc mvc;

    @MockitoBean CreateWalletUseCase createWalletUseCase;
    @MockitoBean GetWalletUseCase    getWalletUseCase;
    @MockitoBean TransferFundsUseCase transferFundsUseCase;

    // ── WalletNotFoundException → 404 ───────────────────────────────────────

    @Test
    void walletNotFound_returns404AndWalletNotFoundCode() throws Exception {
        UUID id = UUID.randomUUID();
        when(getWalletUseCase.getWallet(id)).thenThrow(new WalletNotFoundException(id));

        mvc.perform(get("/api/v1/wallets/" + id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WALLET_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value(containsString(id.toString())))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.details").isArray());
    }

    // ── InsufficientFundsException → 422 ────────────────────────────────────
    // 422 not 409: the wallet state makes the request semantically unprocessable
    // (business rule), not a concurrency conflict between two requests.

    @Test
    void insufficientFunds_returns422AndInsufficientFundsCode() throws Exception {
        UUID src = UUID.randomUUID();
        when(transferFundsUseCase.transfer(any(), any(), any(), any(), any()))
                .thenThrow(new InsufficientFundsException(src, new BigDecimal("30.00"), new BigDecimal("100.00")));

        mvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validTransferBody()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"))
                .andExpect(jsonPath("$.message").value(containsString(src.toString())))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.details").isArray());
    }

    // ── IdempotencyConflictException → 409 ──────────────────────────────────

    @Test
    void idempotencyConflict_returns409AndIdempotencyConflictCode() throws Exception {
        when(transferFundsUseCase.transfer(any(), any(), any(), any(), any()))
                .thenThrow(new IdempotencyConflictException("key-dup"));

        mvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", "key-dup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validTransferBody()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"))
                .andExpect(jsonPath("$.message").value(containsString("key-dup")))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.details").isArray());
    }

    // ── SameWalletTransferException → 400 ───────────────────────────────────

    @Test
    void sameWalletTransfer_returns400AndSameWalletTransferCode() throws Exception {
        when(transferFundsUseCase.transfer(any(), any(), any(), any(), any()))
                .thenThrow(new SameWalletTransferException());

        mvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validTransferBody()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SAME_WALLET_TRANSFER"))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.details").isArray());
    }

    // ── ValidationException (domain) → 400 ──────────────────────────────────
    // Thrown by domain/service logic for business-rule violations, distinct from
    // MethodArgumentNotValidException which is thrown by Jakarta Bean Validation.

    @Test
    void domainValidation_returns400WithValidationErrorCodeAndDetails() throws Exception {
        when(createWalletUseCase.createWallet(any(), any()))
                .thenThrow(new ValidationException("currency", "VND is not yet supported"));

        mvc.perform(post("/api/v1/wallets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ownerName\": \"Alice\", \"currency\": \"USD\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details", hasSize(1)))
                .andExpect(jsonPath("$.details[0]").value("currency: VND is not yet supported"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    // ── MethodArgumentNotValidException → 400 ───────────────────────────────
    // Jakarta Bean Validation on @RequestBody — triggered before the service is called.

    @Test
    void beanValidation_blankOwnerName_returns400WithFieldDetails() throws Exception {
        mvc.perform(post("/api/v1/wallets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ownerName\": \"\", \"currency\": \"USD\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("Request validation failed"))
                .andExpect(jsonPath("$.details", not(empty())))
                .andExpect(jsonPath("$.details[0]", containsString("ownerName")))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void beanValidation_invalidCurrency_returns400WithFieldDetails() throws Exception {
        mvc.perform(post("/api/v1/wallets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ownerName\": \"Alice\", \"currency\": \"FAKE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details", not(empty())))
                .andExpect(jsonPath("$.details[0]", containsString("currency")));
    }

    // ── MissingRequestHeaderException → 400 ─────────────────────────────────

    @Test
    void missingIdempotencyKeyHeader_returns400AndMissingHeaderCode() throws Exception {
        mvc.perform(post("/api/v1/transfers")
                        // Idempotency-Key header intentionally omitted
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validTransferBody()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_HEADER"))
                .andExpect(jsonPath("$.message").value(containsString("Idempotency-Key")))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    // ── Response shape contract ──────────────────────────────────────────────
    // Every error response must carry all four fields — no field may be absent.

    @Test
    void everyErrorResponse_hasAllFourRequiredFields() throws Exception {
        UUID id = UUID.randomUUID();
        when(getWalletUseCase.getWallet(id)).thenThrow(new WalletNotFoundException(id));

        mvc.perform(get("/api/v1/wallets/" + id))
                .andExpect(jsonPath("$.code").exists())
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.details").exists());
    }

    // ── Helper ──────────────────────────────────────────────────────────────

    private String validTransferBody() {
        return """
                {
                  "sourceWalletId": "11111111-1111-1111-1111-111111111111",
                  "destWalletId":   "22222222-2222-2222-2222-222222222222",
                  "amount": 50.00,
                  "currency": "USD"
                }
                """;
    }
}