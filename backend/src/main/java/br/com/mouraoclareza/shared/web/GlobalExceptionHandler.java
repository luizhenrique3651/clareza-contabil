package br.com.mouraoclareza.shared.web;

import br.com.mouraoclareza.shared.exception.ClarezaException;
import br.com.mouraoclareza.shared.exception.ValidationErrorItem;
import br.com.mouraoclareza.shared.security.AuthenticatedTenantContext;
import br.com.mouraoclareza.shared.security.TenantContextHolder;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Handler global RFC 9457 (Problem Details for HTTP APIs) compatível com o contrato
 * https://clareza.example/problems/<code> exigido pelo frontend e pela suíte de regressão.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ClarezaException.class)
    public ResponseEntity<Map<String, Object>> handleClarezaException(
            ClarezaException ex,
            HttpServletRequest request
    ) {
        String codeSlug = ex.getCode().toLowerCase(Locale.ROOT).replace('_', '-');
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "https://clareza.example/problems/" + codeSlug);
        body.put("title", titleForStatus(ex.getStatus()));
        body.put("status", ex.getStatus());
        body.put("detail", ex.getMessage());
        body.put("instance", request.getRequestURI());
        body.put("code", ex.getCode());
        body.put("requestId", resolveRequestId(request));
        if (ex.getErrors() != null && !ex.getErrors().isEmpty()) {
            body.put("errors", ex.getErrors());
        }
        return ResponseEntity
                .status(ex.getStatus())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleBeanValidationException(
            MethodArgumentNotValidException ex,
            HttpServletRequest request
    ) {
        List<ValidationErrorItem> items = ex.getBindingResult().getFieldErrors().stream()
                .map(err -> new ValidationErrorItem(
                        err.getField(),
                        "INVALID_FIELD",
                        err.getDefaultMessage() != null ? err.getDefaultMessage() : "Valor inválido."
                ))
                .toList();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "https://clareza.example/problems/validation-error");
        body.put("title", "Unprocessable Entity");
        body.put("status", HttpStatus.UNPROCESSABLE_ENTITY.value());
        body.put("detail", "Falha de validação nos dados informados.");
        body.put("instance", request.getRequestURI());
        body.put("code", "VALIDATION_ERROR");
        body.put("requestId", resolveRequestId(request));
        body.put("errors", items);

        return ResponseEntity
                .status(HttpStatus.UNPROCESSABLE_ENTITY)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(body);
    }

    private String resolveRequestId(HttpServletRequest request) {
        AuthenticatedTenantContext ctx = TenantContextHolder.getNullableContext();
        if (ctx != null && ctx.requestId() != null) {
            return ctx.requestId();
        }
        String header = request.getHeader("X-Request-Id");
        return (header != null && !header.isBlank()) ? header.trim() : UUID.randomUUID().toString();
    }

    private String titleForStatus(int status) {
        HttpStatus resolved = HttpStatus.resolve(status);
        return resolved != null ? resolved.getReasonPhrase() : "Application Error";
    }
}
