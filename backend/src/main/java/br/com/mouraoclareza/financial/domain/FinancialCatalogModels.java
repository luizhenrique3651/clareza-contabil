package br.com.mouraoclareza.financial.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Modelos de transferência e domínio dos Catálogos Financeiros
 * (Participantes/Counterparties, Categorias Financeiras, Centros de Custo e Contas Financeiras).
 */
public final class FinancialCatalogModels {

    private FinancialCatalogModels() {
    }

    public record PartyView(
            UUID id,
            UUID tenantId,
            UUID companyId,
            UUID branchId,
            String code,
            String legalName,
            String tradeName,
            String documentType,
            String documentNumber,
            String partyRole,
            String email,
            String status,
            Instant createdAt
    ) {
    }

    public record CreatePartyRequest(
            UUID branchId,
            String code,
            String legalName,
            String tradeName,
            String documentNumber,
            String partyRole,
            String email
    ) {
        public CreatePartyRequest(
                UUID branchId,
                String legalName,
                String tradeName,
                String documentNumber,
                String partyRole,
                String email
        ) {
            this(branchId, null, legalName, tradeName, documentNumber, partyRole, email);
        }
    }

    public record FinancialCategoryView(
            UUID id,
            UUID tenantId,
            UUID companyId,
            UUID branchId,
            String code,
            String name,
            String kind,
            String direction,
            String status,
            Instant createdAt
    ) {
    }

    public record CreateCategoryRequest(
            UUID branchId,
            String code,
            String name,
            String kind
    ) {
        public CreateCategoryRequest(String code, String name, String kind) {
            this(null, code, name, kind);
        }
    }

    public record CostCenterView(
            UUID id,
            UUID tenantId,
            UUID companyId,
            UUID branchId,
            String code,
            String name,
            String status,
            Instant createdAt
    ) {
    }

    public record CreateCostCenterRequest(
            UUID branchId,
            String code,
            String name
    ) {
    }

    public record FinancialAccountView(
            UUID id,
            UUID tenantId,
            UUID companyId,
            UUID branchId,
            String code,
            String name,
            String type,
            String currencyCode,
            String status,
            Instant createdAt
    ) {
    }

    public record CreateFinancialAccountRequest(
            UUID branchId,
            String code,
            String name,
            String type,
            String currencyCode
    ) {
    }

    public record CursorPageResponse<T>(
            List<T> items,
            PageInfo pageInfo
    ) {
    }

    public record PageInfo(
            int limit,
            boolean hasNextPage,
            String nextCursor
    ) {
    }
}
