package br.com.mouraoclareza.financial.presentation;

import br.com.mouraoclareza.financial.application.FinancialRecordService;
import br.com.mouraoclareza.financial.domain.FinancialCatalogModels.CursorPageResponse;
import br.com.mouraoclareza.financial.domain.FinancialRecordModels.CancelFinancialRecordRequest;
import br.com.mouraoclareza.financial.domain.FinancialRecordModels.CreateFinancialRecordRequest;
import br.com.mouraoclareza.financial.domain.FinancialRecordModels.CreateSettlementRequest;
import br.com.mouraoclareza.financial.domain.FinancialRecordModels.FinancialEventView;
import br.com.mouraoclareza.financial.domain.FinancialRecordModels.FinancialRecordView;
import br.com.mouraoclareza.financial.domain.FinancialRecordModels.FinancialSummaryView;
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
 * Controller REST para Títulos Financeiros (Contas a Pagar / Contas a Receber), Baixas, Cancelamentos,
 * Sumário Executivo e Eventos Imutáveis (/api/v1/financial-records, /api/v1/financial-summary, /api/v1/financial-events).
 */
@RestController
@RequestMapping("/api/v1")
public class FinancialRecordsController {

    private final FinancialRecordService recordService;

    public FinancialRecordsController(FinancialRecordService recordService) {
        this.recordService = recordService;
    }

    @GetMapping("/financial-records")
    public ResponseEntity<CursorPageResponse<FinancialRecordView>> listRecords(
            @RequestParam(value = "type", required = false) String type,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", defaultValue = "25") int limit
    ) {
        return ResponseEntity.ok(recordService.listRecords(type, status, cursor, limit));
    }

    @GetMapping("/financial-records/{id}")
    public ResponseEntity<FinancialRecordView> getRecordById(@PathVariable("id") String rawId) {
        UUID recordId = DomainIds.parseUuid(rawId, "id", "INVALID_RECORD_ID");
        return ResponseEntity.ok(recordService.getRecordById(recordId));
    }

    @PostMapping("/financial-records")
    public ResponseEntity<FinancialRecordView> createRecord(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody CreateFinancialRecordRequest request
    ) {
        IdempotentResult<FinancialRecordView> result = recordService.createRecord(idempotencyKey, request);
        return ResponseEntity.status(result.status()).body(result.body());
    }

    @PostMapping("/financial-records/{id}/settlements")
    public ResponseEntity<FinancialRecordView> settleRecord(
            @PathVariable("id") String rawId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody CreateSettlementRequest request
    ) {
        UUID recordId = DomainIds.parseUuid(rawId, "id", "INVALID_RECORD_ID");
        IdempotentResult<FinancialRecordView> result = recordService.settleRecord(recordId, idempotencyKey, request);
        return ResponseEntity.status(result.status()).body(result.body());
    }

    @PostMapping("/financial-records/{id}/cancel")
    public ResponseEntity<FinancialRecordView> cancelRecord(
            @PathVariable("id") String rawId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody CancelFinancialRecordRequest request
    ) {
        UUID recordId = DomainIds.parseUuid(rawId, "id", "INVALID_RECORD_ID");
        IdempotentResult<FinancialRecordView> result = recordService.cancelRecord(recordId, idempotencyKey, request);
        return ResponseEntity.status(result.status()).body(result.body());
    }

    @GetMapping("/financial-summary")
    public ResponseEntity<FinancialSummaryView> getSummary() {
        return ResponseEntity.ok(recordService.getSummary());
    }

    @GetMapping("/financial-events")
    public ResponseEntity<CursorPageResponse<FinancialEventView>> listEvents(
            @RequestParam(value = "financialRecordId", required = false) String rawRecordId,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", defaultValue = "25") int limit
    ) {
        UUID recordId = rawRecordId == null || rawRecordId.isBlank()
                ? null
                : DomainIds.parseUuid(rawRecordId, "financialRecordId", "INVALID_RECORD_ID");
        return ResponseEntity.ok(recordService.listEvents(recordId, cursor, limit));
    }
}
