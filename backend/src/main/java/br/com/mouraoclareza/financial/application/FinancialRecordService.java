package br.com.mouraoclareza.financial.application;

import br.com.mouraoclareza.financial.domain.FinancialCatalogModels.CursorPageResponse;
import br.com.mouraoclareza.financial.domain.FinancialCatalogModels.PageInfo;
import br.com.mouraoclareza.financial.domain.FinancialRecordDomainPolicy;
import br.com.mouraoclareza.financial.domain.FinancialRecordModels.CancelFinancialRecordRequest;
import br.com.mouraoclareza.financial.domain.FinancialRecordModels.CreateFinancialRecordRequest;
import br.com.mouraoclareza.financial.domain.FinancialRecordModels.CreateSettlementRequest;
import br.com.mouraoclareza.financial.domain.FinancialRecordModels.FinancialEventView;
import br.com.mouraoclareza.financial.domain.FinancialRecordModels.FinancialRecordView;
import br.com.mouraoclareza.financial.domain.FinancialRecordModels.FinancialSettlementView;
import br.com.mouraoclareza.financial.domain.FinancialRecordModels.FinancialSummaryView;
import br.com.mouraoclareza.financial.domain.FinancialRecordModels.ReverseSettlementRequest;
import br.com.mouraoclareza.shared.audit.AuditTrailService;
import br.com.mouraoclareza.shared.domain.Money;
import br.com.mouraoclareza.shared.exception.DomainValidationException;
import br.com.mouraoclareza.shared.exception.ResourceConflictException;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Date;
import java.sql.Timestamp;
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
 * Serviço de aplicação transacional para Títulos Financeiros (payables / receivables),
 * Baixas Parciais/Integrais (payments / receipts), Estornos (payment_reversals / receipt_reversals),
 * Cancelamento, Sumário Executivo e Eventos Financeiros Imutáveis.
 * Alinhado 100% com V1__init.sql, V5__financial_operations.sql e V6__phase_two_hardening.sql.
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
    private final Clock clock;

    @Autowired
    public FinancialRecordService(
            JdbcTemplate jdbcTemplate,
            RlsTransactionExecutor rlsExecutor,
            IdempotencyExecutor idempotencyExecutor,
            AuditTrailService auditTrailService,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.rlsExecutor = rlsExecutor;
        this.idempotencyExecutor = idempotencyExecutor;
        this.auditTrailService = auditTrailService;
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    public FinancialRecordService(
            JdbcTemplate jdbcTemplate,
            RlsTransactionExecutor rlsExecutor,
            IdempotencyExecutor idempotencyExecutor,
            AuditTrailService auditTrailService,
            ObjectMapper objectMapper
    ) {
        this(jdbcTemplate, rlsExecutor, idempotencyExecutor, auditTrailService, objectMapper, Clock.systemUTC());
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
        String normalizedType = null;
        if (typeFilter != null && !typeFilter.isBlank()) {
            normalizedType = typeFilter.trim().toUpperCase(Locale.ROOT);
            filters.put("type", normalizedType);
        }
        String normalizedStatus = null;
        if (statusFilter != null && !statusFilter.isBlank()) {
            normalizedStatus = statusFilter.trim().toUpperCase(Locale.ROOT);
            filters.put("status", normalizedStatus);
        }

        CursorScope scope = new CursorScope(
                "financial_records",
                ctx.tenantId(),
                ctx.activeCompanyId(),
                ctx.activeBranchId(),
                "due_date_asc,id_asc",
                filters
        );
        Map<String, Object> cursorPos = BoundCursorCodec.decodeCursor(cursor, scope);
        LocalDate cursorDueDate = cursorPos == null ? null : LocalDate.parse(String.valueOf(cursorPos.get("dueDate")));
        UUID cursorId = cursorPos == null ? null : UUID.fromString(String.valueOf(cursorPos.get("id")));
        Date sqlCursorDueDate = cursorDueDate == null ? null : Date.valueOf(cursorDueDate);

        LocalDate manausToday = FinancialRecordDomainPolicy.currentManausDate(clock);
        Date sqlManausToday = Date.valueOf(manausToday);
        final String effectiveTypeFilter = normalizedType;
        final String effectiveStatusFilter = normalizedStatus;

        return rlsExecutor.executeInTenantTransaction(ctx, () -> {
            List<FinancialRecordView> rows = jdbcTemplate.query(
                    """
                    WITH unified_records AS (
                        SELECT
                            p.id, p.tenant_id, p.company_id, p.branch_id,
                            p.counterparty_id AS party_id,
                            COALESCE(cp.name, p.counterparty_name) AS party_name,
                            p.category_id, fc.name AS category_name,
                            p.cost_center_id, cc.name AS cost_center_name,
                            'PAYABLE'::text AS record_type,
                            p.description, p.document_number,
                            COALESCE(p.issue_date, p.due_date) AS issue_date,
                            p.due_date,
                            p.amount,
                            CASE WHEN p.deleted_at IS NOT NULL THEN 0.0000 ELSE p.outstanding_amount END AS open_amount,
                            (p.amount - p.outstanding_amount) AS settled_amount,
                            CASE
                                WHEN p.deleted_at IS NOT NULL THEN 'CANCELLED'
                                WHEN p.status = 'PARTIAL' THEN 'PARTIALLY_SETTLED'
                                ELSE p.status::text
                            END AS persisted_status,
                            p.deleted_at,
                            p.created_at
                          FROM payables p
                          LEFT JOIN counterparties cp
                            ON cp.tenant_id = p.tenant_id AND cp.company_id = p.company_id AND cp.id = p.counterparty_id
                          LEFT JOIN financial_categories fc
                            ON fc.tenant_id = p.tenant_id AND fc.company_id = p.company_id AND fc.id = p.category_id
                          LEFT JOIN cost_centers cc
                            ON cc.tenant_id = p.tenant_id AND cc.company_id = p.company_id AND cc.id = p.cost_center_id
                        UNION ALL
                        SELECT
                            r.id, r.tenant_id, r.company_id, r.branch_id,
                            r.counterparty_id AS party_id,
                            COALESCE(cp.name, r.counterparty_name) AS party_name,
                            r.category_id, fc.name AS category_name,
                            r.cost_center_id, cc.name AS cost_center_name,
                            'RECEIVABLE'::text AS record_type,
                            r.description, r.document_number,
                            COALESCE(r.issue_date, r.due_date) AS issue_date,
                            r.due_date,
                            r.amount,
                            CASE WHEN r.deleted_at IS NOT NULL THEN 0.0000 ELSE r.outstanding_amount END AS open_amount,
                            (r.amount - r.outstanding_amount) AS settled_amount,
                            CASE
                                WHEN r.deleted_at IS NOT NULL THEN 'CANCELLED'
                                WHEN r.status = 'PARTIAL' THEN 'PARTIALLY_SETTLED'
                                ELSE r.status::text
                            END AS persisted_status,
                            r.deleted_at,
                            r.created_at
                          FROM receivables r
                          LEFT JOIN counterparties cp
                            ON cp.tenant_id = r.tenant_id AND cp.company_id = r.company_id AND cp.id = r.counterparty_id
                          LEFT JOIN financial_categories fc
                            ON fc.tenant_id = r.tenant_id AND fc.company_id = r.company_id AND fc.id = r.category_id
                          LEFT JOIN cost_centers cc
                            ON cc.tenant_id = r.tenant_id AND cc.company_id = r.company_id AND cc.id = r.cost_center_id
                    )
                    SELECT
                        u.id, u.tenant_id, u.company_id, u.branch_id,
                        u.party_id, u.party_name,
                        u.category_id, u.category_name,
                        u.cost_center_id, u.cost_center_name,
                        u.record_type, u.description, u.document_number,
                        u.issue_date, u.due_date,
                        u.amount, u.settled_amount, u.open_amount,
                        u.persisted_status, u.created_at
                      FROM unified_records u
                     WHERE u.tenant_id = ?
                       AND u.company_id = ?
                       AND (?::uuid IS NULL OR u.branch_id = ?::uuid)
                       AND (?::text IS NULL OR u.record_type = ?::text)
                       AND (
                           (?::text = 'CANCELLED' AND u.persisted_status = 'CANCELLED')
                           OR (?::text IS DISTINCT FROM 'CANCELLED' AND u.deleted_at IS NULL)
                       )
                       AND (
                           ?::text IS NULL
                           OR (?::text = 'OVERDUE' AND u.persisted_status = 'OPEN' AND u.due_date < ?::date)
                           OR (?::text = 'OPEN' AND u.persisted_status = 'OPEN' AND u.due_date >= ?::date)
                           OR (?::text IN ('PARTIAL', 'PARTIALLY_SETTLED') AND u.persisted_status = 'PARTIALLY_SETTLED')
                           OR (u.persisted_status = ?::text)
                       )
                       AND (?::date IS NULL OR (u.due_date, u.id) > (?::date, ?::uuid))
                     ORDER BY u.due_date ASC, u.id ASC
                     LIMIT ?
                    """,
                    (rs, rowNum) -> {
                        String persistedStatus = rs.getString("persisted_status");
                        LocalDate dueDate = rs.getDate("due_date").toLocalDate();
                        LocalDate issueDate = rs.getDate("issue_date").toLocalDate();
                        String effectiveStatus = FinancialRecordDomainPolicy.deriveEffectiveStatus(
                                persistedStatus, dueDate, manausToday
                        );
                        Money totalAmount = Money.ofBigDecimal(rs.getBigDecimal("amount"));
                        Money settledAmount = Money.ofBigDecimal(rs.getBigDecimal("settled_amount"));
                        Money openAmount = Money.ofBigDecimal(rs.getBigDecimal("open_amount"));
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
                                issueDate,
                                issueDate,
                                dueDate,
                                totalAmount,
                                Money.ZERO,
                                Money.ZERO,
                                Money.ZERO,
                                totalAmount,
                                settledAmount,
                                openAmount,
                                persistedStatus,
                                effectiveStatus,
                                rs.getTimestamp("created_at").toInstant(),
                                List.of()
                        );
                    },
                    ctx.tenantId(),
                    ctx.activeCompanyId(),
                    ctx.activeBranchId(),
                    ctx.activeBranchId(),
                    effectiveTypeFilter,
                    effectiveTypeFilter,
                    effectiveStatusFilter,
                    effectiveStatusFilter,
                    effectiveStatusFilter,
                    effectiveStatusFilter,
                    sqlManausToday,
                    effectiveStatusFilter,
                    sqlManausToday,
                    effectiveStatusFilter,
                    effectiveStatusFilter,
                    sqlCursorDueDate,
                    sqlCursorDueDate,
                    cursorId,
                    limit + 1
            );

            boolean hasNext = rows.size() > limit;
            List<FinancialRecordView> pageItems = hasNext ? rows.subList(0, limit) : rows;
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
        if (description.length() < 3 || description.length() > 500) {
            errors.add(new ValidationErrorItem(
                    "description",
                    "INVALID_DESCRIPTION",
                    "A descrição do título deve conter entre 3 e 500 caracteres."
            ));
        }
        if (request.partyId() == null && (request.counterpartyName() == null || request.counterpartyName().isBlank())) {
            errors.add(new ValidationErrorItem("partyId", "REQUIRED_PARTY", "O participante é obrigatório."));
        }
        if (request.categoryId() == null) {
            errors.add(new ValidationErrorItem("categoryId", "REQUIRED_CATEGORY", "A categoria financeira é obrigatória."));
        }
        if (!errors.isEmpty()) {
            throw new DomainValidationException(errors);
        }

        LocalDate issueDate = request.issueDate();
        LocalDate competenceDate = request.competenceDate() != null ? request.competenceDate() : issueDate;
        FinancialRecordDomainPolicy.validateDates(
                issueDate,
                competenceDate,
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

        String currencyCode = request.currencyCode() == null || request.currencyCode().isBlank()
                ? "BRL"
                : request.currencyCode().trim().toUpperCase(Locale.ROOT);
        LocalDate manausToday = FinancialRecordDomainPolicy.currentManausDate(clock);

        return rlsExecutor.executeInTenantTransaction(ctx, () ->
                idempotencyExecutor.execute(
                        "financial.record.create",
                        idempotencyKey,
                        request,
                        FinancialRecordView.class,
                        () -> {
                            String resolvedCounterpartyName = resolveAndValidateCatalogReferences(
                                    ctx,
                                    effectiveBranchId,
                                    request.partyId(),
                                    request.counterpartyName(),
                                    request.categoryId(),
                                    request.costCenterId(),
                                    recordType
                            );

                            UUID recordId = UUID.randomUUID();
                            String targetTable = "PAYABLE".equals(recordType) ? "payables" : "receivables";
                            String resourceType = "PAYABLE".equals(recordType) ? "Payable" : "Receivable";

                            jdbcTemplate.update(
                                    """
                                    INSERT INTO %s (
                                        id, tenant_id, company_id, branch_id,
                                        counterparty_id, category_id, cost_center_id,
                                        counterparty_name, description, document_number,
                                        issue_date, due_date,
                                        amount, outstanding_amount, currency_code,
                                        status, created_by_id
                                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'OPEN'::financial_status, ?)
                                    """.formatted(targetTable),
                                    recordId,
                                    ctx.tenantId(),
                                    ctx.activeCompanyId(),
                                    effectiveBranchId,
                                    request.partyId(),
                                    request.categoryId(),
                                    request.costCenterId(),
                                    resolvedCounterpartyName,
                                    description,
                                    request.documentNumber(),
                                    Date.valueOf(issueDate),
                                    Date.valueOf(request.dueDate()),
                                    totalAmount.toBigDecimal(),
                                    totalAmount.toBigDecimal(),
                                    currencyCode,
                                    ctx.userId()
                            );

                            FinancialRecordView created = loadRecordByIdInternal(ctx, recordId, manausToday);
                            auditTrailService.recordAuditLog(
                                    "financial.record.created",
                                    resourceType,
                                    recordId,
                                    effectiveBranchId,
                                    null,
                                    created
                            );
                            return new ResponseEnvelope<>(201, resourceType, recordId, created);
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
        Money principalAmount = Money.parsePositive(request.amount(), "amount");
        Money interestAmount = request.interestAmount() == null || request.interestAmount().isBlank()
                ? Money.ZERO
                : Money.parse(request.interestAmount(), "interestAmount").requireNonNegative("interestAmount");
        Money fineAmount = request.fineAmount() == null || request.fineAmount().isBlank()
                ? Money.ZERO
                : Money.parse(request.fineAmount(), "fineAmount").requireNonNegative("fineAmount");
        Money discountAmount = request.discountAmount() == null || request.discountAmount().isBlank()
                ? Money.ZERO
                : Money.parse(request.discountAmount(), "discountAmount").requireNonNegative("discountAmount");
        Money cashAmount = principalAmount.add(interestAmount).add(fineAmount).subtract(discountAmount);
        if (cashAmount.isNegative()) {
            throw new DomainValidationException(List.of(
                    new ValidationErrorItem(
                            "discountAmount",
                            "DISCOUNT_EXCEEDS_TOTAL",
                            "O desconto não pode tornar o movimento de caixa negativo."
                    )
            ));
        }

        LocalDate manausToday = FinancialRecordDomainPolicy.currentManausDate(clock);

        return rlsExecutor.executeInTenantTransaction(ctx, () ->
                idempotencyExecutor.execute(
                        "financial.record.settle:" + recordId,
                        idempotencyKey,
                        request,
                        FinancialRecordView.class,
                        () -> {
                            FinancialRecordView before = lockRecordForUpdate(ctx, recordId, manausToday);
                            FinancialRecordDomainPolicy.calculateSettlement(
                                    before.persistedStatus(),
                                    before.principalAmount(),
                                    before.discountAmount(),
                                    before.interestAmount(),
                                    before.fineAmount(),
                                    before.settledAmount(),
                                    principalAmount
                            );

                            UUID financialAccountId = resolveFinancialAccountId(
                                    ctx,
                                    before.branchId(),
                                    request.financialAccountId()
                            );
                            Instant occurredAt = resolveOccurredAt(request.settlementDate(), manausToday);
                            String note = buildSettlementNote(paymentMethod, request.bankReference(), request.notes());

                            UUID settlementId = UUID.randomUUID();
                            boolean isPayable = "PAYABLE".equals(before.recordType());
                            String eventTable = isPayable ? "payments" : "receipts";
                            String fkColumn = isPayable ? "payable_id" : "receivable_id";
                            String resourceType = isPayable ? "Payment" : "Receipt";

                            // INSERT no log append-only (payments/receipts); a trigger AFTER INSERT
                            // (payments_refresh_projection / receipts_refresh_projection) recalcula
                            // atomicamente outstanding_amount, status e paid_at/received_at no PostgreSQL.
                            jdbcTemplate.update(
                                    """
                                    INSERT INTO %s (
                                        id, tenant_id, company_id, branch_id, %s,
                                        financial_account_id, principal_amount, interest_amount,
                                        fine_amount, discount_amount, cash_amount,
                                        occurred_at, note, created_by_id
                                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                                    """.formatted(eventTable, fkColumn),
                                    settlementId,
                                    ctx.tenantId(),
                                    ctx.activeCompanyId(),
                                    before.branchId(),
                                    recordId,
                                    financialAccountId,
                                    principalAmount.toBigDecimal(),
                                    interestAmount.toBigDecimal(),
                                    fineAmount.toBigDecimal(),
                                    discountAmount.toBigDecimal(),
                                    cashAmount.toBigDecimal(),
                                    Timestamp.from(occurredAt),
                                    note,
                                    ctx.userId()
                            );

                            FinancialRecordView after = loadRecordByIdInternal(ctx, recordId, manausToday);
                            auditTrailService.recordAuditLog(
                                    "financial.record.settled",
                                    resourceType,
                                    settlementId,
                                    before.branchId(),
                                    before,
                                    after
                            );
                            return new ResponseEnvelope<>(200, resourceType, settlementId, after);
                        }
                )
        );
    }

    public IdempotentResult<FinancialRecordView> reverseSettlement(
            UUID recordId,
            UUID settlementId,
            String idempotencyKey,
            ReverseSettlementRequest request
    ) {
        AuthenticatedTenantContext ctx = TenantContextHolder.requirePermission("financial.settle");
        String reason = request != null && request.reason() != null ? request.reason().trim() : "";
        if (reason.length() < 4 || reason.length() > 500) {
            throw new DomainValidationException(List.of(
                    new ValidationErrorItem(
                            "reason",
                            "INVALID_REVERSAL_REASON",
                            "Informe uma justificativa de estorno entre 4 e 500 caracteres."
                    )
            ));
        }
        LocalDate manausToday = FinancialRecordDomainPolicy.currentManausDate(clock);

        return rlsExecutor.executeInTenantTransaction(ctx, () ->
                idempotencyExecutor.execute(
                        "financial.record.reverse:" + recordId + ":" + settlementId,
                        idempotencyKey,
                        request,
                        FinancialRecordView.class,
                        () -> {
                            FinancialRecordView before = lockRecordForUpdate(ctx, recordId, manausToday);
                            boolean isPayable = "PAYABLE".equals(before.recordType());
                            String eventTable = isPayable ? "payments" : "receipts";
                            String recordFkCol = isPayable ? "payable_id" : "receivable_id";
                            String reversalTable = isPayable ? "payment_reversals" : "receipt_reversals";
                            String eventFkCol = isPayable ? "payment_id" : "receipt_id";
                            String resourceType = isPayable ? "PaymentReversal" : "ReceiptReversal";

                            List<Timestamp> eventTimestamps = jdbcTemplate.query(
                                    """
                                    SELECT e.occurred_at, r.id AS reversal_id
                                      FROM %s e
                                      LEFT JOIN %s r
                                        ON r.tenant_id = e.tenant_id
                                       AND r.company_id = e.company_id
                                       AND r.%s = e.id
                                     WHERE e.tenant_id = ?
                                       AND e.company_id = ?
                                       AND e.%s = ?
                                       AND e.id = ?
                                    """.formatted(eventTable, reversalTable, eventFkCol, recordFkCol),
                                    (rs, rowNum) -> {
                                        if (rs.getObject("reversal_id") != null) {
                                            throw new ResourceConflictException(
                                                    "SETTLEMENT_ALREADY_REVERSED",
                                                    "A baixa financeira informada já foi estornada."
                                            );
                                        }
                                        return rs.getTimestamp("occurred_at");
                                    },
                                    ctx.tenantId(),
                                    ctx.activeCompanyId(),
                                    recordId,
                                    settlementId
                            );
                            if (eventTimestamps.isEmpty()) {
                                throw new ResourceNotFoundException(
                                        "SETTLEMENT_NOT_FOUND",
                                        "Baixa financeira não encontrada para este título."
                                );
                            }

                            Instant eventOccurredAt = eventTimestamps.getFirst().toInstant();
                            Instant now = Instant.now(clock);
                            Instant reversalInstant = request.occurredAt() != null ? request.occurredAt() : now;
                            if (reversalInstant.isBefore(eventOccurredAt)) {
                                reversalInstant = eventOccurredAt;
                            }

                            UUID reversalId = UUID.randomUUID();
                            jdbcTemplate.update(
                                    """
                                    INSERT INTO %s (
                                        id, tenant_id, company_id, branch_id, %s,
                                        reason, occurred_at, created_by_id
                                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                                    """.formatted(reversalTable, eventFkCol),
                                    reversalId,
                                    ctx.tenantId(),
                                    ctx.activeCompanyId(),
                                    before.branchId(),
                                    settlementId,
                                    reason,
                                    Timestamp.from(reversalInstant),
                                    ctx.userId()
                            );

                            FinancialRecordView after = loadRecordByIdInternal(ctx, recordId, manausToday);
                            auditTrailService.recordAuditLog(
                                    "financial.record.settlement.reversed",
                                    resourceType,
                                    reversalId,
                                    before.branchId(),
                                    before,
                                    after
                            );
                            return new ResponseEnvelope<>(200, resourceType, reversalId, after);
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

                            String targetTable = "PAYABLE".equals(before.recordType()) ? "payables" : "receivables";
                            String resourceType = "PAYABLE".equals(before.recordType()) ? "Payable" : "Receivable";

                            jdbcTemplate.update(
                                    """
                                    UPDATE %s
                                       SET deleted_at = CURRENT_TIMESTAMP,
                                           updated_by_id = ?,
                                           updated_at = CURRENT_TIMESTAMP
                                     WHERE id = ? AND tenant_id = ? AND company_id = ? AND deleted_at IS NULL
                                    """.formatted(targetTable),
                                    ctx.userId(),
                                    recordId,
                                    ctx.tenantId(),
                                    ctx.activeCompanyId()
                            );

                            FinancialRecordView after = loadRecordByIdInternal(ctx, recordId, manausToday);
                            auditTrailService.recordAuditLog(
                                    "financial.record.cancelled",
                                    resourceType,
                                    recordId,
                                    before.branchId(),
                                    before,
                                    after
                            );
                            return new ResponseEnvelope<>(200, resourceType, recordId, after);
                        }
                )
        );
    }

    public FinancialSummaryView getSummary() {
        AuthenticatedTenantContext ctx = TenantContextHolder.requirePermission("financial.read");
        LocalDate manausToday = FinancialRecordDomainPolicy.currentManausDate(clock);
        Date sqlManausToday = Date.valueOf(manausToday);

        return rlsExecutor.executeInTenantTransaction(ctx, () ->
                jdbcTemplate.queryForObject(
                        """
                        SELECT
                            COALESCE((
                                SELECT SUM(p.outstanding_amount)
                                  FROM payables p
                                 WHERE p.tenant_id = ?
                                   AND p.company_id = ?
                                   AND (?::uuid IS NULL OR p.branch_id = ?::uuid)
                                   AND p.deleted_at IS NULL
                                   AND p.status IN ('OPEN', 'PARTIAL')
                            ), 0.0000) AS open_payables,
                            COALESCE((
                                SELECT SUM(p.outstanding_amount)
                                  FROM payables p
                                 WHERE p.tenant_id = ?
                                   AND p.company_id = ?
                                   AND (?::uuid IS NULL OR p.branch_id = ?::uuid)
                                   AND p.deleted_at IS NULL
                                   AND p.status = 'OPEN'
                                   AND p.due_date < ?::date
                            ), 0.0000) AS overdue_payables,
                            COALESCE((
                                SELECT SUM(p.amount - p.outstanding_amount)
                                  FROM payables p
                                 WHERE p.tenant_id = ?
                                   AND p.company_id = ?
                                   AND (?::uuid IS NULL OR p.branch_id = ?::uuid)
                                   AND p.deleted_at IS NULL
                                   AND p.status IN ('PARTIAL', 'SETTLED')
                            ), 0.0000) AS settled_payables,
                            COALESCE((
                                SELECT SUM(r.outstanding_amount)
                                  FROM receivables r
                                 WHERE r.tenant_id = ?
                                   AND r.company_id = ?
                                   AND (?::uuid IS NULL OR r.branch_id = ?::uuid)
                                   AND r.deleted_at IS NULL
                                   AND r.status IN ('OPEN', 'PARTIAL')
                            ), 0.0000) AS open_receivables,
                            COALESCE((
                                SELECT SUM(r.outstanding_amount)
                                  FROM receivables r
                                 WHERE r.tenant_id = ?
                                   AND r.company_id = ?
                                   AND (?::uuid IS NULL OR r.branch_id = ?::uuid)
                                   AND r.deleted_at IS NULL
                                   AND r.status = 'OPEN'
                                   AND r.due_date < ?::date
                            ), 0.0000) AS overdue_receivables,
                            COALESCE((
                                SELECT SUM(r.amount - r.outstanding_amount)
                                  FROM receivables r
                                 WHERE r.tenant_id = ?
                                   AND r.company_id = ?
                                   AND (?::uuid IS NULL OR r.branch_id = ?::uuid)
                                   AND r.deleted_at IS NULL
                                   AND r.status IN ('PARTIAL', 'SETTLED')
                            ), 0.0000) AS settled_receivables
                        """,
                        (rs, rowNum) -> {
                            Money openPayables = Money.ofBigDecimal(rs.getBigDecimal("open_payables"));
                            Money overduePayables = Money.ofBigDecimal(rs.getBigDecimal("overdue_payables"));
                            Money settledPayables = Money.ofBigDecimal(rs.getBigDecimal("settled_payables"));
                            Money openReceivables = Money.ofBigDecimal(rs.getBigDecimal("open_receivables"));
                            Money overdueReceivables = Money.ofBigDecimal(rs.getBigDecimal("overdue_receivables"));
                            Money settledReceivables = Money.ofBigDecimal(rs.getBigDecimal("settled_receivables"));
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
                        },
                        ctx.tenantId(), ctx.activeCompanyId(), ctx.activeBranchId(), ctx.activeBranchId(),
                        ctx.tenantId(), ctx.activeCompanyId(), ctx.activeBranchId(), ctx.activeBranchId(), sqlManausToday,
                        ctx.tenantId(), ctx.activeCompanyId(), ctx.activeBranchId(), ctx.activeBranchId(),
                        ctx.tenantId(), ctx.activeCompanyId(), ctx.activeBranchId(), ctx.activeBranchId(),
                        ctx.tenantId(), ctx.activeCompanyId(), ctx.activeBranchId(), ctx.activeBranchId(), sqlManausToday,
                        ctx.tenantId(), ctx.activeCompanyId(), ctx.activeBranchId(), ctx.activeBranchId()
                )
        );
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
        Map<String, Object> cursorPos = BoundCursorCodec.decodeCursor(cursor, scope);
        Timestamp cursorOccurredAt = cursorPos == null
                ? null
                : Timestamp.from(Instant.parse(String.valueOf(cursorPos.get("occurredAt"))));
        UUID cursorId = cursorPos == null ? null : UUID.fromString(String.valueOf(cursorPos.get("id")));

        return rlsExecutor.executeInTenantTransaction(ctx, () -> {
            List<FinancialEventView> rows = jdbcTemplate.query(
                    """
                    WITH unified_events AS (
                        SELECT
                            pm.id, pm.tenant_id, pm.company_id, pm.branch_id,
                            pm.payable_id AS financial_record_id,
                            'PAYMENT'::text AS event_type,
                            pm.cash_amount AS amount,
                            pm.principal_amount,
                            pm.financial_account_id,
                            pm.note AS detail,
                            pm.created_by_id AS actor_user_id,
                            pm.occurred_at
                          FROM payments pm
                        UNION ALL
                        SELECT
                            pr.id, pr.tenant_id, pr.company_id, pr.branch_id,
                            pm.payable_id AS financial_record_id,
                            'PAYMENT_REVERSAL'::text AS event_type,
                            pm.cash_amount AS amount,
                            pm.principal_amount,
                            pm.financial_account_id,
                            pr.reason AS detail,
                            pr.created_by_id AS actor_user_id,
                            pr.occurred_at
                          FROM payment_reversals pr
                          JOIN payments pm
                            ON pm.tenant_id = pr.tenant_id
                           AND pm.company_id = pr.company_id
                           AND pm.id = pr.payment_id
                        UNION ALL
                        SELECT
                            rc.id, rc.tenant_id, rc.company_id, rc.branch_id,
                            rc.receivable_id AS financial_record_id,
                            'RECEIPT'::text AS event_type,
                            rc.cash_amount AS amount,
                            rc.principal_amount,
                            rc.financial_account_id,
                            rc.note AS detail,
                            rc.created_by_id AS actor_user_id,
                            rc.occurred_at
                          FROM receipts rc
                        UNION ALL
                        SELECT
                            rr.id, rr.tenant_id, rr.company_id, rr.branch_id,
                            rc.receivable_id AS financial_record_id,
                            'RECEIPT_REVERSAL'::text AS event_type,
                            rc.cash_amount AS amount,
                            rc.principal_amount,
                            rc.financial_account_id,
                            rr.reason AS detail,
                            rr.created_by_id AS actor_user_id,
                            rr.occurred_at
                          FROM receipt_reversals rr
                          JOIN receipts rc
                            ON rc.tenant_id = rr.tenant_id
                           AND rc.company_id = rr.company_id
                           AND rc.id = rr.receipt_id
                    )
                    SELECT
                        e.id, e.tenant_id, e.company_id, e.branch_id,
                        e.financial_record_id, e.event_type, e.amount,
                        e.principal_amount, e.financial_account_id, e.detail,
                        e.actor_user_id, e.occurred_at
                      FROM unified_events e
                     WHERE e.tenant_id = ?
                       AND e.company_id = ?
                       AND (?::uuid IS NULL OR e.branch_id = ?::uuid)
                       AND (?::uuid IS NULL OR e.financial_record_id = ?::uuid)
                       AND (?::timestamptz IS NULL OR (e.occurred_at, e.id) < (?::timestamptz, ?::uuid))
                     ORDER BY e.occurred_at DESC, e.id DESC
                     LIMIT ?
                    """,
                    (rs, rowNum) -> {
                        Map<String, Object> payload = new LinkedHashMap<>();
                        payload.put("principalAmount", Money.ofBigDecimal(rs.getBigDecimal("principal_amount")).toCanonicalString());
                        UUID financialAccountId = rs.getObject("financial_account_id", UUID.class);
                        if (financialAccountId != null) {
                            payload.put("financialAccountId", financialAccountId.toString());
                        }
                        String detail = rs.getString("detail");
                        if (detail != null) {
                            payload.put("note", detail);
                        }
                        return new FinancialEventView(
                                rs.getObject("id", UUID.class),
                                rs.getObject("tenant_id", UUID.class),
                                rs.getObject("company_id", UUID.class),
                                rs.getObject("branch_id", UUID.class),
                                rs.getObject("financial_record_id", UUID.class),
                                rs.getString("event_type"),
                                Money.ofBigDecimal(rs.getBigDecimal("amount")),
                                payload,
                                rs.getObject("actor_user_id", UUID.class),
                                rs.getTimestamp("occurred_at").toInstant()
                        );
                    },
                    ctx.tenantId(),
                    ctx.activeCompanyId(),
                    ctx.activeBranchId(),
                    ctx.activeBranchId(),
                    recordIdFilter,
                    recordIdFilter,
                    cursorOccurredAt,
                    cursorOccurredAt,
                    cursorId,
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

    private String resolveAndValidateCatalogReferences(
            AuthenticatedTenantContext ctx,
            UUID effectiveBranchId,
            UUID partyId,
            String fallbackCounterpartyName,
            UUID categoryId,
            UUID costCenterId,
            String recordType
    ) {
        String resolvedCounterpartyName = fallbackCounterpartyName == null ? null : fallbackCounterpartyName.trim();
        if (partyId != null) {
            record CounterpartyRef(String name, String kind, UUID branchId) {}
            List<CounterpartyRef> partyRows = jdbcTemplate.query(
                    """
                    SELECT name, kind::text AS kind, branch_id
                      FROM counterparties
                     WHERE id = ?
                       AND tenant_id = ?
                       AND company_id = ?
                       AND is_active = true
                       AND deleted_at IS NULL
                    """,
                    (rs, rowNum) -> new CounterpartyRef(
                            rs.getString("name"),
                            rs.getString("kind"),
                            rs.getObject("branch_id", UUID.class)
                    ),
                    partyId, ctx.tenantId(), ctx.activeCompanyId()
            );
            if (partyRows.isEmpty()) {
                throw new ResourceNotFoundException(
                        "PARTY_NOT_FOUND",
                        "Participante não encontrado ou inativo na empresa ativa."
                );
            }
            CounterpartyRef cp = partyRows.getFirst();
            if (cp.branchId() != null && !cp.branchId().equals(effectiveBranchId)) {
                throw new DomainValidationException(List.of(
                        new ValidationErrorItem(
                                "partyId",
                                "COUNTERPARTY_BRANCH_MISMATCH",
                                "O participante pertence a outra filial."
                        )
                ));
            }
            String expectedPartyKind = "PAYABLE".equals(recordType) ? "SUPPLIER" : "CUSTOMER";
            if (!expectedPartyKind.equals(cp.kind()) && !"BOTH".equals(cp.kind())) {
                throw new DomainValidationException(List.of(
                        new ValidationErrorItem(
                                "partyId",
                                "COUNTERPARTY_KIND_MISMATCH",
                                "O tipo do participante é incompatível com o tipo do título."
                        )
                ));
            }
            resolvedCounterpartyName = cp.name();
        }

        record CategoryRef(String direction, UUID branchId) {}
        List<CategoryRef> categoryRows = jdbcTemplate.query(
                """
                SELECT direction::text AS direction, branch_id
                  FROM financial_categories
                 WHERE id = ?
                   AND tenant_id = ?
                   AND company_id = ?
                   AND is_active = true
                   AND deleted_at IS NULL
                """,
                (rs, rowNum) -> new CategoryRef(
                        rs.getString("direction"),
                        rs.getObject("branch_id", UUID.class)
                ),
                categoryId, ctx.tenantId(), ctx.activeCompanyId()
        );
        if (categoryRows.isEmpty()) {
            throw new ResourceNotFoundException(
                    "CATEGORY_NOT_FOUND",
                    "Categoria financeira não encontrada na empresa ativa."
            );
        }
        CategoryRef cat = categoryRows.getFirst();
        if (cat.branchId() != null && !cat.branchId().equals(effectiveBranchId)) {
            throw new DomainValidationException(List.of(
                    new ValidationErrorItem(
                            "categoryId",
                            "CATEGORY_BRANCH_MISMATCH",
                            "A categoria financeira pertence a outra filial."
                    )
            ));
        }
        String expectedDirection = "PAYABLE".equals(recordType) ? "PAYABLE" : "RECEIVABLE";
        if (!expectedDirection.equals(cat.direction()) && !"BOTH".equals(cat.direction())) {
            throw new DomainValidationException(List.of(
                    new ValidationErrorItem(
                            "categoryId",
                            "CATEGORY_KIND_MISMATCH",
                            "A natureza da categoria financeira é incompatível com o tipo do título."
                    )
            ));
        }

        if (costCenterId != null) {
            List<UUID> ccBranches = jdbcTemplate.query(
                    """
                    SELECT branch_id
                      FROM cost_centers
                     WHERE id = ?
                       AND tenant_id = ?
                       AND company_id = ?
                       AND is_active = true
                       AND deleted_at IS NULL
                    """,
                    (rs, rowNum) -> rs.getObject("branch_id", UUID.class),
                    costCenterId, ctx.tenantId(), ctx.activeCompanyId()
            );
            if (ccBranches.isEmpty()) {
                throw new ResourceNotFoundException(
                        "COST_CENTER_NOT_FOUND",
                        "Centro de custo não encontrado na empresa ativa."
                );
            }
            UUID ccBranch = ccBranches.getFirst();
            if (ccBranch != null && !ccBranch.equals(effectiveBranchId)) {
                throw new DomainValidationException(List.of(
                        new ValidationErrorItem(
                                "costCenterId",
                                "COST_CENTER_BRANCH_MISMATCH",
                                "O centro de custo pertence a outra filial."
                        )
                ));
            }
        }

        return resolvedCounterpartyName;
    }

    private UUID resolveFinancialAccountId(
            AuthenticatedTenantContext ctx,
            UUID recordBranchId,
            UUID requestedAccountId
    ) {
        if (requestedAccountId != null) {
            List<UUID> found = jdbcTemplate.query(
                    """
                    SELECT id
                      FROM financial_accounts
                     WHERE id = ?
                       AND tenant_id = ?
                       AND company_id = ?
                       AND is_active = true
                       AND deleted_at IS NULL
                       AND (branch_id IS NULL OR branch_id = ?::uuid)
                    """,
                    (rs, rowNum) -> rs.getObject("id", UUID.class),
                    requestedAccountId,
                    ctx.tenantId(),
                    ctx.activeCompanyId(),
                    recordBranchId
            );
            if (found.isEmpty()) {
                throw new DomainValidationException(List.of(
                        new ValidationErrorItem(
                                "financialAccountId",
                                "FINANCIAL_ACCOUNT_NOT_AVAILABLE",
                                "Selecione uma conta financeira ativa e compatível com a filial do título."
                        )
                ));
            }
            return found.getFirst();
        }

        List<UUID> existingAccounts = jdbcTemplate.query(
                """
                SELECT id
                  FROM financial_accounts
                 WHERE tenant_id = ?
                   AND company_id = ?
                   AND is_active = true
                   AND deleted_at IS NULL
                   AND (branch_id IS NULL OR branch_id = ?::uuid)
                 ORDER BY CASE WHEN branch_id = ?::uuid THEN 0 ELSE 1 END, created_at ASC
                 LIMIT 1
                """,
                (rs, rowNum) -> rs.getObject("id", UUID.class),
                ctx.tenantId(),
                ctx.activeCompanyId(),
                recordBranchId,
                recordBranchId
        );
        if (!existingAccounts.isEmpty()) {
            return existingAccounts.getFirst();
        }

        UUID defaultAccountId = UUID.randomUUID();
        String code = recordBranchId == null
                ? "DEFAULT-CASH"
                : "DEFAULT-CASH-" + recordBranchId.toString().substring(0, 8).toUpperCase(Locale.ROOT);
        jdbcTemplate.update(
                """
                INSERT INTO financial_accounts (
                    id, tenant_id, company_id, branch_id,
                    code, name, type, currency_code, is_active, created_by_id
                ) VALUES (?, ?, ?, ?, ?, 'Conta Caixa Padrão', 'CASH'::financial_account_type, 'BRL', true, ?)
                ON CONFLICT (tenant_id, company_id, code) DO NOTHING
                """,
                defaultAccountId,
                ctx.tenantId(),
                ctx.activeCompanyId(),
                recordBranchId,
                code,
                ctx.userId()
        );
        return jdbcTemplate.queryForObject(
                "SELECT id FROM financial_accounts WHERE tenant_id = ? AND company_id = ? AND code = ?",
                (rs, rowNum) -> rs.getObject("id", UUID.class),
                ctx.tenantId(),
                ctx.activeCompanyId(),
                code
        );
    }

    private Instant resolveOccurredAt(LocalDate settlementDate, LocalDate manausToday) {
        Instant now = Instant.now(clock);
        if (settlementDate == null || !settlementDate.isBefore(manausToday)) {
            return now;
        }
        Instant candidate = settlementDate
                .atTime(12, 0)
                .atZone(FinancialRecordDomainPolicy.MANAUS_ZONE)
                .toInstant();
        return candidate.isAfter(now) ? now : candidate;
    }

    private static String buildSettlementNote(String paymentMethod, String bankReference, String notes) {
        List<String> parts = new ArrayList<>();
        if (paymentMethod != null && !paymentMethod.isBlank()) {
            parts.add("method=" + paymentMethod.trim());
        }
        if (bankReference != null && !bankReference.isBlank()) {
            parts.add("ref=" + bankReference.trim());
        }
        if (notes != null && !notes.isBlank()) {
            parts.add(notes.trim());
        }
        if (parts.isEmpty()) {
            return null;
        }
        String joined = String.join(" | ", parts);
        return joined.length() > 500 ? joined.substring(0, 500) : joined;
    }

    private FinancialRecordView lockRecordForUpdate(
            AuthenticatedTenantContext ctx,
            UUID recordId,
            LocalDate manausToday
    ) {
        List<UUID> lockedPayable = jdbcTemplate.query(
                "SELECT id FROM payables WHERE id = ? AND tenant_id = ? AND company_id = ? FOR UPDATE",
                (rs, rowNum) -> rs.getObject("id", UUID.class),
                recordId, ctx.tenantId(), ctx.activeCompanyId()
        );
        if (lockedPayable.isEmpty()) {
            List<UUID> lockedReceivable = jdbcTemplate.query(
                    "SELECT id FROM receivables WHERE id = ? AND tenant_id = ? AND company_id = ? FOR UPDATE",
                    (rs, rowNum) -> rs.getObject("id", UUID.class),
                    recordId, ctx.tenantId(), ctx.activeCompanyId()
            );
            if (lockedReceivable.isEmpty()) {
                throw new ResourceNotFoundException(
                        "FINANCIAL_RECORD_NOT_FOUND",
                        "Título financeiro não encontrado no contexto ativo."
                );
            }
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
                SELECT
                    pm.id,
                    pm.payable_id AS financial_record_id,
                    (pm.occurred_at AT TIME ZONE 'America/Manaus')::date AS settlement_date,
                    pm.principal_amount AS amount,
                    pm.note,
                    pm.created_at
                  FROM payments pm
                  LEFT JOIN payment_reversals pr
                    ON pr.tenant_id = pm.tenant_id
                   AND pr.company_id = pm.company_id
                   AND pr.payment_id = pm.id
                 WHERE pm.payable_id = ?
                   AND pm.tenant_id = ?
                   AND pm.company_id = ?
                   AND pr.id IS NULL
                UNION ALL
                SELECT
                    rc.id,
                    rc.receivable_id AS financial_record_id,
                    (rc.occurred_at AT TIME ZONE 'America/Manaus')::date AS settlement_date,
                    rc.principal_amount AS amount,
                    rc.note,
                    rc.created_at
                  FROM receipts rc
                  LEFT JOIN receipt_reversals rr
                    ON rr.tenant_id = rc.tenant_id
                   AND rr.company_id = rc.company_id
                   AND rr.receipt_id = rc.id
                 WHERE rc.receivable_id = ?
                   AND rc.tenant_id = ?
                   AND rc.company_id = ?
                   AND rr.id IS NULL
                 ORDER BY settlement_date ASC, created_at ASC
                """,
                (rs, rowNum) -> new FinancialSettlementView(
                        rs.getObject("id", UUID.class),
                        rs.getObject("financial_record_id", UUID.class),
                        rs.getDate("settlement_date").toLocalDate(),
                        Money.ofBigDecimal(rs.getBigDecimal("amount")),
                        "PIX",
                        null,
                        rs.getString("note"),
                        rs.getTimestamp("created_at").toInstant()
                ),
                recordId, ctx.tenantId(), ctx.activeCompanyId(),
                recordId, ctx.tenantId(), ctx.activeCompanyId()
        );

        List<FinancialRecordView> records = jdbcTemplate.query(
                """
                WITH unified_record AS (
                    SELECT
                        p.id, p.tenant_id, p.company_id, p.branch_id,
                        p.counterparty_id AS party_id,
                        COALESCE(cp.name, p.counterparty_name) AS party_name,
                        p.category_id, fc.name AS category_name,
                        p.cost_center_id, cc.name AS cost_center_name,
                        'PAYABLE'::text AS record_type,
                        p.description, p.document_number,
                        COALESCE(p.issue_date, p.due_date) AS issue_date,
                        p.due_date,
                        p.amount,
                        CASE WHEN p.deleted_at IS NOT NULL THEN 0.0000 ELSE p.outstanding_amount END AS open_amount,
                        (p.amount - p.outstanding_amount) AS settled_amount,
                        CASE
                            WHEN p.deleted_at IS NOT NULL THEN 'CANCELLED'
                            WHEN p.status = 'PARTIAL' THEN 'PARTIALLY_SETTLED'
                            ELSE p.status::text
                        END AS persisted_status,
                        p.created_at
                      FROM payables p
                      LEFT JOIN counterparties cp
                        ON cp.tenant_id = p.tenant_id AND cp.company_id = p.company_id AND cp.id = p.counterparty_id
                      LEFT JOIN financial_categories fc
                        ON fc.tenant_id = p.tenant_id AND fc.company_id = p.company_id AND fc.id = p.category_id
                      LEFT JOIN cost_centers cc
                        ON cc.tenant_id = p.tenant_id AND cc.company_id = p.company_id AND cc.id = p.cost_center_id
                     WHERE p.id = ? AND p.tenant_id = ? AND p.company_id = ?
                    UNION ALL
                    SELECT
                        r.id, r.tenant_id, r.company_id, r.branch_id,
                        r.counterparty_id AS party_id,
                        COALESCE(cp.name, r.counterparty_name) AS party_name,
                        r.category_id, fc.name AS category_name,
                        r.cost_center_id, cc.name AS cost_center_name,
                        'RECEIVABLE'::text AS record_type,
                        r.description, r.document_number,
                        COALESCE(r.issue_date, r.due_date) AS issue_date,
                        r.due_date,
                        r.amount,
                        CASE WHEN r.deleted_at IS NOT NULL THEN 0.0000 ELSE r.outstanding_amount END AS open_amount,
                        (r.amount - r.outstanding_amount) AS settled_amount,
                        CASE
                            WHEN r.deleted_at IS NOT NULL THEN 'CANCELLED'
                            WHEN r.status = 'PARTIAL' THEN 'PARTIALLY_SETTLED'
                            ELSE r.status::text
                        END AS persisted_status,
                        r.created_at
                      FROM receivables r
                      LEFT JOIN counterparties cp
                        ON cp.tenant_id = r.tenant_id AND cp.company_id = r.company_id AND cp.id = r.counterparty_id
                      LEFT JOIN financial_categories fc
                        ON fc.tenant_id = r.tenant_id AND fc.company_id = r.company_id AND fc.id = r.category_id
                      LEFT JOIN cost_centers cc
                        ON cc.tenant_id = r.tenant_id AND cc.company_id = r.company_id AND cc.id = r.cost_center_id
                     WHERE r.id = ? AND r.tenant_id = ? AND r.company_id = ?
                )
                SELECT * FROM unified_record
                """,
                (rs, rowNum) -> {
                    String persistedStatus = rs.getString("persisted_status");
                    LocalDate dueDate = rs.getDate("due_date").toLocalDate();
                    LocalDate issueDate = rs.getDate("issue_date").toLocalDate();
                    String effectiveStatus = FinancialRecordDomainPolicy.deriveEffectiveStatus(
                            persistedStatus, dueDate, manausToday
                    );
                    Money totalAmount = Money.ofBigDecimal(rs.getBigDecimal("amount"));
                    Money settledAmount = Money.ofBigDecimal(rs.getBigDecimal("settled_amount"));
                    Money openAmount = Money.ofBigDecimal(rs.getBigDecimal("open_amount"));
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
                            issueDate,
                            issueDate,
                            dueDate,
                            totalAmount,
                            Money.ZERO,
                            Money.ZERO,
                            Money.ZERO,
                            totalAmount,
                            settledAmount,
                            openAmount,
                            persistedStatus,
                            effectiveStatus,
                            rs.getTimestamp("created_at").toInstant(),
                            settlements
                    );
                },
                recordId, ctx.tenantId(), ctx.activeCompanyId(),
                recordId, ctx.tenantId(), ctx.activeCompanyId()
        );

        if (records.isEmpty()) {
            throw new ResourceNotFoundException(
                    "FINANCIAL_RECORD_NOT_FOUND",
                    "Título financeiro não encontrado no contexto ativo."
            );
        }
        return records.getFirst();
    }

    private static int clampLimit(int rawLimit) {
        if (rawLimit < 1) {
            return 25;
        }
        return Math.min(rawLimit, 100);
    }
}
