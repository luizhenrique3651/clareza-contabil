package br.com.mouraoclareza.shared.exception;

import java.util.List;

public class DomainValidationException extends ClarezaException {

    public DomainValidationException(List<ValidationErrorItem> errors) {
        super(422, "VALIDATION_ERROR", "Falha de validação nos dados informados.", errors);
    }

    public DomainValidationException(String message, List<ValidationErrorItem> errors) {
        super(422, "VALIDATION_ERROR", message, errors);
    }
}
