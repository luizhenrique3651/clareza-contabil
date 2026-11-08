package br.com.mouraoclareza.iam.domain;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Sumário completo da identidade autenticada e dos escopos multiempresa/multiunidade autorizados.
 * Contrato compatível com GET /api/v1/me e server/auth-context.mjs.
 */
public record IdentitySummary(
        UserSummary user,
        List<TenantOption> tenants,
        List<CompanyOption> companies,
        List<BranchOption> branches,
        ActiveContextSummary activeContext,
        boolean requiresSelection
) {
    public record UserSummary(UUID id, String email, String name, String status) {
    }

    public record TenantOption(UUID id, String name, String slug, String roleCode, boolean tenantWideAccess) {
    }

    public record CompanyOption(UUID id, UUID tenantId, String legalName, String tradeName, String taxId, String roleCode) {
    }

    public record BranchOption(UUID id, UUID tenantId, UUID companyId, String name, String code, String taxId) {
    }

    public record ActiveContextSummary(
            UUID tenantId,
            String tenantName,
            UUID companyId,
            String companyTradeName,
            UUID branchId,
            String branchName,
            String roleCode,
            boolean tenantWideAccess,
            List<UUID> allowedCompanyIds,
            List<UUID> allowedBranchIds,
            Set<String> permissions
    ) {
    }
}
