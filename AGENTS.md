# AGENTS.md — Mourão Clareza Contábil e Financeira (SDD Edition)

## Missão

Você trabalha na refatoração e evolução do **Mourão Clareza Contábil e Financeira**, um sistema financeiro, contábil e empresarial multiempresa, migrado da base prototipada (`.mjs`/Next.js/Vinext congelada em `legacy/`) para **Java 21 (Spring Boot 3.3, Monólito Modular Hexagonal, Flyway, PostgreSQL 16/17 RLS)** e **React 19 (Vite 6, TypeScript, TanStack Query)**.

---

## Documentos Obrigatórios (Ordem de Leitura com *Progressive Disclosure*)

Para otimizar a janela de contexto, leia nesta ordem:

1. [`docs/ESTADO_ATUAL.md`](./docs/ESTADO_ATUAL.md) — estado atual do repositório e o que já está implementado.
2. [`docs/ROADMAP_MASTER.md`](./docs/ROADMAP_MASTER.md) — identifica qual Épico/Sprint e quais Tasks (`CLZ-XXX`) estão `ATIVA`.
3. [`docs/DECISOES_ARQUITETURAIS.md`](./docs/DECISOES_ARQUITETURAIS.md) — **Índice Roteador de ADRs**: consulte a tabela e abra **apenas** os arquivos [`specs/adr/ADR-00X-*.md`](./specs/adr) exigidos pela Task ativa.
4. [`docs/PROTOCOLO_DE_FASES.md`](./docs/PROTOCOLO_DE_FASES.md) — protocolo de testes e gates.
5. [`docs/PLANO_REFATORACAO_JAVA_REACT_SDD.md`](./docs/PLANO_REFATORACAO_JAVA_REACT_SDD.md) — especificação detalhada de cada Épico e Task `CLZ-001` a `CLZ-025`.

---

## Fluxo Obrigatório Spec-Driven Development (SDD)

Para cada Task `CLZ-XXX` ativa:

1. **Spec First (`specs/`):** Verifique o contrato em [`specs/openapi/clareza-api-v1.yaml`](./specs/openapi/clareza-api-v1.yaml), os ADRs aplicáveis em [`specs/adr/`](./specs/adr) e as features BDD em [`specs/features/`](./specs/features).
2. **Consultar Oráculo Legado (`legacy/`):** Sempre confira as regras de negócio, mensagens de erro, códigos de validação e invariantes SQL equivalentes em `legacy/server/*.mjs` e `legacy/tests/*.test.mjs`. Nunca reduza uma garantia existente.
3. **Implementação Limpa (`backend/` e `frontend/`):**
   - **Domínio Java (`*.domain.*`):** Puro, sem dependência de controllers; valores monetários exclusivamente via [`Money`](./backend/src/main/java/br/com/mouraoclareza/shared/domain/Money.java) (`BigDecimal` escala `4`, `ADR-005`).
   - **Transação e RLS (`RlsTransactionExecutor`):** Toda query ou comando de negócio deve rodar sob `SET LOCAL ROLE clarity_app` e `set_config('app.*', ..., true)` (`ADR-002`).
   - **Idempotência, Auditoria e Cursores (`ADR-006`):** Toda escrita financeira ou bancária deve usar `IdempotencyExecutor` (`pg_advisory_xact_lock`) e `AuditTrailService` (`audit_logs` com `integrity_hash`). Listagens paginadas usam `BoundCursorCodec`.
4. **Revisão Adversarial e Testes:**
   - Crie testes positivos e negativos (acesso cross-tenant, cross-company, cross-branch, replay com payload divergente, valores negativos/zero/overflow).
   - Execute `JAVA_HOME=/opt/homebrew/opt/openjdk /opt/homebrew/bin/mvn -f backend/pom.xml test` em `backend/`.
5. **Atualização de Estado:**
   - Ao concluir uma Task/Sprint, atualize [`docs/ESTADO_ATUAL.md`](./docs/ESTADO_ATUAL.md) e [`docs/ROADMAP_MASTER.md`](./docs/ROADMAP_MASTER.md) e faça commit atômico rastreável.
