# DECISÕES ARQUITETURAIS — Mourão Clareza Contábil e Financeira (Java 21 + React 19)

Este documento consolida as decisões arquiteturais vigentes no monorepo refatorado (`/Users/luiz/Documents/clareza-contabil`). Os registros formais detalhados (ADRs) estão em [`specs/adr/`](../specs/adr).

---

## 1. Stack Tecnológica Oficial (`ADR-001`)

- **Backend:** Java 21 LTS + Spring Boot 3.3 + Maven (`backend/pom.xml`), estruturado como **Monólito Modular com Arquitetura Hexagonal (Ports & Adapters)** nos pacotes:
  - `br.com.mouraoclareza.shared` (Kernel Compartilhado)
  - `br.com.mouraoclareza.iam` (Identidade, Sessão e Acesso Multi-Tenant)
  - `br.com.mouraoclareza.financial` (Catálogos, Contas a Pagar/Receber, Baixas e Eventos)
  - `br.com.mouraoclareza.reconciliation` (Contas Bancárias, Importação OFX/CSV e Conciliação)
  - `br.com.mouraoclareza.accounting` (Motor Contábil de Partidas Dobradas — Fase 4)
- **Banco de Dados & Migrações:** PostgreSQL 16/17 gerenciado pelo **Flyway** (`backend/src/main/resources/db/migration/V1__init.sql` até `V8__user_sessions_and_auth_hardening.sql`).
- **Frontend:** React 19 + Vite 6 + TypeScript 5 (`frontend/`), SPA desacoplada consumindo `/api/v1/**` sob o contrato OpenAPI 3.1 ([`specs/openapi/clareza-api-v1.yaml`](../specs/openapi/clareza-api-v1.yaml)).
- **Legado Congelado:** Todo o código anterior em Node.js/Vinext/Prisma permanece somente para consulta em [`legacy/`](../legacy).

---

## 2. Isolamento Multi-Tenant e Row-Level Security (`ADR-002`)

- Toda operação de leitura ou escrita em tabelas de negócio deve executar dentro do [`RlsTransactionExecutor`](../backend/src/main/java/br/com/mouraoclareza/shared/security/RlsTransactionExecutor.java).
- Ao abrir a transação (com `spring.datasource.hikari.auto-commit=false`), o backend executa na mesma conexão física:
  1. `SET LOCAL ROLE clarity_app`
  2. `SELECT set_config('app.tenant_id', ?, true), set_config('app.user_id', ?, true), set_config('app.company_ids', ?, true), set_config('app.branch_ids', ?, true), set_config('app.tenant_wide_access', ?, true)`
- O frontend **nunca** é fonte de autorização. Os cabeçalhos `X-Clareza-Tenant-Id`, `X-Clareza-Company-Id` e `X-Clareza-Branch-Id` representam apenas a seleção operacional desejada e são rigorosamente validados contra os vínculos ativos retornados por `app.resolve_identity_access`.

---

## 3. Autenticação e Sessões Persistidas (`ADR-003`)

- A autenticação em produção opera com Spring Security 6 (`ClarezaSecurityConfig` + `ClarezaAuthenticationFilter`) validando tokens de sessão persistidos na tabela `user_sessions` (via hash SHA-256 do token em cookie `HttpOnly` `clareza_session` ou `Authorization: Bearer`) e/ou JWT OAuth2/OIDC.
- Para execução determinística de testes automatizados, a propriedade `clareza.security.allow-test-email-header` (desabilitada por padrão e habilitada apenas no perfil `test`) permite injetar `X-Clareza-Verified-User-Email`.

---

## 4. Precisão Monetária e Fuso Horário Operacional

- É **estritamente proibido** utilizar `float` ou `double` para valores financeiros.
- Todo valor monetário usa o Value Object [`Money`](../backend/src/main/java/br/com/mouraoclareza/shared/domain/Money.java) (`BigDecimal` com escala fixa `4`, `RoundingMode.UNNECESSARY`, limite `999999999999999.9999`), mapeado para `NUMERIC(19,4)` no PostgreSQL.
- O fuso horário de negócio para cálculo de vencimento (`OVERDUE`) é `America/Manaus` (`FinancialRecordDomainPolicy.MANAUS_ZONE`).

---

## 5. Idempotência, Auditoria e Paginação por Cursor

- **Idempotência:** Toda mutação financeira exige `Idempotency-Key` (8 a 128 caracteres) e adquire lock transacional `SELECT pg_advisory_xact_lock(hashtextextended(?, 0))` via [`IdempotencyExecutor`](../backend/src/main/java/br/com/mouraoclareza/shared/idempotency/IdempotencyExecutor.java).
- **Auditoria e Eventos Imutáveis:** Toda mutação grava registro em `audit_logs` com `integrity_hash` SHA-256 via [`AuditTrailService`](../backend/src/main/java/br/com/mouraoclareza/shared/audit/AuditTrailService.java), protegido por triggers PostgreSQL que impedem `UPDATE` e `DELETE`.
- **Cursores Vinculados ao Contexto:** Cursores de paginação são assinados com hash SHA-256 do escopo `(resource, tenantId, companyId, branchId, order, filters)` via [`BoundCursorCodec`](../backend/src/main/java/br/com/mouraoclareza/shared/pagination/BoundCursorCodec.java).
