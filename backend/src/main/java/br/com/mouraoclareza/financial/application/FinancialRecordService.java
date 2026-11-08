package br.com.mouraoclareza.financial.application;

import br.com.mouraoclareza.financial.domain.FinancialCatalogModels.CursorPageResponse;
import br.com.mouraoclareza.financial.domain.FinancialCatalogModels.PageInfo;
import br.com.mouraoclareza.financial.domain.FinancialRecordDomainPolicy;
import br.com.mouraoclareza.financial.domain.FinancialRecordDomainPolicy.SettlementTransition;
import br.com.mouraoclareza.financial.domain.FinancialRecordModels.CancelFinancialRecordRequest;
import br.com.mouraoclareza.financial.domain.FinancialRecordModels.CreateFinancialRecordRequest;
import br.com.mouraoclareza.financial.domain.FinancialRecordModels.CreateSettlementRequest;
import br.com.mouraoclareza.financial.domain.FinancialRecordModels.FinancialEventView;
import br.com.mouraoclareza.financial.domain.FinancialRecordModels.FinancialRecordView;
import br.com.mouraoclareza.financial.domain.FinancialRecordModels.FinancialSettlementView;
import br.com.mouraoclareza.financial.domain.FinancialRecordModels.FinancialSummaryView;
import br.com.mouraoclareza.shared.audit.AuditTrailService;
import br.com.mouraoclareza.shared.domain.Money;
import br.com.mouraoclareza.shared.exception.DomainValidationException;
import br.com.mouraoclareza.shared.exception.ResourceNotFoundException;
import br.com.mouraoclareza.shared.exception.ValidationErrorItem;
import br.com.mouraoclareza.shared.idempotency.IdempotencyExecutor;
import br.com.mouraoclareza.shared.idempotency.IdempotencyExecutor.IdempotentResult;
import br.com.mouraoclareza.shared.idempotency.IdempotencyExecutor.ResponseEnvelope;
import br.com.mouraoclareza.shared.pagination.BoundCursorCodec;
import br.com.mouraoclareza.shared.pagination.BoundCursorCodec.CursorScope;
import br.com.mouraoclareza.shared.security.AuthenticatedTenantContext;
import br.com.mouraoclareza.shared.security.RlsTransactionExecutor;
import br.com.mouraoclareza.shared.security.TenantContextHolder;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Date;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Serviço de aplicação transacional para Títulos Financeiros (Contas a Pagar / Contas a Receber),
 * Baixas Parciais/Integrais, Cancelamento, Sumário Executivo e Eventos Financeiros Imutáveis.
 * Cumpre ADR-001, ADR-002, ADR-005 e ADR-006.
 */
@Service
public class FinancialRecordService {

    private static final Set<String> VALID_RECORD_TYPES = Set.of("PAYABLE", "RECEIVABLE");
    private static final Set<String> VALID_PAYMENT_METHODS = Set.of(
            "PIX", "BOLETO", "TED", "BANK_DEBIT", "CASH", "CARD", "OTHER"
    );

    private final JdbcTemplate jdbcTemplate;
    private final RlsTransactionExecutor rlsExecutor;
    private final IdempotencyExecutor idempotencyExecutor;
    private final AuditTrailService auditTrailService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public FinancialRecordService(
            JdbcTemplate jdbcTemplate,
            RlsTransactionExecutor rlsExecutor,
            IdempotencyExecutor idempotencyExecutor,
            AuditTrailService auditTrailService,
            ObjectMapper objectMapper
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.rlsExecutor = rlsExecutor;
        this.idempotencyExecutor = idempotencyExecutor;
        this.auditTrailService = auditTrailService;
        this.objectMapper = objectMapper;
        this.clock = Clock.systemUTC();
    }

    public CursorPageResponse<FinancialRecordView> listRecords(
            String typeFilter,
            String statusFilter,
            String cursor,
            int rawLimit
    ) {
        AuthenticatedTenantContext ctx = TenantContextHolder.requirePermission("financial.read");
        int limit = clampLimit(rawLimit);
        Map<String, Object> filters = new LinkedHashMap<>();
        if (typeFilter != null && !typeFilter.isBlank()) {
            filters.put("type", typeFilter.trim().toUpperCase(Locale.ROOT));
        }
        if (statusFilter != null && !statusFilter.isBlank()) {
            filters.put("status", statusFilter.trim().toUpperCase(Locale.ROOT));
        }

        CursorScope scope = new CursorScope(
                "financial_records",
                ctx.tenantId(),
                ctx.activeCompanyId(),
                ctx.activeBranchId(),
                "due_date_asc,id_asc",
                filters
        );
        BoundCursorCodec.decodeCursor(cursor, scope);
        LocalDate manausToday = FinancialRecordDomainPolicy.currentManausDate(clock);

        return rlsExecutor.executeInTenantTransaction(ctx, () -> {
            List<FinancialRecordView> rows = jdbcTemplate.query(
                    """
                    SELECT r.id, r.tenant_id, r.company_id, r.branch_id,
                           r.party_id, p.trade_name AS party_name,
                           r.category_id, c.name AS category_name,
                           r.cost_center_id, cc.name AS cost_center_name,
                           r.record_type, r.description, r.document_number,
                           r.issue_date, r.competence_date, r.due_date,
                           r.principal_amount, r.discount_amount, r.interest_amount, r.fine_amount,
                           r.total_amount, r.settled_amount, r.open_amount,
                           r.status, r.created_at
                      FROM financial_records r
                      JOIN parties p ON p.id = r.party_id
                      JOIN financial_categories c ON c.id = r.category_id
                      LEFT JOIN cost_centers cc ON cc.id = r.cost_center_id
                     WHERE r.tenant_id = ?
                       AND r.company_id = ?
                       AND (? IS NULL OR r.record_type = ?)
                     ORDER BY r.due_date ASC, r.id ASC
                     LIMIT ?
                    """,
                    (rs, rowNum) -> {
                        String persistedStatus = rs.getString("status");
                        LocalDate dueDate = rs.getDate("due_date").toLocalDate();
                        String effectiveStatus = FinancialRecordDomainPolicy.deriveEffectiveStatus(
                                persistedStatus, dueDate, manausToday
                        );
                        return new FinancialRecordView(
                                rs.getObject("id", UUID.class),
                                rs.getObject("tenant_id", UUID.class),
                                rs.getObject("company_id", UUID.class),
                                rs.getObject("branch_id", UUID.class),
                                rs.getObject("party_id", UUID.class),
                                rs.getString("party_name"),
                                rs.getObject("category_id", UUID.class),
                                rs.getString("category_name"),
                                rs.getObject("cost_center_id", UUID.class),
                                rs.getString("cost_center_name"),
                                rs.getString("record_type"),
                                rs.getString("description"),
                                rs.getString("document_number"),
                                rs.getDate("issue_date").toLocalDate(),
                                rs.getDate("competence_date").toLocalDate(),
                                dueDate,
                                Money.ofBigDecimal(rs.getBigDecimal("principal_amount")),
                                Money.ofBigDecimal(rs.getBigDecimal("discount_amount")),
                                Money.ofBigDecimal(rs.getBigDecimal("interest_amount")),
                                Money.ofBigDecimal(rs.getBigDecimal("fine_amount")),
                                Money.ofBigDecimal(rs.getBigDecimal("total_amount")),
                                Money.ofBigDecimal(rs.getBigDecimal("settled_amount")),
                                Money.ofBigDecimal(rs.getBigDecimal("open_amount")),
                                persistedStatus,
                                effectiveStatus,
                                rs.getTimestamp("created_at").toInstant(),
                                List.of()
                        );
                    },
                    ctx.tenantId(),
                    ctx.activeCompanyId(),
                    filters.get("type"),
                    filters.get("type"),
                    limit + 1
            );

            List<FinancialRecordView> filtered = rows;
            if (filters.containsKey("status")) {
                String targetStatus = (String) filters.get("status");
                filtered = rows.stream()
                        .filter(r -> targetStatus.equals(r.effectiveStatus()) || targetStatus.equals(r.persistedStatus()))
                        .toList();
            }

            boolean hasNext = filtered.size() > limit;
            List<FinancialRecordView> pageItems = hasNext ? filtered.subList(0, limit) : filtered;
            String nextCursor = null;
            if (hasNext && !pageItems.isEmpty()) {
                FinancialRecordView last = pageItems.getLast();
                nextCursor = BoundCursorCodec.encodeCursor(scope, Map.of(
                        "dueDate", last.dueDate().toString(),
                        "id", last.id().toString()
                ));
            }
            return new CursorPageResponse<>(pageItems, new PageInfo(limit, hasNext, nextCursor));
        });
    }

    public FinancialRecordView getRecordById(UUID recordId) {
        AuthenticatedTenantContext ctx = TenantContextHolder.requirePermission("financial.read");
        LocalDate manausToday = FinancialRecordDomainPolicy.currentManausDate(clock);

        return rlsExecutor.executeInTenantTransaction(ctx, () -> loadRecordByIdInternal(ctx, recordId, manausToday));
    }

    public IdempotentResult<FinancialRecordView> createRecord(
            String idempotencyKey,
            CreateFinancialRecordRequest request
    ) {
        AuthenticatedTenantContext ctx = TenantContextHolder.requirePermission("financial.write");
        UUID effectiveBranchId = request.branchId() != null ? request.branchId() : ctx.activeBranchId();
        TenantContextHolder.assertCompanyAndBranchAccess(ctx.activeCompanyId(), effectiveBranchId);

        List<ValidationErrorItem> errors = new ArrayList<>();
        String recordType = request.recordType() == null ? "" : request.recordType().trim().toUpperCase(Locale.ROOT);
        if (!VALID_RECORD_TYPES.contains(recordType)) {
            errors.add(new ValidationErrorItem(
                    "recordType",
                    "INVALID_RECORD_TYPE",
                    "O tipo do título deve ser PAYABLE ou RECEIVABLE."
            ));
        }
        String description = request.description() == null ? "" : request.description().trim();
        if (description.length() < 3 || description.length() > 240) {
            errors.add(new ValidationErrorItem(
                    "description",
                    "INVALID_DESCRIPTION",
                    "A descrição do título deve conter entre 3 e 240 caracteres."
            ));
        }
        if (request.partyId() == null) {
            errors.add(new ValidationErrorItem("partyId", "REQUIRED_PARTY", "O participante é obrigatório."));
        }
        if (request.categoryId() == null) {
            errors.add(new ValidationErrorItem("categoryId", "REQUIRED_CATEGORY", "A categoria financeira é obrigatória."));
        }
        if (!errors.isEmpty()) {
            throw new DomainValidationException(errors);
        }

        FinancialRecordDomainPolicy.validateDates(
                request.issueDate(),
                request.competenceDate(),
                request.dueDate()
        );

        Money principal = Money.parsePositive(request.principalAmount(), "principalAmount");
        Money discount = request.discountAmount() == null || request.discountAmount().isBlank()
                ? Money.ZERO
                : Money.parse(request.discountAmount(), "discountAmount").requireNonNegative("discountAmount");
        Money interest = request.interestAmount() == null || request.interestAmount().isBlank()
                ? Money.ZERO
                : Money.parse(request.interestAmount(), "interestAmount").requireNonNegative("interestAmount");
        Money fine = request.fineAmount() == null || request.fineAmount().isBlank()
                ? Money.ZERO
                : Money.parse(request.fineAmount(), "fineAmount").requireNonNegative("fineAmount");

        Money totalAmount = principal.subtract(discount).add(interest).add(fine);
        if (!totalAmount.isPositive()) {
            throw new DomainValidationException(List.of(
                    new ValidationErrorItem(
                            "discountAmount",
                            "DISCOUNT_EXCEEDS_TOTAL",
                            "O valor líquido do título após descontos deve ser maior que zero."
                    )
            ));
        }

        LocalDate manausToday = FinancialRecordDomainPolicy.currentManausDate(clock);

        return rlsExecutor.executeInTenantTransaction(ctx, () ->
                idempotencyExecutor.execute(
                        "financial.record.create",
                        idempotencyKey,
                        request,
                        FinancialRecordView.class,
                        () -> {
                            assertCatalogReferencesForUpdate(
                                    ctx,
                                    request.partyId(),
                                    request.categoryId(),
                                    request.costCenterId(),
                                    recordType
                            );

                            UUID recordId = UUID.randomUUID();
                            jdbcTemplate.update(
                                    """
                                    INSERT INTO financial_records (
                                        id, tenant_id, company_id, branch_id,
                                        party_id, category_id, cost_center_id,
                                        record_type, description, document_number,
                                        issue_date, competence_date, due_date,
                                        principal_amount, discount_amount, interest_amount, fine_amount,
                                        total_amount, settled_amount, open_amount, status, created_by_user_id
                                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'OPEN', ?)
                                    """,
                                    recordId,
                                    ctx.tenantId(),
                                    ctx.activeCompanyId(),
                                    effectiveBranchId,
                                    request.partyId(),
                                    request.categoryId(),
                                    request.costCenterId(),
                                    recordType,
                                    description,
                                    request.documentNumber(),
                                    Date.valueOf(request.issueDate()),
                                    Date.valueOf(request.competenceDate()),
                                    Date.valueOf(request.dueDate()),
                                    principal.toBigDecimal(),
                                    discount.toBigDecimal(),
                                    interest.toBigDecimal(),
                                    fine.toBigDecimal(),
                                    totalAmount.toBigDecimal(),
                                    Money.ZERO.toBigDecimal(),
                                    totalAmount.toBigDecimal(),
                                    ctx.userId()
                            );

                            recordFinancialEvent(
                                    ctx,
                                    effectiveBranchId,
                                    recordId,
                                    "RECORD_CREATED",
                                    totalAmount,
                                    Map.of(
                                            "recordType", recordType,
                                            "dueDate", request.dueDate().toString(),
                                            "totalAmount", totalAmount.toCanonicalString()
                                    )
                            );

                            FinancialRecordView created = loadRecordByIdInternal(ctx, recordId, manausToday);
                            auditTrailService.recordAuditLog(
                                    "financial.record.created",
                                    "financial_record",
                                    recordId.toString(),
                                    effectiveBranchId,
                                    null,
                                    created
                            );
                            return new ResponseEnvelope<>(201, created);
                        }
                )
        );
    }

    public IdempotentResult<FinancialRecordView> settleRecord(
            UUID recordId,
            String idempotencyKey,
            CreateSettlementRequest request
    ) {
        AuthenticatedTenantContext ctx = TenantContextHolder.requirePermission("financial.settle");
        if (request.settlementDate() == null) {
            throw new DomainValidationException(List.of(
                    new ValidationErrorItem("settlementDate", "REQUIRED_DATE", "A data da baixa é obrigatória.")
            ));
        }
        String paymentMethod = request.paymentMethod() == null
                ? "PIX"
                : request.paymentMethod().trim().toUpperCase(Locale.ROOT);
        if (!VALID_PAYMENT_METHODS.contains(paymentMethod)) {
            throw new DomainValidationException(List.of(
                    new ValidationErrorItem(
                            "paymentMethod",
                            "INVALID_PAYMENT_METHOD",
                            "Meio de pagamento inválido."
                    )
            ));
        }
        Money settlementAmount = Money.parsePositive(request.amount(), "amount");
        LocalDate manausToday = FinancialRecordDomainPolicy.currentManausDate(clock);

        return rlsExecutor.executeInTenantTransaction(ctx, () ->
                idempotencyExecutor.execute(
                        "financial.record.settle:" + recordId,
                        idempotencyKey,
                        request,
                        FinancialRecordView.class,
                        () -> {
                            FinancialRecordView before = lockRecordForUpdate(ctx, recordId, manausToday);
                            SettlementTransition transition = FinancialRecordDomainPolicy.calculateSettlement(
                                    before.persistedStatus(),
                                    before.principalAmount(),
                                    before.discountAmount(),
                                    before.interestAmount(),
                                    before.fineAmount(),
                                    before.settledAmount(),
                                    settlementAmount
                            );

                            UUID settlementId = UUID.randomUUID();
                            jdbcTemplate.update(
                                    """
                                    INSERT INTO financial_settlements (
                                        id, tenant_id, company_id, branch_id, financial_record_id,
                                        settlement_date, amount, payment_method, bank_reference, notes, created_by_user_id
                                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                                    """,
                                    settlementId,
                                    ctx.tenantId(),
                                    ctx.activeCompanyId(),
                                    before.branchId(),
                                    recordId,
                                    Date.valueOf(request.settlementDate()),
                                    settlementAmount.toBigDecimal(),
                                    paymentMethod,
                                    request.bankReference(),
                                    request.notes(),
                                    ctx.userId()
                            );

                            jdbcTemplate.update(
                                    """
                                    UPDATE financial_records
                                       SET settled_amount = ?,
                                           open_amount = ?,
                                           status = ?,
                                           updated_at = CURRENT_TIMESTAMP
                                     WHERE id = ? AND tenant_id = ? AND company_id = ?
                                    """,
                                    transition.settledAmount().toBigDecimal(),
                                    transition.openAmount().toBigDecimal(),
                                    transition.nextPersistedStatus(),
                                    recordId,
                                    ctx.tenantId(),
                                    ctx.activeCompanyId()
                            );

                            String eventType = "SETTLED".equals(transition.nextPersistedStatus())
                                    ? "RECORD_SETTLED"
                                    : "RECORD_PARTIALLY_SETTLED";
                            recordFinancialEvent(
                                    ctx,
                                    before.branchId(),
                                    recordId,
                                    eventType,
                                    settlementAmount,
                                    Map.of(
                                            "settlementId", settlementId.toString(),
                                            "settlementDate", request.settlementDate().toString(),
                                            "paymentMethod", paymentMethod,
                                            "newOpenAmount", transition.openAmount().toCanonicalString()
                                    )
                            );

                            FinancialRecordView after = loadRecordByIdInternal(ctx, recordId, manausToday);
                            auditTrailService.recordAuditLog(
                                    "financial.record.settled",
                                    "financial_record",
                                    recordId.toString(),
                                    before.branchId(),
                                    before,
                                    after
                            );
                            return new ResponseEnvelope<>(200, after);
                        }
                )
        );
    }

    public IdempotentResult<FinancialRecordView> cancelRecord(
            UUID recordId,
            String idempotencyKey,
            CancelFinancialRecordRequest request
    ) {
        AuthenticatedTenantContext ctx = TenantContextHolder.requirePermission("financial.cancel");
        String reason = request != null && request.reason() != null ? request.reason().trim() : "";
        if (reason.length() < 5 || reason.length() > 240) {
            throw new DomainValidationException(List.of(
                    new ValidationErrorItem(
                            "reason",
                            "INVALID_CANCELLATION_REASON",
                            "Informe uma justificativa de cancelamento entre 5 e 240 caracteres."
                    )
            ));
        }
        LocalDate manausToday = FinancialRecordDomainPolicy.currentManausDate(clock);

        return rlsExecutor.executeInTenantTransaction(ctx, () ->
                idempotencyExecutor.execute(
                        "financial.record.cancel:" + recordId,
                        idempotencyKey,
                        request,
                        FinancialRecordView.class,
                        () -> {
                            FinancialRecordView before = lockRecordForUpdate(ctx, recordId, manausToday);
                            FinancialRecordDomainPolicy.assertCanCancel(before.persistedStatus(), before.settledAmount());

                            jdbcTemplate.update(
                                    """
                                    UPDATE financial_records
                                       SET status = 'CANCELLED',
                                           open_amount = 0.0000,
                                           updated_at = CURRENT_TIMESTAMP
                                     WHERE id = ? AND tenant_id = ? AND company_id = ?
                                    """,
                                    recordId,
                                    ctx.tenantId(),
                                    ctx.activeCompanyId()
                            );

                            recordFinancialEvent(
                                    ctx,
                                    before.branchId(),
                                    recordId,
                                    "RECORD_CANCELLED",
                                    before.openAmount(),
                                    Map.of("reason", reason)
                            );

                            FinancialRecordView after = loadRecordByIdInternal(ctx, recordId, manausToday);
                            auditTrailService.recordAuditLog(
                                    "financial.record.cancelled",
                                    "financial_record",
                                    recordId.toString(),
                                    before.branchId(),
                                    before,
                                    after
                            );
                            return new ResponseEnvelope<>(200, after);
                        }
                )
        );
    }

    public FinancialSummaryView getSummary() {
        AuthenticatedTenantContext ctx = TenantContextHolder.requirePermission("financial.read");
        LocalDate manausToday = FinancialRecordDomainPolicy.currentManausDate(clock);

        return rlsExecutor.executeInTenantTransaction(ctx, () -> {
            List<FinancialRecordView> records = listRecords(null, null, null, 100).items();
            Money openPayables = Money.ZERO;
            Money overduePayables = Money.ZERO;
            Money settledPayables = Money.ZERO;
            Money openReceivables = Money.ZERO;
            Money overdueReceivables = Money.ZERO;
            Money settledReceivables = Money.ZERO;

            for (FinancialRecordView r : records) {
                if ("CANCELLED".equals(r.persistedStatus())) {
                    continue;
                }
                if ("PAYABLE".equals(r.recordType())) {
                    openPayables = openPayables.add(r.openAmount());
                    settledPayables = settledPayables.add(r.settledAmount());
                    if ("OVERDUE".equals(r.effectiveStatus())) {
                        overduePayables = overduePayables.add(r.openAmount());
                    }
                } else if ("RECEIVABLE".equals(r.recordType())) {
                    openReceivables = openReceivables.add(r.openAmount());
                    settledReceivables = settledReceivables.add(r.settledAmount());
                    if ("OVERDUE".equals(r.effectiveStatus())) {
                        overdueReceivables = overdueReceivables.add(r.openAmount());
                    }
                }
            }

            Money projectedNet = openReceivables.subtract(openPayables);
            return new FinancialSummaryView(
                    ctx.activeCompanyId(),
                    ctx.activeBranchId(),
                    manausToday,
                    openPayables,
                    overduePayables,
                    settledPayables,
                    openReceivables,
                    overdueReceivables,
                    settledReceivables,
                    projectedNet
            );
        });
    }

    public CursorPageResponse<FinancialEventView> listEvents(UUID recordIdFilter, String cursor, int rawLimit) {
        AuthenticatedTenantContext ctx = TenantContextHolder.requirePermission("financial.read");
        int limit = clampLimit(rawLimit);
        CursorScope scope = new CursorScope(
                "financial_events",
                ctx.tenantId(),
                ctx.activeCompanyId(),
                ctx.activeBranchId(),
                "occurred_at_desc,id_desc",
                recordIdFilter != null ? Map.of("financialRecordId", recordIdFilter.toString()) : Map.of()
        );
        BoundCursorCodec.decodeCursor(cursor, scope);

        return rlsExecutor.executeInTenantTransaction(ctx, () -> {
            List<FinancialEventView> rows = jdbcTemplate.query(
                    """
                    SELECT id, tenant_id, company_id, branch_id, financial_record_id,
                           event_type, amount, payload::text, actor_user_id, occurred_at
                      FROM financial_events
                     WHERE tenant_id = ?
                       AND company_id = ?
                       AND (? IS NULL OR financial_record_id = ?)
                     ORDER BY occurred_at DESC, id DESC
                     LIMIT ?
                    """,
                    (rs, rowNum) -> new FinancialEventView(
                            rs.getObject("id", UUID.class),
                            rs.getObject("tenant_id", UUID.class),
                            rs.getObject("company_id", UUID.class),
                            rs.getObject("branch_id", UUID.class),
                            rs.getObject("financial_record_id", UUID.class),
                            rs.getString("event_type"),
                            Money.ofBigDecimal(rs.getBigDecimal("amount")),
                            parseJsonPayload(rs.getString("payload")),
                            rs.getObject("actor_user_id", UUID.class),
                            rs.getTimestamp("occurred_at").toInstant()
                    ),
                    ctx.tenantId(),
                    ctx.activeCompanyId(),
                    recordIdFilter,
                    recordIdFilter,
                    limit + 1
            );

            boolean hasNext = rows.size() > limit;
            List<FinancialEventView> pageItems = hasNext ? rows.subList(0, limit) : rows;
            String nextCursor = null;
            if (hasNext && !pageItems.isEmpty()) {
                FinancialEventView last = pageItems.getLast();
                nextCursor = BoundCursorCodec.encodeCursor(scope, Map.of(
                        "occurredAt", last.occurredAt().toString(),
                        "id", last.id().toString()
                ));
            }
            return new CursorPageResponse<>(pageItems, new PageInfo(limit, hasNext, nextCursor));
        });
    }

    private void assertCatalogReferencesForUpdate(
            AuthenticatedTenantContext ctx,
            UUID partyId,
            UUID categoryId,
            UUID costCenterId,
            String recordType
    ) {
        List<String> partyStatus = jdbcTemplate.query(
                "SELECT status FROM parties WHERE id = ? AND tenant_id = ? AND company_id = ? FOR UPDATE",
                (rs, rowNum) -> rs.getString("status"),
                partyId, ctx.tenantId(), ctx.activeCompanyId()
        );
        if (partyStatus.isEmpty() || !"ACTIVE".equals(partyStatus.getFirst())) {
            throw new ResourceNotFoundException("PARTY_NOT_FOUND", "Participante não encontrado ou inativo na empresa ativa.");
        }

        List<String> categoryKinds = jdbcTemplate.query(
                "SELECT kind FROM financial_categories WHERE id = ? AND tenant_id = ? AND company_id = ? AND status = 'ACTIVE' FOR UPDATE",
                (rs, rowNum) -> rs.getString("kind"),
                categoryId, ctx.tenantId(), ctx.activeCompanyId()
        );
        if (categoryKinds.isEmpty()) {
            throw new ResourceNotFoundException("CATEGORY_NOT_FOUND", "Categoria financeira não encontrada na empresa ativa.");
        }
        String expectedKind = "PAYABLE".equals(recordType) ? "EXPENSE" : "REVENUE";
        if (!expectedKind.equals(categoryKinds.getFirst())) {
            throw new DomainValidationException(List.of(
                    new ValidationErrorItem(
                            "categoryId",
                            "CATEGORY_KIND_MISMATCH",
                            "A natureza da categoria financeira é incompatível com o tipo do título."
                    )
            ));
        }

        if (costCenterId != null) {
            List<String> ccStatus = jdbcTemplate.query(
                    "SELECT status FROM cost_centers WHERE id = ? AND tenant_id = ? AND company_id = ? FOR UPDATE",
                    (rs, rowNum) -> rs.getString("status"),
                    costCenterId, ctx.tenantId(), ctx.activeCompanyId()
            );
            if (ccStatus.isEmpty() || !"ACTIVE".equals(ccStatus.getFirst())) {
                throw new ResourceNotFoundException("COST_CENTER_NOT_FOUND", "Centro de custo não encontrado na empresa ativa.");
            }
        }
    }

    private FinancialRecordView lockRecordForUpdate(
            AuthenticatedTenantContext ctx,
            UUID recordId,
            LocalDate manausToday
    ) {
        List<UUID> locked = jdbcTemplate.query(
                "SELECT id FROM financial_records WHERE id = ? AND tenant_id = ? AND company_id = ? FOR UPDATE",
                (rs, rowNum) -> rs.getObject("id", UUID.class),
                recordId, ctx.tenantId(), ctx.activeCompanyId()
        );
        if (locked.isEmpty()) {
            throw new ResourceNotFoundException("FINANCIAL_RECORD_NOT_FOUND", "Título financeiro não encontrado no contexto ativo.");
        }
        return loadRecordByIdInternal(ctx, recordId, manausToday);
    }

    private FinancialRecordView loadRecordByIdInternal(
            AuthenticatedTenantContext ctx,
            UUID recordId,
            LocalDate manausToday
    ) {
        List<FinancialSettlementView> settlements = jdbcTemplate.query(
                """
                SELECT id, financial_record_id, settlement_date, amount, payment_method,
                       bank_reference, notes, created_at
                  FROM financial_settlements
                 WHERE financial_record_id = ? AND tenant_id = ? AND company_id = ?
                 ORDER BY settlement_date ASC, created_at ASC
                """,
                (rs, rowNum) -> new FinancialSettlementView(
                        rs.getObject("id", UUID.class),
                        rs.getObject("financial_record_id", UUID.class),
                        rs.getDate("settlement_date").toLocalDate(),
                        Money.ofBigDecimal(rs.getBigDecimal("amount")),
                        rs.getString("payment_method"),
                        rs.getString("bank_reference"),
                        rs.getString("notes"),
                        rs.getTimestamp("created_at").toInstant()
                ),
                recordId, ctx.tenantId(), ctx.activeCompanyId()
        );

        List<FinancialRecordView> records = jdbcTemplate.query(
                """
                SELECT r.id, r.tenant_id, r.company_id, r.branch_id,
                       r.party_id, p.trade_name AS party_name,
                       r.category_id, c.name AS category_name,
                       r.cost_center_id, cc.name AS cost_center_name,
                       r.record_type, r.description, r.document_number,
                       r.issue_date, r.competence_date, r.due_date,
                       r.principal_amount, r.discount_amount, r.interest_amount, r.fine_amount,
                       r.total_amount, r.settled_amount, r.open_amount,
                       r.status, r.created_at
                  FROM financial_records r
                  JOIN parties p ON p.id = r.party_id
                  JOIN financial_categories c ON c.id = r.category_id
                  LEFT JOIN cost_centers cc ON cc.id = r.cost_center_id
                 WHERE r.id = ? AND r.tenant_id = ? AND r.company_id = ?
                """,
                (rs, rowNum) -> {
                    String persistedStatus = rs.getString("status");
                    LocalDate dueDate = rs.getDate("due_date").toLocalDate();
                    String effectiveStatus = FinancialRecordDomainPolicy.deriveEffectiveStatus(
                            persistedStatus, dueDate, manausToday
                    );
                    return new FinancialRecordView(
                            rs.getObject("id", UUID.class),
                            rs.getObject("tenant_id", UUID.class),
                            rs.getObject("company_id", UUID.class),
                            rs.getObject("branch_id", UUID.class),
                            rs.getObject("party_id", UUID.class),
                            rs.getString("party_name"),
                            rs.getObject("category_id", UUID.class),
                            rs.getString("category_name"),
                            rs.getObject("cost_center_id", UUID.class),
                            rs.getString("cost_center_name"),
                            rs.getString("record_type"),
                            rs.getString("description"),
                            rs.getString("document_number"),
                            rs.getDate("issue_date").toLocalDate(),
                            rs.getDate("competence_date").toLocalDate(),
                            dueDate,
                            Money.ofBigDecimal(rs.getBigDecimal("principal_amount")),
                            Money.ofBigDecimal(rs.getBigDecimal("discount_amount")),
                            Money.ofBigDecimal(rs.getBigDecimal("interest_amount")),
                            Money.ofBigDecimal(rs.getBigDecimal("fine_amount")),
                            Money.ofBigDecimal(rs.getBigDecimal("total_amount")),
                            Money.ofBigDecimal(rs.getBigDecimal("settled_amount")),
                            Money.ofBigDecimal(rs.getBigDecimal("open_amount")),
                            persistedStatus,
                            effectiveStatus,
                            rs.getTimestamp("created_at").toInstant(),
                            settlements
                    );
                },
                recordId, ctx.tenantId(), ctx.activeCompanyId()
        );

        if (records.isEmpty()) {
            throw new ResourceNotFoundException("FINANCIAL_RECORD_NOT_FOUND", "Título financeiro não encontrado no contexto ativo.");
        }
        return records.getFirst();
    }

    private void recordFinancialEvent(
            AuthenticatedTenantContext ctx,
            UUID branchId,
            UUID recordId,
            String eventType,
            Money amount,
            Map<String, Object> payload
    ) {
        try {
            String payloadJson = objectMapper.writeValueAsString(payload);
            jdbcTemplate.update(
                    """
                    INSERT INTO financial_events (
                        id, tenant_id, company_id, branch_id, financial_record_id,
                        event_type, amount, payload, actor_user_id
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                    """,
                    UUID.randomUUID(),
                    ctx.tenantId(),
                    ctx.activeCompanyId(),
                    branchId,
                    recordId,
                    eventType,
                    amount.toBigDecimal(),
                    payloadJson,
                    ctx.userId()
            );
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Falha ao serializar payload de evento financeiro.", e);
        }
    }

    private Map<String, Object> parseJsonPayload(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(rawJson, new TypeReference<>() {
            });
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }

    private int clampLimit(int rawLimit) {
        if (rawLimit <= 0) {
            return 25;
        }
        return Math.min(rawLimit, 100);
    }
}
