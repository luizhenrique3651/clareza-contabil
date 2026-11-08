package br.com.mouraoclareza.reconciliation.domain;

import br.com.mouraoclareza.reconciliation.domain.BankReconciliationDomainPolicy.SuggestionScore;
import br.com.mouraoclareza.reconciliation.domain.BankStatementParser.ParsedStatement;
import br.com.mouraoclareza.shared.domain.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BankStatementParserTest {

    private static final UUID TENANT_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID COMPANY_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final UUID ACCOUNT_ID = UUID.fromString("85000000-0000-4000-8000-000000000001");

    @Test
    @DisplayName("Deve fazer parse determinístico de extrato CSV e pontuar candidatos de conciliação")
    void shouldParseCsvAndScoreCandidates() {
        String csv = """
                date,description,amount,reference
                2026-08-10,PAGAMENTO FORNECEDOR HORIZONTE NF-991,-450.25,DOC-991
                2026-08-11,RECEBIMENTO CLIENTE PIX,1200.00,PIX-772
                """;

        ParsedStatement parsed = BankStatementParser.parse(TENANT_ID, COMPANY_ID, ACCOUNT_ID, "CSV", csv);
        assertEquals("CSV", parsed.format());
        assertEquals(2, parsed.movements().size());
        assertEquals("DEBIT", parsed.movements().get(0).direction());
        assertEquals("450.2500", parsed.movements().get(0).amount().toCanonicalString());
        assertEquals("CREDIT", parsed.movements().get(1).direction());
        assertEquals("1200.0000", parsed.movements().get(1).amount().toCanonicalString());

        SuggestionScore score = BankReconciliationDomainPolicy.evaluateSuggestion(
                parsed.movements().get(0).postedDate(),
                parsed.movements().get(0).direction(),
                parsed.movements().get(0).amount(),
                parsed.movements().get(0).description(),
                parsed.movements().get(0).externalReference(),
                LocalDate.of(2026, 8, 10),
                "PAYABLE",
                Money.parse("450.2500", "openAmount"),
                "DOC-991",
                "Fornecedor Horizonte",
                "Fornecimento de insumos NF-991"
        );
        assertTrue(score.score() >= 90);
    }
}
