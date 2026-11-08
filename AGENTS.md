# AGENTS.md / CLAUDE.md — Mourão Clareza Contábil e Financeira (SDD Edition)

## Missão

Você trabalha na refatoração e evolução do **Mourão Clareza Contábil e Financeira**, um sistema financeiro, contábil e empresarial multiempresa, migrado da base prototipada (`.mjs`/Next.js/Vinext em `legacy/`) para **Java 21+ (Spring Boot 3.4, Monólito Modular Hexagonal, Flyway, PostgreSQL 16 RLS)** e **React 19 (Vite, TypeScript, TanStack Query/Router)**.

---

## Documentos Obrigatórios (Ordem de Leitura)

Antes de qualquer alteração relevante, leia:

1. `docs/PLANO_REFATORACAO_JAVA_REACT_SDD.md` (Backlog SDD detalhado: Iniciativas, Épicos, Sprints 0–12 e Tasks `CLZ-001` a `CLZ-025`)
2. `docs/ESTADO_ATUAL.md`
3. `docs/ROADMAP_MASTER.md`
4. `docs/DECISOES_ARQUITETURAIS.md`
5. `specs/openapi/clareza-api-v1.yaml` e `specs/adr/`

---

## Fluxo Obrigatório Spec-Driven Development (SDD)

Para cada Task `CLZ-XXX` ativa:

1. **Spec First (`specs/`):** Verifique ou atualize o contrato OpenAPI (`specs/openapi/clareza-api-v1.yaml`), ADR (`specs/adr/`) ou Feature BDD (`specs/features/`) antes de alterar código de produção.
2. **Consultar Oráculo Legado (`legacy/`):** Sempre confira as regras de negócio, mensagens de erro, códigos de validação e invariantes SQL equivalentes em `legacy/server/*.mjs` e `legacy/tests/*.test.mjs`. Nunca reduza uma garantia existente.
3. **Implementação Limpa (`backend/` e `frontend/`):**
   - **Domínio Java (`*.domain.*`):** Sem dependência de web/controllers; valores monetários exclusivamente via `Money` (`BigDecimal` escala `4`).
   - **Transação e RLS (`RlsTransactionExecutor`):** Toda query ou comando de tenant deve rodar sob `SET LOCAL ROLE clarity_app` e `set_config('app.*', ..., true)`.
   - **Idempotência e Auditoria (`IdempotencyExecutor` + `AuditTrailService`):** Toda escrita financeira ou bancária deve adquirir `pg_advisory_xact_lock`, verificar replay/conflito e gravar `audit_logs` na mesma transação.
4. **Revisão Adversarial e Testes:**
   - Crie testes positivos e negativos (acesso cross-tenant, cross-company, cross-branch, replay com payload divergente, valores negativos/zero/overflow, timestamps futuros).
   - Execute `mvn test` em `backend/` e `npm test` em `frontend/`.
5. **Atualização de Estado:**
   - Ao concluir uma Task/Sprint, atualize `docs/ESTADO_ATUAL.md` e faça commit atômico rastreável (`feat(CLZ-XXX): ...`).

---

## Regras de Ouro de Segurança e Finanças

- **O frontend nunca é fonte de autorização.** Headers como `x-clareza-tenant-id`, `x-clareza-user-id` e `x-clareza-actor-id` vindos do cliente são descartados.
- `x-clareza-company-id` e `x-clareza-branch-id` são validados no servidor contra as memberships ativas retornadas do PostgreSQL.
- `OVERDUE` é um estado derivado em leitura usando o dia financeiro de `America/Manaus`; nunca persista `OVERDUE` como flag estática obsoleta.
- Eventos financeiros (`payments`, `payment_reversals`, `receipts`, `receipt_reversals`, `bank_imports`, `bank_transactions`, `bank_reconciliation_groups`, `bank_reconciliation_items`, `bank_reconciliation_reversals`) são **estritamente append-only**.
- Nunca introduza dados demonstrativos disfarçados de dados operacionais.
