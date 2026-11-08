# ESTADO ATUAL — Mourão Clareza Contábil e Financeira

**Última atualização:** 2026-11-08  
**Repositório Oficial:** `/Users/luiz/Documents/clareza-contabil`  
**Épico Ativo:** `EPIC-05` (Sprints 5–6 — Importação Bancária OFX/CSV & Conciliação 1:1)  
**Task Ativa:** `CLZ-015` (Parsers determinísticos OFX 1.x/2.x e CSV + `BankMovementHashCalculator`)

---

## 1. O que já está concluído e validado (`CLZ-001` a `CLZ-014`)

1. **Fundação Monorepo SDD & Migrações Flyway (`EPIC-01` — `CLZ-001` a `CLZ-003`):**
   - Contrato OpenAPI 3.1 ([`specs/openapi/clareza-api-v1.yaml`](../specs/openapi/clareza-api-v1.yaml)), 7 ADRs individuais ([`specs/adr/ADR-001` a `ADR-007`](../specs/adr)) com índice roteador em [`docs/DECISOES_ARQUITETURAIS.md`](./DECISOES_ARQUITETURAIS.md) e cenários Gherkin ([`specs/features/financial_and_reconciliation.feature`](../specs/features/financial_and_reconciliation.feature)).
   - Migrações Flyway `V1__init.sql` a `V8__user_sessions_and_auth_hardening.sql` em `backend/src/main/resources/db/migration/`.

2. **Shared Kernel de Segurança e Finanças (`EPIC-02` — `CLZ-004` a `CLZ-006`):**
   - `Money` (`BigDecimal` escala 4, `NUMERIC(19,4)`), `DomainIds`, `AuthenticatedTenantContext`, `TenantContextHolder`, `RlsTransactionExecutor` (`SET LOCAL ROLE clarity_app` + `set_config`), `IdempotencyExecutor` (`pg_advisory_xact_lock`), `AuditTrailService` (`integrity_hash` SHA-256), `BoundCursorCodec` e `GlobalExceptionHandler` (RFC 9457).

3. **IAM, Sessão Verificada e Seletor de Contexto React (`EPIC-03` — `CLZ-007` a `CLZ-010`):**
   - `IdentityAccessDirectoryService`, `RolePermissionsCatalog`, `MeController` (`GET /api/v1/me`), `ClarezaAuthenticationFilter` e `ClarezaSecurityConfig`.
   - Frontend React 19 + Vite 6 com `http-client.ts`, `identity-client.ts`, `safeNavigationStorage` e `ApplicationContext.tsx` protegido contra *race conditions*.

4. **Núcleo Operacional Financeiro Completo (`EPIC-04` — `CLZ-011` a `CLZ-014`):**
   - Catálogos Financeiros (`FinancialCatalogDomainPolicy`, `FinancialCatalogService`, `FinancialCatalogsController` para `/api/v1/financial-parties`, `/api/v1/financial-categories`, `/api/v1/cost-centers`).
   - Títulos Financeiros AP/AR, Baixas Parciais/Integrais, Cancelamento, Sumário Executivo e Eventos Imutáveis (`FinancialRecordDomainPolicy`, `FinancialRecordService`, `FinancialRecordsController` para `/api/v1/financial-records`, `/api/v1/financial-summary`, `/api/v1/financial-events`).

---

## 2. Próximo Passo Imediato (`EPIC-05` — Tasks `CLZ-015` a `CLZ-017`)

- **`CLZ-015`:** Implementar o parser determinístico de extratos OFX 1.x/2.x e CSV (`BankStatementParser.java`) integrando com `BankMovementHashCalculator.java`.
- **`CLZ-016`:** Implementar o serviço e controller de Contas Bancárias (`bank-accounts`), Importação Idempotente (`bank-statement-imports`) e Listagem de Movimentos (`bank-transactions`).
- **`CLZ-017`:** Implementar o motor de sugestões determinísticas (score 0–100), conciliação `1:1` (`reconciliations/match`) e desfazer conciliação (`reconciliations/{id}/undo`).
