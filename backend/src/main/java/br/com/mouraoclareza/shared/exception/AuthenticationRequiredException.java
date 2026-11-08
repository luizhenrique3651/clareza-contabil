package br.com.mouraoclareza.shared.exception;

public class AuthenticationRequiredException extends ClarezaException {

    public AuthenticationRequiredException() {
        this("Autenticação obrigatória.");
    }

    public AuthenticationRequiredException(String message) {
        super(401, "AUTHENTICATION_REQUIRED", message);
    }
}
