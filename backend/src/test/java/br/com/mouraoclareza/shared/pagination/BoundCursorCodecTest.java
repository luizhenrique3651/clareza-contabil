package br.com.mouraoclareza.shared.pagination;

import br.com.mouraoclareza.shared.exception.DomainValidationException;
import br.com.mouraoclareza.shared.pagination.BoundCursorCodec.CursorScope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BoundCursorCodecTest {

    private static final UUID TENANT_A = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID COMPANY_A = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID COMPANY_B = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID BRANCH_A = UUID.fromString("44444444-4444-4444-8444-444444444444");

    @Test
    @DisplayName("Deve codificar e decodificar cursor válido no mesmo escopo de empresa e filtros")
    void shouldEncodeAndDecodeValidCursor() {
        CursorScope scope = new CursorScope(
                "financial_records",
                TENANT_A,
                COMPANY_A,
                BRANCH_A,
                "dueDate_asc,id_asc",
                Map.of("type", "PAYABLE", "status", "OPEN")
        );
        Map<String, Object> position = Map.of("dueDate", "2026-08-15", "id", "99999999-9999-4999-8999-999999999999");

        String token = BoundCursorCodec.encodeCursor(scope, position);
        Map<String, Object> decoded = BoundCursorCodec.decodeCursor(token, scope);

        assertEquals("2026-08-15", decoded.get("dueDate"));
        assertEquals("99999999-9999-4999-8999-999999999999", decoded.get("id"));
    }

    @Test
    @DisplayName("Deve rejeitar cursor reutilizado em outra empresa ou com filtros alterados (422 INVALID_CURSOR)")
    void shouldRejectCursorAcrossDifferentCompanyOrFilters() {
        CursorScope scopeCompanyA = new CursorScope(
                "financial_records",
                TENANT_A,
                COMPANY_A,
                BRANCH_A,
                "dueDate_asc,id_asc",
                Map.of("type", "PAYABLE")
        );
        CursorScope scopeCompanyB = new CursorScope(
                "financial_records",
                TENANT_A,
                COMPANY_B,
                BRANCH_A,
                "dueDate_asc,id_asc",
                Map.of("type", "PAYABLE")
        );

        String token = BoundCursorCodec.encodeCursor(scopeCompanyA, Map.of("id", "123"));

        DomainValidationException ex = assertThrows(
                DomainValidationException.class,
                () -> BoundCursorCodec.decodeCursor(token, scopeCompanyB)
        );
        assertEquals(422, ex.getStatus());
        assertEquals("INVALID_CURSOR", ex.getErrors().getFirst().code());
    }
}
