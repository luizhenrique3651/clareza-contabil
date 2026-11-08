package br.com.mouraoclareza.shared.exception;

public class DatabaseUnavailableException extends ClarezaException {

    public DatabaseUnavailableException(String message, Throwable cause) {
        super(503, "DATABASE_UNAVAILABLE", message, null, cause);
    }
}
