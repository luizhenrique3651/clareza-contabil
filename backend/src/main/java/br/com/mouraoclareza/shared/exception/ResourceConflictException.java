package br.com.mouraoclareza.shared.exception;

public class ResourceConflictException extends ClarezaException {

    public ResourceConflictException(String code, String message) {
        super(409, code, message);
    }
}
