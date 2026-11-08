# ADR-002: Propagação Transacional de Contexto e Row-Level Security (RLS) no PostgreSQL 16

- **Status:** Aprovado
- **Data:** 2026-11-08
- **Tasks Relacionadas:** `CLZ-002`, `CLZ-004`

## Contexto

O isolamento multi-tenant, multi-empresa e multi-filial do Clareza depende de políticas de Row-Level Security (RLS) do PostgreSQL avaliadas sob a role restrita `clarity_app` (`NOBYPASSRLS`) e das variáveis transacionais `app.tenant_id`, `app.user_id`, `app.company_ids`, `app.branch_ids` e `app.tenant_wide_access`.

## Decisão

1. Portar integralmente as migrations `0001` a `0007` para o **Flyway** (`V1__init.sql` a `V7__bank_reconciliation.sql`), preservando todas as policies, funções `SECURITY DEFINER`, triggers e constraints compostas.
2. Implementar `RlsTransactionExecutor` no Shared Kernel Java, que abre uma transação gerenciada pelo Spring e executa imediatamente na mesma conexão JDBC:
   - `SET LOCAL ROLE clarity_app`
   - `SELECT set_config('app.tenant_id', ?, true), set_config('app.user_id', ?, true), set_config('app.company_ids', ?, true), set_config('app.branch_ids', ?, true), set_config('app.tenant_wide_access', ?, true)`
3. Como `SET LOCAL` e `set_config(..., true)` têm escopo estritamente limitado à transação atual do PostgreSQL, o commit ou rollback restaura automaticamente a conexão antes que o HikariCP a devolva ao pool.
