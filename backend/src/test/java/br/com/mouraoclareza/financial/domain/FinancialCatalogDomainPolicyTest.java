package br.com.mouraoclareza.financial.domain;

import br.com.mouraoclareza.financial.domain.FinancialCatalogDomainPolicy.ValidatedCategory;
import br.com.mouraoclareza.financial.domain.FinancialCatalogDomainPolicy.ValidatedCostCenter;
import br.com.mouraoclareza.financial.domain.FinancialCatalogDomainPolicy.ValidatedParty;
import br.com.mouraoclareza.shared.exception.DomainValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FinancialCatalogDomainPolicyTest {

    @Test
    @DisplayName("Deve normalizar CPF/CNPJ, razão social, papel e e-mail de participante")
    void shouldNormalizeAndValidatePartyInput() {
        ValidatedParty party = FinancialCatalogDomainPolicy.validatePartyInput(
                "  Fornecedor Amazônia LTDA  ",
                null,
                "12.345.678/0001-99",
                "supplier",
                "  Financeiro@Amazonia.Example "
        );

        assertEquals("Fornecedor Amazônia LTDA", party.legalName());
        assertEquals("Fornecedor Amazônia LTDA", party.tradeName());
        assertEquals("CNPJ", party.documentType());
        assertEquals("12345678000199", party.documentNumber());
        assertEquals("SUPPLIER", party.partyRole());
        assertEquals("financeiro@amazonia.example", party.email());
    }

    @Test
    @DisplayName("Deve rejeitar documento com tamanho inválido ou papel desconhecido em participante")
    void shouldRejectInvalidPartyDocumentAndRole() {
        DomainValidationException ex = assertThrows(
                DomainValidationException.class,
                () -> FinancialCatalogDomainPolicy.validatePartyInput(
                        "A",
                        null,
                        "12345",
                        "INVALID_ROLE",
                        "email-invalido"
                )
        );
        assertEquals(422, ex.getStatus());
        assertEquals(4, ex.getErrors().size());
    }

    @Test
    @DisplayName("Deve validar e normalizar código e natureza de categoria financeira e centro de custo")
    void shouldValidateCategoryAndCostCenter() {
        ValidatedCategory category = FinancialCatalogDomainPolicy.validateCategoryInput(
                " desp-oper-01 ",
                "Despesas Operacionais",
                "expense"
        );
        assertEquals("DESP-OPER-01", category.code());
        assertEquals("EXPENSE", category.kind());

        ValidatedCostCenter costCenter = FinancialCatalogDomainPolicy.validateCostCenterInput(
                " cc_manaus_01 ",
                "Operações Manaus"
        );
        assertEquals("CC_MANAUS_01", costCenter.code());
        assertEquals("Operações Manaus", costCenter.name());
    }
}
