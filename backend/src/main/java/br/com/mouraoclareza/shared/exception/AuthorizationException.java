package br.com.mouraoclareza.shared.exception;

public class AuthorizationException extends ClarezaException {

    public AuthorizationException(String message) {
        this("FORBIDDEN", message);
    }

    public AuthorizationException(String code, String message) {
        super(403, code, message);
    }
}
