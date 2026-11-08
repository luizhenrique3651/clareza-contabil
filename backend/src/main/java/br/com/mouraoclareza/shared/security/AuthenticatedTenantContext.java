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

    private static final java.util.Map<String, Set<String>> PERMISSION_EQUIVALENTS = java.util.Map.ofEntries(
            java.util.Map.entry("context.read", Set.of("companies.view", "financial.view")),
            java.util.Map.entry("company.read", Set.of("companies.view")),
            java.util.Map.entry("branch.read", Set.of("companies.view", "financial.view")),
            java.util.Map.entry("catalog.read", Set.of("financial.view", "financial.manage", "payables.view", "receivables.view")),
            java.util.Map.entry("catalog.write", Set.of("financial.manage")),
            java.util.Map.entry("financial.read", Set.of("financial.view", "payables.view", "receivables.view")),
            java.util.Map.entry("financial.write", Set.of("financial.manage", "payables.manage", "receivables.manage")),
            java.util.Map.entry("financial.settle", Set.of("financial.manage", "payables.manage", "receivables.manage")),
            java.util.Map.entry("financial.cancel", Set.of("financial.manage", "payables.manage", "receivables.manage")),
            java.util.Map.entry("bank.read", Set.of("financial.view")),
            java.util.Map.entry("bank.write", Set.of("financial.manage")),
            java.util.Map.entry("reconciliation.read", Set.of("financial.view", "bank.read")),
            java.util.Map.entry("reconciliation.write", Set.of("financial.manage", "bank.write")),
            java.util.Map.entry("accounting.read", Set.of("accounting.view")),
            java.util.Map.entry("audit.read", Set.of("audit.view"))
    );

    public boolean hasPermission(String permission) {
        Objects.requireNonNull(permission, "permission must not be null");
        if (permissions.contains(permission)) {
            return true;
        }
        Set<String> equivalents = PERMISSION_EQUIVALENTS.get(permission);
        if (equivalents != null) {
            for (String eq : equivalents) {
                if (permissions.contains(eq)) {
                    return true;
                }
            }
        }
        return false;
    }

    public boolean canAccessCompany(UUID companyId) {
        if (companyId == null) {
            return false;
        }
        if (tenantWideAccess || allowedCompanyIds.contains(companyId)) {
            return true;
        }
        return companyId.equals(activeCompanyId) && !allowedBranchIds.isEmpty();
    }

    public boolean canAccessBranch(UUID branchId) {
        if (branchId == null) {
            return tenantWideAccess || allowedCompanyIds.contains(activeCompanyId);
        }
        if (tenantWideAccess || allowedCompanyIds.contains(activeCompanyId)) {
            return allowedBranchIds.isEmpty() || allowedBranchIds.contains(branchId);
        }
        return allowedBranchIds.contains(branchId);
    }

    public String companyIdsCsv() {
        return allowedCompanyIds.stream().map(UUID::toString).reduce((a, b) -> a + "," + b).orElse("");
    }

    public String branchIdsCsv() {
        return allowedBranchIds.stream().map(UUID::toString).reduce((a, b) -> a + "," + b).orElse("");
    }
}
