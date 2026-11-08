# Mourão Clareza Contábil e Financeira

> **Plataforma empresarial multiempresa e multiunidade de Gestão Financeira, Conciliação Bancária e Contabilidade por Partidas Dobradas**, projetada sob **Spec-Driven Development (SDD)** com foco em segurança *Zero-Trust*, precisão monetária exata e rastreabilidade auditável.

---

## 1. O que é o Mourão Clareza Contábil e Financeira?

O **Mourão Clareza Contábil e Financeira** é um sistema corporativo (ERP Financeiro e Contábil) construído para grupos empresariais, escritórios contábeis e operações multiunidades que exigem clareza decisória, isolamento rigoroso entre empresas e conformidade contábil.

Diferente de sistemas financeiros convencionais onde saldos são editados manualmente sem lastro, no **Clareza** toda posição financeira e contábil é consequência determinística de **eventos imutáveis e auditáveis**:

- **Governança Multiempresa e Multiunidade (*Multi-Tenant / Multi-Company / Multi-Branch*):** Um único operador autenticado (ex.: Sócio, CFO, Contador ou Analista) pode alternar com segurança entre múltiplas empresas (CNPJs) e filiais/unidades autorizadas, com isolamento garantido até a camada física do banco de dados via **PostgreSQL Row-Level Security (RLS)**.
- **Núcleo Operacional Financeiro (Contas a Pagar e a Receber):** Gestão completa de participantes (clientes/fornecedores com validação de CPF/CNPJ), categorias financeiras (receitas/despesas), centros de custo, emissão de títulos, baixas parciais e integrais, cálculo automático de vencimento (`OVERDUE`) no fuso horário operacional (`America/Manaus`), cancelamentos controlados e sumário executivo de caixa.
- **Importação de Extratos e Conciliação Bancária Determinística:** Processamento idempotente de extratos **OFX (1.x/2.x)** e **CSV** com deduplicação criptográfica (`SHA-256`) por arquivo e por linha, motor de sugestões determinísticas de pareamento (pontuação auditável de 0 a 100) e conciliação atômica `1:1` com suporte a reversão rastreável.
- **Motor Contábil de Partidas Dobradas (Fase 4):** Escrituração contábil estruturada em Plano de Contas hierárquico, Livro Diário, Livro Razão, Balancete de Verificação e Demonstração do Resultado do Exercício (DRE), garantindo a invariante fundamental $\sum \text{Débitos} = \sum \text{Créditos}$.

---

## 2. Pilares de Engenharia e Segurança

| Pilar | Implementação Técnica no Clareza |
|---|---|
| **Isolamento Multi-Tenant Físico (`ADR-002`)** | Nenhuma consulta depende apenas de filtros `WHERE` na aplicação. Toda transação executa `SET LOCAL ROLE clarity_app` e injeta o contexto autenticado via `SELECT set_config('app.*', ..., true)`, acionando políticas **Row-Level Security (RLS)** nativas no PostgreSQL. |
| **Precisão Monetária Exata (`ADR-005`)** | Uso estritamente proibido de `float` ou `double`. Todo valor monetário utiliza o Value Object imutável `Money` (`BigDecimal` com escala fixa `4`, `RoundingMode.UNNECESSARY`, mapeado para `NUMERIC(19,4)` no banco). |
| **Idempotência Transacional (`ADR-006`)** | Todas as mutações financeiras e bancárias exigem cabeçalho `Idempotency-Key`, adquirem lock transacional `pg_advisory_xact_lock(hashtextextended(?, 0))` e validam o hash SHA-256 do payload contra replays divergentes (`409 IDEMPOTENCY_CONFLICT`). |
| **Auditoria e Eventos Append-Only (`ADR-006`)** | Tabelas `audit_logs`, `financial_events` e `bank_reconciliation_events` possuem triggers no PostgreSQL que bloqueiam `UPDATE` e `DELETE`, registrando `before_state`, `after_state` e `integrity_hash` (SHA-256). |
| **Paginação por Cursores Vinculados (`ADR-006`)** | Cursores `base64url` assinados com o hash SHA-256 do escopo `(resource, tenantId, companyId, branchId, order, filters)`, impedindo reutilização ou adulteração de cursores entre empresas (`422 INVALID_CURSOR`). |
| **Segurança no Cliente (`ADR-007`)** | O frontend nunca é fonte de autorização. `localStorage` armazena exclusivamente preferências de navegação (`tenantId`, `companyId`, `branchId`), e trocas de contexto são protegidas contra *race conditions* via `AbortController`. |

---

## 3. Arquitetura e Estrutura do Monorepo (SDD)

O repositório segue **Spec-Driven Development (SDD)**, estruturado como um **Monólito Modular com Arquitetura Hexagonal** no backend e SPA reativa no frontend:

```text
clareza-contabil/
├── specs/                        # Fonte de verdade das especificações (Spec-Driven Development)
│   ├── openapi/                  # Contrato formal OpenAPI 3.1 (clareza-api-v1.yaml)
│   ├── adr/                      # Architecture Decision Records individuais (ADR-001 a ADR-007)
│   └── features/                 # Especificações comportamentais BDD / Gherkin (pt-BR)
├── backend/                      # Backend Java 21 LTS + Spring Boot 3.3 + Maven
│   ├── src/main/java/br/com/mouraoclareza/
│   │   ├── shared/               # Shared Kernel (Money, DomainIds, RLS, Idempotência, Auditoria, Cursores, RFC 9457)
│   │   ├── iam/                  # Identidade, Sessões (user_sessions), RBAC e /api/v1/me
│   │   ├── financial/            # Catálogos, Contas a Pagar/Receber, Baixas, Cancelamentos e Eventos
│   │   ├── reconciliation/       # Contas Bancárias, Importação OFX/CSV e Conciliação
│   │   └── accounting/           # Motor Contábil de Partidas Dobradas (Plano de Contas, Diário, Razão, DRE)
│   └── src/main/resources/
│       └── db/migration/         # Migrações SQL Flyway (V1__init.sql até V8__user_sessions_and_auth_hardening.sql)
├── frontend/                     # Frontend React 19 + Vite 6 + TypeScript 5 + TanStack Query
│   └── src/
│       ├── features/             # Módulos de negócio (auth, context-selector, financial, reconciliation)
│       ├── shared/api/           # Cliente HTTP tipado com suporte a RFC 9457 e cabeçalhos X-Clareza-*
│       └── styles/               # Design System Mourão Clareza (Marinho, Dourado e Marfim)
├── docs/                         # Documentação viva do projeto (Plano SDD, Roadmap Master, Índice de ADRs)
└── legacy/                       # Código legado congelado (Node.js/Vinext/Prisma) preservado como oráculo
```

---

## 4. Documentação de Engenharia e ADRs

- **[`docs/PLANO_REFATORACAO_JAVA_REACT_SDD.md`](./docs/PLANO_REFATORACAO_JAVA_REACT_SDD.md):** Plano mestre de engenharia contendo as 4 Iniciativas, 8 Épicos (`EPIC-01` a `EPIC-08`), 12 Sprints e 25 Tasks (`CLZ-001` a `CLZ-025`).
- **[`docs/ROADMAP_MASTER.md`](./docs/ROADMAP_MASTER.md):** Quadro de acompanhamento do progresso por Épico/Sprint e Task ativa.
- **[`docs/DECISOES_ARQUITETURAIS.md`](./docs/DECISOES_ARQUITETURAIS.md):** Índice Roteador para Agentes de IA apontando para os 7 ADRs em [`specs/adr/`](./specs/adr):
  - [`ADR-001`](./specs/adr/ADR-001-java21-spring-modulith-hexagonal.md) — Java 21, Spring Boot e Arquitetura Hexagonal Modular
  - [`ADR-002`](./specs/adr/ADR-002-postgresql-rls-transaction-propagation.md) — PostgreSQL RLS e Propagação Transacional de Contexto
  - [`ADR-003`](./specs/adr/ADR-003-oauth2-jwt-and-persisted-sessions.md) — OAuth2/JWT e Sessões Persistidas (`user_sessions`)
  - [`ADR-004`](./specs/adr/ADR-004-accounting-engine-double-entry.md) — Motor Contábil de Partidas Dobradas
  - [`ADR-005`](./specs/adr/ADR-005-monetary-precision-and-manaus-timezone.md) — Precisão Monetária `NUMERIC(19,4)` e Fuso `America/Manaus`
  - [`ADR-006`](./specs/adr/ADR-006-idempotency-audit-and-bound-cursors.md) — Idempotência, Auditoria Imutável, Cursores e RFC 9457
  - [`ADR-007`](./specs/adr/ADR-007-react19-frontend-and-context-safety.md) — Frontend React 19, Prevenção de Corrida e *Zero-Trust*

---

## 5. Como Executar Localmente

### Pré-requisitos
- **Java 21+** (OpenJDK) e **Maven 3.9+**
- **Node.js 22+** e **npm 10+**
- **Docker** e **Docker Compose** (para PostgreSQL 16 local)

### 1. Subir o Banco de Dados PostgreSQL
```bash
docker compose up -d postgres
```

### 2. Executar Testes e Subir o Backend (Spring Boot)
```bash
# Executar a suíte de testes JUnit 5
mvn -f backend/pom.xml test

# Iniciar a API na porta 8080 (executa as migrations Flyway V1 a V8 automaticamente)
mvn -f backend/pom.xml spring-boot:run
```

### 3. Executar o Frontend (React 19 + Vite 6)
```bash
cd frontend
npm install
npm run dev
```
Acesse a aplicação em `http://localhost:5173`.
