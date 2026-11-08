package br.com.mouraoclareza.shared.exception;

public class InvalidContextSelectionException extends ClarezaException {

    public InvalidContextSelectionException(String message) {
        super(400, "INVALID_CONTEXT_SELECTION", message);
    }
}
