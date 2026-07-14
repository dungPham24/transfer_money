package commonlib.transfer_money.api.exception;

import commonlib.transfer_money.domain.exception.IdempotencyConflictException;
import commonlib.transfer_money.domain.exception.InsufficientFundsException;
import commonlib.transfer_money.domain.exception.SameWalletTransferException;
import commonlib.transfer_money.domain.exception.ValidationException;
import commonlib.transfer_money.domain.exception.WalletNotFoundException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(WalletNotFoundException.class)
    ResponseEntity<ApiError> handleNotFound(WalletNotFoundException ex) {
        log.atWarn()
                .addKeyValue("outcome", "FAILED")
                .addKeyValue("reason", "WALLET_NOT_FOUND")
                .log("transfer.failed");
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of("WALLET_NOT_FOUND", ex.getMessage()));
    }

    // 422 not 409: the request is well-formed and understood, but the current wallet
    // state prevents processing (business rule). 409 is reserved for concurrent-request
    // conflicts (e.g. idempotency key collision).
    @ExceptionHandler(InsufficientFundsException.class)
    ResponseEntity<ApiError> handleInsufficientFunds(InsufficientFundsException ex) {
        log.atWarn()
                .addKeyValue("outcome", "FAILED")
                .addKeyValue("reason", "INSUFFICIENT_FUNDS")
                .log("transfer.failed");
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(ApiError.of("INSUFFICIENT_FUNDS", ex.getMessage()));
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    ResponseEntity<ApiError> handleIdempotencyConflict(IdempotencyConflictException ex) {
        log.atWarn()
                .addKeyValue("outcome", "FAILED")
                .addKeyValue("reason", "IDEMPOTENCY_CONFLICT")
                .log("transfer.failed");
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of("IDEMPOTENCY_CONFLICT", ex.getMessage()));
    }

    @ExceptionHandler(SameWalletTransferException.class)
    ResponseEntity<ApiError> handleSameWallet(SameWalletTransferException ex) {
        log.atWarn()
                .addKeyValue("outcome", "FAILED")
                .addKeyValue("reason", "SAME_WALLET_TRANSFER")
                .log("transfer.failed");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiError.of("SAME_WALLET_TRANSFER", ex.getMessage()));
    }

    // Domain-level business rule violations (e.g. currency mismatch, invalid state transition)
    @ExceptionHandler(ValidationException.class)
    ResponseEntity<ApiError> handleDomainValidation(ValidationException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiError.of("VALIDATION_ERROR", ex.getMessage(), ex.getDetails()));
    }

    // Jakarta Bean Validation on @RequestBody fields
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> handleBeanValidation(MethodArgumentNotValidException ex) {
        List<String> details = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .toList();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiError.of("VALIDATION_ERROR", "Request validation failed", details));
    }

    // Jakarta Bean Validation on @RequestParam / @PathVariable in @Validated controllers
    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ApiError> handleConstraintViolation(ConstraintViolationException ex) {
        List<String> details = ex.getConstraintViolations().stream()
                .map(v -> {
                    String path = v.getPropertyPath().toString();
                    int dot = path.lastIndexOf('.');
                    return (dot >= 0 ? path.substring(dot + 1) : path) + ": " + v.getMessage();
                })
                .toList();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiError.of("VALIDATION_ERROR", "Request validation failed", details));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<ApiError> handleMissingHeader(MissingRequestHeaderException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiError.of("MISSING_HEADER",
                        "Required header '" + ex.getHeaderName() + "' is missing"));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> handleUnexpected(Exception ex) {
        log.atError()
                .addKeyValue("outcome", "FAILED")
                .addKeyValue("reason", "INTERNAL_ERROR")
                .setCause(ex)
                .log("transfer.failed");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiError.of("INTERNAL_ERROR", "An unexpected error occurred"));
    }
}