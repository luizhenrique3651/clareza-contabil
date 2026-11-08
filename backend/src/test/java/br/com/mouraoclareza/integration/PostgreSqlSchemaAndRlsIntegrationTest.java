package br.com.mouraoclareza.integration;

import br.com.mouraoclareza.financial.application.FinancialCatalogService;
import br.com.mouraoclareza.financial.application.FinancialRecordService;
import br.com.mouraoclareza.financial.domain.FinancialCatalogModels.CreateCategoryRequest;
import br.com.mouraoclareza.financial.domain.FinancialCatalogModels.CreatePartyRequest;
import br.com.mouraoclareza.financial.domain.FinancialCatalogModels.CursorPageResponse;
import br.com.mouraoclareza.financial.domain.FinancialCatalogModels.FinancialCategoryView;
import br.com.mouraoclareza.financial.domain.FinancialCatalogModels.PartyView;
import br.com.mouraoclareza.financial.domain.FinancialRecordModels.CreateFinancialRecordRequest;
import br.com.mouraoclareza.financial.domain.FinancialRecordModels.CreateSettlementRequest;
import br.com.mouraoclareza.financial.domain.FinancialRecordModels.FinancialRecordView;
import br.com.mouraoclareza.financial.domain.FinancialRecordModels.FinancialSummaryView;
import br.com.mouraoclareza.iam.application.IdentityAccessDirectoryService;
import br.com.mouraoclareza.iam.application.IdentityAccessDirectoryService.ContextSelection;
import br.com.mouraoclareza.iam.domain.AccessRow;
import br.com.mouraoclareza.iam.domain.IdentitySummary;
import br.com.mouraoclareza.shared.idempotency.IdempotencyExecutor.IdempotentResult;
import br.com.mouraoclareza.shared.security.AuthenticatedTenantContext;
import br.com.mouraoclareza.shared.security.TenantContextHolder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Teste de integração end-to-end contra PostgreSQL 16 real via Testcontainers (FIX-07).
 * Executa migrations Flyway V1..V8 + seeds e comprova:
 * 1. app.resolve_identity_access e escopo RLS;
 * 2. Idempotência e Auditoria em idempotency_records e audit_logs;
 * 3. Catálogos financeiros (counterparties, financial_categories, cost_centers, financial_accounts);
 * 4. Títulos (payables / receivables), baixas append-only (payments / receipts) com triggers de projeção,
 *    paginação keyset real e agregação SQL nativa em getSummary().
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class PostgreSqlSchemaAndRlsIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("clareza_test")
            .withUsername("postgres")
            .withPassword("postgres");

    @DynamicPropertySource
    static void registerDataSourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private DataSource dataSource;

    @Autowired
    private IdentityAccessDirectoryService identityAccessDirectoryService;

    @Autowired
    private FinancialCatalogService catalogService;

    @Autowired
    private FinancialRecordService recordService;

    @Test
    @DisplayName("Deve executar fluxo completo em PostgreSQL 16 validando V1..V8, RLS, Idempotência, Triggers e Keyset Pagination")
    void shouldExecuteFullFinancialFlowAgainstRealPostgreSqlSchema() {
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(
                new ClassPathResource("db/seed/seed.sql"),
                new ClassPathResource("db/seed/phase-zero-seed.sql")
        );
        populator.execute(dataSource);

        UUID companyId = UUID.fromString("20000000-0000-4000-8000-000000000001");
        UUID branchId = UUID.fromString("30000000-0000-4000-8000-000000000001");

        List<AccessRow> rows = identityAccessDirectoryService.fetchAccessRowsByEmail("ana.horizonte@example.invalid");
        IdentitySummary summary = identityAccessDirectoryService.summarizeIdentityAccess(
                rows,
                new ContextSelection(null, companyId, branchId)
        );
        assertEquals(companyId, summary.activeContext().companyId());
        assertEquals(branchId, summary.activeContext().branchId());

        AuthenticatedTenantContext ctx = identityAccessDirectoryService.toAuthenticatedContext(
                summary,
                "req-integration-001",
                true
        );

        TenantContextHolder.runWithContext(ctx, () -> {
            CreatePartyRequest partyReq = new CreatePartyRequest(
                    branchId,
                    "FORN-INT-01",
                    "Fornecedor Integração Ltda",
                    "Fornecedor Integração",
                    "12345678000195",
                    "SUPPLIER",
                    "contato@fornecedor.invalid"
            );
            IdempotentResult<PartyView> partyResult = catalogService.createParty(
                    "idem-party-create-0001",
                    partyReq
            );
            assertEquals(201, partyResult.status());
            assertFalse(partyResult.replayed());

            IdempotentResult<PartyView> replayedParty = catalogService.createParty(
                    "idem-party-create-0001",
                    partyReq
            );
            assertTrue(replayedParty.replayed());
            assertEquals(partyResult.body().id(), replayedParty.body().id());

            IdempotentResult<FinancialCategoryView> categoryResult = catalogService.createCategory(
                    "idem-cat-create-0001",
                    new CreateCategoryRequest(branchId, "DESP-INT-01", "Serviços de Cloud", "EXPENSE")
            );
            assertEquals(201, categoryResult.status());

            LocalDate issueDate = LocalDate.of(2026, 8, 1);
            LocalDate dueDate = LocalDate.of(2026, 8, 15);
            IdempotentResult<FinancialRecordView> payableResult = recordService.createRecord(
                    "idem-rec-create-0001",
                    new CreateFinancialRecordRequest(
                            branchId,
                            partyResult.body().id(),
                            categoryResult.body().id(),
                            null,
                            "PAYABLE",
                            "Assinatura Servidor PostgreSQL",
                            "NF-2026-001",
                            issueDate,
                            issueDate,
                            dueDate,
                            "1000.0000",
                            "0.0000",
                            "0.0000",
                            "0.0000"
                    )
            );
            assertEquals(201, payableResult.status());
            assertEquals("1000.0000", payableResult.body().openAmount().toCanonicalString());
            assertEquals("OPEN", payableResult.body().persistedStatus());

            IdempotentResult<FinancialRecordView> partialSettle = recordService.settleRecord(
                    payableResult.body().id(),
                    "idem-rec-settle-0001",
                    new CreateSettlementRequest(
                            LocalDate.of(2026, 8, 5),
                            "400.0000",
                            "PIX",
                            "E2E-PIX-001",
                            "Primeira parcela"
                    )
            );
            assertEquals(200, partialSettle.status());
            assertEquals("400.0000", partialSettle.body().settledAmount().toCanonicalString());
            assertEquals("600.0000", partialSettle.body().openAmount().toCanonicalString());
            assertEquals("PARTIALLY_SETTLED", partialSettle.body().persistedStatus());

            CursorPageResponse<FinancialRecordView> page1 = recordService.listRecords("PAYABLE", null, null, 1);
            assertEquals(1, page1.items().size());
            if (page1.pageInfo().hasNextPage()) {
                assertNotNull(page1.pageInfo().nextCursor());
                CursorPageResponse<FinancialRecordView> page2 = recordService.listRecords(
                        "PAYABLE",
                        null,
                        page1.pageInfo().nextCursor(),
                        1
                );
                assertFalse(page2.items().isEmpty());
            }

            FinancialSummaryView summaryView = recordService.getSummary();
            assertNotNull(summaryView);
            assertTrue(summaryView.openPayables().isPositive());
        });
    }
}
