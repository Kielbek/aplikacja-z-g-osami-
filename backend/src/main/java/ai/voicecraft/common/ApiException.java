package ai.voicecraft.common;

import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

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
