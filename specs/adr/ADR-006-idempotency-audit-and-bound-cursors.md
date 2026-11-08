# ADR-006: Idempotência Transacional, Trilha de Auditoria Imutável, Paginação Keyset (`BoundCursorCodec`) e RFC 9457

- **Status:** ACCEPTED (Atualizado após Auditoria Sênior `FIX-01`, `FIX-02`, `FIX-06`)
- **Data:** 2026-11-08
- **Aplica-se a:** `shared`, `iam`, `financial`, `reconciliation`, `accounting`
- **Tasks Relacionadas:** `CLZ-005`, `CLZ-006`, `CLZ-011` a `CLZ-017`, `CLZ-020` a `CLZ-022`

---

## Contexto

Mutações financeiras e contábeis estão sujeitas a retentativas de rede, cliques duplos e concorrência. Além disso, a conformidade contábil exige rastreabilidade imutável de quem executou cada alteração (`before_data` / `after_data`) e paginação keyset de alta performance que filtre e agregue diretamente no PostgreSQL.

---

## Decisão

1. **Idempotência Transacional (`IdempotencyExecutor` — `FIX-01`):**
   - Todo endpoint de mutação (`POST`) exige o cabeçalho `Idempotency-Key` validado contra a regex `^[A-Za-z0-9._:-]{8,128}$` (retornando `422 INVALID_IDEMPOTENCY_KEY` se ausente ou fora do padrão).
   - Dentro da transação RLS, o backend adquire `SELECT pg_advisory_xact_lock(hashtextextended(?, 0))` usando `tenantId:userId:operation:idempotencyKey`.
   - Alinhado 100% com `V3__financial_api_foundation.sql`, `idempotency_records` utiliza as colunas `(id, tenant_id, user_id, operation, key, request_hash, resource_type, resource_id, response_status, response_body, expires_at)`. Registros expirados (`expires_at <= CURRENT_TIMESTAMP`) para a mesma chave são removidos antes da verificação.
   - Todos os controllers `POST` devem devolver o header HTTP `Idempotency-Replayed: true|false`.

2. **Auditoria Append-Only com Hash de Integridade (`AuditTrailService` — `FIX-02`):**
   - Toda mutação de negócio insere atomicamente um registro em `audit_logs` alinhado com `V1__init.sql`: `(id, tenant_id, company_id, branch_id, actor_user_id, session_id, action, entity_type, entity_id [UUID], before_data, after_data, metadata, integrity_hash)`.
   - Metadados de rastreamento (`requestId`, `roleCode`, `source`) são armazenados na coluna JSONB `metadata`.

3. **Paginação Keyset Real e Agregações SQL Nativas (`BoundCursorCodec` — `FIX-06`):**
   - Cursores de paginação codificam em `base64url` o envelope `{ v: 1, resource, scopeHash, position }`, onde `scopeHash` é o SHA-256 de `(resource, tenantId, companyId, branchId, order, filters)`.
   - **Regra de Performance Inegociável:** O retorno `Map<String, Object> cursorPos = BoundCursorCodec.decodeCursor(cursor, scope)` **deve obrigatoriamente ser aplicado na cláusula `WHERE` do SQL** via comparação de tupla do PostgreSQL (ex.: `AND (?::date IS NULL OR (due_date, id) > (?::date, ?::uuid))`).
   - É **proibido** filtrar `status` (incluindo `OVERDUE` derivado em tempo de leitura) em memória Java após `LIMIT`, ou calcular sumários financeiros iterando sobre listas truncadas na JVM. Filtros de status e agregações (`SUM(...) FILTER (WHERE ...)`) devem ser executados diretamente no PostgreSQL.

4. **Contrato de Erros RFC 9457 (`GlobalExceptionHandler`):**
   - Todas as falhas HTTP retornam `Content-Type: application/problem+json` com `type: "https://clareza.example/problems/<codigo-kebab>"`, `title`, `status`, `detail`, `instance`, `code`, `requestId` e `errors[]` opcional.
