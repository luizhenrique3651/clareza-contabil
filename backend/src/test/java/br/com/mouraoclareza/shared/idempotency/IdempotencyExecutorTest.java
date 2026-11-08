package br.com.mouraoclareza.shared.idempotency;

import br.com.mouraoclareza.shared.exception.DomainValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IdempotencyExecutorTest {

    private final IdempotencyExecutor executor = new IdempotencyExecutor(new JdbcTemplate());

    @Test
    @DisplayName("Deve validar Idempotency-Key estritamente contra regex ^[A-Za-z0-9._:-]{8,128}$ (FIX-01)")
    void shouldValidateIdempotencyKeyFormat() {
        assertEquals(
                "idem-key-2026-08-10:0001",
                IdempotencyExecutor.requireValidIdempotencyKey("  idem-key-2026-08-10:0001  ")
        );

        assertThrows(DomainValidationException.class, () -> IdempotencyExecutor.requireValidIdempotencyKey(null));
        assertThrows(DomainValidationException.class, () -> IdempotencyExecutor.requireValidIdempotencyKey("curta77"));
        assertThrows(DomainValidationException.class, () -> IdempotencyExecutor.requireValidIdempotencyKey("invalida com espaco"));
        assertThrows(DomainValidationException.class, () -> IdempotencyExecutor.requireValidIdempotencyKey("invalida@caractere"));
        assertThrows(DomainValidationException.class, () -> IdempotencyExecutor.requireValidIdempotencyKey("a".repeat(129)));
    }

    @Test
    @DisplayName("Deve produzir hash SHA-256 canônico idêntico independentemente da ordem de chaves no Map (FIX-07)")
    void shouldComputeDeterministicCanonicalRequestHash() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("zeta", "100.0000");
        first.put("alpha", "2026-08-10");

        Map<String, Object> second = new LinkedHashMap<>();
        second.put("alpha", "2026-08-10");
        second.put("zeta", "100.0000");

        assertEquals(executor.computeSha256Json(first), executor.computeSha256Json(second));
    }
}
