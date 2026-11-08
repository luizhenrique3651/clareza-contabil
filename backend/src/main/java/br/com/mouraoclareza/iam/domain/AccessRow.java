package br.com.mouraoclareza.iam.domain;

import java.util.UUID;

/**
 * Linha retornada pela função SECURITY DEFINER app.resolve_identity_access(verified_email).
 */
public record AccessRow(
        UUID userId,
        String userEmail,
        String userName,
        String userStatus,
        UUID membershipId,
        UUID tenantId,
        String tenantName,
        String tenantSlug,
        String roleCode,
        boolean tenantWideAccess,
        UUID companyId,
        String companyLegalName,
        String companyTradeName,
        String companyTaxId,
        UUID branchId,
        String branchName,
        String branchCode,
        String branchTaxId
) {
}
