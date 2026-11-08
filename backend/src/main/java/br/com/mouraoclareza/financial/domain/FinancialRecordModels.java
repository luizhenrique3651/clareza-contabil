package br.com.mouraoclareza.financial.domain;

import br.com.mouraoclareza.shared.domain.Money;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Modelos de transferência e domínio para Títulos Financeiros (payables / receivables),
 * Baixas (payments / receipts), Estornos (payment_reversals / receipt_reversals),
 * Sumário Executivo e Eventos Imutáveis.
 * Paridade integral com V1__init.sql, V5__financial_operations.sql, V6__phase_two_hardening.sql,
 * legacy/server/financial-records.mjs e legacy/server/financial-events.mjs.
 */
public final class FinancialRecordModels {

    private FinancialRecordModels() {
    }

    public record FinancialRecordView(
            UUID id,
            UUID tenantId,
            UUID companyId,
            UUID branchId,
            UUID partyId,
            String partyName,
            UUID categoryId,
            String categoryName,
            UUID costCenterId,
            String costCenterName,
            String recordType,
            String description,
            String documentNumber,
            LocalDate issueDate,
            LocalDate competenceDate,
            LocalDate dueDate,
            Money principalAmount,
            Money discountAmount,
            Money interestAmount,
            Money fineAmount,
            Money totalAmount,
            Money settledAmount,
            Money openAmount,
            String persistedStatus,
            String effectiveStatus,
            Instant createdAt,
            List<FinancialSettlementView> settlements
    ) {
    }

    public record CreateFinancialRecordRequest(
            UUID branchId,
            UUID partyId,
            UUID categoryId,
            UUID costCenterId,
            String recordType,
            String description,
            String documentNumber,
            LocalDate issueDate,
            LocalDate competenceDate,
            LocalDate dueDate,
            String principalAmount,
            String discountAmount,
            String interestAmount,
            String fineAmount,
            String counterpartyName,
            String currencyCode
    ) {
        public CreateFinancialRecordRequest(
                UUID branchId,
                UUID partyId,
                UUID categoryId,
                UUID costCenterId,
                String recordType,
                String description,
                String documentNumber,
                LocalDate issueDate,
                LocalDate competenceDate,
                LocalDate dueDate,
                String principalAmount,
                String discountAmount,
                String interestAmount,
                String fineAmount
        ) {
            this(
                    branchId,
                    partyId,
                    categoryId,
                    costCenterId,
                    recordType,
                    description,
                    documentNumber,
                    issueDate,
                    competenceDate,
                    dueDate,
                    principalAmount,
                    discountAmount,
                    interestAmount,
                    fineAmount,
                    null,
                    "BRL"
            );
        }
    }

    public record FinancialSettlementView(
            UUID id,
            UUID financialRecordId,
            LocalDate settlementDate,
            Money amount,
            String paymentMethod,
            String bankReference,
            String notes,
            Instant createdAt
    ) {
    }

    public record CreateSettlementRequest(
            LocalDate settlementDate,
            String amount,
            String paymentMethod,
            String bankReference,
            String notes,
            UUID financialAccountId,
            String interestAmount,
            String fineAmount,
            String discountAmount
    ) {
        public CreateSettlementRequest(
                LocalDate settlementDate,
                String amount,
                String paymentMethod,
                String bankReference,
                String notes
        ) {
            this(settlementDate, amount, paymentMethod, bankReference, notes, null, null, null, null);
        }
    }

    public record ReverseSettlementRequest(
            String reason,
            Instant occurredAt
    ) {
    }

    public record CancelFinancialRecordRequest(
            String reason
    ) {
    }

    public record FinancialEventView(
            UUID id,
            UUID tenantId,
            UUID companyId,
            UUID branchId,
            UUID financialRecordId,
            String eventType,
            Money amount,
            Map<String, Object> payload,
            UUID actorUserId,
            Instant occurredAt
    ) {
    }

    public record FinancialSummaryView(
            UUID companyId,
            UUID branchId,
            LocalDate referenceDateManaus,
            Money openPayables,
            Money overduePayables,
            Money settledPayables,
            Money openReceivables,
            Money overdueReceivables,
            Money settledReceivables,
            Money projectedNetBalance
    ) {
    }
}
