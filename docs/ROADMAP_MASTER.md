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
| **INIT-02 / EPIC-04:** Núcleo Financeiro AP/AR, Catálogos, Baixas & Eventos (Paridade Fase 2) | Sprints 3–4 | `CLZ-011`, `CLZ-012`, `CLZ-013`, `CLZ-014` | `ATIVA` | `AUTO_ADVANCE: true` |
| **INIT-02 / EPIC-05:** Importação Bancária OFX/CSV & Conciliação 1:1 (Paridade Fase 3) | Sprints 5–6 | `CLZ-015`, `CLZ-016`, `CLZ-017` | `PENDENTE` | `AUTO_ADVANCE: true` |
| **INIT-03 / EPIC-06:** Frontend React 19 Completo (Design System, Workspace Financeiro & Conciliação) | Sprint 7 | `CLZ-018`, `CLZ-019` | `PENDENTE` | `AUTO_ADVANCE: true` |
| **INIT-04 / EPIC-07:** Motor Contábil de Partidas Dobradas (Fase 4 — Diário, Razão, Balancete, DRE) | Sprints 8–10 | `CLZ-020`, `CLZ-021`, `CLZ-022` | `PENDENTE` | `AUTO_ADVANCE: true` |
| **INIT-04 / EPIC-08:** Hardening, Observabilidade, Cutover & Descomissionamento do Legado | Sprints 11–12 | `CLZ-023`, `CLZ-024`, `CLZ-025` | `PENDENTE` | Requer validação final |

---

## Fase / Épico Ativo: `EPIC-04` (Sprints 3–4 — Tasks `CLZ-011` a `CLZ-014`)

### Escopo Obrigatório
1. **`CLZ-011` — Catálogos Financeiros (`parties`, `financial_categories`, `cost_centers`):**
   - Validação estrita de CPF/CNPJ, normalização de códigos, escopo por empresa/unidade, paginação determinística com `BoundCursorCodec`, idempotência (`IdempotencyExecutor`) e auditoria (`AuditTrailService`).
2. **`CLZ-012` — Títulos Financeiros (`financial_records`) e Sumário (`financial-summary`):**
   - Criação idempotente de `PAYABLE`/`RECEIVABLE` com lock `FOR UPDATE` nas referências;
   - Derivação de `OVERDUE` na leitura usando fuso `America/Manaus`;
   - Listagem paginada por cursor e KPIs agregados exatos em `NUMERIC(19,4)`.
3. **`CLZ-013` — Baixas Financeiras (`financial_settlements`) Parciais e Integrais:**
   - Transação atômica com `SELECT ... FOR UPDATE` no título;
   - Transição para `PARTIALLY_SETTLED` ou `SETTLED`;
   - Bloqueio de sobre-liquidação (`409 SETTLEMENT_AMOUNT_EXCEEDS_OPEN_BALANCE`).
4. **`CLZ-014` — Cancelamento de Títulos e Eventos Financeiros Imutáveis (`financial_events`):**
   - Cancelamento atômico bloqueado quando há baixas (`409 FINANCIAL_RECORD_HAS_SETTLEMENTS`);
   - Emissão append-only em `financial_events` e `audit_logs`.

### Critérios de Gate (`EPIC-04`)
- Compilação limpa e 100% dos testes unitários e de domínio em `backend/` (`mvn test`).
- Preservação total de RLS, RBAC, `Money` (`NUMERIC(19,4)`), idempotência e auditoria.
- Documentação atualizada em `docs/ESTADO_ATUAL.md` e relatório técnico em `docs/fases/`.
