package br.com.mouraoclareza.financial.application;

import br.com.mouraoclareza.financial.domain.FinancialCatalogDomainPolicy;
import br.com.mouraoclareza.financial.domain.FinancialCatalogDomainPolicy.ValidatedCategory;
import br.com.mouraoclareza.financial.domain.FinancialCatalogDomainPolicy.ValidatedCostCenter;
import br.com.mouraoclareza.financial.domain.FinancialCatalogDomainPolicy.ValidatedParty;
import br.com.mouraoclareza.financial.domain.FinancialCatalogModels.CostCenterView;
import br.com.mouraoclareza.financial.domain.FinancialCatalogModels.CreateCategoryRequest;
import br.com.mouraoclareza.financial.domain.FinancialCatalogModels.CreateCostCenterRequest;
import br.com.mouraoclareza.financial.domain.FinancialCatalogModels.CreatePartyRequest;
import br.com.mouraoclareza.financial.domain.FinancialCatalogModels.CursorPageResponse;
import br.com.mouraoclareza.financial.domain.FinancialCatalogModels.FinancialCategoryView;
import br.com.mouraoclareza.financial.domain.FinancialCatalogModels.PageInfo;
import br.com.mouraoclareza.financial.domain.FinancialCatalogModels.PartyView;
import br.com.mouraoclareza.shared.audit.AuditTrailService;
import br.com.mouraoclareza.shared.idempotency.IdempotencyExecutor;
import br.com.mouraoclareza.shared.idempotency.IdempotencyExecutor.IdempotentResult;
import br.com.mouraoclareza.shared.idempotency.IdempotencyExecutor.ResponseEnvelope;
import br.com.mouraoclareza.shared.pagination.BoundCursorCodec;
import br.com.mouraoclareza.shared.pagination.BoundCursorCodec.CursorScope;
import br.com.mouraoclareza.shared.security.AuthenticatedTenantContext;
import br.com.mouraoclareza.shared.security.RlsTransactionExecutor;
import br.com.mouraoclareza.shared.security.TenantContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Serviço de aplicação transacional para gestão de Participantes, Categorias Financeiras e Centros de Custo.
 * Preserva RLS, RBAC, idempotência e trilha de auditoria da Fase 2.
 */
@Service
public class FinancialCatalogService {

    private final JdbcTemplate jdbcTemplate;
    private final RlsTransactionExecutor rlsExecutor;
    private final IdempotencyExecutor idempotencyExecutor;
    private final AuditTrailService auditTrailService;

    public FinancialCatalogService(
            JdbcTemplate jdbcTemplate,
            RlsTransactionExecutor rlsExecutor,
            IdempotencyExecutor idempotencyExecutor,
            AuditTrailService auditTrailService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.rlsExecutor = rlsExecutor;
        this.idempotencyExecutor = idempotencyExecutor;
        this.auditTrailService = auditTrailService;
    }

    public CursorPageResponse<PartyView> listParties(String roleFilter, String cursor, int rawLimit) {
        AuthenticatedTenantContext ctx = TenantContextHolder.requirePermission("catalog.read");
        int limit = clampLimit(rawLimit);
        CursorScope scope = new CursorScope(
                "parties",
                ctx.tenantId(),
                ctx.activeCompanyId(),
                ctx.activeBranchId(),
                "legal_name_asc,id_asc",
                roleFilter != null ? Map.of("role", roleFilter) : Map.of()
        );
        Map<String, Object> decoded = BoundCursorCodec.decodeCursor(cursor, scope);

        return rlsExecutor.executeInTenantTransaction(ctx, () -> {
            List<PartyView> rows = jdbcTemplate.query(
                    """
                    SELECT id, tenant_id, company_id, branch_id, legal_name, trade_name,
                           document_type, document_number, party_role, email, status, created_at
                      FROM parties
                     WHERE tenant_id = ?
                       AND company_id = ?
                       AND (? IS NULL OR party_role = ?)
                     ORDER BY legal_name ASC, id ASC
                     LIMIT ?
                    """,
                    (rs, rowNum) -> new PartyView(
                            rs.getObject("id", UUID.class),
                            rs.getObject("tenant_id", UUID.class),
                            rs.getObject("company_id", UUID.class),
                            rs.getObject("branch_id", UUID.class),
                            rs.getString("legal_name"),
                            rs.getString("trade_name"),
                            rs.getString("document_type"),
                            rs.getString("document_number"),
                            rs.getString("party_role"),
                            rs.getString("email"),
                            rs.getString("status"),
                            rs.getTimestamp("created_at").toInstant()
                    ),
                    ctx.tenantId(),
                    ctx.activeCompanyId(),
                    roleFilter,
                    roleFilter,
                    limit + 1
            );

            boolean hasNext = rows.size() > limit;
            List<PartyView> pageItems = hasNext ? rows.subList(0, limit) : rows;
            String nextCursor = null;
            if (hasNext && !pageItems.isEmpty()) {
                PartyView last = pageItems.getLast();
                nextCursor = BoundCursorCodec.encodeCursor(scope, Map.of(
                        "legalName", last.legalName(),
                        "id", last.id().toString()
                ));
            }
            return new CursorPageResponse<>(pageItems, new PageInfo(limit, hasNext, nextCursor));
        });
    }

    public IdempotentResult<PartyView> createParty(String idempotencyKey, CreatePartyRequest request) {
        AuthenticatedTenantContext ctx = TenantContextHolder.requirePermission("catalog.write");
        UUID effectiveBranchId = request.branchId() != null ? request.branchId() : ctx.activeBranchId();
        TenantContextHolder.assertCompanyAndBranchAccess(ctx.activeCompanyId(), effectiveBranchId);
        ValidatedParty validated = FinancialCatalogDomainPolicy.validatePartyInput(
                request.legalName(),
                request.tradeName(),
                request.documentNumber(),
                request.partyRole(),
                request.email()
        );

        return rlsExecutor.executeInTenantTransaction(ctx, () ->
                idempotencyExecutor.execute(
                        "financial.party.create",
                        idempotencyKey,
                        request,
                        PartyView.class,
                        () -> {
                            UUID id = UUID.randomUUID();
                            Instant now = Instant.now();
                            jdbcTemplate.update(
                                    """
                                    INSERT INTO parties (
                                        id, tenant_id, company_id, branch_id, legal_name, trade_name,
                                        document_type, document_number, party_role, email, status
                                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE')
                                    """,
                                    id,
                                    ctx.tenantId(),
                                    ctx.activeCompanyId(),
                                    effectiveBranchId,
                                    validated.legalName(),
                                    validated.tradeName(),
                                    validated.documentType(),
                                    validated.documentNumber(),
                                    validated.partyRole(),
                                    validated.email()
                            );

                            PartyView created = new PartyView(
                                    id,
                                    ctx.tenantId(),
                                    ctx.activeCompanyId(),
                                    effectiveBranchId,
                                    validated.legalName(),
                                    validated.tradeName(),
                                    validated.documentType(),
                                    validated.documentNumber(),
                                    validated.partyRole(),
                                    validated.email(),
                                    "ACTIVE",
                                    now
                            );

                            auditTrailService.recordAuditLog(
                                    "financial.party.created",
                                    "party",
                                    id.toString(),
                                    effectiveBranchId,
                                    null,
                                    created
                            );
                            return new ResponseEnvelope<>(201, created);
                        }
                )
        );
    }

    public CursorPageResponse<FinancialCategoryView> listCategories(String kindFilter, String cursor, int rawLimit) {
        AuthenticatedTenantContext ctx = TenantContextHolder.requirePermission("catalog.read");
        int limit = clampLimit(rawLimit);
        CursorScope scope = new CursorScope(
                "financial_categories",
                ctx.tenantId(),
                ctx.activeCompanyId(),
                ctx.activeBranchId(),
                "code_asc,id_asc",
                kindFilter != null ? Map.of("kind", kindFilter) : Map.of()
        );
        BoundCursorCodec.decodeCursor(cursor, scope);

        return rlsExecutor.executeInTenantTransaction(ctx, () -> {
            List<FinancialCategoryView> rows = jdbcTemplate.query(
                    """
                    SELECT id, tenant_id, company_id, code, name, kind, status, created_at
                      FROM financial_categories
                     WHERE tenant_id = ?
                       AND company_id = ?
                       AND (? IS NULL OR kind = ?)
                     ORDER BY code ASC, id ASC
                     LIMIT ?
                    """,
                    (rs, rowNum) -> new FinancialCategoryView(
                            rs.getObject("id", UUID.class),
                            rs.getObject("tenant_id", UUID.class),
                            rs.getObject("company_id", UUID.class),
                            rs.getString("code"),
                            rs.getString("name"),
                            rs.getString("kind"),
                            rs.getString("status"),
                            rs.getTimestamp("created_at").toInstant()
                    ),
                    ctx.tenantId(),
                    ctx.activeCompanyId(),
                    kindFilter,
                    kindFilter,
                    limit + 1
            );

            boolean hasNext = rows.size() > limit;
            List<FinancialCategoryView> pageItems = hasNext ? rows.subList(0, limit) : rows;
            String nextCursor = null;
            if (hasNext && !pageItems.isEmpty()) {
                FinancialCategoryView last = pageItems.getLast();
                nextCursor = BoundCursorCodec.encodeCursor(scope, Map.of(
                        "code", last.code(),
                        "id", last.id().toString()
                ));
            }
            return new CursorPageResponse<>(pageItems, new PageInfo(limit, hasNext, nextCursor));
        });
    }

    public IdempotentResult<FinancialCategoryView> createCategory(String idempotencyKey, CreateCategoryRequest request) {
        AuthenticatedTenantContext ctx = TenantContextHolder.requirePermission("catalog.write");
        ValidatedCategory validated = FinancialCatalogDomainPolicy.validateCategoryInput(
                request.code(),
                request.name(),
                request.kind()
        );

        return rlsExecutor.executeInTenantTransaction(ctx, () ->
                idempotencyExecutor.execute(
                        "financial.category.create",
                        idempotencyKey,
                        request,
                        FinancialCategoryView.class,
                        () -> {
                            UUID id = UUID.randomUUID();
                            Instant now = Instant.now();
                            jdbcTemplate.update(
                                    """
                                    INSERT INTO financial_categories (
                                        id, tenant_id, company_id, code, name, kind, status
                                    ) VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE')
                                    """,
                                    id,
                                    ctx.tenantId(),
                                    ctx.activeCompanyId(),
                                    validated.code(),
                                    validated.name(),
                                    validated.kind()
                            );

                            FinancialCategoryView created = new FinancialCategoryView(
                                    id,
                                    ctx.tenantId(),
                                    ctx.activeCompanyId(),
                                    validated.code(),
                                    validated.name(),
                                    validated.kind(),
                                    "ACTIVE",
                                    now
                            );

                            auditTrailService.recordAuditLog(
                                    "financial.category.created",
                                    "financial_category",
                                    id.toString(),
                                    ctx.activeBranchId(),
                                    null,
                                    created
                            );
                            return new ResponseEnvelope<>(201, created);
                        }
                )
        );
    }

    public CursorPageResponse<CostCenterView> listCostCenters(String cursor, int rawLimit) {
        AuthenticatedTenantContext ctx = TenantContextHolder.requirePermission("catalog.read");
        int limit = clampLimit(rawLimit);
        CursorScope scope = new CursorScope(
                "cost_centers",
                ctx.tenantId(),
                ctx.activeCompanyId(),
                ctx.activeBranchId(),
                "code_asc,id_asc",
                Map.of()
        );
        BoundCursorCodec.decodeCursor(cursor, scope);

        return rlsExecutor.executeInTenantTransaction(ctx, () -> {
            List<CostCenterView> rows = jdbcTemplate.query(
                    """
                    SELECT id, tenant_id, company_id, branch_id, code, name, status, created_at
                      FROM cost_centers
                     WHERE tenant_id = ?
                       AND company_id = ?
                     ORDER BY code ASC, id ASC
                     LIMIT ?
                    """,
                    (rs, rowNum) -> new CostCenterView(
                            rs.getObject("id", UUID.class),
                            rs.getObject("tenant_id", UUID.class),
                            rs.getObject("company_id", UUID.class),
                            rs.getObject("branch_id", UUID.class),
                            rs.getString("code"),
                            rs.getString("name"),
                            rs.getString("status"),
                            rs.getTimestamp("created_at").toInstant()
                    ),
                    ctx.tenantId(),
                    ctx.activeCompanyId(),
                    limit + 1
            );

            boolean hasNext = rows.size() > limit;
            List<CostCenterView> pageItems = hasNext ? rows.subList(0, limit) : rows;
            String nextCursor = null;
            if (hasNext && !pageItems.isEmpty()) {
                CostCenterView last = pageItems.getLast();
                nextCursor = BoundCursorCodec.encodeCursor(scope, Map.of(
                        "code", last.code(),
                        "id", last.id().toString()
                ));
            }
            return new CursorPageResponse<>(pageItems, new PageInfo(limit, hasNext, nextCursor));
        });
    }

    public IdempotentResult<CostCenterView> createCostCenter(String idempotencyKey, CreateCostCenterRequest request) {
        AuthenticatedTenantContext ctx = TenantContextHolder.requirePermission("catalog.write");
        UUID effectiveBranchId = request.branchId() != null ? request.branchId() : ctx.activeBranchId();
        TenantContextHolder.assertCompanyAndBranchAccess(ctx.activeCompanyId(), effectiveBranchId);
        ValidatedCostCenter validated = FinancialCatalogDomainPolicy.validateCostCenterInput(
                request.code(),
                request.name()
        );

        return rlsExecutor.executeInTenantTransaction(ctx, () ->
                idempotencyExecutor.execute(
                        "financial.cost_center.create",
                        idempotencyKey,
                        request,
                        CostCenterView.class,
                        () -> {
                            UUID id = UUID.randomUUID();
                            Instant now = Instant.now();
                            jdbcTemplate.update(
                                    """
                                    INSERT INTO cost_centers (
                                        id, tenant_id, company_id, branch_id, code, name, status
                                    ) VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE')
                                    """,
                                    id,
                                    ctx.tenantId(),
                                    ctx.activeCompanyId(),
                                    effectiveBranchId,
                                    validated.code(),
                                    validated.name()
                            );

                            CostCenterView created = new CostCenterView(
                                    id,
                                    ctx.tenantId(),
                                    ctx.activeCompanyId(),
                                    effectiveBranchId,
                                    validated.code(),
                                    validated.name(),
                                    "ACTIVE",
                                    now
                            );

                            auditTrailService.recordAuditLog(
                                    "financial.cost_center.created",
                                    "cost_center",
                                    id.toString(),
                                    effectiveBranchId,
                                    null,
                                    created
                            );
                            return new ResponseEnvelope<>(201, created);
                        }
                )
        );
    }

    private int clampLimit(int rawLimit) {
        if (rawLimit <= 0) {
            return 25;
        }
        return Math.min(rawLimit, 100);
    }
}
