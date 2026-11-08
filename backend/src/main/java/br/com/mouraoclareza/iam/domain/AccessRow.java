package br.com.mouraoclareza.iam.domain;

import java.util.List;
import java.util.UUID;

/**
 * Linha retornada pela função SECURITY DEFINER app.resolve_identity_access(verified_email).
 * Alinhada 100% com as 11 colunas definidas em V2__identity_bootstrap_and_branch_rls.sql
 * e V8__user_sessions_and_auth_hardening.sql.
 */
public record AccessRow(
        UUID tenantId,
        String tenantName,
        UUID userId,
        String displayName,
        String email,
        String scope,
        UUID companyId,
        String companyName,
        UUID branchId,
        String branchName,
        List<String> permissions
) {
    public AccessRow {
        permissions = permissions == null ? List.of() : List.copyOf(permissions);
    }
}
