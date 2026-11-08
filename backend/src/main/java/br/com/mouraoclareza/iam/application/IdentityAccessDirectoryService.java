package br.com.mouraoclareza.iam.application;

import br.com.mouraoclareza.iam.domain.AccessRow;
import br.com.mouraoclareza.iam.domain.IdentitySummary;
import br.com.mouraoclareza.iam.domain.IdentitySummary.ActiveContextSummary;
import br.com.mouraoclareza.iam.domain.IdentitySummary.BranchOption;
import br.com.mouraoclareza.iam.domain.IdentitySummary.CompanyOption;
import br.com.mouraoclareza.iam.domain.IdentitySummary.TenantOption;
import br.com.mouraoclareza.iam.domain.IdentitySummary.UserSummary;
import br.com.mouraoclareza.iam.domain.RolePermissionsCatalog;
import br.com.mouraoclareza.shared.exception.AuthenticationRequiredException;
import br.com.mouraoclareza.shared.exception.AuthorizationException;
import br.com.mouraoclareza.shared.exception.InvalidContextSelectionException;
import br.com.mouraoclareza.shared.security.AuthenticatedTenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Serviço de resolução de diretório de acesso multi-tenant e derivação de contexto operacional.
 * Porta fielmente a lógica de server/auth-context.mjs com tipagem forte.
 */
@Service
public class IdentityAccessDirectoryService {

    private final JdbcTemplate jdbcTemplate;

    public IdentityAccessDirectoryService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
    }

    public List<AccessRow> fetchAccessRowsByEmail(String rawEmail) {
        String email = normalizeEmail(rawEmail);
        return jdbcTemplate.query(
                "SELECT * FROM app.resolve_identity_access(?)",
                (rs, rowNum) -> new AccessRow(
                        rs.getObject("user_id", UUID.class),
                        rs.getString("user_email"),
                        rs.getString("user_name"),
                        rs.getString("user_status"),
                        rs.getObject("membership_id", UUID.class),
                        rs.getObject("tenant_id", UUID.class),
                        rs.getString("tenant_name"),
                        rs.getString("tenant_slug"),
                        rs.getString("role_code"),
                        rs.getBoolean("tenant_wide_access"),
                        rs.getObject("company_id", UUID.class),
                        rs.getString("company_legal_name"),
                        rs.getString("company_trade_name"),
                        rs.getString("company_tax_id"),
                        rs.getObject("branch_id", UUID.class),
                        rs.getString("branch_name"),
                        rs.getString("branch_code"),
                        rs.getString("branch_tax_id")
                ),
                email
        );
    }

    public IdentitySummary summarizeIdentityAccess(List<AccessRow> rows, ContextSelection selection) {
        if (rows == null || rows.isEmpty()) {
            throw new AuthorizationException("IDENTITY_NOT_AUTHORIZED", "Identidade autenticada sem vínculos ativos no sistema.");
        }

        AccessRow first = rows.getFirst();
        if (!"ACTIVE".equalsIgnoreCase(first.userStatus())) {
            throw new AuthorizationException("USER_INACTIVE", "Usuário autenticado não está ativo.");
        }

        UserSummary user = new UserSummary(first.userId(), first.userEmail(), first.userName(), first.userStatus());

        Map<UUID, TenantOption> tenantsById = new LinkedHashMap<>();
        Map<UUID, CompanyOption> companiesById = new LinkedHashMap<>();
        Map<UUID, BranchOption> branchesById = new LinkedHashMap<>();

        for (AccessRow row : rows) {
            if (row.tenantId() != null) {
                tenantsById.putIfAbsent(
                        row.tenantId(),
                        new TenantOption(row.tenantId(), row.tenantName(), row.tenantSlug(), row.roleCode(), row.tenantWideAccess())
                );
            }
            if (row.companyId() != null) {
                companiesById.putIfAbsent(
                        row.companyId(),
                        new CompanyOption(
                                row.companyId(),
                                row.tenantId(),
                                row.companyLegalName(),
                                row.companyTradeName(),
                                row.companyTaxId(),
                                row.roleCode()
                        )
                );
            }
            if (row.branchId() != null && row.companyId() != null) {
                branchesById.putIfAbsent(
                        row.branchId(),
                        new BranchOption(
                                row.branchId(),
                                row.tenantId(),
                                row.companyId(),
                                row.branchName(),
                                row.branchCode(),
                                row.branchTaxId()
                        )
                );
            }
        }

        if (tenantsById.isEmpty() || companiesById.isEmpty()) {
            throw new AuthorizationException("NO_ACTIVE_MEMBERSHIP", "Usuário não possui empresas ativas vinculadas.");
        }

        ContextSelection effectiveSelection = selection == null ? ContextSelection.empty() : selection;

        // 1. Resolve Tenant
        UUID selectedTenantId = effectiveSelection.tenantId();
        if (selectedTenantId != null && !tenantsById.containsKey(selectedTenantId)) {
            throw new InvalidContextSelectionException("O tenant selecionado não pertence aos vínculos ativos do usuário.");
        }

        // 2. Resolve Company
        UUID selectedCompanyId = effectiveSelection.companyId();
        if (selectedCompanyId != null) {
            CompanyOption company = companiesById.get(selectedCompanyId);
            if (company == null) {
                throw new InvalidContextSelectionException("A empresa selecionada não está autorizada para este usuário.");
            }
            if (selectedTenantId != null && !company.tenantId().equals(selectedTenantId)) {
                throw new InvalidContextSelectionException("A empresa selecionada não pertence ao tenant informado.");
            }
            selectedTenantId = company.tenantId();
        } else {
            if (selectedTenantId == null && tenantsById.size() == 1) {
                selectedTenantId = tenantsById.keySet().iterator().next();
            }
            if (selectedTenantId != null) {
                UUID tenantRef = selectedTenantId;
                List<CompanyOption> tenantCompanies = companiesById.values().stream()
                        .filter(c -> c.tenantId().equals(tenantRef))
                        .toList();
                if (tenantCompanies.size() == 1) {
                    selectedCompanyId = tenantCompanies.getFirst().id();
                } else if (!tenantCompanies.isEmpty()) {
                    selectedCompanyId = tenantCompanies.getFirst().id();
                }
            } else {
                selectedCompanyId = companiesById.keySet().iterator().next();
                selectedTenantId = companiesById.get(selectedCompanyId).tenantId();
            }
        }

        TenantOption activeTenant = tenantsById.get(selectedTenantId);
        CompanyOption activeCompany = companiesById.get(selectedCompanyId);

        // 3. Resolve Branch
        UUID selectedBranchId = effectiveSelection.branchId();
        UUID finalCompanyId = activeCompany.id();
        List<BranchOption> companyBranches = branchesById.values().stream()
                .filter(b -> b.companyId().equals(finalCompanyId))
                .toList();

        BranchOption activeBranch = null;
        if (selectedBranchId != null) {
            BranchOption branch = branchesById.get(selectedBranchId);
            if (branch == null) {
                throw new InvalidContextSelectionException("A unidade selecionada não está autorizada para este usuário.");
            }
            if (!branch.companyId().equals(activeCompany.id())) {
                throw new InvalidContextSelectionException("A unidade selecionada não pertence à empresa ativa.");
            }
            activeBranch = branch;
        } else if (companyBranches.size() == 1) {
            activeBranch = companyBranches.getFirst();
        }

        UUID finalTenantId = activeTenant.id();
        List<UUID> allowedCompanyIds = companiesById.values().stream()
                .filter(c -> c.tenantId().equals(finalTenantId))
                .map(CompanyOption::id)
                .toList();

        List<UUID> allowedBranchIds = companyBranches.stream()
                .map(BranchOption::id)
                .toList();

        Set<String> permissions = new LinkedHashSet<>(RolePermissionsCatalog.permissionsForRole(activeCompany.roleCode()));

        ActiveContextSummary activeContext = new ActiveContextSummary(
                activeTenant.id(),
                activeTenant.name(),
                activeCompany.id(),
                activeCompany.tradeName(),
                activeBranch != null ? activeBranch.id() : null,
                activeBranch != null ? activeBranch.name() : null,
                activeCompany.roleCode(),
                activeTenant.tenantWideAccess(),
                allowedCompanyIds,
                allowedBranchIds,
                permissions
        );

        boolean requiresSelection = companiesById.size() > 1 && effectiveSelection.companyId() == null;

        return new IdentitySummary(
                user,
                new ArrayList<>(tenantsById.values()),
                new ArrayList<>(companiesById.values()),
                new ArrayList<>(branchesById.values()),
                activeContext,
                requiresSelection
        );
    }

    public AuthenticatedTenantContext toAuthenticatedContext(IdentitySummary summary, String requestId) {
        Objects.requireNonNull(summary, "summary must not be null");
        ActiveContextSummary active = summary.activeContext();
        return new AuthenticatedTenantContext(
                active.tenantId(),
                summary.user().id(),
                summary.user().email(),
                summary.user().name(),
                active.roleCode(),
                active.tenantWideAccess(),
                active.allowedCompanyIds(),
                active.allowedBranchIds(),
                active.companyId(),
                active.branchId(),
                active.permissions(),
                requestId
        );
    }

    public static String normalizeEmail(String rawEmail) {
        if (rawEmail == null || rawEmail.isBlank()) {
            throw new AuthenticationRequiredException("E-mail da identidade autenticada não informado.");
        }
        return rawEmail.trim().toLowerCase(Locale.ROOT);
    }

    public record ContextSelection(UUID tenantId, UUID companyId, UUID branchId) {
        public static ContextSelection empty() {
            return new ContextSelection(null, null, null);
        }
    }
}
