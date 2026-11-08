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

import java.sql.Array;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
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
 * Alinhado 100% com as 11 colunas de app.resolve_identity_access (V2/V8) e com as regras de
 * propagação de escopo TENANT / COMPANY / BRANCH de legacy/server/auth-context.mjs (FIX-03).
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
                """
                SELECT tenant_id, tenant_name, user_id, display_name, email,
                       scope::text AS scope, company_id, company_name,
                       branch_id, branch_name, permissions
                  FROM app.resolve_identity_access(?)
                """,
                (rs, rowNum) -> new AccessRow(
                        rs.getObject("tenant_id", UUID.class),
                        rs.getString("tenant_name"),
                        rs.getObject("user_id", UUID.class),
                        rs.getString("display_name"),
                        rs.getString("email"),
                        rs.getString("scope"),
                        rs.getObject("company_id", UUID.class),
                        rs.getString("company_name"),
                        rs.getObject("branch_id", UUID.class),
                        rs.getString("branch_name"),
                        extractPermissionsArray(rs.getArray("permissions"))
                ),
                email
        );
    }

    public IdentitySummary summarizeIdentityAccess(List<AccessRow> rows, ContextSelection selection) {
        if (rows == null || rows.isEmpty()) {
            throw new AuthorizationException("IDENTITY_NOT_AUTHORIZED", "Identidade autenticada sem vínculos ativos no sistema.");
        }

        AccessRow first = rows.getFirst();
        UserSummary user = new UserSummary(first.userId(), first.email(), first.displayName(), "ACTIVE");

        Map<UUID, TenantOption> tenantsById = new LinkedHashMap<>();
        Map<UUID, CompanyOption> companiesById = new LinkedHashMap<>();
        Map<UUID, BranchOption> branchesById = new LinkedHashMap<>();

        for (AccessRow row : rows) {
            boolean rowTenantWide = "TENANT".equalsIgnoreCase(row.scope());
            if (row.tenantId() != null) {
                TenantOption existingTenant = tenantsById.get(row.tenantId());
                boolean mergedTenantWide = (existingTenant != null && existingTenant.tenantWideAccess()) || rowTenantWide;
                String effectiveScope = mergedTenantWide ? "TENANT" : normalizeScope(row.scope());
                tenantsById.put(
                        row.tenantId(),
                        new TenantOption(
                                row.tenantId(),
                                row.tenantName(),
                                slugify(row.tenantName()),
                                effectiveScope,
                                mergedTenantWide
                        )
                );
            }
            if (row.companyId() != null) {
                CompanyOption existingCompany = companiesById.get(row.companyId());
                String nextScope = normalizeScope(row.scope());
                String effectiveScope = existingCompany == null
                        ? nextScope
                        : higherScope(existingCompany.roleCode(), nextScope);
                companiesById.put(
                        row.companyId(),
                        new CompanyOption(
                                row.companyId(),
                                row.tenantId(),
                                row.companyName(),
                                row.companyName(),
                                "",
                                effectiveScope
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
                                "",
                                ""
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
                if (!tenantCompanies.isEmpty()) {
                    selectedCompanyId = tenantCompanies.getFirst().id();
                }
            } else {
                selectedCompanyId = companiesById.keySet().iterator().next();
                selectedTenantId = companiesById.get(selectedCompanyId).tenantId();
            }
        }

        TenantOption activeTenant = tenantsById.get(selectedTenantId);
        CompanyOption activeCompany = companiesById.get(selectedCompanyId);
        UUID finalTenantId = activeTenant.id();
        UUID finalCompanyId = activeCompany.id();

        List<AccessRow> companyRows = rows.stream()
                .filter(r -> finalTenantId.equals(r.tenantId()) && finalCompanyId.equals(r.companyId()))
                .toList();

        boolean hasTenantScopeInActiveTenant = rows.stream()
                .anyMatch(r -> finalTenantId.equals(r.tenantId()) && "TENANT".equalsIgnoreCase(r.scope()));
        boolean hasCompanyAccess = companyRows.stream()
                .anyMatch(r -> "TENANT".equalsIgnoreCase(r.scope()) || "COMPANY".equalsIgnoreCase(r.scope()));

        // 3. Resolve Branch
        UUID selectedBranchId = effectiveSelection.branchId();
        List<BranchOption> companyBranches = branchesById.values().stream()
                .filter(b -> b.companyId().equals(finalCompanyId))
                .toList();

        BranchOption activeBranch = null;
        if (selectedBranchId != null) {
            BranchOption branch = branchesById.get(selectedBranchId);
            if (branch == null) {
                throw new InvalidContextSelectionException("A unidade selecionada não está autorizada para este usuário.");
            }
            if (!branch.companyId().equals(finalCompanyId)) {
                throw new InvalidContextSelectionException("A unidade selecionada não pertence à empresa ativa.");
            }
            activeBranch = branch;
        } else if (!hasCompanyAccess && companyBranches.size() == 1) {
            activeBranch = companyBranches.getFirst();
        } else if (hasCompanyAccess && companyBranches.size() == 1) {
            activeBranch = companyBranches.getFirst();
        }

        // Regra crítica de RLS (legacy/server/auth-context.mjs#L234-L236):
        // Se o membro possui apenas escopo BRANCH na empresa selecionada, allowedCompanyIds DEVE ser vazio
        // para que app.can_access_company(company_id) não libere acesso às demais filiais da empresa.
        List<UUID> allowedCompanyIds = hasCompanyAccess ? List.of(finalCompanyId) : List.of();
        List<UUID> allowedBranchIds = companyRows.stream()
                .filter(r -> "BRANCH".equalsIgnoreCase(r.scope()) && r.branchId() != null)
                .map(AccessRow::branchId)
                .distinct()
                .toList();

        BranchOption finalActiveBranch = activeBranch;
        List<AccessRow> applicableRows = companyRows.stream()
                .filter(r -> {
                    if ("TENANT".equalsIgnoreCase(r.scope()) || "COMPANY".equalsIgnoreCase(r.scope())) {
                        return true;
                    }
                    return finalActiveBranch != null && finalActiveBranch.id().equals(r.branchId());
                })
                .toList();

        Set<String> permissions = new LinkedHashSet<>();
        for (AccessRow r : applicableRows) {
            if (r.permissions() != null && !r.permissions().isEmpty()) {
                permissions.addAll(r.permissions());
            } else {
                permissions.addAll(RolePermissionsCatalog.permissionsForRole(r.scope()));
            }
        }
        permissions.add("context.read");

        boolean requiresBranchSelection = !hasCompanyAccess && activeBranch == null;
        boolean requiresCompanySelection = companiesById.size() > 1 && effectiveSelection.companyId() == null;

        ActiveContextSummary activeContext = new ActiveContextSummary(
                finalTenantId,
                activeTenant.name(),
                finalCompanyId,
                activeCompany.tradeName(),
                activeBranch != null ? activeBranch.id() : null,
                activeBranch != null ? activeBranch.name() : null,
                activeCompany.roleCode(),
                hasTenantScopeInActiveTenant,
                allowedCompanyIds,
                allowedBranchIds,
                permissions
        );

        return new IdentitySummary(
                user,
                new ArrayList<>(tenantsById.values()),
                new ArrayList<>(companiesById.values()),
                new ArrayList<>(branchesById.values()),
                activeContext,
                requiresCompanySelection || requiresBranchSelection
        );
    }

    public AuthenticatedTenantContext toAuthenticatedContext(
            IdentitySummary summary,
            String requestId,
            boolean requireBranchIfBranchScoped
    ) {
        Objects.requireNonNull(summary, "summary must not be null");
        ActiveContextSummary active = summary.activeContext();
        if (requireBranchIfBranchScoped
                && !active.tenantWideAccess()
                && active.allowedCompanyIds().isEmpty()
                && active.branchId() == null) {
            throw new AuthorizationException("FORBIDDEN", "Selecione uma unidade autorizada para continuar.");
        }
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

    public AuthenticatedTenantContext toAuthenticatedContext(IdentitySummary summary, String requestId) {
        return toAuthenticatedContext(summary, requestId, false);
    }

    public static String normalizeEmail(String rawEmail) {
        if (rawEmail == null || rawEmail.isBlank()) {
            throw new AuthenticationRequiredException("E-mail da identidade autenticada não informado.");
        }
        return rawEmail.trim().toLowerCase(Locale.ROOT);
    }

    private static List<String> extractPermissionsArray(Array sqlArray) throws SQLException {
        if (sqlArray == null) {
            return List.of();
        }
        Object raw = sqlArray.getArray();
        if (raw instanceof String[] strArray) {
            return Arrays.stream(strArray)
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .distinct()
                    .sorted()
                    .toList();
        }
        if (raw instanceof Object[] objArray) {
            return Arrays.stream(objArray)
                    .filter(Objects::nonNull)
                    .map(Object::toString)
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .distinct()
                    .sorted()
                    .toList();
        }
        return List.of();
    }

    private static String normalizeScope(String rawScope) {
        if (rawScope == null || rawScope.isBlank()) {
            return "BRANCH";
        }
        return rawScope.trim().toUpperCase(Locale.ROOT);
    }

    private static String higherScope(String a, String b) {
        if ("TENANT".equalsIgnoreCase(a) || "TENANT".equalsIgnoreCase(b)) {
            return "TENANT";
        }
        if ("COMPANY".equalsIgnoreCase(a) || "COMPANY".equalsIgnoreCase(b)) {
            return "COMPANY";
        }
        return normalizeScope(b);
    }

    private static String slugify(String text) {
        if (text == null || text.isBlank()) {
            return "tenant";
        }
        return text.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
    }

    public record ContextSelection(UUID tenantId, UUID companyId, UUID branchId) {
        public static ContextSelection empty() {
            return new ContextSelection(null, null, null);
        }
    }
}
