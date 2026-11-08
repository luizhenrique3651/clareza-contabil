# ROADMAP MASTER — Mourão Clareza Contábil e Financeira (Java 21 + React 19 SDD)

Este documento governa a ordem de execução das Sprints, Épicos e Tasks da refatoração para **Java 21 (Spring Boot 3.3) + React 19 (Vite 6 + TypeScript)**, detalhada integralmente em [`docs/PLANO_REFATORACAO_JAVA_REACT_SDD.md`](./PLANO_REFATORACAO_JAVA_REACT_SDD.md) e auditada/alinhada com o schema SQL real em [`docs/PLANO_CORRECAO_BACKEND_ALINHAMENTO_SQL_PERFORMANCE.md`](./PLANO_CORRECAO_BACKEND_ALINHAMENTO_SQL_PERFORMANCE.md).

> **Nota sobre o legado:** A documentação e os relatórios das Fases 0 a 3 do protótipo anterior (Node.js / Vinext / Prisma) encontram-se congelados exclusivamente em [`legacy/docs/`](../legacy/docs) para fins de consulta histórica e verificação de paridade.

---

## Status Global do Roadmap SDD

| Iniciativa / Épico | Sprints | Tasks | Status | Gate de Transição |
|---|---|---|---|---|
| **INIT-01 / EPIC-01:** Fundação Monorepo, Specs & Flyway (`V1`–`V8`) | Sprint 0 | `CLZ-001`, `CLZ-002`, `CLZ-003` | `APROVADA` | `AUTO_ADVANCE: true` |
| **INIT-01 / EPIC-02:** Shared Kernel (RLS, RBAC, Idempotência, Auditoria, Cursores) | Sprint 1 | `CLZ-004`, `CLZ-005`, `CLZ-006` + `FIX-01`, `FIX-02`, `FIX-07` | `APROVADA` | `AUTO_ADVANCE: true` |
| **INIT-02 / EPIC-03:** IAM, Sessão Verificada (`user_sessions`) & Seletor de Contexto | Sprint 2 | `CLZ-007`, `CLZ-008`, `CLZ-009`, `CLZ-010` + `FIX-03` | `APROVADA` | `AUTO_ADVANCE: true` |
| **INIT-02 / EPIC-04:** Núcleo Financeiro AP/AR (`payables`/`receivables`), Catálogos (`counterparties`/`financial_categories`/`cost_centers`/`financial_accounts`), Baixas (`payments`/`receipts`) & Eventos (`V1`–`V6`) | Sprints 3–4 | `CLZ-011`, `CLZ-012`, `CLZ-013`, `CLZ-014` + `FIX-04`, `FIX-05`, `FIX-06` | `APROVADA` | `AUTO_ADVANCE: true` |
| **INIT-02 / EPIC-05:** Importação Bancária OFX/CSV (`bank_imports`/`bank_transactions`) & Conciliação (`bank_reconciliation_groups`/`items`/`reversals` — `V7`) | Sprints 5–6 | `CLZ-015`, `CLZ-016`, `CLZ-017` | `APROVADA` | `AUTO_ADVANCE: true` |
| **INIT-03 / EPIC-06:** Frontend React 19 Completo (Design System, Workspace Financeiro & Conciliação) | Sprint 7 | `CLZ-018`, `CLZ-019` | `ATIVA` | `AUTO_ADVANCE: true` |
| **INIT-04 / EPIC-07:** Motor Contábil de Partidas Dobradas (Fase 4 — Diário (`journal_entries`/`journal_lines`), Razão, Balancete, DRE) | Sprints 8–10 | `CLZ-020`, `CLZ-021`, `CLZ-022` | `PENDENTE` | `AUTO_ADVANCE: true` |
| **INIT-04 / EPIC-08:** Hardening, Observabilidade, Cutover & Descomissionamento do Legado | Sprints 11–12 | `CLZ-023`, `CLZ-024`, `CLZ-025` | `PENDENTE` | Requer validação final |

---

## Progresso Detalhado por Task (`CLZ-001` a `CLZ-025` + `FIX-01` a `FIX-07`)

- [x] **`CLZ-001`** — Bootstrap do Monorepo SDD (`specs/`, `backend/`, `frontend/`, `legacy/`)
- [x] **`CLZ-002`** — Portabilidade das Migrations SQL (`V1` a `V7`) + `V8__user_sessions_and_auth_hardening.sql` (incluindo `app.resolve_session_token` `SECURITY DEFINER`) no Flyway
- [x] **`CLZ-003`** — Configuração HikariCP (`auto-commit: false`, sem JPA/Hibernate) e infraestrutura base Spring Boot 3.3 (`@Bean Clock`)
- [x] **`CLZ-004`** — `AuthenticatedTenantContext`, `TenantContextHolder` e `RlsTransactionExecutor` (`SET LOCAL ROLE clarity_app` + `set_config`)
- [x] **`CLZ-005` + `FIX-01` + `FIX-02`** — `IdempotencyExecutor` (`idempotency_records` `V3`, regex `^[A-Za-z0-9._:-]{8,128}$`, header `Idempotency-Replayed`) e `AuditTrailService` (`audit_logs` `V1`, `entity_id` UUID, `before_data`/`after_data`/`metadata`, `integrity_hash` SHA-256)
- [x] **`CLZ-006`** — Value Object `Money` (`NUMERIC(19,4)`), `DomainIds`, `BoundCursorCodec` e `GlobalExceptionHandler` (RFC 9457)
- [x] **`CLZ-007` + `FIX-03`** — `IdentityAccessDirectoryService` (alinhado com as 11 colunas de `app.resolve_identity_access` e regra RLS de escopo `BRANCH`), `RolePermissionsCatalog` e `GET /api/v1/me` (1 único round-trip via cache de request)
- [x] **`CLZ-008` + `FIX-03`** — `ClarezaAuthenticationFilter` e `ClarezaSecurityConfig` (`app.resolve_session_token` + unificação de fallback header de teste)
- [x] **`CLZ-009`** — Cliente HTTP tipado (`http-client.ts`), `identity-client.ts` e `safeNavigationStorage` (*Zero-Trust*)
- [x] **`CLZ-010`** — `ApplicationContext.tsx` à prova de condição de corrida (`AbortController` + sequência monotônica) e Shell Principal
- [x] **`CLZ-011` + `FIX-04` + `FIX-06`** — Catálogos Financeiros (`counterparties`, `financial_categories`, `cost_centers`, `financial_accounts` em `V5`/`V6`) com paginação keyset SQL real e tratamento de `409 FINANCIAL_CATALOG_CONFLICT`
- [x] **`CLZ-012` + `FIX-05` + `FIX-06`** — Títulos Financeiros AP/AR (`payables` / `receivables` em `V1`/`V5`/`V6`), filtro `OVERDUE` em SQL e `getSummary()` com agregação SQL nativa (`SUM(...) FILTER (WHERE ...)`)
- [x] **`CLZ-013` + `FIX-05`** — Baixas Financeiras Parciais e Integrais via log append-only (`payments` / `receipts`) acionando triggers `payments_refresh_projection` / `receipts_refresh_projection` do PostgreSQL
- [x] **`CLZ-014` + `FIX-05`** — Estorno de Baixas (`payment_reversals` / `receipt_reversals`), Cancelamento controlado de títulos e listagem keyset de eventos financeiros imutáveis
- [x] **`CLZ-015`** — Parsers determinísticos de extratos bancários OFX 1.x/2.x e CSV (`BankStatementParser`) com `content_hash` e `fingerprint` SHA-256 (`BankMovementHashCalculator`)
- [x] **`CLZ-016`** — Gestão de Contas Bancárias (`financial_accounts`), Importação Idempotente (`bank_imports` + `bank_transactions` em `V7`) e Listagem Keyset de Movimentos
- [x] **`CLZ-017`** — Motor de Sugestão Determinística (score 0–100), Pareamento Atômico `1:1` (`bank_reconciliation_groups` + `bank_reconciliation_items`) e Reversão Append-Only (`bank_reconciliation_reversals`)
- [ ] **`CLZ-018` (TASK ATUAL)** — Workspace Financeiro Completo em React 19 (Listagem AP/AR, Criação, Baixa, Estorno, Cancelamento e Timeline de Eventos)
- [ ] **`CLZ-019`** — Workspace de Conciliação Bancária em React 19 (Contas, Upload OFX/CSV, Sugestões e Pareamento)
- [ ] **`CLZ-020`** — Motor Contábil de Partidas Dobradas (`accounts`, `journal_entries`, `journal_lines` de `V1__init.sql` + `V9` para períodos fiscais e triggers de balanceamento)
- [ ] **`CLZ-021`** — Integração de Eventos Financeiros/Conciliação com Lançamentos Contábeis e Estornos (`reverses_entry_id`)
- [ ] **`CLZ-022`** — Relatórios Contábeis (Diário, Razão, Balancete de Verificação e DRE) no Backend e Frontend
- [ ] **`CLZ-023`** — Observabilidade OpenTelemetry, Métricas Micrometer e Health Checks Profundos
- [ ] **`CLZ-024`** — Bateria de Testes de Segurança, Isolamento Multi-Tenant e Carga
- [ ] **`CLZ-025`** — Cutover Final e Arquivamento Definitivo do Legado

---

## Regras Arquiteturais de Ouro para as Próximas Tasks (`CLZ-018` a `CLZ-025`)

1. **Alinhamento 100% com o Schema SQL de `V1` a `V8`:**
   - **Proibido inventar tabelas paralelas.** Para o Motor Contábil (`EPIC-07`), reutilizar obrigatoriamente as tabelas já criadas em `V1__init.sql`: **`accounts`** (`code, name, account_type, normal_side, is_posting, is_active, parent_id`), **`journal_entries`** (`entry_number, entry_date, description, source_type, source_id, status::journal_entry_status, posted_at, reverses_entry_id, created_by_id`) e **`journal_lines`** (`journal_entry_id, line_number, account_id, branch_id, cost_center_id, description, debit_amount, credit_amount`).
2. **Projeções Governadas por Eventos Append-Only:**
   - Respeitar sempre as triggers de guarda de projeção (`pg_trigger_depth()`) do PostgreSQL.
3. **Paginação Keyset e Agregação no Banco:**
   - Todo método paginado deve aplicar `BoundCursorCodec.decodeCursor` na cláusula `WHERE` via comparação de tupla `(col, id) > (?, ?)` ou `(col, id) < (?, ?)`, e todo relatório/sumário contábil ou financeiro deve agregar diretamente via SQL (`SUM(...) FILTER (WHERE ...)`).
