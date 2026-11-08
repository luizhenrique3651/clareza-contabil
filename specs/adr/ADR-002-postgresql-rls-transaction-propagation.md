# ADR-002: Propagação Transacional de Contexto, Alinhamento SQL Estrito (`V1`–`V8`) e Row-Level Security (RLS) no PostgreSQL 16

- **Status:** ACCEPTED (Atualizado após Auditoria Sênior `FIX-01` a `FIX-07`)
- **Data:** 2026-11-08
- **Tasks Relacionadas:** `CLZ-002`, `CLZ-004`, `CLZ-007`, `CLZ-011` a `CLZ-017`, `CLZ-020` a `CLZ-022`

---

## Contexto

O isolamento multi-tenant, multi-empresa e multi-filial do Clareza depende de políticas de Row-Level Security (RLS) do PostgreSQL avaliadas sob a role restrita `clarity_app` (`NOBYPASSRLS`) e das variáveis transacionais `app.tenant_id`, `app.user_id`, `app.company_ids`, `app.branch_ids` e `app.tenant_wide_access`. Além disso, as migrations `V1__init.sql` a `V8__user_sessions_and_auth_hardening.sql` implementam triggers de integridade, escopo e projeção automática que exigem alinhamento 100% estrito das queries SQL no Java.

---

## Decisão

1. **Propagação Transacional RLS (`RlsTransactionExecutor`):**
   - Toda operação de banco deve rodar dentro de `RlsTransactionExecutor`, que abre uma transação Spring e executa imediatamente na mesma conexão JDBC:
     - `SET LOCAL ROLE clarity_app`
     - `SELECT set_config('app.tenant_id', ?, true), set_config('app.user_id', ?, true), set_config('app.company_ids', ?, true), set_config('app.branch_ids', ?, true), set_config('app.tenant_wide_access', ?, true)`
   - **Regra Crítica de Escopo `BRANCH` (`FIX-03`):** Quando o usuário possui apenas vínculos de escopo `BRANCH` na empresa ativa (sem vínculo `COMPANY` ou `TENANT`), `allowedCompanyIds` **deve ser vazio (`List.of()`)** para que `app.can_access_company(company_id)` retorne `false` e o acesso seja autorizado exclusivamente por `app.can_access_branch(branch_id)`.

2. **Mapeamento Canônico de Tabelas (`V1` a `V8`) — Proibição de Tabelas Paralelas (`FIX-04`, `FIX-05`):**
   - É **estritamente proibido** referenciar nomes de tabelas paralelas inexistentes (`parties`, `financial_records`, `financial_settlements`, `financial_events`, `bank_accounts`, `bank_statement_imports`, `bank_reconciliations`).
   - Todas as queries `JdbcTemplate` devem usar exclusivamente as tabelas reais do schema Flyway:
     - **Catálogos (`V5`, `V6`):** `counterparties` (`kind::counterparty_kind`), `financial_categories` (`direction::financial_direction`), `cost_centers`, `financial_accounts` (`type::financial_account_type`).
     - **Títulos Financeiros (`V1`, `V5`, `V6`):** `payables` (com `paid_at`) e `receivables` (com `received_at`).
     - **Eventos Financeiros Append-Only (`V5`, `V6`):** `payments`, `payment_reversals`, `receipts`, `receipt_reversals`.
     - **Importação e Conciliação Bancária (`V7`):** `bank_imports`, `bank_transactions` (com `amount` assinado: negativo para `DEBIT`, positivo para `CREDIT`), `bank_reconciliation_groups`, `bank_reconciliation_items` (`participant_kind`: `'BANK_TRANSACTION'`, `'PAYMENT'`, `'RECEIPT'`) e `bank_reconciliation_reversals`.

3. **Regra de Projeção via Triggers Append-Only (`V6` linhas 671–720):**
   - As triggers `payables_projection_guard` e `receivables_projection_guard` bloqueiam qualquer `UPDATE` direto em `amount`, `outstanding_amount`, `status` e `paid_at`/`received_at` quando `pg_trigger_depth() <= 1`.
   - Portanto, baixas e estornos no Java **nunca** devem executar `UPDATE payables/receivables SET outstanding_amount = ..., status = ...`. Basta executar `INSERT INTO payments / receipts / payment_reversals / receipt_reversals`, e as triggers `AFTER INSERT` (`payments_refresh_projection` / `receipts_refresh_projection`) recalculam e atualizam a projeção atomicamente no PostgreSQL.
   - O cancelamento de título aberto sem baixas realiza soft-delete (`UPDATE payables/receivables SET deleted_at = CURRENT_TIMESTAMP, updated_by_id = ?`), sem tocar nas colunas protegidas pela projection guard.
