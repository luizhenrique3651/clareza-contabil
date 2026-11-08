package br.com.mouraoclareza.iam.application;

import br.com.mouraoclareza.iam.application.IdentityAccessDirectoryService.ContextSelection;
import br.com.mouraoclareza.iam.domain.AccessRow;
import br.com.mouraoclareza.iam.domain.IdentitySummary;
import br.com.mouraoclareza.shared.exception.AuthorizationException;
import br.com.mouraoclareza.shared.exception.InvalidContextSelectionException;
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
    private static final UUID MEMBERSHIP_ID = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID COMPANY_MATRIZ = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID COMPANY_FILIAL = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID BRANCH_MANAUS = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID BRANCH_SP = UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final UUID UNAUTHORIZED_COMPANY = UUID.fromString("99999999-9999-4999-8999-999999999999");

    private IdentityAccessDirectoryService service;

    @BeforeEach
    void setUp() {
        service = new IdentityAccessDirectoryService(new JdbcTemplate());
    }

    @Test
    @DisplayName("Deve sumarizar acesso multiempresa e validar seleção de empresa e unidade autorizadas")
    void shouldSummarizeMultiCompanyAccessAndValidateSelection() {
        List<AccessRow> rows = List.of(
                new AccessRow(
                        USER_ID, "ceo@mourao.example", "Dr. Mourão", "ACTIVE",
                        MEMBERSHIP_ID, TENANT_ID, "Grupo Mourão", "grupo-mourao", "OWNER", true,
                        COMPANY_MATRIZ, "Mourão Contábil LTDA", "Mourão Matriz", "12345678000199",
                        BRANCH_MANAUS, "Unidade Manaus", "MAO-01", "12345678000199"
                ),
                new AccessRow(
                        USER_ID, "ceo@mourao.example", "Dr. Mourão", "ACTIVE",
                        MEMBERSHIP_ID, TENANT_ID, "Grupo Mourão", "grupo-mourao", "OWNER", true,
                        COMPANY_FILIAL, "Mourão Consultoria S.A.", "Mourão Consultoria", "98765432000188",
                        BRANCH_SP, "Unidade São Paulo", "SPO-01", "98765432000188"
                )
        );

        // Sem seleção explícita em múltiplas empresas -> requiresSelection = true
        IdentitySummary initial = service.summarizeIdentityAccess(rows, ContextSelection.empty());
        assertTrue(initial.requiresSelection());
        assertEquals(2, initial.companies().size());

        // Com seleção explícita da segunda empresa e sua unidade
        IdentitySummary selected = service.summarizeIdentityAccess(
                rows,
                new ContextSelection(TENANT_ID, COMPANY_FILIAL, BRANCH_SP)
        );
        assertFalse(selected.requiresSelection());
        assertEquals(COMPANY_FILIAL, selected.activeContext().companyId());
        assertEquals(BRANCH_SP, selected.activeContext().branchId());
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
    @DisplayName("Deve bloquear usuário inativo ou sem vínculos")
    void shouldRejectInactiveOrUnlinkedUser() {
        assertThrows(
                AuthorizationException.class,
                () -> service.summarizeIdentityAccess(List.of(), ContextSelection.empty())
        );

        List<AccessRow> inactiveRows = List.of(
                new AccessRow(
                        USER_ID, "ex@mourao.example", "Ex Colaborador", "SUSPENDED",
                        MEMBERSHIP_ID, TENANT_ID, "Grupo Mourão", "grupo-mourao", "ANALYST", false,
                        COMPANY_MATRIZ, "Mourão Contábil LTDA", "Mourão Matriz", "12345678000199",
                        BRANCH_MANAUS, "Unidade Manaus", "MAO-01", "12345678000199"
                )
        );

        AuthorizationException ex = assertThrows(
                AuthorizationException.class,
                () -> service.summarizeIdentityAccess(inactiveRows, ContextSelection.empty())
        );
        assertEquals("USER_INACTIVE", ex.getCode());
    }
}
