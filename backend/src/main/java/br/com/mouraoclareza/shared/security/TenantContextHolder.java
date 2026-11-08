package br.com.mouraoclareza.shared.security;

import br.com.mouraoclareza.shared.exception.AuthenticationRequiredException;
import br.com.mouraoclareza.shared.exception.AuthorizationException;
import br.com.mouraoclareza.shared.exception.TenantIsolationException;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

import static br.com.mouraoclareza.shared.domain.DomainIds.requireCompanyId;
import static br.com.mouraoclareza.shared.domain.DomainIds.validateBranchId;

/**
 * Gerenciador e guard de contexto multi-tenant por thread/execução.
 * Equivalente tipado às funções runWithTenantContext, getTenantContext,
 * requirePermission e companyScopedWhere do legado (server/tenant-context.mjs).
 */
public final class TenantContextHolder {

    private static final ThreadLocal<AuthenticatedTenantContext> CURRENT = new ThreadLocal<>();

    private TenantContextHolder() {
    }

    public static <T> T runWithContext(AuthenticatedTenantContext context, Supplier<T> action) {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(action, "action must not be null");
        AuthenticatedTenantContext previous = CURRENT.get();
        CURRENT.set(context);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    public static void runWithContext(AuthenticatedTenantContext context, Runnable action) {
        runWithContext(context, () -> {
            action.run();
            return null;
        });
    }

    public static AuthenticatedTenantContext getRequiredContext() {
        AuthenticatedTenantContext context = CURRENT.get();
        if (context == null) {
            throw new AuthenticationRequiredException("Operação executada sem contexto multi-tenant autenticado.");
        }
        return context;
    }

    public static AuthenticatedTenantContext getNullableContext() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static AuthenticatedTenantContext requirePermission(String permission) {
        AuthenticatedTenantContext context = getRequiredContext();
        if (!context.hasPermission(permission)) {
            throw new AuthorizationException(
                    "FORBIDDEN",
                    "Permissão insuficiente para executar a operação: " + permission
            );
        }
        return context;
    }

    public static void assertCompanyAndBranchAccess(UUID companyId, UUID branchId) {
        AuthenticatedTenantContext context = getRequiredContext();
        requireCompanyId(companyId);
        if (!context.canAccessCompany(companyId)) {
            throw new TenantIsolationException("Acesso negado à empresa informada.");
        }
        if (!context.activeCompanyId().equals(companyId)) {
            throw new TenantIsolationException("A operação deve pertencer à empresa ativa selecionada no contexto.");
        }
        if (branchId != null) {
            validateBranchId(branchId);
            if (!context.canAccessBranch(branchId)) {
                throw new TenantIsolationException("Acesso negado à unidade informada.");
            }
            if (context.activeBranchId() != null && !context.activeBranchId().equals(branchId)) {
                throw new TenantIsolationException("A operação deve pertencer à unidade ativa selecionada no contexto.");
            }
        }
    }

    /**
     * Equivalente tipado a companyScopedWhere(extraWhere) do legado.
     * Rejeita qualquer tentativa de sobrescrever tenantId ou companyId fora do contexto ativo.
     */
    public static ScopedFilter companyScopedWhere(UUID requestedTenantId, UUID requestedCompanyId, UUID requestedBranchId) {
        AuthenticatedTenantContext context = getRequiredContext();
        if (requestedTenantId != null && !context.tenantId().equals(requestedTenantId)) {
            throw new TenantIsolationException("Violação de isolamento: tenantId divergente do contexto autenticado.");
        }
        UUID effectiveCompanyId = requestedCompanyId != null ? requestedCompanyId : context.activeCompanyId();
        assertCompanyAndBranchAccess(effectiveCompanyId, requestedBranchId);
        UUID effectiveBranchId = requestedBranchId != null ? requestedBranchId : context.activeBranchId();
        return new ScopedFilter(context.tenantId(), effectiveCompanyId, effectiveBranchId, Map.of(
                "tenantId", context.tenantId().toString(),
                "companyId", effectiveCompanyId.toString()
        ));
    }

    public record ScopedFilter(
            UUID tenantId,
            UUID companyId,
            UUID branchId,
            Map<String, String> attributes
    ) {
    }
}
