package br.com.mouraoclareza.financial.presentation;

import br.com.mouraoclareza.financial.application.FinancialCatalogService;
import br.com.mouraoclareza.financial.domain.FinancialCatalogModels.CostCenterView;
import br.com.mouraoclareza.financial.domain.FinancialCatalogModels.CreateCategoryRequest;
import br.com.mouraoclareza.financial.domain.FinancialCatalogModels.CreateCostCenterRequest;
import br.com.mouraoclareza.financial.domain.FinancialCatalogModels.CreatePartyRequest;
import br.com.mouraoclareza.financial.domain.FinancialCatalogModels.CursorPageResponse;
import br.com.mouraoclareza.financial.domain.FinancialCatalogModels.FinancialCategoryView;
import br.com.mouraoclareza.financial.domain.FinancialCatalogModels.PartyView;
import br.com.mouraoclareza.shared.idempotency.IdempotencyExecutor.IdempotentResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller REST para Catálogos Financeiros (/api/v1/financial-parties, /api/v1/financial-categories, /api/v1/cost-centers).
 */
@RestController
@RequestMapping("/api/v1")
public class FinancialCatalogsController {

    private final FinancialCatalogService catalogService;

    public FinancialCatalogsController(FinancialCatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @GetMapping("/financial-parties")
    public ResponseEntity<CursorPageResponse<PartyView>> listParties(
            @RequestParam(value = "role", required = false) String role,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", defaultValue = "25") int limit
    ) {
        return ResponseEntity.ok(catalogService.listParties(role, cursor, limit));
    }

    @PostMapping("/financial-parties")
    public ResponseEntity<PartyView> createParty(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody CreatePartyRequest request
    ) {
        IdempotentResult<PartyView> result = catalogService.createParty(idempotencyKey, request);
        return ResponseEntity.status(result.status()).body(result.body());
    }

    @GetMapping("/financial-categories")
    public ResponseEntity<CursorPageResponse<FinancialCategoryView>> listCategories(
            @RequestParam(value = "kind", required = false) String kind,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", defaultValue = "25") int limit
    ) {
        return ResponseEntity.ok(catalogService.listCategories(kind, cursor, limit));
    }

    @PostMapping("/financial-categories")
    public ResponseEntity<FinancialCategoryView> createCategory(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody CreateCategoryRequest request
    ) {
        IdempotentResult<FinancialCategoryView> result = catalogService.createCategory(idempotencyKey, request);
        return ResponseEntity.status(result.status()).body(result.body());
    }

    @GetMapping("/cost-centers")
    public ResponseEntity<CursorPageResponse<CostCenterView>> listCostCenters(
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", defaultValue = "25") int limit
    ) {
        return ResponseEntity.ok(catalogService.listCostCenters(cursor, limit));
    }

    @PostMapping("/cost-centers")
    public ResponseEntity<CostCenterView> createCostCenter(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody CreateCostCenterRequest request
    ) {
        IdempotentResult<CostCenterView> result = catalogService.createCostCenter(idempotencyKey, request);
        return ResponseEntity.status(result.status()).body(result.body());
    }
}
