# CLAUDE.md — Guia de Execução para Claude Code / Antigravity

Consulte integralmente [AGENTS.md](./AGENTS.md) e [docs/PLANO_REFATORACAO_JAVA_REACT_SDD.md](./docs/PLANO_REFATORACAO_JAVA_REACT_SDD.md).

## Ambiente Local Detectado (macOS Apple Silicon)

- **Java / OpenJDK:** `/opt/homebrew/opt/openjdk/bin/java` (`JAVA_HOME=/opt/homebrew/opt/openjdk`)
- **Maven:** `/opt/homebrew/bin/mvn`
- **Node.js / npm:** `/usr/local/bin/node` e `/usr/local/bin/npm`
- **Docker:** `/usr/local/bin/docker`
- **Código Legado (Oráculo de Referência):** `./legacy/` (ou `./_legacy_reference/`)

## Como Executar o Gate de Verificação

```bash
# Backend Java
JAVA_HOME=/opt/homebrew/opt/openjdk /opt/homebrew/bin/mvn -f backend/pom.xml test

# Frontend React
npm --prefix frontend run typecheck
npm --prefix frontend test
```

## Ordem de Execução do Backlog SDD

- **Sprint 0 (`CLZ-001`, `CLZ-002`):** Estrutura Monorepo SDD, isolamento do legado em `legacy/`, portabilidade das migrations `V1`–`V8` para Flyway.
- **Sprint 1 (`CLZ-003` a `CLZ-006`):** Shared Kernel Java (`Money`, Identificadores tipados, `TenantContextHolder`, `RlsTransactionExecutor`, `IdempotencyExecutor`, `AuditTrailService`, `BoundCursorCodec`, `GlobalExceptionHandler`).
- **Sprint 2 (`CLZ-007`, `CLZ-008`):** Módulo IAM (`IdentityAccessDirectoryService`, `ClarezaContextFilter`, `/api/v1/me` e sessões `user_sessions`).
- **Sprint 3 (`CLZ-009`, `CLZ-010`):** Design System Mourão Clareza no React 19 + `ApplicationContextProvider` + TanStack Query.
- **Sprint 4 (`CLZ-011`, `CLZ-012`):** Catálogos Financeiros e Títulos (`Payable` / `Receivable`).
- **Sprint 5 (`CLZ-013`, `CLZ-014`):** Baixas (`Settlement`), Estornos (`Reversal`) e Histórico (`Timeline`).
- **Sprint 6 (`CLZ-015`):** Decomposição de `financial-workspace.tsx` em Feature Slices React.
- **Sprint 7 (`CLZ-016`, `CLZ-017`):** Parsers Bancários CSV/OFX e Importação/Listagem de Movimentações em Java.
- **Sprint 8 (`CLZ-018`, `CLZ-019`):** Motor de Conciliação Bancária (`1:1`, `1:N`, `N:1`), Reversão e Mesa de Conciliação React.
- **Sprints 9–12 (`CLZ-020` a `CLZ-025`):** Motor Contábil, Dashboard Real, Governança/Riscos/Glossário e Hardening de Produção.
