# PROTOCOLO DE FASES E SPRINTS (SDD) — Mourão Clareza Contábil e Financeira

Este documento define o protocolo de engenharia obrigatório para execução de cada Épico/Sprint do [`docs/PLANO_REFATORACAO_JAVA_REACT_SDD.md`](./PLANO_REFATORACAO_JAVA_REACT_SDD.md).

---

## 1. Ciclo Spec-Driven Development (SDD) por Épico/Sprint

1. **Verificação de Especificação (`specs/`):**
   - Conferir o contrato em [`specs/openapi/clareza-api-v1.yaml`](../specs/openapi/clareza-api-v1.yaml), os ADRs em [`specs/adr/`](../specs/adr) e os cenários Gherkin em [`specs/features/`](../specs/features).
2. **Inspeção de Paridade no Legado (`legacy/`):**
   - Consultar a regra correspondente em `legacy/server/*.mjs` e `legacy/tests/*.test.mjs` para garantir que nenhum código de erro RFC 9457 ou invariante de negócio seja perdido.
3. **Implementação Limpa (Java 21 / React 19):**
   - Implementar domínio puro sem dependências de framework, casos de uso transacionais (`RlsTransactionExecutor` + `IdempotencyExecutor` + `AuditTrailService`) e adaptadores REST/JDBC.
4. **Testes Automatizados Positivos e Negativos:**
   - Criar testes JUnit 5 cobrindo tanto o caminho feliz quanto tentativas de violação de isolamento multi-tenant, sobre-liquidação, cursores adulterados e payloads inválidos.
5. **Revisão Adversarial:**
   - Revisar a implementação procurando vazamento de contexto, corrida transacional, aritmética inexata ou falta de validação de escopo.
6. **Atualização Documental e Commit:**
   - Atualizar [`docs/ESTADO_ATUAL.md`](./ESTADO_ATUAL.md) e [`docs/ROADMAP_MASTER.md`](./ROADMAP_MASTER.md) e registrar commit semântico no Git.

---

## 2. Comandos Oficiais de Verificação

### Backend (Java 21 + Maven)
```bash
JAVA_HOME=/opt/homebrew/opt/openjdk /opt/homebrew/bin/mvn -f backend/pom.xml test
```

### Frontend (React 19 + Vite 6 + TypeScript)
```bash
npm --prefix frontend run typecheck
npm --prefix frontend run build
```

---

## 3. Critérios Bloqueantes de Gate

Nenhuma Sprint ou Épico pode ser marcada como `APROVADA` se houver:
- Falha de compilação ou teste quebrado em `backend/`;
- Uso de `float`/`double` para valores monetários;
- Endpoint de negócio que confie em `tenantId`/`companyId` enviado pelo cliente sem validação contra a sessão autenticada;
- Mutação financeira sem `Idempotency-Key`, sem transação RLS ou sem registro de auditoria.
