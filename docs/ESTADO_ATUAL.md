# ESTADO ATUAL — Mourão Clareza Contábil e Financeira

**Última atualização:** 2026-11-08  
**Repositório Alvo:** `/Users/luiz/Documents/clareza-contabil`  
**Arquitetura:** Monorepo Spec-Driven Development (`specs/`, `backend/` Java 21 + Spring Boot 3.3, `frontend/` React 19 + Vite 6 + TypeScript 5.8, `legacy/` referência congelada)

---

## 1. Resumo Executivo da Refatoração (SDD Java + React)

O projeto passou por auditoria arquitetural completa e iniciou a execução do [Plano de Refatoração Java + React (SDD)](./PLANO_REFATORACAO_JAVA_REACT_SDD.md):

1. **Preservação Integral do Legado (`legacy/` e `_legacy_reference`):**
   - Todo o código original (Node.js/Vinext/Prisma/SQL) foi preservado intacto em `legacy/` para referência de contratos e paridade de regras de negócio.
   - O bug de regressão em `legacy/tests/rendered-html.test.mjs` (divergência de título após rebranding para `"Mourão Clareza Contábil e Financeira | Central de comando"`) foi corrigido.

2. **Artefatos Spec-Driven Development (`specs/`):**
   - `specs/openapi/clareza-api-v1.yaml`: Contrato OpenAPI 3.1 completo cobrindo `/api/v1/me`, `/api/v1/financial-*`, `/api/v1/bank-*`, `/api/v1/reconciliations` e envelopes RFC 9457 (`https://clareza.example/problems/<code>`).
   - `specs/adr/ADR-001-java21-spring-modulith-hexagonal.md`: Arquitetura Hexagonal + Monólito Modular.
   - `specs/adr/ADR-002-postgresql-rls-transaction-propagation.md`: Propagação de contexto PostgreSQL RLS via `SET LOCAL ROLE clarity_app` e `SELECT set_config(..., true)`.
   - `specs/adr/ADR-003-oauth2-jwt-and-persisted-sessions.md`: Autenticação OAuth2/JWT + sessões persistidas em `user_sessions`.
   - `specs/adr/ADR-004-accounting-engine-double-entry.md`: Motor contábil de partidas dobradas na Fase 4.
   - `specs/features/financial_and_reconciliation.feature`: Cenários BDD Gherkin em `pt-BR`.

3. **Banco de Dados e Migrações Flyway (`backend/src/main/resources/db/migration/`):**
   - `V1__init.sql` a `V7__bank_reconciliation.sql`: Paridade 100% com as migrations PostgreSQL das Fases 0 a 3 (RLS, RBAC, triggers de imutabilidade em `audit_logs`, `financial_events` e `bank_reconciliation_events`, precisão `NUMERIC(19,4)`).
   - `V8__user_sessions_and_auth_hardening.sql`: Tabela `user_sessions` com RLS e sobrecarga de `app.resolve_identity_access(verified_email, auth_provider)`.

4. **Backend Java 21 + Spring Boot 3.3 (`backend/`):**
   - **Shared Kernel (`br.com.mouraoclareza.shared`):**
     - `Money`: Value Object imutável `NUMERIC(19,4)` com proibição de ponto flutuante binário.
     - `DomainIds`: Validação estrita de UUID v1–v5.
     - `AuthenticatedTenantContext`, `TenantContextHolder`, `RlsTransactionExecutor`: Isolamento multi-tenant, guards RBAC e propagação transacional para o PostgreSQL.
     - `IdempotencyExecutor`: Lock transacional `pg_advisory_xact_lock(hashtextextended(?, 0))` e verificação de hash SHA-256.
     - `AuditTrailService`: Gravação append-only em `audit_logs` com `integrity_hash` SHA-256.
     - `BoundCursorCodec`: Cursores `base64url` vinculados criptograficamente ao escopo `(resource, tenantId, companyId, branchId, order, filters)`.
     - `GlobalExceptionHandler`: Respostas padronizadas RFC 9457 (`application/problem+json`).
   - **Módulo IAM (`br.com.mouraoclareza.iam`):**
     - `IdentityAccessDirectoryService`, `RolePermissionsCatalog`, `MeController` (`GET /api/v1/me`) e `ClarezaSecurityConfig`.
   - **Regras de Domínio Financeiro e Conciliação (`br.com.mouraoclareza.financial` / `reconciliation`):**
     - `FinancialRecordDomainPolicy`: Derivação de `OVERDUE` no fuso `America/Manaus`, aritmética de liquidação parcial/integral e bloqueio de cancelamento de títulos com baixas.
     - `BankMovementHashCalculator`: Cálculo determinístico de `movement_hash` e `source_checksum` SHA-256.
   - **Testes Unitários JUnit 5 (`mvn test`):** 12/12 testes passando (`MoneyTest`, `BoundCursorCodecTest`, `TenantContextHolderTest`, `IdentityAccessDirectoryServiceTest`, `FinancialRecordDomainPolicyTest`, `BankMovementHashCalculatorTest`).

5. **Frontend React 19 + Vite 6 + TypeScript (`frontend/`):**
   - Design system Mourão Clareza (`theme.css` com paleta marinho `#112239`, dourado `#c5a059`, marfim `#f7f4ec`, tipografia `Manrope` e `Newsreader`).
   - Cliente HTTP tipado (`http-client.ts`) com suporte nativo a cabeçalhos `X-Clareza-*`, `Idempotency-Key` e erros RFC 9457.
   - `identity-client.ts` e `ApplicationContext.tsx` à prova de condição de corrida (`AbortController` + contador monotônico de requisições) e armazenamento seguro restrito a `(tenantId, companyId, branchId)`.

---

## 2. Próximos Passos no Roadmap de Execução

- **Sprint 3–4 (`EPIC-04` — Tasks `CLZ-011` a `CLZ-014`):** Completar os repositórios JDBC/JPA e controllers REST de Catálogos Financeiros (`parties`, `financial_categories`, `cost_centers`) e Títulos Financeiros (`financial_records`, `financial_settlements`, `financial_events`) com testes de integração Testcontainers PostgreSQL.
- **Sprint 5–6 (`EPIC-05` — Tasks `CLZ-015` a `CLZ-017`):** Completar os parsers streaming OFX/CSV, endpoints de `bank-accounts`, `bank-statement-imports`, `bank-Transactions` e motor de conciliação (`reconciliations`).
- **Sprint 7 (`EPIC-06` — Tasks `CLZ-018` a `CLZ-019`):** Portar as telas operacionais completas do Workspace Financeiro e de Conciliação Bancária em React 19 + TanStack Query v5.
- **Sprint 8–10 (`EPIC-07` — Tasks `CLZ-020` a `CLZ-022`):** Implementar a Fase 4 do Roadmap (Motor Contábil de Partidas Dobradas, Plano de Contas, Diário, Razão, Balancete e DRE).
