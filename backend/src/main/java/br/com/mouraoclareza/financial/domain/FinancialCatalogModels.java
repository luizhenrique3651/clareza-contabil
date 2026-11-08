package br.com.mouraoclareza.financial.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Modelos de transferência e domínio dos Catálogos Financeiros (Participantes, Categorias e Centros de Custo).
 */
public final class FinancialCatalogModels {

    private FinancialCatalogModels() {
    }

    public record PartyView(
            UUID id,
            UUID tenantId,
            UUID companyId,
            UUID branchId,
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
            String legalName,
            String tradeName,
            String documentNumber,
            String partyRole,
            String email
    ) {
    }

    public record FinancialCategoryView(
            UUID id,
            UUID tenantId,
            UUID companyId,
            String code,
            String name,
            String kind,
            String status,
            Instant createdAt
    ) {
    }

    public record CreateCategoryRequest(
            String code,
            String name,
            String kind
    ) {
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
