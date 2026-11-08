package br.com.mouraoclareza.reconciliation.application;

import br.com.mouraoclareza.financial.domain.FinancialCatalogModels.CursorPageResponse;
import br.com.mouraoclareza.financial.domain.FinancialCatalogModels.PageInfo;
import br.com.mouraoclareza.reconciliation.domain.BankReconciliationDomainPolicy;
import br.com.mouraoclareza.reconciliation.domain.BankReconciliationDomainPolicy.SuggestionScore;
import br.com.mouraoclareza.reconciliation.domain.BankReconciliationModels.BankAccountView;
import br.com.mouraoclareza.reconciliation.domain.BankReconciliationModels.BankReconciliationView;
import br.com.mouraoclareza.reconciliation.domain.BankReconciliationModels.BankStatementImportView;
import br.com.mouraoclareza.reconciliation.domain.BankReconciliationModels.BankTransactionView;
import br.com.mouraoclareza.reconciliation.domain.BankReconciliationModels.CreateBankAccountRequest;
import br.com.mouraoclareza.reconciliation.domain.BankReconciliationModels.CreateStatementImportRequest;
import br.com.mouraoclareza.reconciliation.domain.BankReconciliationModels.MatchReconciliationRequest;
import br.com.mouraoclareza.reconciliation.domain.BankReconciliationModels.ReconciliationSuggestionView;
import br.com.mouraoclareza.reconciliation.domain.BankReconciliationModels.UndoReconciliationRequest;
import br.com.mouraoclareza.reconciliation.domain.BankStatementParser;
import br.com.mouraoclareza.reconciliation.domain.BankStatementParser.ParsedMovementLine;
import br.com.mouraoclareza.reconciliation.domain.BankStatementParser.ParsedStatement;
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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Serviço de aplicação transacional para Contas Bancárias (financial_accounts),
 * Importação Idempotente de Extratos (bank_imports / bank_transactions),
 * Sugestões Determinísticas de Conciliação e Pareamento/Reversão Atômica Append-Only
 * (bank_reconciliation_groups / bank_reconciliation_items / bank_reconciliation_reversals).
 * Alinhado 100% com V5__financial_operations.sql, V6__phase_two_hardening.sql e V7__bank_reconciliation.sql.
 */
@Service
public class BankReconciliationService {

    private static final ZoneId MANAUS_ZONE = ZoneId.of("America/Manaus");
    private static final Set<String> VALID_BANK_ACCOUNT_TYPES = Set.of("CHECKING", "SAVINGS", "PAYMENT");

    private final JdbcTemplate jdbcTemplate;
    private final RlsTransactionExecutor rlsExecutor;
    private final IdempotencyExecutor idempotencyExecutor;
    private final AuditTrailService auditTrailService;

    public BankReconciliationService(
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

    public CursorPageResponse<BankAccountView> listBankAccounts(String cursor, int rawLimit) {
        AuthenticatedTenantContext ctx = TenantContextHolder.requirePermission("bank.read");
        int limit = clampLimit(rawLimit);
        CursorScope scope = new CursorScope(
                "bank_accounts",
                ctx.tenantId(),
                ctx.activeCompanyId(),
                ctx.activeBranchId(),
                "bank_name_asc,id_asc",
                Map.of()
        );
        Map<String, Object> cursorPos = BoundCursorCodec.decodeCursor(cursor, scope);
        String cursorName = cursorPos == null ? null : (String) cursorPos.get("bankName");
        UUID cursorId = cursorPos == null ? null : UUID.fromString(String.valueOf(cursorPos.get("id")));

        return rlsExecutor.executeInTenantTransaction(ctx, () -> {
            List<BankAccountView> rows = jdbcTemplate.query(
                    """
                    SELECT id, tenant_id, company_id, branch_id, code, name,
                           type::text AS account_type, currency_code, is_active, created_at
                      FROM financial_accounts
                     WHERE tenant_id = ?
                       AND company_id = ?
                       AND deleted_at IS NULL
                       AND is_active = true
                       AND type IN ('CHECKING', 'SAVINGS', 'PAYMENT')
                       AND (?::uuid IS NULL OR branch_id IS NULL OR branch_id = ?::uuid)
                       AND (?::text IS NULL OR (name, id) > (?::text, ?::uuid))
                     ORDER BY name ASC, id ASC
                     LIMIT ?
                    """,
                    (rs, rowNum) -> new BankAccountView(
                            rs.getObject("id", UUID.class),
                            rs.getObject("tenant_id", UUID.class),
                            rs.getObject("company_id", UUID.class),
                            rs.getObject("branch_id", UUID.class),
                            rs.getString("code"),
                            rs.getString("name"),
                            "0001",
                            rs.getString("code"),
                            rs.getString("account_type"),
                            rs.getString("currency_code"),
                            Money.ZERO,
                            rs.getBoolean("is_active") ? "ACTIVE" : "INACTIVE",
                            rs.getTimestamp("created_at").toInstant()
                    ),
                    ctx.tenantId(),
                    ctx.activeCompanyId(),
                    ctx.activeBranchId(),
                    ctx.activeBranchId(),
                    cursorName,
                    cursorName,
                    cursorId,
                    limit + 1
            );

            boolean hasNext = rows.size() > limit;
            List<BankAccountView> items = hasNext ? rows.subList(0, limit) : rows;
            String nextCursor = null;
            if (hasNext && !items.isEmpty()) {
                BankAccountView last = items.getLast();
                nextCursor = BoundCursorCodec.encodeCursor(scope, Map.of(
                        "bankName", last.bankName(),
                        "id", last.id().toString()
                ));
            }
            return new CursorPageResponse<>(items, new PageInfo(limit, hasNext, nextCursor));
        });
    }

    public IdempotentResult<BankAccountView> createBankAccount(
            String idempotencyKey,
            CreateBankAccountRequest request
    ) {
        AuthenticatedTenantContext ctx = TenantContextHolder.requirePermission("bank.write");
        UUID effectiveBranchId = request.branchId() != null ? request.branchId() : ctx.activeBranchId();
        TenantContextHolder.assertCompanyAndBranchAccess(ctx.activeCompanyId(), effectiveBranchId);

        List<ValidationErrorItem> errors = new ArrayList<>();
        if (request.bankCode() == null || request.bankCode().isBlank()) {
            errors.add(new ValidationErrorItem("bankCode", "REQUIRED_BANK_CODE", "Código do banco é obrigatório."));
        }
        if (request.bankName() == null || request.bankName().isBlank()) {
            errors.add(new ValidationErrorItem("bankName", "REQUIRED_BANK_NAME", "Nome do banco é obrigatório."));
        }
        if (request.accountNumber() == null || request.accountNumber().isBlank()) {
            errors.add(new ValidationErrorItem("accountNumber", "REQUIRED_ACCOUNT_NUMBER", "Número da conta é obrigatório."));
        }
        String accountType = request.accountType() == null || request.accountType().isBlank()
                ? "CHECKING"
                : request.accountType().trim().toUpperCase(Locale.ROOT);
        if (!VALID_BANK_ACCOUNT_TYPES.contains(accountType)) {
            errors.add(new ValidationErrorItem(
                    "accountType",
                    "INVALID_ACCOUNT_TYPE",
                    "O tipo da conta bancária deve ser CHECKING, SAVINGS ou PAYMENT."
            ));
        }
        if (!errors.isEmpty()) {
            throw new DomainValidationException(errors);
        }

        Money initialBalance = request.initialBalance() == null || request.initialBalance().isBlank()
                ? Money.ZERO
                : Money.parse(request.initialBalance(), "initialBalance");
        String code = ("BANK-" + request.bankCode().trim() + "-" + request.accountNumber().trim())
                .toUpperCase(Locale.ROOT);
        if (code.length() > 40) {
            code = code.substring(0, 40);
        }
        final String accountCode = code;

        return rlsExecutor.executeInTenantTransaction(ctx, () ->
                idempotencyExecutor.execute(
                        "bank.account.create",
                        idempotencyKey,
                        request,
                        BankAccountView.class,
                        () -> {
                            UUID id = UUID.randomUUID();
                            Instant now = Instant.now();
                            try {
                                jdbcTemplate.update(
                                        """
                                        INSERT INTO financial_accounts (
                                            id, tenant_id, company_id, branch_id,
                                            code, name, type, currency_code, is_active, created_by_id
                                        ) VALUES (?, ?, ?, ?, ?, ?, ?::financial_account_type, 'BRL', true, ?)
                                        """,
                                        id,
                                        ctx.tenantId(),
                                        ctx.activeCompanyId(),
                                        effectiveBranchId,
                                        accountCode,
                                        request.bankName().trim(),
                                        accountType,
                                        ctx.userId()
                                );
                            } catch (DuplicateKeyException ex) {
                                throw new ResourceConflictException(
                                        "FINANCIAL_CATALOG_CONFLICT",
                                        "Já existe uma conta bancária cadastrada com este código."
                                );
                            }

                            BankAccountView created = new BankAccountView(
                                    id,
                                    ctx.tenantId(),
                                    ctx.activeCompanyId(),
                                    effectiveBranchId,
                                    request.bankCode().trim(),
                                    request.bankName().trim(),
                                    request.agencyNumber() != null ? request.agencyNumber().trim() : "0001",
                                    request.accountNumber().trim(),
                                    accountType,
                                    "BRL",
                                    initialBalance,
                                    "ACTIVE",
                                    now
                            );
                            auditTrailService.recordAuditLog(
                                    "bank.account.created",
                                    "FinancialAccount",
                                    id,
                                    effectiveBranchId,
                                    null,
                                    created
                            );
                            return new ResponseEnvelope<>(201, "FinancialAccount", id, created);
                        }
                )
        );
    }

    public IdempotentResult<BankStatementImportView> importStatement(
            String idempotencyKey,
            CreateStatementImportRequest request
    ) {
        AuthenticatedTenantContext ctx = TenantContextHolder.requirePermission("bank.write");
        if (request.bankAccountId() == null) {
            throw new DomainValidationException(List.of(
                    new ValidationErrorItem("bankAccountId", "REQUIRED_BANK_ACCOUNT", "A conta bancária é obrigatória.")
            ));
        }

        ParsedStatement parsed = BankStatementParser.parse(
                ctx.tenantId(),
                ctx.activeCompanyId(),
                request.bankAccountId(),
                request.format(),
                request.content()
        );

        return rlsExecutor.executeInTenantTransaction(ctx, () ->
                idempotencyExecutor.execute(
                        "bank.statement.import",
                        idempotencyKey,
                        request,
                        BankStatementImportView.class,
                        () -> {
                            record AccountScope(UUID branchId, String currencyCode, String type) {}
                            List<AccountScope> accountRows = jdbcTemplate.query(
                                    """
                                    SELECT branch_id, currency_code, type::text AS type
                                      FROM financial_accounts
                                     WHERE id = ?
                                       AND tenant_id = ?
                                       AND company_id = ?
                                       AND is_active = true
                                       AND deleted_at IS NULL
                                     FOR UPDATE
                                    """,
                                    (rs, rowNum) -> new AccountScope(
                                            rs.getObject("branch_id", UUID.class),
                                            rs.getString("currency_code"),
                                            rs.getString("type")
                                    ),
                                    request.bankAccountId(), ctx.tenantId(), ctx.activeCompanyId()
                            );
                            if (accountRows.isEmpty()) {
                                throw new ResourceNotFoundException(
                                        "BANK_ACCOUNT_NOT_FOUND",
                                        "Conta bancária não encontrada na empresa ativa."
                                );
                            }
                            AccountScope account = accountRows.getFirst();
                            if (!VALID_BANK_ACCOUNT_TYPES.contains(account.type())) {
                                throw new DomainValidationException(List.of(
                                        new ValidationErrorItem(
                                                "bankAccountId",
                                                "INVALID_BANK_ACCOUNT_TYPE",
                                                "Apenas contas CHECKING, SAVINGS ou PAYMENT aceitam importação de extrato."
                                        )
                                ));
                            }

                            List<BankStatementImportView> existingChecksum = jdbcTemplate.query(
                                    """
                                    SELECT id, tenant_id, company_id, branch_id, financial_account_id,
                                           format::text AS format, source_name, content_hash,
                                           total_rows, imported_rows, possible_duplicates, created_at
                                      FROM bank_imports
                                     WHERE tenant_id = ?
                                       AND company_id = ?
                                       AND financial_account_id = ?
                                       AND content_hash = ?
                                    """,
                                    (rs, rowNum) -> new BankStatementImportView(
                                            rs.getObject("id", UUID.class),
                                            rs.getObject("tenant_id", UUID.class),
                                            rs.getObject("company_id", UUID.class),
                                            rs.getObject("branch_id", UUID.class),
                                            rs.getObject("financial_account_id", UUID.class),
                                            rs.getString("format"),
                                            rs.getString("source_name"),
                                            rs.getString("content_hash"),
                                            "DUPLICATE_FILE",
                                            rs.getInt("total_rows"),
                                            0,
                                            rs.getInt("total_rows"),
                                            rs.getTimestamp("created_at").toInstant()
                                    ),
                                    ctx.tenantId(), ctx.activeCompanyId(), request.bankAccountId(), parsed.sourceChecksum()
                            );

                            if (!existingChecksum.isEmpty()) {
                                BankStatementImportView existing = existingChecksum.getFirst();
                                return new ResponseEnvelope<>(200, "BankImport", existing.id(), existing);
                            }

                            String fileName = (request.fileName() != null && !request.fileName().isBlank())
                                    ? request.fileName().trim()
                                    : "extrato." + parsed.format().toLowerCase(Locale.ROOT);
                            int totalRows = parsed.movements().size();
                            int possibleDuplicates = 0;

                            for (ParsedMovementLine line : parsed.movements()) {
                                Integer count = jdbcTemplate.queryForObject(
                                        """
                                        SELECT COUNT(*)
                                          FROM bank_transactions
                                         WHERE tenant_id = ?
                                           AND company_id = ?
                                           AND financial_account_id = ?
                                           AND fingerprint = ?
                                        """,
                                        Integer.class,
                                        ctx.tenantId(),
                                        ctx.activeCompanyId(),
                                        request.bankAccountId(),
                                        line.movementHash()
                                );
                                if (count != null && count > 0) {
                                    possibleDuplicates++;
                                }
                            }

                            UUID importId = UUID.randomUUID();
                            jdbcTemplate.update(
                                    """
                                    INSERT INTO bank_imports (
                                        id, tenant_id, company_id, branch_id, financial_account_id,
                                        format, content_hash, source_name, total_rows,
                                        imported_rows, possible_duplicates, created_by_id
                                    ) VALUES (?, ?, ?, ?, ?, ?::bank_import_format, ?, ?, ?, ?, ?, ?)
                                    """,
                                    importId,
                                    ctx.tenantId(),
                                    ctx.activeCompanyId(),
                                    account.branchId(),
                                    request.bankAccountId(),
                                    parsed.format(),
                                    parsed.sourceChecksum(),
                                    fileName,
                                    totalRows,
                                    totalRows,
                                    possibleDuplicates,
                                    ctx.userId()
                            );

                            int sourceLine = 1;
                            for (ParsedMovementLine line : parsed.movements()) {
                                BigDecimal signedAmount = "DEBIT".equalsIgnoreCase(line.direction())
                                        ? line.amount().toBigDecimal().negate()
                                        : line.amount().toBigDecimal();
                                String externalRef = normalizeExternalId(
                                        ctx,
                                        request.bankAccountId(),
                                        line.externalReference()
                                );
                                Instant bookedAt = resolveBookedAt(line.postedDate());

                                jdbcTemplate.update(
                                        """
                                        INSERT INTO bank_transactions (
                                            id, tenant_id, company_id, branch_id,
                                            bank_import_id, financial_account_id, source_line,
                                            external_id, fingerprint, booked_at,
                                            amount, currency_code, description, memo
                                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                                        """,
                                        UUID.randomUUID(),
                                        ctx.tenantId(),
                                        ctx.activeCompanyId(),
                                        account.branchId(),
                                        importId,
                                        request.bankAccountId(),
                                        sourceLine++,
                                        externalRef,
                                        line.movementHash(),
                                        Timestamp.from(bookedAt),
                                        signedAmount,
                                        account.currencyCode(),
                                        line.description(),
                                        line.externalReference() != null && externalRef == null
                                                ? line.externalReference()
                                                : null
                                );
                            }

                            BankStatementImportView created = new BankStatementImportView(
                                    importId,
                                    ctx.tenantId(),
                                    ctx.activeCompanyId(),
                                    account.branchId(),
                                    request.bankAccountId(),
                                    parsed.format(),
                                    fileName,
                                    parsed.sourceChecksum(),
                                    "NEW_IMPORT",
                                    totalRows,
                                    totalRows - possibleDuplicates,
                                    possibleDuplicates,
                                    Instant.now()
                            );

                            auditTrailService.recordAuditLog(
                                    "bank.statement.imported",
                                    "BankImport",
                                    importId,
                                    account.branchId(),
                                    null,
                                    created
                            );
                            return new ResponseEnvelope<>(201, "BankImport", importId, created);
                        }
                )
        );
    }

    public CursorPageResponse<BankTransactionView> listBankTransactions(
            UUID bankAccountId,
            String statusFilter,
            String cursor,
            int rawLimit
    ) {
        AuthenticatedTenantContext ctx = TenantContextHolder.requirePermission("bank.read");
        int limit = clampLimit(rawLimit);
        Map<String, Object> filters = new LinkedHashMap<>();
        if (bankAccountId != null) {
            filters.put("bankAccountId", bankAccountId.toString());
        }
        String normalizedStatus = null;
        if (statusFilter != null && !statusFilter.isBlank()) {
            normalizedStatus = statusFilter.trim().toUpperCase(Locale.ROOT);
            filters.put("status", normalizedStatus);
        }

        CursorScope scope = new CursorScope(
                "bank_transactions",
                ctx.tenantId(),
                ctx.activeCompanyId(),
                ctx.activeBranchId(),
                "booked_at_desc,id_desc",
                filters
        );
        Map<String, Object> cursorPos = BoundCursorCodec.decodeCursor(cursor, scope);
        Timestamp cursorBookedAt = cursorPos == null
                ? null
                : Timestamp.from(Instant.parse(String.valueOf(cursorPos.get("bookedAt"))));
        UUID cursorId = cursorPos == null ? null : UUID.fromString(String.valueOf(cursorPos.get("id")));
        final String effectiveStatusFilter = normalizedStatus;

        return rlsExecutor.executeInTenantTransaction(ctx, () -> {
            List<CandidateFinancialRecord> openRecords = jdbcTemplate.query(
                    """
                    SELECT
                        p.id,
                        'PAYABLE'::text AS record_type,
                        p.description,
                        p.document_number,
                        p.due_date,
                        p.outstanding_amount AS open_amount,
                        COALESCE(cp.name, p.counterparty_name) AS party_name
                      FROM payables p
                      LEFT JOIN counterparties cp
                        ON cp.tenant_id = p.tenant_id AND cp.company_id = p.company_id AND cp.id = p.counterparty_id
                     WHERE p.tenant_id = ?
                       AND p.company_id = ?
                       AND p.deleted_at IS NULL
                       AND p.status IN ('OPEN', 'PARTIAL')
                    UNION ALL
                    SELECT
                        r.id,
                        'RECEIVABLE'::text AS record_type,
                        r.description,
                        r.document_number,
                        r.due_date,
                        r.outstanding_amount AS open_amount,
                        COALESCE(cp.name, r.counterparty_name) AS party_name
                      FROM receivables r
                      LEFT JOIN counterparties cp
                        ON cp.tenant_id = r.tenant_id AND cp.company_id = r.company_id AND cp.id = r.counterparty_id
                     WHERE r.tenant_id = ?
                       AND r.company_id = ?
                       AND r.deleted_at IS NULL
                       AND r.status IN ('OPEN', 'PARTIAL')
                    """,
                    (rs, rowNum) -> new CandidateFinancialRecord(
                            rs.getObject("id", UUID.class),
                            rs.getString("record_type"),
                            rs.getString("description"),
                            rs.getString("document_number"),
                            rs.getDate("due_date").toLocalDate(),
                            Money.ofBigDecimal(rs.getBigDecimal("open_amount")),
                            rs.getString("party_name")
                    ),
                    ctx.tenantId(), ctx.activeCompanyId(),
                    ctx.tenantId(), ctx.activeCompanyId()
            );

            List<BankTransactionView> rows = jdbcTemplate.query(
                    """
                    WITH tx_with_status AS (
                        SELECT
                            bt.id, bt.tenant_id, bt.company_id, bt.branch_id,
                            bt.financial_account_id AS bank_account_id,
                            bt.bank_import_id AS statement_import_id,
                            bt.booked_at,
                            (bt.booked_at AT TIME ZONE 'America/Manaus')::date AS posted_date,
                            CASE WHEN bt.amount < 0 THEN 'DEBIT' ELSE 'CREDIT' END AS direction,
                            ABS(bt.amount) AS abs_amount,
                            bt.description,
                            COALESCE(bt.external_id, bt.memo) AS external_reference,
                            bt.fingerprint AS movement_hash,
                            CASE
                                WHEN EXISTS (
                                    SELECT 1
                                      FROM bank_reconciliation_items bri
                                      JOIN bank_reconciliation_groups brg
                                        ON brg.tenant_id = bri.tenant_id
                                       AND brg.company_id = bri.company_id
                                       AND brg.id = bri.group_id
                                      LEFT JOIN bank_reconciliation_reversals brr
                                        ON brr.tenant_id = brg.tenant_id
                                       AND brr.company_id = brg.company_id
                                       AND brr.group_id = brg.id
                                     WHERE bri.tenant_id = bt.tenant_id
                                       AND bri.company_id = bt.company_id
                                       AND bri.participant_kind = 'BANK_TRANSACTION'
                                       AND bri.bank_transaction_id = bt.id
                                       AND brr.id IS NULL
                                ) THEN 'RECONCILED'
                                ELSE 'UNMATCHED'
                            END AS reconciliation_status,
                            bt.created_at
                          FROM bank_transactions bt
                         WHERE bt.tenant_id = ?
                           AND bt.company_id = ?
                           AND (?::uuid IS NULL OR bt.branch_id = ?::uuid)
                           AND (?::uuid IS NULL OR bt.financial_account_id = ?::uuid)
                    )
                    SELECT *
                      FROM tx_with_status t
                     WHERE (?::text IS NULL OR t.reconciliation_status = ?::text)
                       AND (?::timestamptz IS NULL OR (t.booked_at, t.id) < (?::timestamptz, ?::uuid))
                     ORDER BY t.booked_at DESC, t.id DESC
                     LIMIT ?
                    """,
                    (rs, rowNum) -> {
                        LocalDate postedDate = rs.getDate("posted_date").toLocalDate();
                        String direction = rs.getString("direction");
                        Money amount = Money.ofBigDecimal(rs.getBigDecimal("abs_amount"));
                        String desc = rs.getString("description");
                        String extRef = rs.getString("external_reference");
                        String recStatus = rs.getString("reconciliation_status");

                        List<ReconciliationSuggestionView> suggestions = List.of();
                        if ("UNMATCHED".equals(recStatus)) {
                            suggestions = computeSuggestionsForTransaction(
                                    postedDate, direction, amount, desc, extRef, openRecords
                            );
                        }

                        return new BankTransactionView(
                                rs.getObject("id", UUID.class),
                                rs.getObject("tenant_id", UUID.class),
                                rs.getObject("company_id", UUID.class),
                                rs.getObject("branch_id", UUID.class),
                                rs.getObject("bank_account_id", UUID.class),
                                rs.getObject("statement_import_id", UUID.class),
                                postedDate,
                                direction,
                                amount,
                                desc,
                                extRef,
                                rs.getString("movement_hash"),
                                recStatus,
                                suggestions,
                                rs.getTimestamp("booked_at").toInstant()
                        );
                    },
                    ctx.tenantId(),
                    ctx.activeCompanyId(),
                    ctx.activeBranchId(),
                    ctx.activeBranchId(),
                    bankAccountId,
                    bankAccountId,
                    effectiveStatusFilter,
                    effectiveStatusFilter,
                    cursorBookedAt,
                    cursorBookedAt,
                    cursorId,
                    limit + 1
            );

            boolean hasNext = rows.size() > limit;
            List<BankTransactionView> items = hasNext ? rows.subList(0, limit) : rows;
            String nextCursor = null;
            if (hasNext && !items.isEmpty()) {
                BankTransactionView last = items.getLast();
                nextCursor = BoundCursorCodec.encodeCursor(scope, Map.of(
                        "bookedAt", last.createdAt().toString(),
                        "id", last.id().toString()
                ));
            }
            return new CursorPageResponse<>(items, new PageInfo(limit, hasNext, nextCursor));
        });
    }

    public IdempotentResult<BankReconciliationView> matchReconciliation(
            String idempotencyKey,
            MatchReconciliationRequest request
    ) {
        AuthenticatedTenantContext ctx = TenantContextHolder.requirePermission("reconciliation.write");
        if (request.bankTransactionId() == null || request.financialRecordId() == null) {
            throw new DomainValidationException(List.of(
                    new ValidationErrorItem(
                            "bankTransactionId",
                            "REQUIRED_IDS",
                            "Os identificadores bankTransactionId e financialRecordId são obrigatórios."
                    )
            ));
        }

        return rlsExecutor.executeInTenantTransaction(ctx, () ->
                idempotencyExecutor.execute(
                        "reconciliation.match",
                        idempotencyKey,
                        request,
                        BankReconciliationView.class,
                        () -> {
                            List<LockedBankTx> txRows = jdbcTemplate.query(
                                    """
                                    SELECT
                                        bt.id,
                                        bt.branch_id,
                                        bt.financial_account_id,
                                        bt.booked_at,
                                        (bt.booked_at AT TIME ZONE 'America/Manaus')::date AS posted_date,
                                        CASE WHEN bt.amount < 0 THEN 'DEBIT' ELSE 'CREDIT' END AS direction,
                                        ABS(bt.amount) AS abs_amount,
                                        CASE
                                            WHEN EXISTS (
                                                SELECT 1
                                                  FROM bank_reconciliation_items bri
                                                  JOIN bank_reconciliation_groups brg
                                                    ON brg.tenant_id = bri.tenant_id
                                                   AND brg.company_id = bri.company_id
                                                   AND brg.id = bri.group_id
                                                  LEFT JOIN bank_reconciliation_reversals brr
                                                    ON brr.tenant_id = brg.tenant_id
                                                   AND brr.company_id = brg.company_id
                                                   AND brr.group_id = brg.id
                                                 WHERE bri.tenant_id = bt.tenant_id
                                                   AND bri.company_id = bt.company_id
                                                   AND bri.participant_kind = 'BANK_TRANSACTION'
                                                   AND bri.bank_transaction_id = bt.id
                                                   AND brr.id IS NULL
                                            ) THEN 'RECONCILED'
                                            ELSE 'UNMATCHED'
                                        END AS reconciliation_status
                                      FROM bank_transactions bt
                                     WHERE bt.id = ? AND bt.tenant_id = ? AND bt.company_id = ?
                                     FOR UPDATE
                                    """,
                                    (rs, rowNum) -> new LockedBankTx(
                                            rs.getObject("id", UUID.class),
                                            rs.getObject("branch_id", UUID.class),
                                            rs.getObject("financial_account_id", UUID.class),
                                            rs.getTimestamp("booked_at").toInstant(),
                                            rs.getDate("posted_date").toLocalDate(),
                                            rs.getString("direction"),
                                            Money.ofBigDecimal(rs.getBigDecimal("abs_amount")),
                                            rs.getString("reconciliation_status")
                                    ),
                                    request.bankTransactionId(), ctx.tenantId(), ctx.activeCompanyId()
                            );
                            if (txRows.isEmpty()) {
                                throw new ResourceNotFoundException(
                                        "BANK_TRANSACTION_NOT_FOUND",
                                        "Movimentação bancária não encontrada."
                                );
                            }
                            LockedBankTx bankTx = txRows.getFirst();

                            LockedFinancialRecord finRec = lockFinancialRecordForReconciliation(
                                    ctx,
                                    request.financialRecordId()
                            );

                            BankReconciliationDomainPolicy.assertCanMatch(
                                    bankTx.status(),
                                    bankTx.direction(),
                                    bankTx.amount(),
                                    finRec.status(),
                                    finRec.recordType(),
                                    finRec.openAmount()
                            );

                            UUID settlementId = UUID.randomUUID();
                            UUID groupId = UUID.randomUUID();
                            Instant now = Instant.now();
                            Instant occurredAt = bankTx.bookedAt().isAfter(now) ? now : bankTx.bookedAt();
                            boolean isPayable = "PAYABLE".equals(finRec.recordType());
                            String eventTable = isPayable ? "payments" : "receipts";
                            String recordFkCol = isPayable ? "payable_id" : "receivable_id";
                            String participantKind = isPayable ? "PAYMENT" : "RECEIPT";
                            String itemParticipantCol = isPayable ? "payment_id" : "receipt_id";

                            // 1. Insere evento financeiro append-only (payments ou receipts) na mesma conta bancária
                            jdbcTemplate.update(
                                    """
                                    INSERT INTO %s (
                                        id, tenant_id, company_id, branch_id, %s,
                                        financial_account_id, principal_amount, interest_amount,
                                        fine_amount, discount_amount, cash_amount,
                                        occurred_at, note, created_by_id
                                    ) VALUES (?, ?, ?, ?, ?, ?, ?, 0, 0, 0, ?, ?, ?, ?)
                                    """.formatted(eventTable, recordFkCol),
                                    settlementId,
                                    ctx.tenantId(),
                                    ctx.activeCompanyId(),
                                    finRec.branchId(),
                                    finRec.id(),
                                    bankTx.financialAccountId(),
                                    bankTx.amount().toBigDecimal(),
                                    bankTx.amount().toBigDecimal(),
                                    Timestamp.from(occurredAt),
                                    request.notes() != null && !request.notes().isBlank()
                                            ? request.notes().trim()
                                            : "Baixa automática via conciliação bancária",
                                    ctx.userId()
                            );

                            // 2. Insere grupo de conciliação imutável (bank_reconciliation_groups)
                            jdbcTemplate.update(
                                    """
                                    INSERT INTO bank_reconciliation_groups (
                                        id, tenant_id, company_id, branch_id,
                                        financial_account_id, reconciled_at, note, created_by_id
                                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                                    """,
                                    groupId,
                                    ctx.tenantId(),
                                    ctx.activeCompanyId(),
                                    bankTx.branchId(),
                                    bankTx.financialAccountId(),
                                    Timestamp.from(now),
                                    request.notes() != null && !request.notes().isBlank()
                                            ? request.notes().trim()
                                            : null,
                                    ctx.userId()
                            );

                            // 3. Insere os 2 participantes (BANK_TRANSACTION + PAYMENT/RECEIPT) em bank_reconciliation_items
                            jdbcTemplate.update(
                                    """
                                    INSERT INTO bank_reconciliation_items (
                                        id, tenant_id, company_id, group_id,
                                        participant_kind, bank_transaction_id
                                    ) VALUES (?, ?, ?, ?, 'BANK_TRANSACTION'::reconciliation_participant_kind, ?)
                                    """,
                                    UUID.randomUUID(),
                                    ctx.tenantId(),
                                    ctx.activeCompanyId(),
                                    groupId,
                                    bankTx.id()
                            );

                            jdbcTemplate.update(
                                    """
                                    INSERT INTO bank_reconciliation_items (
                                        id, tenant_id, company_id, group_id,
                                        participant_kind, %s
                                    ) VALUES (?, ?, ?, ?, ?::reconciliation_participant_kind, ?)
                                    """.formatted(itemParticipantCol),
                                    UUID.randomUUID(),
                                    ctx.tenantId(),
                                    ctx.activeCompanyId(),
                                    groupId,
                                    participantKind,
                                    settlementId
                            );

                            BankReconciliationView created = new BankReconciliationView(
                                    groupId,
                                    ctx.tenantId(),
                                    ctx.activeCompanyId(),
                                    finRec.branchId(),
                                    bankTx.id(),
                                    finRec.id(),
                                    settlementId,
                                    bankTx.amount(),
                                    "MATCHED",
                                    request.notes(),
                                    ctx.userId(),
                                    now,
                                    null,
                                    null
                            );

                            auditTrailService.recordAuditLog(
                                    "reconciliation.matched",
                                    "BankReconciliationGroup",
                                    groupId,
                                    finRec.branchId(),
                                    null,
                                    created
                            );
                            return new ResponseEnvelope<>(201, "BankReconciliationGroup", groupId, created);
                        }
                )
        );
    }

    public IdempotentResult<BankReconciliationView> undoReconciliation(
            UUID reconciliationId,
            String idempotencyKey,
            UndoReconciliationRequest request
    ) {
        AuthenticatedTenantContext ctx = TenantContextHolder.requirePermission("reconciliation.write");
        String reason = request != null && request.reason() != null ? request.reason().trim() : "";
        if (reason.length() < 5 || reason.length() > 240) {
            throw new DomainValidationException(List.of(
                    new ValidationErrorItem(
                            "reason",
                            "INVALID_UNDO_REASON",
                            "Informe uma justificativa entre 5 e 240 caracteres para desfazer a conciliação."
                    )
            ));
        }

        return rlsExecutor.executeInTenantTransaction(ctx, () ->
                idempotencyExecutor.execute(
                        "reconciliation.undo:" + reconciliationId,
                        idempotencyKey,
                        request,
                        BankReconciliationView.class,
                        () -> {
                            record LoadedGroup(
                                    UUID id,
                                    UUID tenantId,
                                    UUID companyId,
                                    UUID branchId,
                                    UUID bankTransactionId,
                                    UUID financialRecordId,
                                    UUID settlementId,
                                    String settlementKind,
                                    Money matchedAmount,
                                    String status,
                                    String notes,
                                    UUID matchedByUserId,
                                    Instant matchedAt,
                                    Instant undoneAt,
                                    String undoReason
                            ) {}

                            List<LoadedGroup> rows = jdbcTemplate.query(
                                    """
                                    SELECT
                                        brg.id, brg.tenant_id, brg.company_id, brg.branch_id,
                                        bt_item.bank_transaction_id,
                                        COALESCE(pm.payable_id, rc.receivable_id) AS financial_record_id,
                                        COALESCE(fin_item.payment_id, fin_item.receipt_id) AS settlement_id,
                                        fin_item.participant_kind::text AS settlement_kind,
                                        COALESCE(pm.cash_amount, rc.cash_amount, ABS(bt.amount)) AS matched_amount,
                                        CASE WHEN brr.id IS NOT NULL THEN 'UNDONE' ELSE 'MATCHED' END AS status,
                                        brg.note AS notes,
                                        brg.created_by_id AS matched_by_user_id,
                                        brg.reconciled_at AS matched_at,
                                        brr.occurred_at AS undone_at,
                                        brr.reason AS undo_reason
                                      FROM bank_reconciliation_groups brg
                                      LEFT JOIN bank_reconciliation_reversals brr
                                        ON brr.tenant_id = brg.tenant_id
                                       AND brr.company_id = brg.company_id
                                       AND brr.group_id = brg.id
                                      LEFT JOIN bank_reconciliation_items bt_item
                                        ON bt_item.tenant_id = brg.tenant_id
                                       AND bt_item.company_id = brg.company_id
                                       AND bt_item.group_id = brg.id
                                       AND bt_item.participant_kind = 'BANK_TRANSACTION'
                                      LEFT JOIN bank_transactions bt
                                        ON bt.tenant_id = bt_item.tenant_id
                                       AND bt.company_id = bt_item.company_id
                                       AND bt.id = bt_item.bank_transaction_id
                                      LEFT JOIN bank_reconciliation_items fin_item
                                        ON fin_item.tenant_id = brg.tenant_id
                                       AND fin_item.company_id = brg.company_id
                                       AND fin_item.group_id = brg.id
                                       AND fin_item.participant_kind IN ('PAYMENT', 'RECEIPT')
                                      LEFT JOIN payments pm
                                        ON pm.tenant_id = fin_item.tenant_id
                                       AND pm.company_id = fin_item.company_id
                                       AND pm.id = fin_item.payment_id
                                      LEFT JOIN receipts rc
                                        ON rc.tenant_id = fin_item.tenant_id
                                       AND rc.company_id = fin_item.company_id
                                       AND rc.id = fin_item.receipt_id
                                     WHERE brg.id = ? AND brg.tenant_id = ? AND brg.company_id = ?
                                    """,
                                    (rs, rowNum) -> new LoadedGroup(
                                            rs.getObject("id", UUID.class),
                                            rs.getObject("tenant_id", UUID.class),
                                            rs.getObject("company_id", UUID.class),
                                            rs.getObject("branch_id", UUID.class),
                                            rs.getObject("bank_transaction_id", UUID.class),
                                            rs.getObject("financial_record_id", UUID.class),
                                            rs.getObject("settlement_id", UUID.class),
                                            rs.getString("settlement_kind"),
                                            Money.ofBigDecimal(rs.getBigDecimal("matched_amount")),
                                            rs.getString("status"),
                                            rs.getString("notes"),
                                            rs.getObject("matched_by_user_id", UUID.class),
                                            rs.getTimestamp("matched_at").toInstant(),
                                            rs.getTimestamp("undone_at") != null ? rs.getTimestamp("undone_at").toInstant() : null,
                                            rs.getString("undo_reason")
                                    ),
                                    reconciliationId, ctx.tenantId(), ctx.activeCompanyId()
                            );
                            if (rows.isEmpty()) {
                                throw new ResourceNotFoundException(
                                        "RECONCILIATION_NOT_FOUND",
                                        "Conciliação bancária não encontrada."
                                );
                            }
                            LoadedGroup loaded = rows.getFirst();
                            BankReconciliationDomainPolicy.assertCanUndo(loaded.status());

                            Instant now = Instant.now();
                            Instant reversalInstant = now.isBefore(loaded.matchedAt()) ? loaded.matchedAt() : now;

                            // 1. Insere em bank_reconciliation_reversals (trigger libera bank_reconciliation_reservations)
                            UUID recReversalId = UUID.randomUUID();
                            jdbcTemplate.update(
                                    """
                                    INSERT INTO bank_reconciliation_reversals (
                                        id, tenant_id, company_id, branch_id,
                                        group_id, reason, occurred_at, created_by_id
                                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                                    """,
                                    recReversalId,
                                    ctx.tenantId(),
                                    ctx.activeCompanyId(),
                                    loaded.branchId(),
                                    reconciliationId,
                                    reason,
                                    Timestamp.from(reversalInstant),
                                    ctx.userId()
                            );

                            // 2. Estorna o evento financeiro pareado (payment_reversals ou receipt_reversals)
                            if (loaded.settlementId() != null) {
                                boolean isPayment = "PAYMENT".equals(loaded.settlementKind());
                                String finReversalTable = isPayment ? "payment_reversals" : "receipt_reversals";
                                String finFkCol = isPayment ? "payment_id" : "receipt_id";
                                jdbcTemplate.update(
                                        """
                                        INSERT INTO %s (
                                            id, tenant_id, company_id, branch_id,
                                            %s, reason, occurred_at, created_by_id
                                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                                        ON CONFLICT (tenant_id, company_id, %s) DO NOTHING
                                        """.formatted(finReversalTable, finFkCol, finFkCol),
                                        UUID.randomUUID(),
                                        ctx.tenantId(),
                                        ctx.activeCompanyId(),
                                        loaded.branchId(),
                                        loaded.settlementId(),
                                        reason,
                                        Timestamp.from(reversalInstant),
                                        ctx.userId()
                                );
                            }

                            BankReconciliationView before = new BankReconciliationView(
                                    loaded.id(),
                                    loaded.tenantId(),
                                    loaded.companyId(),
                                    loaded.branchId(),
                                    loaded.bankTransactionId(),
                                    loaded.financialRecordId(),
                                    loaded.settlementId(),
                                    loaded.matchedAmount(),
                                    loaded.status(),
                                    loaded.notes(),
                                    loaded.matchedByUserId(),
                                    loaded.matchedAt(),
                                    loaded.undoneAt(),
                                    loaded.undoReason()
                            );

                            BankReconciliationView after = new BankReconciliationView(
                                    loaded.id(),
                                    loaded.tenantId(),
                                    loaded.companyId(),
                                    loaded.branchId(),
                                    loaded.bankTransactionId(),
                                    loaded.financialRecordId(),
                                    loaded.settlementId(),
                                    loaded.matchedAmount(),
                                    "UNDONE",
                                    loaded.notes(),
                                    loaded.matchedByUserId(),
                                    loaded.matchedAt(),
                                    reversalInstant,
                                    reason
                            );

                            auditTrailService.recordAuditLog(
                                    "reconciliation.undone",
                                    "BankReconciliationReversal",
                                    recReversalId,
                                    loaded.branchId(),
                                    before,
                                    after
                            );
                            return new ResponseEnvelope<>(200, "BankReconciliationReversal", recReversalId, after);
                        }
                )
        );
    }

    private LockedFinancialRecord lockFinancialRecordForReconciliation(
            AuthenticatedTenantContext ctx,
            UUID financialRecordId
    ) {
        List<LockedFinancialRecord> payableRows = jdbcTemplate.query(
                """
                SELECT
                    id, branch_id, 'PAYABLE'::text AS record_type,
                    CASE
                        WHEN deleted_at IS NOT NULL THEN 'CANCELLED'
                        WHEN status = 'PARTIAL' THEN 'PARTIALLY_SETTLED'
                        ELSE status::text
                    END AS status,
                    (amount - outstanding_amount) AS settled_amount,
                    outstanding_amount AS open_amount
                  FROM payables
                 WHERE id = ? AND tenant_id = ? AND company_id = ?
                 FOR UPDATE
                """,
                (rs, rowNum) -> new LockedFinancialRecord(
                        rs.getObject("id", UUID.class),
                        rs.getObject("branch_id", UUID.class),
                        rs.getString("record_type"),
                        rs.getString("status"),
                        Money.ofBigDecimal(rs.getBigDecimal("settled_amount")),
                        Money.ofBigDecimal(rs.getBigDecimal("open_amount"))
                ),
                financialRecordId, ctx.tenantId(), ctx.activeCompanyId()
        );
        if (!payableRows.isEmpty()) {
            return payableRows.getFirst();
        }

        List<LockedFinancialRecord> receivableRows = jdbcTemplate.query(
                """
                SELECT
                    id, branch_id, 'RECEIVABLE'::text AS record_type,
                    CASE
                        WHEN deleted_at IS NOT NULL THEN 'CANCELLED'
                        WHEN status = 'PARTIAL' THEN 'PARTIALLY_SETTLED'
                        ELSE status::text
                    END AS status,
                    (amount - outstanding_amount) AS settled_amount,
                    outstanding_amount AS open_amount
                  FROM receivables
                 WHERE id = ? AND tenant_id = ? AND company_id = ?
                 FOR UPDATE
                """,
                (rs, rowNum) -> new LockedFinancialRecord(
                        rs.getObject("id", UUID.class),
                        rs.getObject("branch_id", UUID.class),
                        rs.getString("record_type"),
                        rs.getString("status"),
                        Money.ofBigDecimal(rs.getBigDecimal("settled_amount")),
                        Money.ofBigDecimal(rs.getBigDecimal("open_amount"))
                ),
                financialRecordId, ctx.tenantId(), ctx.activeCompanyId()
        );
        if (!receivableRows.isEmpty()) {
            return receivableRows.getFirst();
        }

        throw new ResourceNotFoundException("FINANCIAL_RECORD_NOT_FOUND", "Título financeiro não encontrado.");
    }

    private String normalizeExternalId(AuthenticatedTenantContext ctx, UUID accountId, String rawExternalRef) {
        if (rawExternalRef == null || rawExternalRef.isBlank()) {
            return null;
        }
        String trimmed = rawExternalRef.trim();
        Integer existing = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                  FROM bank_transactions
                 WHERE tenant_id = ? AND company_id = ? AND financial_account_id = ? AND external_id = ?
                """,
                Integer.class,
                ctx.tenantId(),
                ctx.activeCompanyId(),
                accountId,
                trimmed
        );
        return (existing != null && existing > 0) ? null : trimmed;
    }

    private static Instant resolveBookedAt(LocalDate postedDate) {
        Instant now = Instant.now();
        if (postedDate == null) {
            return now;
        }
        Instant candidate = postedDate.atTime(12, 0).atZone(MANAUS_ZONE).toInstant();
        return candidate.isAfter(now) ? now : candidate;
    }

    private List<ReconciliationSuggestionView> computeSuggestionsForTransaction(
            LocalDate postedDate,
            String direction,
            Money amount,
            String description,
            String externalRef,
            List<CandidateFinancialRecord> candidates
    ) {
        List<ReconciliationSuggestionView> matches = new ArrayList<>();
        for (CandidateFinancialRecord candidate : candidates) {
            SuggestionScore score = BankReconciliationDomainPolicy.evaluateSuggestion(
                    postedDate,
                    direction,
                    amount,
                    description,
                    externalRef,
                    candidate.dueDate(),
                    candidate.recordType(),
                    candidate.openAmount(),
                    candidate.documentNumber(),
                    candidate.partyName(),
                    candidate.description()
            );
            if (score.score() > 0) {
                matches.add(new ReconciliationSuggestionView(
                        candidate.id(),
                        candidate.recordType(),
                        candidate.description(),
                        candidate.partyName(),
                        candidate.dueDate(),
                        candidate.openAmount(),
                        score.score(),
                        score.reasons()
                ));
            }
        }
        matches.sort(Comparator.comparingInt(ReconciliationSuggestionView::score).reversed()
                .thenComparing(ReconciliationSuggestionView::dueDate));
        return matches.size() > 5 ? matches.subList(0, 5) : matches;
    }

    private static int clampLimit(int rawLimit) {
        if (rawLimit < 1) {
            return 25;
        }
        return Math.min(rawLimit, 100);
    }

    private record CandidateFinancialRecord(
            UUID id,
            String recordType,
            String description,
            String documentNumber,
            LocalDate dueDate,
            Money openAmount,
            String partyName
    ) {
    }

    private record LockedBankTx(
            UUID id,
            UUID branchId,
            UUID financialAccountId,
            Instant bookedAt,
            LocalDate postedDate,
            String direction,
            Money amount,
            String status
    ) {
    }

    private record LockedFinancialRecord(
            UUID id,
            UUID branchId,
            String recordType,
            String status,
            Money settledAmount,
            Money openAmount
    ) {
    }
}
