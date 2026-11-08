package br.com.mouraoclareza.shared.domain;

import br.com.mouraoclareza.shared.exception.DomainValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MoneyTest {

    @Test
    @DisplayName("Deve normalizar valores monetários para NUMERIC(19,4) sem imprecisão binária")
    void shouldNormalizeAndAddExactDecimals() {
        Money a = Money.parse("0.1", "amount");
        Money b = Money.parse("0.2", "amount");
        Money sum = a.add(b);

        assertEquals("0.3000", sum.toCanonicalString());
        assertTrue(sum.isPositive());
        assertFalse(sum.isZero());
    }

    @Test
    @DisplayName("Deve rejeitar valores com mais de 4 casas decimais sem arredondamento silencioso")
    void shouldRejectMoreThanFourDecimalPlaces() {
        DomainValidationException ex = assertThrows(
                DomainValidationException.class,
                () -> Money.parse("125.12345", "principalAmount")
        );
        assertEquals(422, ex.getStatus());
        assertEquals("VALIDATION_ERROR", ex.getCode());
        assertEquals("principalAmount", ex.getErrors().getFirst().field());
    }

    @Test
    @DisplayName("Deve rejeitar valores negativos quando exigido não-negativo ou positivo")
    void shouldRejectNegativeAndZeroWhenPositiveRequired() {
        Money negative = Money.parse("-10.00", "discountAmount");
        assertThrows(DomainValidationException.class, () -> negative.requireNonNegative("discountAmount"));
        assertThrows(DomainValidationException.class, () -> Money.ZERO.requirePositive("settlementAmount"));
    }
}
