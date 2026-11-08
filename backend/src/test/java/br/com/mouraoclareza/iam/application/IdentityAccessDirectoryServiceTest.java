package br.com.mouraoclareza.iam.application;

import br.com.mouraoclareza.iam.application.IdentityAccessDirectoryService.ContextSelection;
import br.com.mouraoclareza.iam.domain.AccessRow;
import br.com.mouraoclareza.iam.domain.IdentitySummary;
import br.com.mouraoclareza.shared.exception.AuthorizationException;
import br.com.mouraoclareza.shared.exception.InvalidContextSelectionException;
import br.com.mouraoclareza.shared.security.AuthenticatedTenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IdentityAccessDirectoryServiceTest {

    private static final UUID USER_ID = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID COMPANY_MATRIZ = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID COMPANY_FILIAL = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID BRANCH_MANAUS = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID BRANCH_SP = UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final UUID BRANCH_BELEM = UUID.fromString("66666666-6666-4666-8666-666666666666");
    private static final UUID UNAUTHORIZED_COMPANY = UUID.fromString("99999999-9999-4999-8999-999999999999");

    private IdentityAccessDirectoryService service;

    @BeforeEach
    void setUp() {
        service = new IdentityAccessDirectoryService(new JdbcTemplate());
    }

    @Test
    @DisplayName("Deve sumarizar as 11 colunas de app.resolve_identity_access e validar seleção multiempresa")
    void shouldSummarizeMultiCompanyAccessAndValidateSelection() {
        List<AccessRow> rows = List.of(
                new AccessRow(
                        TENANT_ID, "Grupo Mourão", USER_ID, "Dr. Mourão", "ceo@mourao.example",
                        "TENANT", COMPANY_MATRIZ, "Mourão Matriz", BRANCH_MANAUS, "Unidade Manaus",
                        List.of("financial.read", "financial.write", "financial.settle", "financial.cancel")
                ),
                new AccessRow(
                        TENANT_ID, "Grupo Mourão", USER_ID, "Dr. Mourão", "ceo@mourao.example",
                        "TENANT", COMPANY_FILIAL, "Mourão Consultoria", BRANCH_SP, "Unidade São Paulo",
                        List.of("financial.read", "financial.write", "financial.settle", "financial.cancel")
                )
        );

        IdentitySummary initial = service.summarizeIdentityAccess(rows, ContextSelection.empty());
        assertTrue(initial.requiresSelection());
        assertEquals(2, initial.companies().size());

        IdentitySummary selected = service.summarizeIdentityAccess(
                rows,
                new ContextSelection(TENANT_ID, COMPANY_FILIAL, BRANCH_SP)
        );
        assertFalse(selected.requiresSelection());
        assertEquals(COMPANY_FILIAL, selected.activeContext().companyId());
        assertEquals(BRANCH_SP, selected.activeContext().branchId());
        assertEquals(List.of(COMPANY_FILIAL), selected.activeContext().allowedCompanyIds());
        assertTrue(selected.activeContext().permissions().contains("financial.cancel"));

        // Rejeita unidade que pertence a outra empresa
        assertThrows(
                InvalidContextSelectionException.class,
                () -> service.summarizeIdentityAccess(rows, new ContextSelection(TENANT_ID, COMPANY_MATRIZ, BRANCH_SP))
        );

        // Rejeita empresa não autorizada
        assertThrows(
                InvalidContextSelectionException.class,
                () -> service.summarizeIdentityAccess(rows, new ContextSelection(TENANT_ID, UNAUTHORIZED_COMPANY, null))
        );
    }

    @Test
    @DisplayName("Membro com escopo apenas BRANCH deve ter allowedCompanyIds vazio e exigir filial selecionada (FIX-03)")
    void shouldKeepAllowedCompanyIdsEmptyForBranchScopedMember() {
        List<AccessRow> branchOnlyRows = List.of(
                new AccessRow(
                        TENANT_ID, "Grupo Mourão", USER_ID, "Analista Filial", "filial@mourao.example",
                        "BRANCH", COMPANY_MATRIZ, "Mourão Matriz", BRANCH_MANAUS, "Unidade Manaus",
                        List.of("financial.read")
                ),
                new AccessRow(
                        TENANT_ID, "Grupo Mourão", USER_ID, "Analista Filial", "filial@mourao.example",
                        "BRANCH", COMPANY_MATRIZ, "Mourão Matriz", BRANCH_BELEM, "Unidade Belém",
                        List.of("financial.read")
                )
        );

        // Sem branchId selecionado quando há 2 filiais -> requiresSelection = true e toAuthenticatedContext(..., true) falha fechado (403)
        IdentitySummary unselectedBranch = service.summarizeIdentityAccess(
                branchOnlyRows,
                new ContextSelection(TENANT_ID, COMPANY_MATRIZ, null)
        );
        assertTrue(unselectedBranch.requiresSelection());
        assertTrue(unselectedBranch.activeContext().allowedCompanyIds().isEmpty());
        assertEquals(List.of(BRANCH_MANAUS, BRANCH_BELEM), unselectedBranch.activeContext().allowedBranchIds());

        assertThrows(
                AuthorizationException.class,
                () -> service.toAuthenticatedContext(unselectedBranch, "req-1", true)
        );

        // Selecionando uma filial autorizada -> allowedCompanyIds permanece vazio e activeBranchId = BRANCH_MANAUS
        IdentitySummary selectedBranch = service.summarizeIdentityAccess(
                branchOnlyRows,
                new ContextSelection(TENANT_ID, COMPANY_MATRIZ, BRANCH_MANAUS)
        );
        AuthenticatedTenantContext ctx = service.toAuthenticatedContext(selectedBranch, "req-2", true);
        assertTrue(ctx.allowedCompanyIds().isEmpty());
        assertEquals(List.of(BRANCH_MANAUS, BRANCH_BELEM), ctx.allowedBranchIds());
        assertEquals(BRANCH_MANAUS, ctx.activeBranchId());
    }

    @Test
    @DisplayName("Deve bloquear identidade sem vínculos ativos")
    void shouldRejectUnlinkedUser() {
        assertThrows(
                AuthorizationException.class,
                () -> service.summarizeIdentityAccess(List.of(), ContextSelection.empty())
        );
    }
}
