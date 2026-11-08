package br.com.mouraoclareza.reconciliation.domain;

import br.com.mouraoclareza.shared.domain.Money;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Modelos de transferência e domínio para Contas Bancárias, Importação de Extratos (OFX/CSV)
 * e Conciliação Bancária 1:1.
 */
public final class BankReconciliationModels {

    private BankReconciliationModels() {
    }

    public record BankAccountView(
            UUID id,
            UUID tenantId,
            UUID companyId,
            UUID branchId,
            String bankCode,
            String bankName,
            String agencyNumber,
            String accountNumber,
            String accountType,
            String currencyCode,
            Money initialBalance,
            String status,
            Instant createdAt
    ) {
    }

    public record CreateBankAccountRequest(
            UUID branchId,
            String bankCode,
            String bankName,
            String agencyNumber,
            String accountNumber,
            String accountType,
            String initialBalance
    ) {
    }

    public record BankStatementImportView(
            UUID id,
            UUID tenantId,
            UUID companyId,
            UUID branchId,
            UUID bankAccountId,
            String format,
            String fileName,
            String sourceChecksum,
            String importOutcome,
            int totalLines,
            int insertedLines,
            int duplicateLines,
            Instant importedAt
    ) {
    }

    public record CreateStatementImportRequest(
            UUID bankAccountId,
            String format,
            String fileName,
            String content
    ) {
    }

    public record BankTransactionView(
            UUID id,
            UUID tenantId,
            UUID companyId,
            UUID branchId,
            UUID bankAccountId,
            UUID statementImportId,
            LocalDate postedDate,
            String direction,
            Money amount,
            String description,
            String externalReference,
            String movementHash,
            String reconciliationStatus,
            List<ReconciliationSuggestionView> suggestions,
            Instant createdAt
    ) {
    }

    public record ReconciliationSuggestionView(
            UUID financialRecordId,
            String recordType,
            String description,
            String partyName,
            LocalDate dueDate,
            Money openAmount,
            int score,
            List<String> reasons
    ) {
    }

    public record BankReconciliationView(
            UUID id,
            UUID tenantId,
            UUID companyId,
            UUID branchId,
            UUID bankTransactionId,
            UUID financialRecordId,
            UUID settlementId,
            Money matchedAmount,
            String status,
            String notes,
            UUID matchedByUserId,
            Instant matchedAt,
            Instant undoneAt,
            String undoReason
    ) {
    }

    public record MatchReconciliationRequest(
            UUID bankTransactionId,
            UUID financialRecordId,
            String notes
    ) {
    }

    public record UndoReconciliationRequest(
            String reason
    ) {
    }
}
