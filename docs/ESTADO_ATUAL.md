# ESTADO ATUAL — Mourão Clareza Contábil e Financeira

**Última atualização:** 2026-11-08  
**Repositório Oficial:** `/Users/luiz/Documents/clareza-contabil`  
**Épico Ativo:** `EPIC-06` (Sprint 7 — Frontend React 19 Completo: Workspace Financeiro & Conciliação)  
**Task Ativa:** `CLZ-018` (Workspace Financeiro Completo em React 19 integrado às APIs `/api/v1/financial-*`)

---

## 1. Adequação Completa da Auditoria Sênior (`FIX-01` a `FIX-07` — Concluída)

Todas as correções apontadas em [`docs/PLANO_CORRECAO_BACKEND_ALINHAMENTO_SQL_PERFORMANCE.md`](./PLANO_CORRECAO_BACKEND_ALINHAMENTO_SQL_PERFORMANCE.md) foram integralmente implementadas e verificadas:

1. **`FIX-01` (`IdempotencyExecutor` & Controllers):**
   - Validação estrita do header `Idempotency-Key` com regex `^[A-Za-z0-9._:-]{8,128}$` (`422 INVALID_IDEMPOTENCY_KEY`).
   - Queries `SELECT`, `DELETE` (para chaves expiradas) e `INSERT` alinhadas 100% com `idempotency_records` de `V3__financial_api_foundation.sql` (`key`, `resource_type`, `resource_id`, `expires_at`).
   - Retorno do header HTTP `Idempotency-Replayed: true|false` em todos os endpoints `POST` (`FinancialCatalogsController`, `FinancialRecordsController`, `BankReconciliationController`).
2. **`FIX-02` (`AuditTrailService`):**
   - Parâmetro `entityId` tipado como `UUID` e `INSERT INTO audit_logs` alinhado 100% com `V1__init.sql` (`entity_id`, `before_data`, `after_data`, `metadata`, `integrity_hash`).
3. **`FIX-03` (`IdentityAccessDirectoryService`, `ClarezaAuthenticationFilter`, `MeController`, `V8`):**
   - Função `app.resolve_session_token(text)` (`SECURITY DEFINER`) adicionada em `V8__user_sessions_and_auth_hardening.sql`.
   - `AccessRow` e `IdentityAccessDirectoryService` alinhados com as 11 colunas reais de `app.resolve_identity_access(text)`.
   - Regra crítica de RLS para escopo `BRANCH` aplicada (`allowedCompanyIds = List.of()` quando o usuário possui apenas vínculo `BRANCH` na empresa ativa, exigindo seleção de filial).
   - Eliminação do duplo round-trip em `GET /api/v1/me` via atributo de request `clareza.identitySummary`.
4. **`FIX-04` (`FinancialCatalogService`):**
   - Alinhado 100% com `counterparties` (`kind::counterparty_kind`), `financial_categories` (`direction::financial_direction`), `cost_centers` e `financial_accounts` (`type::financial_account_type`) de `V5` e `V6`, incluindo tratamento de `DuplicateKeyException` $\rightarrow$ `409 FINANCIAL_CATALOG_CONFLICT`.
5. **`FIX-05` (`FinancialRecordService` & `BankReconciliationService`):**
   - Alinhado 100% com `payables`, `receivables`, `payments`, `payment_reversals`, `receipts` e `receipt_reversals` (`V1`, `V5`, `V6`), respeitando as triggers `payables_projection_guard` e `receivables_projection_guard` (`pg_trigger_depth()`) e `payments_refresh_projection` / `receipts_refresh_projection`.
   - Conciliação bancária (`EPIC-05`) alinhada 100% com `bank_imports`, `bank_transactions`, `bank_reconciliation_groups`, `bank_reconciliation_items` e `bank_reconciliation_reversals` (`V7__bank_reconciliation.sql`).
6. **`FIX-06` (Performance & Paginação Keyset):**
   - Todas as listagens paginadas aplicam o cursor decodificado por `BoundCursorCodec.decodeCursor` diretamente no `WHERE` SQL via comparação de tupla (`(name, id) > (?, ?)`, `(code, id) > (?, ?)`, `(due_date, id) > (?, ?)`, `(occurred_at, id) < (?, ?)`, `(booked_at, id) < (?, ?)`).
   - Filtro de `status` (incluindo `OVERDUE` em `America/Manaus`) movido para o SQL e `getSummary()` reescrito com agregação SQL nativa (`SUM(...) FILTER (WHERE ...)`).
7. **`FIX-07` (Hardening de Build, `Clock`, Jackson `JsonMapper` & Testes):**
   - Remoção de `spring-boot-starter-data-jpa` / `spring.jpa.*`, adição de `@Bean Clock`, substituição de API depreciada do Jackson por `JsonMapper.builder()` (zero warnings em `-Xlint:deprecation`), `HexagonalArchitectureTest`, `IdempotencyExecutorTest`, `BankStatementParserTest` e `PostgreSqlSchemaAndRlsIntegrationTest` (Testcontainers PostgreSQL 16).

---

## 2. Próximo Passo Imediato (`EPIC-06` — Tasks `CLZ-018` e `CLZ-019`)

- **`CLZ-018`:** Implementar o Workspace Financeiro Completo em React 19 (`frontend/src/`) consumindo `/api/v1/financial-parties`, `/api/v1/financial-categories`, `/api/v1/cost-centers`, `/api/v1/financial-accounts`, `/api/v1/financial-records`, `/api/v1/financial-summary` e `/api/v1/financial-events`.
- **`CLZ-019`:** Implementar o Workspace de Conciliação Bancária em React 19 consumindo `/api/v1/bank-accounts`, `/api/v1/bank-statement-imports`, `/api/v1/bank-transactions` e `/api/v1/reconciliations/*`.
