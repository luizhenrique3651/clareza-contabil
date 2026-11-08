package br.com.mouraoclareza.shared.exception;

/**
 * Item individual de erro de validação retornado no envelope RFC 9457 (422 VALIDATION_ERROR).
 */
public record ValidationErrorItem(
        String field,
        String code,
        String message
) {
}
