package br.com.mouraoclareza.shared.security;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static br.com.mouraoclareza.shared.domain.DomainIds.requireCompanyId;
import static br.com.mouraoclareza.shared.domain.DomainIds.requireTenantId;
import static br.com.mouraoclareza.shared.domain.DomainIds.requireUserId;
import static br.com.mouraoclareza.shared.domain.DomainIds.validateBranchId;

/**
 * Contexto multi-tenant imutável resolvido no servidor a partir da sessão autenticada
 * e da seleção operacional validada contra memberships ativos no banco.
 */
public record AuthenticatedTenantContext(
        UUID tenantId,
        UUID userId,
        String userEmail,
        String userName,
        String roleCode,
        boolean tenantWideAccess,
        List<UUID> allowedCompanyIds,
        List<UUID> allowedBranchIds,
        UUID activeCompanyId,
        UUID activeBranchId,
        Set<String> permissions,
        String requestId
) {
    public AuthenticatedTenantContext {
        requireTenantId(tenantId);
        requireUserId(userId);
        requireCompanyId(activeCompanyId);
        if (activeBranchId != null) {
            validateBranchId(activeBranchId);
        }
        allowedCompanyIds = allowedCompanyIds == null ? List.of() : List.copyOf(allowedCompanyIds);
        allowedBranchIds = allowedBranchIds == null ? List.of() : List.copyOf(allowedBranchIds);
        permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
        requestId = (requestId == null || requestId.isBlank()) ? UUID.randomUUID().toString() : requestId;
    }

    public boolean hasPermission(String permission) {
        return permissions.contains(Objects.requireNonNull(permission, "permission must not be null"));
    }

    public boolean canAccessCompany(UUID companyId) {
        if (companyId == null) {
            return false;
        }
        return tenantWideAccess || allowedCompanyIds.contains(companyId);
    }

    public boolean canAccessBranch(UUID branchId) {
        if (branchId == null) {
            return true;
        }
        return tenantWideAccess || allowedBranchIds.isEmpty() || allowedBranchIds.contains(branchId);
    }

    public String companyIdsCsv() {
        return allowedCompanyIds.stream().map(UUID::toString).reduce((a, b) -> a + "," + b).orElse("");
    }

    public String branchIdsCsv() {
        return allowedBranchIds.stream().map(UUID::toString).reduce((a, b) -> a + "," + b).orElse("");
    }
}
