package br.com.mouraoclareza.reconciliation.domain;

import br.com.mouraoclareza.shared.domain.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class BankMovementHashCalculatorTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID COMPANY_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID BANK_ACCOUNT_ID = UUID.fromString("66666666-6666-4666-8666-666666666666");

    @Test
    @DisplayName("Deve gerar movement_hash determinístico normalizando espaços e caixa de descrição")
    void shouldComputeDeterministicMovementHash() {
        String hash1 = BankMovementHashCalculator.computeMovementHash(
                TENANT_ID,
                COMPANY_ID,
                BANK_ACCOUNT_ID,
                LocalDate.of(2026, 8, 5),
                "DEBIT",
                Money.parse("250.5000", "amount"),
                "  PIX   FORNECEDOR   LTDA ",
                "FITID-001"
        );
        String hash2 = BankMovementHashCalculator.computeMovementHash(
                TENANT_ID,
                COMPANY_ID,
                BANK_ACCOUNT_ID,
                LocalDate.of(2026, 8, 5),
                "debit",
                Money.parse("250.50", "amount"),
                "pix fornecedor ltda",
                "fitid-001"
        );
        String hashDifferentAmount = BankMovementHashCalculator.computeMovementHash(
                TENANT_ID,
                COMPANY_ID,
                BANK_ACCOUNT_ID,
                LocalDate.of(2026, 8, 5),
                "DEBIT",
                Money.parse("250.51", "amount"),
                "pix fornecedor ltda",
                "fitid-001"
        );

        assertEquals(hash1, hash2);
        assertEquals(64, hash1.length());
        assertNotEquals(hash1, hashDifferentAmount);
    }
}
