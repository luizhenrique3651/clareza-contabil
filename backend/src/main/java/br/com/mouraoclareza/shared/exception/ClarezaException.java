package br.com.mouraoclareza.shared.exception;

import java.util.List;

/**
 * Exceção base do domínio Mourão Clareza com suporte nativo ao RFC 9457 (Problem Details).
 */
public class ClarezaException extends RuntimeException {

    private final int status;
    private final String code;
    private final List<ValidationErrorItem> errors;

    public ClarezaException(int status, String code, String message) {
        this(status, code, message, null, null);
    }

    public ClarezaException(int status, String code, String message, List<ValidationErrorItem> errors) {
        this(status, code, message, errors, null);
    }

    public ClarezaException(int status, String code, String message, List<ValidationErrorItem> errors, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.code = code;
        this.errors = errors == null ? List.of() : List.copyOf(errors);
    }

    public int getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public List<ValidationErrorItem> getErrors() {
        return errors;
    }
}
