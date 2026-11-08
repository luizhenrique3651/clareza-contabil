package br.com.mouraoclareza.financial.application;

import br.com.mouraoclareza.financial.domain.FinancialCatalogDomainPolicy;
import br.com.mouraoclareza.financial.domain.FinancialCatalogDomainPolicy.ValidatedCategory;
import br.com.mouraoclareza.financial.domain.FinancialCatalogDomainPolicy.ValidatedCostCenter;
import br.com.mouraoclareza.financial.domain.FinancialCatalogDomainPolicy.ValidatedFinancialAccount;
import br.com.mouraoclareza.financial.domain.FinancialCatalogDomainPolicy.ValidatedParty;
import br.com.mouraoclareza.financial.domain.FinancialCatalogModels.CostCenterView;
import br.com.mouraoclareza.financial.domain.FinancialCatalogModels.CreateCategoryRequest;
import br.com.mouraoclareza.financial.domain.FinancialCatalogModels.CreateCostCenterRequest;
import br.com.mouraoclareza.financial.domain.FinancialCatalogModels.CreateFinancialAccountRequest;
import br.com.mouraoclareza.financial.domain.FinancialCatalogModels.CreatePartyRequest;
import br.com.mouraoclareza.financial.domain.FinancialCatalogModels.CursorPageResponse;
import br.com.mouraoclareza.financial.domain.FinancialCatalogModels.FinancialAccountView;
import br.com.mouraoclareza.financial.domain.FinancialCatalogModels.FinancialCategoryView;
import br.com.mouraoclareza.financial.domain.FinancialCatalogModels.PageInfo;
import br.com.mouraoclareza.financial.domain.FinancialCatalogModels.PartyView;
import br.com.mouraoclareza.shared.audit.AuditTrailService;
import br.com.mouraoclareza.shared.exception.ResourceConflictException;
import br.com.mouraoclareza.shared.idempotency.IdempotencyExecutor;
import br.com.mouraoclareza.shared.idempotency.IdempotencyExecutor.IdempotentResult;
import br.com.mouraoclareza.shared.idempotency.IdempotencyExecutor.ResponseEnvelope;
import br.com.mouraoclareza.shared.pagination.BoundCursorCodec;
import br.com.mouraoclareza.shared.pagination.BoundCursorCodec.CursorScope;
import br.com.mouraoclareza.shared.security.AuthenticatedTenantContext;
import br.com.mouraoclareza.shared.security.RlsTransactionExecutor;
import br.com.mouraoclareza.shared.security.TenantContextHolder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Serviço de aplicação transacional para gestão de Catálogos Financeiros:
 * counterparties, financial_categories, cost_centers e financial_accounts.
 * Alinhado 100% com o schema PostgreSQL de V5__financial_operations.sql e V6__phase_two_hardening.sql (FIX-04 e FIX-06).
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
        String dbKindFilter = normalizePartyKindFilter(roleFilter);
        CursorScope scope = new CursorScope(
                "counterparties",
                ctx.tenantId(),
                ctx.activeCompanyId(),
                ctx.activeBranchId(),
                "name_asc,id_asc",
                dbKindFilter != null ? Map.of("kind", dbKindFilter) : Map.of()
        );
        Map<String, Object> cursorPos = BoundCursorCodec.decodeCursor(cursor, scope);
        String cursorName = cursorPos != null ? (String) cursorPos.get("name") : null;
        UUID cursorId = cursorPos != null && cursorPos.get("id") != null
                ? UUID.fromString(cursorPos.get("id").toString())
                : null;

        return rlsExecutor.executeInTenantTransaction(ctx, () -> {
            List<PartyView> rows = jdbcTemplate.query(
                    """
                    SELECT id, tenant_id, company_id, branch_id, code, name,
                           tax_id, kind::text AS kind, is_active, created_at
                      FROM counterparties
                     WHERE tenant_id = ?
                       AND company_id = ?
                       AND deleted_at IS NULL
                       AND is_active = true
                       AND (?::uuid IS NULL OR branch_id IS NULL OR branch_id = ?::uuid)
                       AND (?::text IS NULL OR kind = ?::counterparty_kind)
                       AND (?::text IS NULL OR (name, id) > (?::text, ?::uuid))
                     ORDER BY name ASC, id ASC
                     LIMIT ?
                    """,
                    (rs, rowNum) -> {
                        String taxId = rs.getString("tax_id");
                        String docType = taxId != null && taxId.length() == 11 ? "CPF" : "CNPJ";
                        String name = rs.getString("name");
                        return new PartyView(
                                rs.getObject("id", UUID.class),
                                rs.getObject("tenant_id", UUID.class),
                                rs.getObject("company_id", UUID.class),
                                rs.getObject("branch_id", UUID.class),
                                rs.getString("code"),
                                name,
                                name,
                                docType,
                                taxId,
                                rs.getString("kind"),
                                null,
                                rs.getBoolean("is_active") ? "ACTIVE" : "INACTIVE",
                                rs.getTimestamp("created_at").toInstant()
                        );
                    },
                    ctx.tenantId(),
                    ctx.activeCompanyId(),
                    ctx.activeBranchId(),
                    ctx.activeBranchId(),
                    dbKindFilter,
                    dbKindFilter,
                    cursorName,
                    cursorName,
                    cursorId,
                    limit + 1
            );

            boolean hasNext = rows.size() > limit;
            List<PartyView> pageItems = hasNext ? rows.subList(0, limit) : rows;
            String nextCursor = null;
            if (hasNext && !pageItems.isEmpty()) {
                PartyView last = pageItems.getLast();
                nextCursor = BoundCursorCodec.encodeCursor(scope, Map.of(
                        "name", last.legalName(),
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
                request.code(),
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
                            try {
                                jdbcTemplate.update(
                                        """
                                        INSERT INTO counterparties (
                                            id, tenant_id, company_id, branch_id, code, name,
                                            tax_id, kind, is_active, created_by_id
                                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?::counterparty_kind, true, ?)
                                        """,
                                        id,
                                        ctx.tenantId(),
                                        ctx.activeCompanyId(),
                                        effectiveBranchId,
                                        validated.code(),
                                        validated.legalName(),
                                        validated.documentNumber(),
                                        validated.dbKind(),
                                        ctx.userId()
                                );
                            } catch (DuplicateKeyException ex) {
                                throw new ResourceConflictException(
                                        "FINANCIAL_CATALOG_CONFLICT",
                                        "Já existe um participante cadastrado com este código na empresa ativa."
                                );
                            }

                            PartyView created = new PartyView(
                                    id,
                                    ctx.tenantId(),
                                    ctx.activeCompanyId(),
                                    effectiveBranchId,
                                    validated.code(),
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
                                    "counterparty",
                                    id,
                                    effectiveBranchId,
                                    null,
                                    created
                            );
                            return new ResponseEnvelope<>(201, "counterparty", id, created);
                        }
                )
        );
    }

    public CursorPageResponse<FinancialCategoryView> listCategories(String kindFilter, String cursor, int rawLimit) {
        AuthenticatedTenantContext ctx = TenantContextHolder.requirePermission("catalog.read");
        int limit = clampLimit(rawLimit);
        String dbDirectionFilter = normalizeDirectionFilter(kindFilter);
        CursorScope scope = new CursorScope(
                "financial_categories",
                ctx.tenantId(),
                ctx.activeCompanyId(),
                ctx.activeBranchId(),
                "code_asc,id_asc",
                dbDirectionFilter != null ? Map.of("direction", dbDirectionFilter) : Map.of()
        );
        Map<String, Object> cursorPos = BoundCursorCodec.decodeCursor(cursor, scope);
        String cursorCode = cursorPos != null ? (String) cursorPos.get("code") : null;
        UUID cursorId = cursorPos != null && cursorPos.get("id") != null
                ? UUID.fromString(cursorPos.get("id").toString())
                : null;

        return rlsExecutor.executeInTenantTransaction(ctx, () -> {
            List<FinancialCategoryView> rows = jdbcTemplate.query(
                    """
                    SELECT id, tenant_id, company_id, branch_id, code, name,
                           direction::text AS direction, is_active, created_at
                      FROM financial_categories
                     WHERE tenant_id = ?
                       AND company_id = ?
                       AND deleted_at IS NULL
                       AND is_active = true
                       AND (?::uuid IS NULL OR branch_id IS NULL OR branch_id = ?::uuid)
                       AND (?::text IS NULL OR direction = ?::financial_direction)
                       AND (?::text IS NULL OR (code, id) > (?::text, ?::uuid))
                     ORDER BY code ASC, id ASC
                     LIMIT ?
                    """,
                    (rs, rowNum) -> {
                        String direction = rs.getString("direction");
                        String kind = "PAYABLE".equals(direction) ? "EXPENSE"
                                : ("RECEIVABLE".equals(direction) ? "REVENUE" : "BOTH");
                        return new FinancialCategoryView(
                                rs.getObject("id", UUID.class),
                                rs.getObject("tenant_id", UUID.class),
                                rs.getObject("company_id", UUID.class),
                                rs.getObject("branch_id", UUID.class),
                                rs.getString("code"),
                                rs.getString("name"),
                                kind,
                                direction,
                                rs.getBoolean("is_active") ? "ACTIVE" : "INACTIVE",
                                rs.getTimestamp("created_at").toInstant()
                        );
                    },
                    ctx.tenantId(),
                    ctx.activeCompanyId(),
                    ctx.activeBranchId(),
                    ctx.activeBranchId(),
                    dbDirectionFilter,
                    dbDirectionFilter,
                    cursorCode,
                    cursorCode,
                    cursorId,
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
        UUID effectiveBranchId = request.branchId() != null ? request.branchId() : ctx.activeBranchId();
        TenantContextHolder.assertCompanyAndBranchAccess(ctx.activeCompanyId(), effectiveBranchId);
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
                            try {
                                jdbcTemplate.update(
                                        """
                                        INSERT INTO financial_categories (
                                            id, tenant_id, company_id, branch_id, code, name,
                                            direction, is_active, created_by_id
                                        ) VALUES (?, ?, ?, ?, ?, ?, ?::financial_direction, true, ?)
                                        """,
                                        id,
                                        ctx.tenantId(),
                                        ctx.activeCompanyId(),
                                        effectiveBranchId,
                                        validated.code(),
                                        validated.name(),
                                        validated.dbDirection(),
                                        ctx.userId()
                                );
                            } catch (DuplicateKeyException ex) {
                                throw new ResourceConflictException(
                                        "FINANCIAL_CATALOG_CONFLICT",
                                        "Já existe uma categoria financeira com este código na empresa ativa."
                                );
                            }

                            FinancialCategoryView created = new FinancialCategoryView(
                                    id,
                                    ctx.tenantId(),
                                    ctx.activeCompanyId(),
                                    effectiveBranchId,
                                    validated.code(),
                                    validated.name(),
                                    validated.kind(),
                                    validated.dbDirection(),
                                    "ACTIVE",
                                    now
                            );

                            auditTrailService.recordAuditLog(
                                    "financial.category.created",
                                    "financial_category",
                                    id,
                                    effectiveBranchId,
                                    null,
                                    created
                            );
                            return new ResponseEnvelope<>(201, "financial_category", id, created);
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
        Map<String, Object> cursorPos = BoundCursorCodec.decodeCursor(cursor, scope);
        String cursorCode = cursorPos != null ? (String) cursorPos.get("code") : null;
        UUID cursorId = cursorPos != null && cursorPos.get("id") != null
                ? UUID.fromString(cursorPos.get("id").toString())
                : null;

        return rlsExecutor.executeInTenantTransaction(ctx, () -> {
            List<CostCenterView> rows = jdbcTemplate.query(
                    """
                    SELECT id, tenant_id, company_id, branch_id, code, name, is_active, created_at
                      FROM cost_centers
                     WHERE tenant_id = ?
                       AND company_id = ?
                       AND deleted_at IS NULL
                       AND is_active = true
                       AND (?::uuid IS NULL OR branch_id IS NULL OR branch_id = ?::uuid)
                       AND (?::text IS NULL OR (code, id) > (?::text, ?::uuid))
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
                            rs.getBoolean("is_active") ? "ACTIVE" : "INACTIVE",
                            rs.getTimestamp("created_at").toInstant()
                    ),
                    ctx.tenantId(),
                    ctx.activeCompanyId(),
                    ctx.activeBranchId(),
                    ctx.activeBranchId(),
                    cursorCode,
                    cursorCode,
                    cursorId,
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
                            try {
                                jdbcTemplate.update(
                                        """
                                        INSERT INTO cost_centers (
                                            id, tenant_id, company_id, branch_id, code, name, is_active, created_by_id
                                        ) VALUES (?, ?, ?, ?, ?, ?, true, ?)
                                        """,
                                        id,
                                        ctx.tenantId(),
                                        ctx.activeCompanyId(),
                                        effectiveBranchId,
                                        validated.code(),
                                        validated.name(),
                                        ctx.userId()
                                );
                            } catch (DuplicateKeyException ex) {
                                throw new ResourceConflictException(
                                        "FINANCIAL_CATALOG_CONFLICT",
                                        "Já existe um centro de custo com este código na empresa ativa."
                                );
                            }

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
                                    id,
                                    effectiveBranchId,
                                    null,
                                    created
                            );
                            return new ResponseEnvelope<>(201, "cost_center", id, created);
                        }
                )
        );
    }

    public CursorPageResponse<FinancialAccountView> listFinancialAccounts(String cursor, int rawLimit) {
        AuthenticatedTenantContext ctx = TenantContextHolder.requirePermission("catalog.read");
        int limit = clampLimit(rawLimit);
        CursorScope scope = new CursorScope(
                "financial_accounts",
                ctx.tenantId(),
                ctx.activeCompanyId(),
                ctx.activeBranchId(),
                "code_asc,id_asc",
                Map.of()
        );
        Map<String, Object> cursorPos = BoundCursorCodec.decodeCursor(cursor, scope);
        String cursorCode = cursorPos != null ? (String) cursorPos.get("code") : null;
        UUID cursorId = cursorPos != null && cursorPos.get("id") != null
                ? UUID.fromString(cursorPos.get("id").toString())
                : null;

        return rlsExecutor.executeInTenantTransaction(ctx, () -> {
            List<FinancialAccountView> rows = jdbcTemplate.query(
                    """
                    SELECT id, tenant_id, company_id, branch_id, code, name,
                           type::text AS type, currency_code, is_active, created_at
                      FROM financial_accounts
                     WHERE tenant_id = ?
                       AND company_id = ?
                       AND deleted_at IS NULL
                       AND is_active = true
                       AND (?::uuid IS NULL OR branch_id IS NULL OR branch_id = ?::uuid)
                       AND (?::text IS NULL OR (code, id) > (?::text, ?::uuid))
                     ORDER BY code ASC, id ASC
                     LIMIT ?
                    """,
                    (rs, rowNum) -> new FinancialAccountView(
                            rs.getObject("id", UUID.class),
                            rs.getObject("tenant_id", UUID.class),
                            rs.getObject("company_id", UUID.class),
                            rs.getObject("branch_id", UUID.class),
                            rs.getString("code"),
                            rs.getString("name"),
                            rs.getString("type"),
                            rs.getString("currency_code"),
                            rs.getBoolean("is_active") ? "ACTIVE" : "INACTIVE",
                            rs.getTimestamp("created_at").toInstant()
                    ),
                    ctx.tenantId(),
                    ctx.activeCompanyId(),
                    ctx.activeBranchId(),
                    ctx.activeBranchId(),
                    cursorCode,
                    cursorCode,
                    cursorId,
                    limit + 1
            );

            boolean hasNext = rows.size() > limit;
            List<FinancialAccountView> pageItems = hasNext ? rows.subList(0, limit) : rows;
            String nextCursor = null;
            if (hasNext && !pageItems.isEmpty()) {
                FinancialAccountView last = pageItems.getLast();
                nextCursor = BoundCursorCodec.encodeCursor(scope, Map.of(
                        "code", last.code(),
                        "id", last.id().toString()
                ));
            }
            return new CursorPageResponse<>(pageItems, new PageInfo(limit, hasNext, nextCursor));
        });
    }

    public IdempotentResult<FinancialAccountView> createFinancialAccount(
            String idempotencyKey,
            CreateFinancialAccountRequest request
    ) {
        AuthenticatedTenantContext ctx = TenantContextHolder.requirePermission("catalog.write");
        UUID effectiveBranchId = request.branchId() != null ? request.branchId() : ctx.activeBranchId();
        TenantContextHolder.assertCompanyAndBranchAccess(ctx.activeCompanyId(), effectiveBranchId);
        ValidatedFinancialAccount validated = FinancialCatalogDomainPolicy.validateFinancialAccountInput(
                request.code(),
                request.name(),
                request.type(),
                request.currencyCode()
        );

        return rlsExecutor.executeInTenantTransaction(ctx, () ->
                idempotencyExecutor.execute(
                        "financial.account.create",
                        idempotencyKey,
                        request,
                        FinancialAccountView.class,
                        () -> {
                            UUID id = UUID.randomUUID();
                            Instant now = Instant.now();
                            try {
                                jdbcTemplate.update(
                                        """
                                        INSERT INTO financial_accounts (
                                            id, tenant_id, company_id, branch_id, code, name,
                                            type, currency_code, is_active, created_by_id
                                        ) VALUES (?, ?, ?, ?, ?, ?, ?::financial_account_type, ?, true, ?)
                                        """,
                                        id,
                                        ctx.tenantId(),
                                        ctx.activeCompanyId(),
                                        effectiveBranchId,
                                        validated.code(),
                                        validated.name(),
                                        validated.type(),
                                        validated.currencyCode(),
                                        ctx.userId()
                                );
                            } catch (DuplicateKeyException ex) {
                                throw new ResourceConflictException(
                                        "FINANCIAL_CATALOG_CONFLICT",
                                        "Já existe uma conta financeira com este código na empresa ativa."
                                );
                            }

                            FinancialAccountView created = new FinancialAccountView(
                                    id,
                                    ctx.tenantId(),
                                    ctx.activeCompanyId(),
                                    effectiveBranchId,
                                    validated.code(),
                                    validated.name(),
                                    validated.type(),
                                    validated.currencyCode(),
                                    "ACTIVE",
                                    now
                            );

                            auditTrailService.recordAuditLog(
                                    "financial.account.created",
                                    "financial_account",
                                    id,
                                    effectiveBranchId,
                                    null,
                                    created
                            );
                            return new ResponseEnvelope<>(201, "financial_account", id, created);
                        }
                )
        );
    }

    private String normalizePartyKindFilter(String rawRole) {
        if (rawRole == null || rawRole.isBlank()) {
            return null;
        }
        String upper = rawRole.trim().toUpperCase(Locale.ROOT);
        return switch (upper) {
            case "CUSTOMER" -> "CUSTOMER";
            case "SUPPLIER" -> "SUPPLIER";
            case "BOTH", "OTHER" -> "BOTH";
            default -> null;
        };
    }

    private String normalizeDirectionFilter(String rawKind) {
        if (rawKind == null || rawKind.isBlank()) {
            return null;
        }
        String upper = rawKind.trim().toUpperCase(Locale.ROOT);
        return switch (upper) {
            case "EXPENSE", "PAYABLE" -> "PAYABLE";
            case "REVENUE", "RECEIVABLE" -> "RECEIVABLE";
            case "BOTH" -> "BOTH";
            default -> null;
        };
    }

    private int clampLimit(int rawLimit) {
        if (rawLimit <= 0) {
            return 25;
        }
        return Math.min(rawLimit, 100);
    }
}
