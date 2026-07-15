package commonlib.transfer_money.domain.exception;

public class FxRateNotFoundException extends RuntimeException {
    public FxRateNotFoundException(String fromCurrency, String toCurrency) {
        super("No FX rate available for " + fromCurrency + " → " + toCurrency);
    }
}