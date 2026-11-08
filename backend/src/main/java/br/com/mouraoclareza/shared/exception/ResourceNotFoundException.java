package br.com.mouraoclareza.shared.exception;

public class ResourceNotFoundException extends ClarezaException {

    public ResourceNotFoundException(String message) {
        this("NOT_FOUND", message);
    }

    public ResourceNotFoundException(String code, String message) {
        super(404, code, message);
    }
}
