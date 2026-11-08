package br.com.mouraoclareza.shared.exception;

public class TenantIsolationException extends ClarezaException {

    public TenantIsolationException(String message) {
        super(403, "TENANT_ISOLATION_VIOLATION", message);
    }
}
