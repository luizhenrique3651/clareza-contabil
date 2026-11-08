package br.com.mouraoclareza.shared.security;

import br.com.mouraoclareza.shared.exception.AuthenticationRequiredException;
import br.com.mouraoclareza.shared.exception.AuthorizationException;
import br.com.mouraoclareza.shared.exception.TenantIsolationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TenantContextHolderTest {

    private static final UUID TENANT_1 = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID TENANT_2 = UUID.fromString("99999999-9999-4999-8999-999999999999");
    private static final UUID USER_1 = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private static final UUID COMPANY_1 = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID COMPANY_2 = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID BRANCH_1 = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID BRANCH_2 = UUID.fromString("55555555-5555-4555-8555-555555555555");

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    @DisplayName("Deve falhar fechado (401) quando executado sem contexto autenticado")
    void shouldFailClosedWithoutContext() {
        assertThrows(AuthenticationRequiredException.class, TenantContextHolder::getRequiredContext);
    }

    @Test
    @DisplayName("Deve bloquear (403) acesso cruzado a outro tenant, empresa ou unidade")
    void shouldBlockCrossTenantCompanyAndBranchAccess() {
        AuthenticatedTenantContext context = new AuthenticatedTenantContext(
                TENANT_1,
                USER_1,
                "analista@mourao.example",
                "Analista",
                "ANALYST",
                false,
                List.of(COMPANY_1),
                List.of(BRANCH_1),
                COMPANY_1,
                BRANCH_1,
                Set.of("financial.read"),
                "req-123"
        );

        TenantContextHolder.runWithContext(context, () -> {
            assertThrows(
                    TenantIsolationException.class,
                    () -> TenantContextHolder.companyScopedWhere(TENANT_2, COMPANY_1, BRANCH_1)
            );
            assertThrows(
                    TenantIsolationException.class,
                    () -> TenantContextHolder.companyScopedWhere(TENANT_1, COMPANY_2, BRANCH_1)
            );
            assertThrows(
                    TenantIsolationException.class,
                    () -> TenantContextHolder.companyScopedWhere(TENANT_1, COMPANY_1, BRANCH_2)
            );
            assertThrows(
                    AuthorizationException.class,
                    () -> TenantContextHolder.requirePermission("financial.cancel")
            );

            TenantContextHolder.ScopedFilter valid = TenantContextHolder.companyScopedWhere(TENANT_1, COMPANY_1, BRANCH_1);
            assertEquals(TENANT_1, valid.tenantId());
            assertEquals(COMPANY_1, valid.companyId());
            assertEquals(BRANCH_1, valid.branchId());
        });
    }
}
