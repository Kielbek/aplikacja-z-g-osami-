package ai.voicecraft.common;

import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private java.util.Map<String, Object> details;

    public ApiException(HttpStatus status, String message) {
        this(status, message, null);
    }

    public ApiException(HttpStatus status, String message, String code) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public String code() {
        return code;
    }

    /** Dodatkowe dane dla interfejsu (np. ile znaków potrzeba i ile jest dostępnych). */
    public java.util.Map<String, Object> details() {
        return details;
    }

    public ApiException withDetails(java.util.Map<String, Object> details) {
        this.details = details;
        return this;
    }

    /** Brak znaków w limicie planu i pakietach — interfejs proponuje pakiet lub wyższy plan. */
    public static ApiException quota(String message, long needed, long available) {
        return new ApiException(HttpStatus.PAYMENT_REQUIRED, message, "QUOTA_EXCEEDED")
                .withDetails(java.util.Map.of("needed", needed, "available", Math.max(0, available)));
    }

    /** Akcja niedostępna w bieżącym planie lub wyczerpany limit — interfejs proponuje zmianę planu. */
    public static ApiException plan(String message) {
        return new ApiException(HttpStatus.PAYMENT_REQUIRED, message, "PLAN_LIMIT");
    }

    public HttpStatus status() {
        return status;
    }

    public static ApiException notFound(String what) {
        return new ApiException(HttpStatus.NOT_FOUND, what + " nie istnieje.");
    }
}
