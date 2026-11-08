package br.com.mouraoclareza.reconciliation.domain;

import br.com.mouraoclareza.shared.domain.Money;
import br.com.mouraoclareza.shared.exception.ResourceConflictException;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Política de domínio pura para Conciliação Bancária Determinística 1:1 e Motor de Sugestões Auditáveis.
 * Paridade integral com legacy/server/bank-service.mjs (Fase 3).
 */
public final class BankReconciliationDomainPolicy {

    private BankReconciliationDomainPolicy() {
    }

    public static boolean isDirectionCompatible(String bankDirection, String financialRecordType) {
        if ("DEBIT".equalsIgnoreCase(bankDirection) && "PAYABLE".equalsIgnoreCase(financialRecordType)) {
            return true;
        }
        return "CREDIT".equalsIgnoreCase(bankDirection) && "RECEIVABLE".equalsIgnoreCase(financialRecordType);
    }

    public static void assertCanMatch(
            String bankStatus,
            String bankDirection,
            Money bankAmount,
            String recordStatus,
            String recordType,
            Money recordOpenAmount
    ) {
        if (!"UNMATCHED".equalsIgnoreCase(bankStatus)) {
            throw new ResourceConflictException(
                    "BANK_TRANSACTION_ALREADY_RECONCILED",
                    "A movimentação bancária selecionada já se encontra conciliada."
            );
        }
        if ("CANCELLED".equalsIgnoreCase(recordStatus) || "SETTLED".equalsIgnoreCase(recordStatus)) {
            throw new ResourceConflictException(
                    "FINANCIAL_RECORD_NOT_OPEN",
                    "O título financeiro não está aberto para conciliação."
            );
        }
        if (!isDirectionCompatible(bankDirection, recordType)) {
            throw new ResourceConflictException(
                    "RECONCILIATION_DIRECTION_MISMATCH",
                    "Direção incompatível: lançamentos DEBIT só podem conciliar PAYABLE e CREDIT só podem conciliar RECEIVABLE."
            );
        }
        if (bankAmount.compareTo(recordOpenAmount) != 0) {
            throw new ResourceConflictException(
                    "RECONCILIATION_AMOUNT_MISMATCH",
                    "Na conciliação 1:1 o valor do movimento bancário deve corresponder exatamente ao saldo em aberto do título."
            );
        }
    }

    public static void assertCanUndo(String reconciliationStatus) {
        if ("UNDONE".equalsIgnoreCase(reconciliationStatus)) {
            throw new ResourceConflictException(
                    "RECONCILIATION_ALREADY_UNDONE",
                    "Esta conciliação bancária já foi desfeita anteriormente."
            );
        }
    }

    /**
     * Calcula a pontuação determinística (0 a 100) entre um movimento bancário UNMATCHED e um título em aberto.
     * Retorna score 0 se a direção for incompatível ou se o valor divergir.
     */
    public static SuggestionScore evaluateSuggestion(
            LocalDate postedDate,
            String bankDirection,
            Money bankAmount,
            String bankDescription,
            String bankExternalRef,
            LocalDate recordDueDate,
            String recordType,
            Money recordOpenAmount,
            String recordDocumentNumber,
            String partyName,
            String recordDescription
    ) {
        if (!isDirectionCompatible(bankDirection, recordType)) {
            return new SuggestionScore(0, List.of());
        }
        if (bankAmount.compareTo(recordOpenAmount) != 0) {
            return new SuggestionScore(0, List.of());
        }

        int score = 60;
        List<String> reasons = new ArrayList<>();
        reasons.add("Valor exato (" + bankAmount.toCanonicalString() + ") e natureza compatível (+60)");

        long daysDiff = Math.abs(ChronoUnit.DAYS.between(postedDate, recordDueDate));
        if (daysDiff == 0) {
            score += 25;
            reasons.add("Data da movimentação coincide exatamente com o vencimento (+25)");
        } else if (daysDiff <= 3) {
            score += 25;
            reasons.add("Movimentação dentro da janela de 3 dias do vencimento (+25)");
        } else if (daysDiff <= 7) {
            score += 15;
            reasons.add("Movimentação dentro da janela de 7 dias do vencimento (+15)");
        }

        String normBankText = ((bankDescription == null ? "" : bankDescription) + " "
                + (bankExternalRef == null ? "" : bankExternalRef)).toLowerCase(Locale.ROOT);

        if (recordDocumentNumber != null && !recordDocumentNumber.isBlank()
                && normBankText.contains(recordDocumentNumber.trim().toLowerCase(Locale.ROOT))) {
            score += 10;
            reasons.add("Documento/referência localizado no histórico bancário (+10)");
        }

        String candidateText = ((partyName == null ? "" : partyName) + " "
                + (recordDescription == null ? "" : recordDescription)).toLowerCase(Locale.ROOT);
        if (hasSignificantTokenOverlap(normBankText, candidateText)) {
            score += 5;
            reasons.add("Correspondência textual com participante/descrição (+5)");
        }

        return new SuggestionScore(Math.min(score, 100), List.copyOf(reasons));
    }

    private static boolean hasSignificantTokenOverlap(String textA, String textB) {
        String[] tokens = textB.split("[^a-z0-9]+");
        for (String token : tokens) {
            if (token.length() >= 4 && textA.contains(token)) {
                return true;
            }
        }
        return false;
    }

    public record SuggestionScore(
            int score,
            List<String> reasons
    ) {
    }
}
