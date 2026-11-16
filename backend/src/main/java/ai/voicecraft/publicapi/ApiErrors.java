package ai.voicecraft.publicapi;

import ai.voicecraft.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Jednolity format błędów publicznego API:
 * {@code {"error": {"code": "quota_exceeded", "message": "…", "request_id": "req_…"}}}.
 * Kody są stabilne (do obsługi w kodzie klienta), komunikaty — po polsku, dla ludzi.
 */
@RestControllerAdvice(assignableTypes = V1Controller.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ApiErrors {
    private static final Logger log = LoggerFactory.getLogger(ApiErrors.class);
    static final String REQUEST_ID = "narrivy.requestId";

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Map<String, Object>> api(ApiException e, HttpServletRequest req) {
        String code = code(e);
        var res = ResponseEntity.status(e.status());
        if ("engine_busy".equals(code)) res.header("Retry-After", "5");
        if (e.status().is5xxServerError()) log.warn("API {} {}: {}", e.status().value(), code, e.getMessage());
        return res.body(body(code, e.getMessage(), req));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, Object>> invalid(IllegalArgumentException e, HttpServletRequest req) {
        return ResponseEntity.badRequest().body(body("invalid_request", e.getMessage(), req));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, Object>> unreadable(HttpMessageNotReadableException e, HttpServletRequest req) {
        return ResponseEntity.badRequest().body(body("invalid_json", "Treść żądania nie jest poprawnym JSON-em lub ma pola niewłaściwego typu.", req));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<Map<String, Object>> mediaType(HttpMediaTypeNotSupportedException e, HttpServletRequest req) {
        return ResponseEntity.status(415).body(body("unsupported_media_type", "Wyślij treść jako application/json.", req));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<Map<String, Object>> method(HttpRequestMethodNotSupportedException e, HttpServletRequest req) {
        return ResponseEntity.status(405).body(body("method_not_allowed", "Metoda " + e.getMethod() + " nie jest obsługiwana dla tego adresu.", req));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, Object>> unexpected(Exception e, HttpServletRequest req) {
        log.error("Nieoczekiwany błąd API [{}]", req.getAttribute(REQUEST_ID), e);
        return ResponseEntity.status(500).body(body("internal_error", "Wystąpił nieoczekiwany błąd. Podaj request_id, kontaktując się z pomocą.", req));
    }

    /** Stabilny kod błędu na podstawie wyjątku aplikacji. */
    static String code(ApiException e) {
        String c = e.code();
        if (c != null) {
            return switch (c) {
                case "PLAN_LIMIT" -> "quota_exceeded";
                case "ENGINE_BUSY" -> "engine_busy";
                case "EMAIL_NOT_VERIFIED" -> "email_not_verified";
                default -> c.toLowerCase();
            };
        }
        return switch (e.status()) {
            case NOT_FOUND -> "not_found";
            case BAD_REQUEST -> "invalid_request";
            case CONFLICT -> "conflict";
            case SERVICE_UNAVAILABLE -> "engine_unavailable";
            case FORBIDDEN -> "forbidden";
            case UNAUTHORIZED -> "unauthorized";
            default -> e.status().is5xxServerError() ? "engine_error" : "error";
        };
    }

    static Map<String, Object> body(String code, String message, HttpServletRequest req) {
        var err = new LinkedHashMap<String, Object>();
        err.put("code", code);
        err.put("message", message == null ? "" : message);
        Object id = req == null ? null : req.getAttribute(REQUEST_ID);
        if (id != null) err.put("request_id", id);
        return Map.of("error", err);
    }

    /** Zapis błędu bezpośrednio w filtrze (przed kontrolerem). */
    static void write(HttpServletResponse res, HttpServletRequest req, HttpStatus status, String code, String message) throws IOException {
        res.setStatus(status.value());
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setCharacterEncoding("UTF-8");
        String id = String.valueOf(req.getAttribute(REQUEST_ID));
        res.getWriter().write("{\"error\":{\"code\":\"" + code + "\",\"message\":\"" + message.replace("\\", "\\\\").replace("\"", "\\\"")
                + "\",\"request_id\":\"" + id + "\"}}");
    }
}
