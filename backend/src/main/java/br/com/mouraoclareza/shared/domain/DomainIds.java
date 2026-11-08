package br.com.mouraoclareza.shared.domain;

import br.com.mouraoclareza.shared.exception.DomainValidationException;
import br.com.mouraoclareza.shared.exception.InvalidContextSelectionException;
import br.com.mouraoclareza.shared.exception.ValidationErrorItem;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Validated UUID identifiers aligned with RFC 4122 v1-v5 pattern enforced across the legacy suite.
 */
public final class DomainIds {

    public static final Pattern STRICT_UUID_PATTERN = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
            Pattern.CASE_INSENSITIVE
    );

    private DomainIds() {
    }

    public static UUID requireUuid(String raw, String fieldName) {
        if (raw == null || !STRICT_UUID_PATTERN.matcher(raw.trim()).matches()) {
            throw new IllegalArgumentException(fieldName + " inválido.");
        }
        return UUID.fromString(raw.trim().toLowerCase(Locale.ROOT));
    }

    public static UUID parseUuid(String raw, String fieldName, String errorCode) {
        if (raw == null || !STRICT_UUID_PATTERN.matcher(raw.trim()).matches()) {
            if ("INVALID_CONTEXT_SELECTION".equals(errorCode)) {
                throw new InvalidContextSelectionException("Cabeçalho de contexto inválido: " + fieldName);
            }
            throw new DomainValidationException(List.of(
                    new ValidationErrorItem(fieldName, errorCode, "Identificador UUID inválido para " + fieldName)
            ));
        }
        return UUID.fromString(raw.trim().toLowerCase(Locale.ROOT));
    }

    public static UUID requireTenantId(UUID tenantId) {
        if (tenantId == null || !STRICT_UUID_PATTERN.matcher(tenantId.toString()).matches()) {
            throw new InvalidContextSelectionException("tenantId ausente ou inválido.");
        }
        return tenantId;
    }

    public static UUID requireUserId(UUID userId) {
        if (userId == null || !STRICT_UUID_PATTERN.matcher(userId.toString()).matches()) {
            throw new InvalidContextSelectionException("userId ausente ou inválido.");
        }
        return userId;
    }

    public static UUID requireCompanyId(UUID companyId) {
        if (companyId == null || !STRICT_UUID_PATTERN.matcher(companyId.toString()).matches()) {
            throw new InvalidContextSelectionException("companyId ausente ou inválido.");
        }
        return companyId;
    }

    public static UUID validateBranchId(UUID branchId) {
        if (branchId != null && !STRICT_UUID_PATTERN.matcher(branchId.toString()).matches()) {
            throw new InvalidContextSelectionException("branchId inválido.");
        }
        return branchId;
    }

    public static UUID requireContextSelectionUuid(String raw, String fieldName) {
        if (raw == null || !STRICT_UUID_PATTERN.matcher(raw.trim()).matches()) {
            throw new InvalidContextSelectionException(fieldName);
        }
        return UUID.fromString(raw.trim().toLowerCase(Locale.ROOT));
    }

    public static UUID optionalContextSelectionUuid(String raw, String fieldName) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return requireContextSelectionUuid(raw, fieldName);
    }
}
