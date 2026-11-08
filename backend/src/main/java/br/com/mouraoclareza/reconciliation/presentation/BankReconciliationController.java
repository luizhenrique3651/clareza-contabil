package br.com.mouraoclareza.reconciliation.presentation;

import br.com.mouraoclareza.financial.domain.FinancialCatalogModels.CursorPageResponse;
import br.com.mouraoclareza.reconciliation.application.BankReconciliationService;
import br.com.mouraoclareza.reconciliation.domain.BankReconciliationModels.BankAccountView;
import br.com.mouraoclareza.reconciliation.domain.BankReconciliationModels.BankReconciliationView;
import br.com.mouraoclareza.reconciliation.domain.BankReconciliationModels.BankStatementImportView;
import br.com.mouraoclareza.reconciliation.domain.BankReconciliationModels.BankTransactionView;
import br.com.mouraoclareza.reconciliation.domain.BankReconciliationModels.CreateBankAccountRequest;
import br.com.mouraoclareza.reconciliation.domain.BankReconciliationModels.CreateStatementImportRequest;
import br.com.mouraoclareza.reconciliation.domain.BankReconciliationModels.MatchReconciliationRequest;
import br.com.mouraoclareza.reconciliation.domain.BankReconciliationModels.UndoReconciliationRequest;
import br.com.mouraoclareza.shared.domain.DomainIds;
import br.com.mouraoclareza.shared.idempotency.IdempotencyExecutor.IdempotentResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Controller REST para Contas Bancárias, Importação de Extratos OFX/CSV, Transações Bancárias
 * e Conciliação Determinística 1:1 (/api/v1/bank-*, /api/v1/reconciliations/*).
 */
@RestController
@RequestMapping("/api/v1")
public class BankReconciliationController {

    private static final String IDEMPOTENCY_REPLAYED_HEADER = "Idempotency-Replayed";

    private final BankReconciliationService reconciliationService;

    public BankReconciliationController(BankReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    @GetMapping("/bank-accounts")
    public ResponseEntity<CursorPageResponse<BankAccountView>> listBankAccounts(
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", defaultValue = "25") int limit
    ) {
        return ResponseEntity.ok(reconciliationService.listBankAccounts(cursor, limit));
    }

    @PostMapping("/bank-accounts")
    public ResponseEntity<BankAccountView> createBankAccount(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody CreateBankAccountRequest request
    ) {
        IdempotentResult<BankAccountView> result = reconciliationService.createBankAccount(idempotencyKey, request);
        return ResponseEntity.status(result.status())
                .header(IDEMPOTENCY_REPLAYED_HEADER, Boolean.toString(result.replayed()))
                .body(result.body());
    }

    @PostMapping("/bank-statement-imports")
    public ResponseEntity<BankStatementImportView> importStatement(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody CreateStatementImportRequest request
    ) {
        IdempotentResult<BankStatementImportView> result = reconciliationService.importStatement(idempotencyKey, request);
        return ResponseEntity.status(result.status())
                .header(IDEMPOTENCY_REPLAYED_HEADER, Boolean.toString(result.replayed()))
                .body(result.body());
    }

    @GetMapping("/bank-transactions")
    public ResponseEntity<CursorPageResponse<BankTransactionView>> listBankTransactions(
            @RequestParam(value = "bankAccountId", required = false) String rawBankAccountId,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", defaultValue = "25") int limit
    ) {
        UUID bankAccountId = rawBankAccountId == null || rawBankAccountId.isBlank()
                ? null
                : DomainIds.parseUuid(rawBankAccountId, "bankAccountId", "INVALID_BANK_ACCOUNT_ID");
        return ResponseEntity.ok(reconciliationService.listBankTransactions(bankAccountId, status, cursor, limit));
    }

    @PostMapping("/reconciliations/match")
    public ResponseEntity<BankReconciliationView> matchReconciliation(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody MatchReconciliationRequest request
    ) {
        IdempotentResult<BankReconciliationView> result = reconciliationService.matchReconciliation(idempotencyKey, request);
        return ResponseEntity.status(result.status())
                .header(IDEMPOTENCY_REPLAYED_HEADER, Boolean.toString(result.replayed()))
                .body(result.body());
    }

    @PostMapping("/reconciliations/{id}/undo")
    public ResponseEntity<BankReconciliationView> undoReconciliation(
            @PathVariable("id") String rawId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody UndoReconciliationRequest request
    ) {
        UUID reconciliationId = DomainIds.parseUuid(rawId, "id", "INVALID_RECONCILIATION_ID");
        IdempotentResult<BankReconciliationView> result = reconciliationService.undoReconciliation(reconciliationId, idempotencyKey, request);
        return ResponseEntity.status(result.status())
                .header(IDEMPOTENCY_REPLAYED_HEADER, Boolean.toString(result.replayed()))
                .body(result.body());
    }
}
