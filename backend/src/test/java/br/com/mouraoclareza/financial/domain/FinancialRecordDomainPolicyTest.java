package br.com.mouraoclareza.financial.domain;

import br.com.mouraoclareza.financial.domain.FinancialRecordDomainPolicy.SettlementTransition;
import br.com.mouraoclareza.shared.domain.Money;
import br.com.mouraoclareza.shared.exception.ResourceConflictException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FinancialRecordDomainPolicyTest {

    @Test
    @DisplayName("Deve derivar OVERDUE em tempo de leitura quando OPEN e dueDate anterior à data de Manaus")
    void shouldDeriveOverdueStatusAtReadTime() {
        LocalDate manausToday = LocalDate.of(2026, 8, 10);
        assertEquals(
                "OVERDUE",
                FinancialRecordDomainPolicy.deriveEffectiveStatus("OPEN", LocalDate.of(2026, 8, 9), manausToday)
        );
        assertEquals(
                "OPEN",
                FinancialRecordDomainPolicy.deriveEffectiveStatus("OPEN", LocalDate.of(2026, 8, 10), manausToday)
        );
        assertEquals(
                "PARTIALLY_SETTLED",
                FinancialRecordDomainPolicy.deriveEffectiveStatus("PARTIALLY_SETTLED", LocalDate.of(2026, 8, 1), manausToday)
        );
    }

    @Test
    @DisplayName("Deve calcular baixa parcial e baixa total preservando precisão NUMERIC(19,4)")
    void shouldCalculatePartialAndFullSettlement() {
        Money principal = Money.parse("1000.0000", "principalAmount");
        Money discount = Money.parse("50.0000", "discountAmount");
        Money interest = Money.parse("10.2500", "interestAmount");
        Money fine = Money.parse("5.0000", "fineAmount");
        // Total = 1000 - 50 + 10.25 + 5 = 965.2500

        SettlementTransition firstPartial = FinancialRecordDomainPolicy.calculateSettlement(
                "OPEN",
                principal,
                discount,
                interest,
                fine,
                Money.ZERO,
                Money.parse("400.2500", "amount")
        );

        assertEquals("965.2500", firstPartial.totalAmount().toCanonicalString());
        assertEquals("400.2500", firstPartial.settledAmount().toCanonicalString());
        assertEquals("565.0000", firstPartial.openAmount().toCanonicalString());
        assertEquals("PARTIALLY_SETTLED", firstPartial.nextPersistedStatus());

        // Impede cancelamento após liquidação parcial
        ResourceConflictException cancelConflict = assertThrows(
                ResourceConflictException.class,
                () -> FinancialRecordDomainPolicy.assertCanCancel(
                        firstPartial.nextPersistedStatus(),
                        firstPartial.settledAmount()
                )
        );
        assertEquals("FINANCIAL_RECORD_HAS_SETTLEMENTS", cancelConflict.getCode());

        // Baixa restante integral
        SettlementTransition fullSettlement = FinancialRecordDomainPolicy.calculateSettlement(
                firstPartial.nextPersistedStatus(),
                principal,
                discount,
                interest,
                fine,
                firstPartial.settledAmount(),
                Money.parse("565.0000", "amount")
        );

        assertEquals("965.2500", fullSettlement.settledAmount().toCanonicalString());
        assertEquals("0.0000", fullSettlement.openAmount().toCanonicalString());
        assertEquals("SETTLED", fullSettlement.nextPersistedStatus());
    }
}
