package br.com.mouraoclareza.shared.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Executor transacional que propaga o contexto multi-tenant para o PostgreSQL via
 * SET LOCAL ROLE clarity_app e SELECT set_config(..., true) na mesma conexão física.
 * Cumpre integralmente o ADR-002 e preserva as garantias de RLS da Fase 0.
 */
@Component
public class RlsTransactionExecutor {

    private final TransactionTemplate transactionTemplate;
    private final JdbcTemplate jdbcTemplate;

    public RlsTransactionExecutor(TransactionTemplate transactionTemplate, JdbcTemplate jdbcTemplate) {
        this.transactionTemplate = Objects.requireNonNull(transactionTemplate, "transactionTemplate must not be null");
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
    }

    public <T> T executeInTenantTransaction(Supplier<T> callback) {
        AuthenticatedTenantContext context = TenantContextHolder.getRequiredContext();
        return executeInTenantTransaction(context, callback);
    }

    public <T> T executeInTenantTransaction(AuthenticatedTenantContext context, Supplier<T> callback) {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(callback, "callback must not be null");

        return TenantContextHolder.runWithContext(context, () ->
                transactionTemplate.execute(status -> {
                    jdbcTemplate.execute("SET LOCAL ROLE clarity_app");
                    jdbcTemplate.queryForObject(
                            """
                            SELECT
                              set_config('app.tenant_id', ?, true),
                              set_config('app.user_id', ?, true),
                              set_config('app.company_ids', ?, true),
                              set_config('app.branch_ids', ?, true),
                              set_config('app.tenant_wide_access', ?, true)
                            """,
                            (rs, rowNum) -> rs.getString(1),
                            context.tenantId().toString(),
                            context.userId().toString(),
                            context.companyIdsCsv(),
                            context.branchIdsCsv(),
                            Boolean.toString(context.tenantWideAccess())
                    );
                    return callback.get();
                })
        );
    }
}
