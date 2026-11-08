# ADR-006: Idempotência Transacional, Trilha de Auditoria Imutável, Cursores Vinculados e RFC 9457

- **Status:** ACCEPTED
- **Data:** 2026-11-08
- **Aplica-se a:** `shared`, `iam`, `financial`, `reconciliation`, `accounting`
- **Tasks Relacionadas:** `CLZ-005`, `CLZ-006`, `CLZ-011` a `CLZ-017`, `CLZ-020` a `CLZ-022`

---

## Contexto

Mutações financeiras e contábeis estão sujeitas a retentativas de rede, cliques duplos e concorrência. Além disso, a conformidade contábil exige rastreabilidade imutável de quem executou cada alteração (`before_state` / `after_state`) e paginação segura que não permita vazar ou embaralhar cursores entre empresas ou filtros distintos.

---

## Decisão

1. **Idempotência Transacional (`IdempotencyExecutor`):**
   - Todo endpoint de mutação (`POST` de criação, baixa, cancelamento, importação bancária, conciliação e lançamento contábil) exige o cabeçalho `Idempotency-Key` (8 a 128 caracteres).
   - Dentro da transação RLS, o backend adquire `SELECT pg_advisory_xact_lock(hashtextextended(?, 0))` usando a chave composta `tenantId:userId:operation:idempotencyKey`.
   - O payload da requisição é serializado canonicamente (chaves ordenadas) e resumido via SHA-256 (`request_hash`).
   - Se a chave já existir com o mesmo `request_hash`, a resposta original armazenada em `idempotency_records` é retornada sem reexecutar o efeito colateral. Se o `request_hash` divergir, retorna `409 IDEMPOTENCY_CONFLICT`.
2. **Auditoria Append-Only com Hash de Integridade (`AuditTrailService`):**
   - Toda mutação de negócio insere atomicamente um registro em `audit_logs` contendo `before_state`, `after_state`, `actor_user_id`, `actor_role`, `request_id` e `integrity_hash` (SHA-256 canônico).
   - Triggers no PostgreSQL bloqueiam `UPDATE` e `DELETE` em `audit_logs`, `financial_events` e `bank_reconciliation_events`.
3. **Cursores Vinculados ao Contexto (`BoundCursorCodec`):**
   - Cursores de paginação codificam em `base64url` o envelope `{ v: 1, resource, scopeHash, position }`, onde `scopeHash` é o SHA-256 de `(resource, tenantId, companyId, branchId, order, filters)`.
   - Cursores adulterados ou reutilizados em outra empresa/filtro falham com `422 VALIDATION_ERROR` (`INVALID_CURSOR`).
4. **Contrato de Erros RFC 9457 (`GlobalExceptionHandler`):**
   - Todas as falhas HTTP retornam `Content-Type: application/problem+json` com `type: "https://clareza.example/problems/<codigo-kebab>"`, `title`, `status`, `detail`, `instance`, `code`, `requestId` e `errors[]` opcional.
