# ROADMAP MASTER — Mourão Clareza Contábil e Financeira (Java 21 + React 19 SDD)

Este documento governa a ordem de execução das Sprints, Épicos e Tasks da refatoração para **Java 21 (Spring Boot 3.3) + React 19 (Vite 6 + TypeScript)**, detalhada integralmente em [`docs/PLANO_REFATORACAO_JAVA_REACT_SDD.md`](./PLANO_REFATORACAO_JAVA_REACT_SDD.md).

> **Nota sobre o legado:** A documentação e os relatórios das Fases 0 a 3 do protótipo anterior (Node.js / Vinext / Prisma) encontram-se congelados exclusivamente em [`legacy/docs/`](../legacy/docs) para fins de consulta histórica e verificação de paridade.

---

## Status Global do Roadmap SDD

| Iniciativa / Épico | Sprints | Tasks | Status | Gate de Transição |
|---|---|---|---|---|
| **INIT-01 / EPIC-01:** Fundação Monorepo, Specs & Flyway (`V1`–`V8`) | Sprint 0 | `CLZ-001`, `CLZ-002`, `CLZ-003` | `APROVADA` | `AUTO_ADVANCE: true` |
| **INIT-01 / EPIC-02:** Shared Kernel (RLS, RBAC, Idempotência, Auditoria, Cursores) | Sprint 1 | `CLZ-004`, `CLZ-005`, `CLZ-006` | `APROVADA` | `AUTO_ADVANCE: true` |
| **INIT-02 / EPIC-03:** IAM, Sessão Verificada (`user_sessions`) & Seletor de Contexto | Sprint 2 | `CLZ-007`, `CLZ-008`, `CLZ-009`, `CLZ-010` | `APROVADA` | `AUTO_ADVANCE: true` |
| **INIT-02 / EPIC-04:** Núcleo Financeiro AP/AR, Catálogos, Baixas & Eventos (Paridade Fase 2) | Sprints 3–4 | `CLZ-011`, `CLZ-012`, `CLZ-013`, `CLZ-014` | `APROVADA` | `AUTO_ADVANCE: true` |
| **INIT-02 / EPIC-05:** Importação Bancária OFX/CSV & Conciliação 1:1 (Paridade Fase 3) | Sprints 5–6 | `CLZ-015`, `CLZ-016`, `CLZ-017` | `ATIVA` | `AUTO_ADVANCE: true` |
| **INIT-03 / EPIC-06:** Frontend React 19 Completo (Design System, Workspace Financeiro & Conciliação) | Sprint 7 | `CLZ-018`, `CLZ-019` | `PENDENTE` | `AUTO_ADVANCE: true` |
| **INIT-04 / EPIC-07:** Motor Contábil de Partidas Dobradas (Fase 4 — Diário, Razão, Balancete, DRE) | Sprints 8–10 | `CLZ-020`, `CLZ-021`, `CLZ-022` | `PENDENTE` | `AUTO_ADVANCE: true` |
| **INIT-04 / EPIC-08:** Hardening, Observabilidade, Cutover & Descomissionamento do Legado | Sprints 11–12 | `CLZ-023`, `CLZ-024`, `CLZ-025` | `PENDENTE` | Requer validação final |

---

## Progresso Detalhado por Task (`CLZ-001` a `CLZ-025`)

- [x] **`CLZ-001`** — Bootstrap do Monorepo SDD (`specs/`, `backend/`, `frontend/`, `legacy/`)
- [x] **`CLZ-002`** — Portabilidade das Migrations SQL (`V1` a `V7`) + `V8__user_sessions_and_auth_hardening.sql` no Flyway
- [x] **`CLZ-003`** — Configuração HikariCP (`auto-commit: false`) e infraestrutura base Spring Boot 3.3
- [x] **`CLZ-004`** — `AuthenticatedTenantContext`, `TenantContextHolder` e `RlsTransactionExecutor` (`SET LOCAL ROLE clarity_app` + `set_config`)
- [x] **`CLZ-005`** — `IdempotencyExecutor` (`pg_advisory_xact_lock`) e `AuditTrailService` (`integrity_hash` SHA-256)
- [x] **`CLZ-006`** — Value Object `Money` (`NUMERIC(19,4)`), `DomainIds`, `BoundCursorCodec` e `GlobalExceptionHandler` (RFC 9457)
- [x] **`CLZ-007`** — `IdentityAccessDirectoryService`, `RolePermissionsCatalog` e `GET /api/v1/me`
- [x] **`CLZ-008`** — `ClarezaAuthenticationFilter` e `ClarezaSecurityConfig` (sessões `user_sessions` + header de teste isolado)
- [x] **`CLZ-009`** — Cliente HTTP tipado (`http-client.ts`), `identity-client.ts` e `safeNavigationStorage` (*Zero-Trust*)
- [x] **`CLZ-010`** — `ApplicationContext.tsx` à prova de condição de corrida (`AbortController` + sequência monotônica) e Shell Principal
- [x] **`CLZ-011`** — Catálogos Financeiros (`FinancialCatalogDomainPolicy`, `FinancialCatalogService`, `FinancialCatalogsController`)
- [x] **`CLZ-012`** — Títulos Financeiros AP/AR (`FinancialRecordDomainPolicy`, `FinancialRecordService`, `FinancialRecordsController`) e Sumário Executivo
- [x] **`CLZ-013`** — Baixas Financeiras Parciais e Integrais com lock pessimista (`SELECT ... FOR UPDATE`)
- [x] **`CLZ-014`** — Cancelamento controlado de títulos e trilha de `financial_events` imutáveis
- [ ] **`CLZ-015` (TASK ATUAL)** — Parsers determinísticos de extratos bancários OFX 1.x/2.x e CSV com `source_checksum` e `movement_hash` SHA-256
- [ ] **`CLZ-016`** — Gestão de Contas Bancárias (`bank-accounts`), Importação Idempotente (`bank-statement-imports`) e Listagem de Movimentos (`bank-transactions`)
- [ ] **`CLZ-017`** — Motor de Sugestão Determinística (score 0–100), Pareamento Atômico `1:1` (`reconciliations/match`) e Reversão (`reconciliations/{id}/undo`)
- [ ] **`CLZ-018`** — Workspace Financeiro Completo em React 19 (Listagem AP/AR, Criação, Baixa, Cancelamento e Timeline de Eventos)
- [ ] **`CLZ-019`** — Workspace de Conciliação Bancária em React 19 (Contas, Upload OFX/CSV, Sugestões e Pareamento)
- [ ] **`CLZ-020`** — Flyway `V9` e Domínio do Motor Contábil de Partidas Dobradas (Plano de Contas, Períodos e Lançamentos Contábeis)
- [ ] **`CLZ-021`** — Integração de Eventos Financeiros/Conciliação com Lançamentos Contábeis e Estornos
- [ ] **`CLZ-022`** — Relatórios Contábeis (Diário, Razão, Balancete de Verificação e DRE) no Backend e Frontend
- [ ] **`CLZ-023`** — Observabilidade OpenTelemetry, Métricas Micrometer e Health Checks Profundos
- [ ] **`CLZ-024`** — Bateria de Testes de Segurança, Isolamento Multi-Tenant e Carga
- [ ] **`CLZ-025`** — Cutover Final e Arquivamento Definitivo do Legado
