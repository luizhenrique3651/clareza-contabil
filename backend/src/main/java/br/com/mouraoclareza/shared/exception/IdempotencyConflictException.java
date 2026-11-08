package br.com.mouraoclareza.shared.exception;

public class IdempotencyConflictException extends ClarezaException {

    public IdempotencyConflictException(String message) {
        super(409, "IDEMPOTENCY_CONFLICT", message);
    }
}
