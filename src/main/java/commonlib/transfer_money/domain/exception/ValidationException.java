package commonlib.transfer_money.domain.exception;

import java.util.List;

public class ValidationException extends RuntimeException {

    private final List<String> details;

    public ValidationException(String field, String reason) {
        super("Validation failed");
        this.details = List.of(field + ": " + reason);
    }

    public ValidationException(List<String> details) {
        super("Validation failed");
        this.details = List.copyOf(details);
    }

    public List<String> getDetails() {
        return details;
    }
}
