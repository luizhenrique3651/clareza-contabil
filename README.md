# Mourão Clareza Contábil e Financeira
## Plataforma Corporativa Multiempresa (Java 21 + Spring Boot 3.4 & React 19 + TypeScript)

Bem-vindo ao repositório oficial refatorado do **Mourão Clareza Contábil e Financeira**, estruturado sob **Spec-Driven Development (SDD)** e **Arquitetura Hexagonal / Monólito Modular**.

---

## 1. Estrutura do Monorepo

```text
clareza-contabil/
├── specs/                        # Fonte de Verdade SDD (OpenAPI 3.1, ADRs, BDD Features)
│   ├── openapi/
│   │   └── clareza-api-v1.yaml   # Contrato formal HTTP / Problem Details (RFC 9457)
│   ├── adr/                      # Architecture Decision Records (ADR-001 a ADR-004)
│   └── features/                 # Cenários Gherkin (.feature) de segurança e domínio
├── backend/                      # Java 21+ / Spring Boot 3.4 / Maven / Flyway / PostgreSQL 16
│   ├── pom.xml
│   └── src/
│       ├── main/java/br/com/mouraoclareza/
│       │   ├── shared/           # Shared Kernel (Money, RlsTransactionExecutor, Idempotency, Audit)
│       │   ├── iam/              # Identidade, Sessões Persistidas, Diretório RBAC (/api/v1/me)
│       │   ├── finance/          # Catálogos, Títulos (Payable/Receivable), Baixas e Estornos
│       │   ├── banking/          # Importação CSV/OFX, Transações e Conciliação 1:1 / 1:N / N:1
│       │   ├── accounting/       # Motor Contábil (Partidas Dobradas, Períodos e DRE)
│       │   └── governance/       # KPIs Reais, Riscos, Planos de Ação, Pendências e Glossário
│       └── main/resources/db/migration/ # Migrations Flyway (V1__init.sql a V8__...)
├── frontend/                     # React 19 + TypeScript 5.8 + Vite 6 + Tailwind v4
│   ├── package.json
│   └── src/
│       ├── components/           # Design System Mourão Clareza (Azul-Marinho, Dourado, Marfim)
│       ├── features/             # Feature Slices desacoplados
│       └── lib/                  # Utilitários monetários e clientes tipados
├── docs/                         # Documentação operacional, Roadmap, Fases e Plano SDD
│   └── PLANO_REFATORACAO_JAVA_REACT_SDD.md
├── legacy/                       # Código-fonte legado preservado como oráculo de regressão
└── _legacy_reference -> legacy   # Alias simbólico para scripts e agentes
```

---

## 2. Garantias Não-Negociáveis de Engenharia

1. **Segurança Fail-Closed e RLS Real:**
   - Toda operação de banco de dados ocorre dentro do `RlsTransactionExecutor`, que executa `SET LOCAL ROLE clarity_app` (`NOBYPASSRLS`) e `set_config('app.*', ..., true)` com variáveis vinculadas à transação, impedindo vazamento de contexto no pool HikariCP.
2. **Precisão Monetária Estrita (`NUMERIC(19,4)`):**
   - Proibido usar `double` ou `float` para valores financeiros. Todo cálculo utiliza o Value Object imutável `Money` (`BigDecimal` com escala fixa `4`).
3. **Auditoria e Idempotência Atômicas:**
   - Mutações exigem `Idempotency-Key`, adquirem lock transacional (`pg_advisory_xact_lock(hashtextextended(..., 0))`), gravam `AuditLog` com `integrityHash` SHA-256 e persistem o resultado em `idempotency_records` na mesma transação.

---

## 3. Comandos Rápidos

### Subir Banco PostgreSQL 16 Local
```bash
docker compose up -d
```

### Backend (Java / Maven)
```bash
cd backend
mvn test
mvn verify
```

### Frontend (React 19 / Vite)
```bash
cd frontend
npm install
npm run typecheck
npm test
npm run build
```

---

## 4. Guia para Agentes Autônomos (Antigravity / Claude Code)

Leia obrigatoriamente `AGENTS.md` (ou `CLAUDE.md`) e `docs/PLANO_REFATORACAO_JAVA_REACT_SDD.md` antes de iniciar qualquer Task.
