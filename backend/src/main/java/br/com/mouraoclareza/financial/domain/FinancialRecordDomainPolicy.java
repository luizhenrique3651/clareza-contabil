package br.com.mouraoclareza.financial.domain;

import br.com.mouraoclareza.shared.domain.Money;
import br.com.mouraoclareza.shared.exception.DomainValidationException;
import br.com.mouraoclareza.shared.exception.ResourceConflictException;
import br.com.mouraoclareza.shared.exception.ValidationErrorItem;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Política de domínio pura para títulos financeiros (Contas a Pagar / Contas a Receber).
 * Preserva integralmente as regras de server/financial-records.mjs e server/financial-events.mjs:
 * - OVERDUE derivado na leitura quando status == OPEN e dueDate < hoje em America/Manaus;
 * - Liquidação parcial/integral exata em NUMERIC(19,4) sem saldo negativo;
 * - Proibição de cancelamento de título com liquidações ativas (SETTLED / PARTIALLY_SETTLED).
 */
public final class FinancialRecordDomainPolicy {

    public static final ZoneId MANAUS_ZONE = ZoneId.of("America/Manaus");

    private FinancialRecordDomainPolicy() {
    }

    public static LocalDate currentManausDate(Clock clock) {
        return LocalDate.now(clock.withZone(MANAUS_ZONE));
    }

    public static String deriveEffectiveStatus(String persistedStatus, LocalDate dueDate, LocalDate referenceManausDate) {
        Objects.requireNonNull(persistedStatus, "persistedStatus must not be null");
        Objects.requireNonNull(dueDate, "dueDate must not be null");
        Objects.requireNonNull(referenceManausDate, "referenceManausDate must not be null");

        if ("OPEN".equals(persistedStatus) && dueDate.isBefore(referenceManausDate)) {
            return "OVERDUE";
        }
        return persistedStatus;
    }

    public static void validateDates(LocalDate issueDate, LocalDate competenceDate, LocalDate dueDate) {
        List<ValidationErrorItem> errors = new ArrayList<>();
        if (issueDate == null) {
            errors.add(new ValidationErrorItem("issueDate", "REQUIRED_DATE", "Data de emissão é obrigatória."));
        }
        if (competenceDate == null) {
            errors.add(new ValidationErrorItem("competenceDate", "REQUIRED_DATE", "Data de competência é obrigatória."));
        }
        if (dueDate == null) {
            errors.add(new ValidationErrorItem("dueDate", "REQUIRED_DATE", "Data de vencimento é obrigatória."));
        }
        if (issueDate != null && dueDate != null && dueDate.isBefore(issueDate)) {
            errors.add(new ValidationErrorItem(
                    "dueDate",
                    "DUE_DATE_BEFORE_ISSUE_DATE",
                    "A data de vencimento não pode ser anterior à data de emissão."
            ));
        }
        if (!errors.isEmpty()) {
            throw new DomainValidationException(errors);
        }
    }

    public static SettlementTransition calculateSettlement(
            String currentStatus,
            Money principalAmount,
            Money discountAmount,
            Money interestAmount,
            Money fineAmount,
            Money currentSettledAmount,
            Money settlementAmount
    ) {
        if ("CANCELLED".equals(currentStatus)) {
            throw new ResourceConflictException(
                    "FINANCIAL_RECORD_CANCELLED",
                    "Não é permitido liquidar um título cancelado."
            );
        }
        if ("SETTLED".equals(currentStatus)) {
            throw new ResourceConflictException(
                    "FINANCIAL_RECORD_ALREADY_SETTLED",
                    "O título financeiro já se encontra integralmente liquidado."
            );
        }

        Money validSettlement = settlementAmount.requirePositive("amount");
        Money totalPayable = principalAmount.subtract(discountAmount).add(interestAmount).add(fineAmount);
        Money openAmount = totalPayable.subtract(currentSettledAmount);

        if (validSettlement.compareTo(openAmount) > 0) {
            throw new ResourceConflictException(
                    "SETTLEMENT_AMOUNT_EXCEEDS_OPEN_BALANCE",
                    "O valor da baixa excede o saldo em aberto do título."
            );
        }

        Money nextSettledAmount = currentSettledAmount.add(validSettlement);
        Money nextOpenAmount = totalPayable.subtract(nextSettledAmount);
        String nextStatus = nextOpenAmount.isZero() ? "SETTLED" : "PARTIALLY_SETTLED";

        return new SettlementTransition(totalPayable, nextSettledAmount, nextOpenAmount, nextStatus);
    }

    public static void assertCanCancel(String currentStatus, Money settledAmount) {
        if ("CANCELLED".equals(currentStatus)) {
            throw new ResourceConflictException(
                    "FINANCIAL_RECORD_ALREADY_CANCELLED",
                    "O título financeiro já está cancelado."
            );
        }
        if ("SETTLED".equals(currentStatus) || "PARTIALLY_SETTLED".equals(currentStatus) || settledAmount.isPositive()) {
            throw new ResourceConflictException(
                    "FINANCIAL_RECORD_HAS_SETTLEMENTS",
                    "Não é permitido cancelar um título que já possui baixas financeiras registradas."
            );
        }
    }

    public record SettlementTransition(
            Money totalAmount,
            Money settledAmount,
            Money openAmount,
            String nextPersistedStatus
    ) {
    }
}
