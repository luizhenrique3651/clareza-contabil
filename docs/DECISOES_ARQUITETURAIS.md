# ÍNDICE DE DECISÕES ARQUITETURAIS (ADRs) — Mourão Clareza Contábil e Financeira

> **Instrução para Agentes de IA (Antigravity / Claude Code):**  
> Este arquivo é o **Índice Roteador de ADRs**. Para economizar janela de contexto (*Progressive Disclosure*), **não** carregue todos os ADRs de uma só vez: consulte a tabela abaixo e leia apenas os arquivos `specs/adr/ADR-00X-*.md` relevantes para o módulo ou Task (`CLZ-XXX`) em execução.

---

## Tabela de Roteamento de ADRs (`specs/adr/`)

| ADR | Arquivo em `specs/adr/` | Módulos Afetados | Quando Ler (Gatilho para o Agente) | Invariante Inegociável (Resumo de 1 Linha) |
|---|---|---|---|---|
| **ADR-001** | [`ADR-001-java21-spring-modulith-hexagonal.md`](../specs/adr/ADR-001-java21-spring-modulith-hexagonal.md) | Todos (`backend`, `frontend`) | Ao criar novos pacotes, módulos, entidades ou adapters | Monólito Modular Hexagonal (`domain` puro sem Spring/JPA; `application`; `infrastructure`; `presentation`). |
| **ADR-002** | [`ADR-002-postgresql-rls-transaction-propagation.md`](../specs/adr/ADR-002-postgresql-rls-transaction-propagation.md) | `shared`, `financial`, `reconciliation`, `accounting` | Ao escrever queries SQL/JDBC/JPA ou serviços transacionais | Toda transação deve rodar em `RlsTransactionExecutor` (`SET LOCAL ROLE clarity_app` + `SELECT set_config(..., true)`). |
| **ADR-003** | [`ADR-003-oauth2-jwt-and-persisted-sessions.md`](../specs/adr/ADR-003-oauth2-jwt-and-persisted-sessions.md) | `iam`, `shared.security` | Ao alterar autenticação, filtros de segurança ou `/api/v1/me` | Sessões validadas no servidor (`user_sessions` / OAuth2 JWT) e escopo verificado via `app.resolve_identity_access`. |
| **ADR-004** | [`ADR-004-accounting-engine-double-entry.md`](../specs/adr/ADR-004-accounting-engine-double-entry.md) | `accounting`, `financial`, `reconciliation` | Ao implementar a Fase 4 (`EPIC-07` — Tasks `CLZ-020` a `CLZ-022`) | Partidas dobradas estritas ($\sum \text{Débito} = \sum \text{Crédito}$), lançamentos postados imutáveis e reversão apenas por estorno. |
| **ADR-005** | [`ADR-005-monetary-precision-and-manaus-timezone.md`](../specs/adr/ADR-005-monetary-precision-and-manaus-timezone.md) | `shared.domain`, `financial`, `reconciliation`, `accounting` | Ao manipular valores monetários, datas de vencimento ou `OVERDUE` | Proibido `float`/`double`; usar sempre `Money` (`BigDecimal` escala 4, `NUMERIC(19,4)`) e fuso `America/Manaus`. |
| **ADR-006** | [`ADR-006-idempotency-audit-and-bound-cursors.md`](../specs/adr/ADR-006-idempotency-audit-and-bound-cursors.md) | `shared`, controllers e services de negócio | Ao criar endpoints `POST` (mutações) ou `GET` paginados e erros HTTP | Exigir `IdempotencyExecutor`, gravar `AuditTrailService` (`integrity_hash`), paginar com `BoundCursorCodec` e erros RFC 9457. |
| **ADR-007** | [`ADR-007-react19-frontend-and-context-safety.md`](../specs/adr/ADR-007-react19-frontend-and-context-safety.md) | `frontend` | Ao criar telas React, hooks de API ou alterar seletor de contexto | Zero-Trust no `localStorage` (`safeNavigationStorage`), `AbortController` contra *race conditions* e paleta Mourão Clareza. |

---

## Mapeamento Rápido Task Jira (`CLZ`) $\rightarrow$ ADRs Obrigatórios

- **`CLZ-001` a `CLZ-006` (Fundação & Shared Kernel):** `ADR-001`, `ADR-002`, `ADR-005`, `ADR-006`
- **`CLZ-007` a `CLZ-010` (IAM, Sessão & Contexto React):** `ADR-002`, `ADR-003`, `ADR-006`, `ADR-007`
- **`CLZ-011` a `CLZ-014` (Núcleo Financeiro AP/AR, Baixas & Eventos):** `ADR-001`, `ADR-002`, `ADR-005`, `ADR-006`
- **`CLZ-015` a `CLZ-017` (Importação OFX/CSV & Conciliação Bancária):** `ADR-001`, `ADR-002`, `ADR-005`, `ADR-006`
- **`CLZ-018` a `CLZ-019` (Workspaces React Financeiro & Conciliação):** `ADR-005`, `ADR-006`, `ADR-007`
- **`CLZ-020` a `CLZ-022` (Motor Contábil de Partidas Dobradas — Fase 4):** `ADR-001`, `ADR-002`, `ADR-004`, `ADR-005`, `ADR-006`
- **`CLZ-023` a `CLZ-025` (Observabilidade, Hardening & Cutover):** Todos (`ADR-001` a `ADR-007`)
