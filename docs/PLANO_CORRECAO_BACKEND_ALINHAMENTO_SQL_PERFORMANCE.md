# Plano de Correção Arquitetural, Alinhamento SQL e Hardening de Performance (Épicos 02 a 04)

**Status:** `PRONTO PARA EXECUÇÃO`  
**Autor:** Tech Lead Backend Java  
**Público-Alvo:** Agente Executor de Engenharia (Java 21 / Spring Boot 3.3 / PostgreSQL 16 RLS)  
**Regra Inegociável de Alinhamento:** **NÃO** alterar a estrutura das tabelas existentes nas migrations `V1` a `V8` para criar tabelas paralelas (`parties`, `financial_records`, `financial_settlements`, `financial_events`). Todas as queries `JdbcTemplate` em Java **devem ser ajustadas para bater 100% com o schema PostgreSQL existente (`V1` a `V8`)**, preservando todas as triggers de invariantes, projeções (`app.refresh_payable_projection` / `app.refresh_receivable_projection`), policies RLS e constraints.

---

## Sumário Executivo das Frentes de Correção

| ID | Frente Técnica | Severidade | Arquivos Java Afetados | Migrations de Referência |
| :--- | :--- | :---: | :--- | :--- |
| **FIX-01** | Alinhamento SQL de `IdempotencyExecutor` + Regex `Idempotency-Key` + Header `Idempotency-Replayed` | **P0 (Bloqueante)** | `IdempotencyExecutor.java`, `FinancialCatalogsController.java`, `FinancialRecordsController.java` | `V3__financial_api_foundation.sql` |
| **FIX-02** | Alinhamento SQL de `AuditTrailService` com `audit_logs` | **P0 (Bloqueante)** | `AuditTrailService.java` | `V1__init.sql` (linhas 527–562) |
| **FIX-03** | Alinhamento de `IdentityAccessDirectoryService` com `app.resolve_identity_access` + Resolução Segura de `user_sessions` | **P0 (Bloqueante)** | `IdentityAccessDirectoryService.java`, `AccessRow.java`, `ClarezaAuthenticationFilter.java`, `MeController.java`, `V8__user_sessions_and_auth_hardening.sql` | `V1__init.sql`, `V2__identity_bootstrap_and_branch_rls.sql`, `V8__user_sessions_and_auth_hardening.sql` |
| **FIX-04** | Alinhamento SQL de `FinancialCatalogService` com `counterparties`, `financial_categories`, `cost_centers` e `financial_accounts` | **P0 (Bloqueante)** | `FinancialCatalogService.java`, `FinancialCatalogDomainPolicy.java`, `FinancialCatalogModels.java`, `FinancialCatalogsController.java` | `V5__financial_operations.sql`, `V6__phase_two_hardening.sql` |
| **FIX-05** | Alinhamento SQL de `FinancialRecordService` com `payables`, `receivables`, `payments`, `payment_reversals`, `receipts`, `receipt_reversals` e Triggers de Projeção | **P0 (Bloqueante)** | `FinancialRecordService.java`, `FinancialRecordDomainPolicy.java`, `FinancialRecordModels.java`, `FinancialRecordsController.java` | `V1__init.sql`, `V5__financial_operations.sql`, `V6__phase_two_hardening.sql` |
| **FIX-06** | Correções de Performance (Keyset Pagination Real no `WHERE`, Filtro `status`/`OVERDUE` em SQL, Agregação SQL em `getSummary()`, Remoção de `FOR UPDATE` em Catálogos, Eliminação de Duplo Round-Trip no `/api/v1/me`) | **P1 (Alta)** | `FinancialCatalogService.java`, `FinancialRecordService.java`, `ClarezaAuthenticationFilter.java`, `MeController.java` | Índices de `V1`, `V5` e `V6` |
| **FIX-07** | Hardening de Engenharia, Dependências Maven, `Clock` Bean, Jackson `JsonMapper` e Testes de Integração (Testcontainers + ArchUnit) | **P1 (Alta)** | `pom.xml`, `application.yml`, `ClarezaApplication.java`, `src/test/java/**` | Todas (`V1` a `V8`) |

---

## Detalhamento Técnico para Execução Passo a Passo

---

### `FIX-01` — Alinhamento SQL de `IdempotencyExecutor`, Regex Estrita e Header `Idempotency-Replayed`

#### 1. Diagnóstico do Problema Atual
Em [`IdempotencyExecutor.java`](../backend/src/main/java/br/com/mouraoclareza/shared/idempotency/IdempotencyExecutor.java#L73-L126):
- A query `SELECT` e o `INSERT` referenciam colunas inexistentes (`actor_user_id`, `idempotency_key`, `company_id`) e omitem colunas `NOT NULL` exigidas pela tabela `idempotency_records` definida em [`V3__financial_api_foundation.sql`](../backend/src/main/resources/db/migration/V3__financial_api_foundation.sql#L3-L33).
- A validação `requireValidIdempotencyKey` verifica apenas `length() < 8 || length() > 128`, sem aplicar o padrão `^[A-Za-z0-9._:-]{8,128}$` exigido em [`specs/openapi/clareza-api-v1.yaml`](../specs/openapi/clareza-api-v1.yaml#L35) e [`legacy/server/financial-records.mjs`](../legacy/server/financial-records.mjs#L16).
- Os controllers REST não retornam o cabeçalho HTTP `Idempotency-Replayed: true|false`.

#### 2. Schema Real em `V3__financial_api_foundation.sql`
```sql
CREATE TABLE idempotency_records (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL,
  user_id uuid NOT NULL,
  operation varchar(100) NOT NULL,
  key varchar(128) NOT NULL,
  request_hash char(64) NOT NULL,
  resource_type varchar(100) NOT NULL,
  resource_id uuid,
  response_status integer NOT NULL,
  response_body jsonb NOT NULL,
  created_at timestamptz(6) NOT NULL DEFAULT now(),
  expires_at timestamptz(6) NOT NULL
  -- UNIQUE (tenant_id, user_id, operation, key)
  -- CHECK (jsonb_typeof(response_body) = 'object')
  -- CHECK (expires_at > created_at)
);
```

#### 3. Especificação Exata da Correção
1. **Regex de Validação:**
   - Adicionar `private static final Pattern IDEMPOTENCY_KEY_PATTERN = Pattern.compile("^[A-Za-z0-9._:-]{8,128}$");` em `IdempotencyExecutor`.
   - Em `requireValidIdempotencyKey`, rejeitar com `DomainValidationException` (`field: "Idempotency-Key"`, `code: "INVALID_IDEMPOTENCY_KEY"`) qualquer valor nulo ou que não case com `IDEMPOTENCY_KEY_PATTERN`.
2. **Assinatura de `ResponseEnvelope<T>` e `execute(...)`:**
   - Atualizar `ResponseEnvelope<T>` para `public record ResponseEnvelope<T>(int status, String resourceType, UUID resourceId, T body) {}` (ou receber `String resourceType` no método `execute` e extrair `resourceId` do `ResponseEnvelope`).
3. **Query `SELECT` Correta em `IdempotencyExecutor`:**
   ```sql
   SELECT request_hash, response_status, response_body::text
     FROM idempotency_records
    WHERE tenant_id = ?
      AND user_id = ?
      AND operation = ?
      AND key = ?
      AND expires_at > CURRENT_TIMESTAMP
   ```
4. **Query `INSERT` Correta em `IdempotencyExecutor`:**
   - Caso exista um registro expirado com a mesma `(tenant_id, user_id, operation, key)`, removê-lo antes ou usar `DELETE FROM idempotency_records WHERE tenant_id = ? AND user_id = ? AND operation = ? AND key = ? AND expires_at <= CURRENT_TIMESTAMP`.
   - Inserir com `expires_at = CURRENT_TIMESTAMP + INTERVAL '24 hours'`:
   ```sql
   INSERT INTO idempotency_records (
       id, tenant_id, user_id, operation, key,
       request_hash, resource_type, resource_id,
       response_status, response_body, expires_at
   ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, CURRENT_TIMESTAMP + INTERVAL '24 hours')
   ```
5. **Propagação do Header `Idempotency-Replayed` nos Controllers:**
   - Em `FinancialCatalogsController` e `FinancialRecordsController` (e futuros controllers de mutação):
   ```java
   return ResponseEntity.status(result.status())
           .header("Idempotency-Replayed", Boolean.toString(result.replayed()))
           .body(result.body());
   ```

---

### `FIX-02` — Alinhamento SQL de `AuditTrailService` com `audit_logs`

#### 1. Diagnóstico do Problema Atual
Em [`AuditTrailService.java`](../backend/src/main/java/br/com/mouraoclareza/shared/audit/AuditTrailService.java#L63-L84):
- O `INSERT INTO audit_logs` usa as colunas `actor_role`, `before_state`, `after_state`, `request_id` e passa `entityId` como `String`.
- Na migration [`V1__init.sql`](../backend/src/main/resources/db/migration/V1__init.sql#L527-L557), essas colunas **não existem**: os nomes reais são `session_id`, `before_data`, `after_data`, `metadata`, `redacted_fields`, e `entity_id` é do tipo `uuid`.

#### 2. Schema Real em `V1__init.sql`
```sql
CREATE TABLE audit_logs (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL,
  company_id uuid,
  branch_id uuid,
  actor_user_id uuid,
  session_id uuid,
  action varchar(100) NOT NULL,
  entity_type varchar(100) NOT NULL,
  entity_id uuid,
  before_data jsonb,
  after_data jsonb,
  metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
  redacted_fields text[] NOT NULL DEFAULT ARRAY[]::text[],
  ip_hash varchar(128),
  user_agent varchar(500),
  integrity_hash varchar(128),
  occurred_at timestamptz(6) NOT NULL DEFAULT now()
);
```

#### 3. Especificação Exata da Correção
1. Alterar o parâmetro `entityId` de `String` para `UUID` em `recordAuditLog(...)`.
2. Armazenar `requestId` e `roleCode` dentro do objeto JSON `metadata` (`Map.of("requestId", context.requestId(), "roleCode", context.roleCode() != null ? context.roleCode() : "")`).
3. Ajustar o `INSERT` para bater 100% com `V1__init.sql`:
   ```sql
   INSERT INTO audit_logs (
       id, tenant_id, company_id, branch_id, actor_user_id, session_id,
       action, entity_type, entity_id, before_data, after_data,
       metadata, integrity_hash
   ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?)
   ```

---

### `FIX-03` — Alinhamento de `IdentityAccessDirectoryService`, `AccessRow` e `ClarezaAuthenticationFilter`

#### 1. Diagnóstico do Problema Atual
1. **Retorno de `app.resolve_identity_access`:**
   - Em [`V2__identity_bootstrap_and_branch_rls.sql`](../backend/src/main/resources/db/migration/V2__identity_bootstrap_and_branch_rls.sql#L174-L187) e [`V8__user_sessions_and_auth_hardening.sql`](../backend/src/main/resources/db/migration/V8__user_sessions_and_auth_hardening.sql#L47-L63), a função `app.resolve_identity_access` retorna **exatamente 11 colunas**:
     `tenant_id (uuid)`, `tenant_name (text)`, `user_id (uuid)`, `display_name (text)`, `email (text)`, `scope (membership_scope)`, `company_id (uuid)`, `company_name (text)`, `branch_id (uuid)`, `branch_name (text)`, `permissions (text[])`.
   - Já [`IdentityAccessDirectoryService.fetchAccessRowsByEmail`](../backend/src/main/java/br/com/mouraoclareza/iam/application/IdentityAccessDirectoryService.java#L41-L67) tenta ler 18 colunas com nomes diferentes (`user_email`, `user_name`, `user_status`, `membership_id`, `tenant_slug`, `role_code`, `tenant_wide_access`, `company_legal_name`, `company_trade_name`, `company_tax_id`, `branch_code`, `branch_tax_id`), o que lança `SQLException: The column name user_email was not found in this ResultSet`.
2. **Propagação de Escopos e Permissões (`TENANT`, `COMPANY`, `BRANCH`):**
   - No oráculo legado ([`legacy/server/auth-context.mjs`](../legacy/server/auth-context.mjs#L40-L248)):
     - As permissões vêm do array `permissions` (`text[]`) retornado pelo PostgreSQL a partir das tabelas `role_permissions` e `permissions`.
     - Quando `scope` é `TENANT` ou `COMPANY`, as permissões da empresa propagam para todas as suas filiais (`branches`), e `companyIds` contém a empresa ativa.
     - Quando o usuário possui apenas `scope = 'BRANCH'` na empresa selecionada:
       - Se não enviar `X-Clareza-Branch-Id`, deve falhar com `403 FORBIDDEN` (`"Selecione uma unidade autorizada para continuar."`).
       - `companyIds` no `AuthenticatedTenantContext` (que alimenta `app.company_ids` no RLS) **deve ser vazio (`List.of()`)** e `branchIds` deve conter as filiais autorizadas (`app.branch_ids`), exatamente como em [`legacy/server/auth-context.mjs#L234-L236`](../legacy/server/auth-context.mjs#L234-L236)! Caso contrário, colocar a empresa em `app.company_ids` para um membro `BRANCH` concederia acesso a todas as filiais da empresa via `app.can_access_company(company_id)` no PostgreSQL!
3. **Consulta a `user_sessions` sob RLS em `ClarezaAuthenticationFilter`:**
   - Em [`ClarezaAuthenticationFilter.java`](../backend/src/main/java/br/com/mouraoclareza/iam/security/ClarezaAuthenticationFilter.java#L137-L149), a query faz `SELECT u.email FROM user_sessions s JOIN users u ... WHERE s.token_hash = ? AND u.status = 'ACTIVE'`.
   - Dois erros fatais:
     1. A tabela `users` em `V1__init.sql` **não tem coluna `status`**; ela tem `is_active boolean NOT NULL` e `deleted_at timestamptz`.
     2. A tabela `user_sessions` e a tabela `users` possuem `FORCE ROW LEVEL SECURITY` exigindo `app.current_tenant_id()` e `app.current_user_id()`. No momento da autenticação do token, o filtro ainda não sabe quem é o `tenant_id`/`user_id`!
4. **Inconsistência de Propriedade em `application.yml`:**
   - [`application.yml`](../backend/src/main/resources/application.yml#L33) usa `clareza.security.allow-header-identity-fallback`, mas `ClarezaAuthenticationFilter` lê `clareza.security.allow-test-email-header`.

#### 2. Especificação Exata da Correção
1. **Função `SECURITY DEFINER` em `V8__user_sessions_and_auth_hardening.sql`:**
   - Adicionar em `V8__user_sessions_and_auth_hardening.sql` a função `app.resolve_session_token(raw_token_hash text)` com `SECURITY DEFINER SET search_path = pg_catalog, public, app` (mesmo padrão seguro de `app.resolve_identity_access`):
     ```sql
     CREATE OR REPLACE FUNCTION app.resolve_session_token(raw_token_hash text)
     RETURNS TABLE (
       session_id uuid,
       tenant_id uuid,
       user_id uuid,
       email text,
       authentication_source text
     )
     LANGUAGE sql
     STABLE
     SECURITY DEFINER
     SET search_path = pg_catalog, public, app
     AS $function$
       SELECT
         s.id AS session_id,
         s.tenant_id,
         s.user_id,
         u.email::text,
         s.authentication_source::text
       FROM public.user_sessions s
       JOIN public.users u
         ON u.tenant_id = s.tenant_id
        AND u.id = s.user_id
       JOIN public.tenants t
         ON t.id = s.tenant_id
       WHERE s.token_hash = lower(btrim(raw_token_hash))
         AND s.revoked_at IS NULL
         AND s.expires_at > CURRENT_TIMESTAMP
         AND u.is_active = true
         AND u.deleted_at IS NULL
         AND t.status = 'ACTIVE'
         AND t.deleted_at IS NULL
       LIMIT 1;
     $function$;

     REVOKE ALL ON FUNCTION app.resolve_session_token(text) FROM PUBLIC;
     GRANT EXECUTE ON FUNCTION app.resolve_session_token(text) TO clarity_app;
     ```
2. **Alinhamento de `AccessRow.java` e `IdentityAccessDirectoryService.java`:**
   - Atualizar `AccessRow` para refletir exatamente as 11 colunas retornadas por `SELECT * FROM app.resolve_identity_access(?, ?)`:
     ```java
     public record AccessRow(
         UUID tenantId,
         String tenantName,
         UUID userId,
         String displayName,
         String email,
         String scope, // "TENANT", "COMPANY", "BRANCH"
         UUID companyId,
         String companyName,
         UUID branchId,
         String branchName,
         List<String> permissions
     ) {}
     ```
   - No `RowMapper` de `fetchAccessRowsByEmail`:
     - Extrair `java.sql.Array sqlPermArray = rs.getArray("permissions");` e converter `String[]` para `List<String>` ordenada e sem duplicatas (ou usar fallback de `RolePermissionsCatalog` apenas se o array vier vazio em testes unitários).
   - Em `summarizeIdentityAccess` e `toAuthenticatedContext`:
     - Preservar a regra exata de [`legacy/server/auth-context.mjs`](../legacy/server/auth-context.mjs#L183-L247):
       - `boolean hasCompanyAccess = applicableRows.stream().anyMatch(r -> "TENANT".equals(r.scope()) || "COMPANY".equals(r.scope()));`
       - `List<UUID> allowedCompanyIds = hasCompanyAccess ? List.of(activeCompanyId) : List.of();`
       - `List<UUID> allowedBranchIds = companyRows.stream().filter(r -> "BRANCH".equals(r.scope()) && r.branchId() != null).map(AccessRow::branchId).distinct().toList();`
       - Se o usuário tiver apenas escopo `BRANCH` na empresa ativa e `selectedBranchId == null` (em rotas de domínio que exigem contexto), lançar `AuthorizationException("FORBIDDEN", "Selecione uma unidade autorizada para continuar.")`.
3. **Eliminação do Duplo Round-Trip no `GET /api/v1/me`:**
   - No `ClarezaAuthenticationFilter`, armazenar o `IdentitySummary` resolvido num atributo da request (`request.setAttribute("clareza.identitySummary", summary)`).
   - No `MeController`, reutilizar o `IdentitySummary` já resolvido pelo filtro quando presente, evitando uma segunda chamada a `app.resolve_identity_access`.
4. **Unificar a Propriedade de Header de Identidade em `application.yml` e `ClarezaAuthenticationFilter`:**
   - Aceitar tanto `X-Clareza-Verified-User-Email` quanto `oai-authenticated-user-email` quando `clareza.security.allow-header-identity-fallback` (ou `clareza.security.allow-test-email-header`) estiver habilitado.

---

### `FIX-04` — Alinhamento SQL de `FinancialCatalogService` com `V5__financial_operations.sql` e `V6__phase_two_hardening.sql`

#### 1. Diagnóstico do Problema Atual
Em [`FinancialCatalogService.java`](../backend/src/main/java/br/com/mouraoclareza/financial/application/FinancialCatalogService.java):
- Usa a tabela inexistente `parties` em vez de `counterparties`.
- Usa colunas inexistentes (`legal_name`, `trade_name`, `document_type`, `document_number`, `party_role`, `email`, `status`) em vez das colunas reais de `counterparties` (`code`, `name`, `tax_id`, `kind`, `is_active`, `created_by_id`, `created_at`, `updated_at`, `deleted_at`).
- Em `financial_categories`, usa `kind` (`REVENUE`/`EXPENSE`) e `status` (`ACTIVE`) em vez das colunas reais (`direction` do tipo enum `financial_direction`: `'PAYABLE'`, `'RECEIVABLE'`, `'BOTH'`; `is_active boolean`; `branch_id uuid`; `created_by_id uuid NOT NULL`).
- Em `cost_centers`, usa `status` (`ACTIVE`) e omite `created_by_id` (`NOT NULL`).
- Não expõe `financial_accounts` (tabela criada em `V5` e obrigatória para baixas financeiras em `payments`/`receipts` e para conciliação bancária na Fase 3 / `EPIC-05`).

#### 2. Mapeamento Exato das Tabelas de Catálogo (`V5__financial_operations.sql`)

| Recurso | Tabela Real (`V5`) | Colunas Reais | Constraints / Regras de Escopo (`V6`) |
| :--- | :--- | :--- | :--- |
| **Participantes (Counterparties)** | `counterparties` | `id`, `tenant_id`, `company_id`, `branch_id`, `code`, `name`, `tax_id`, `kind` (`'SUPPLIER'\|'CUSTOMER'\|'BOTH'`), `is_active`, `created_by_id`, `created_at`, `updated_at`, `deleted_at` | `UNIQUE (tenant_id, company_id, code)` |
| **Categorias Financeiras** | `financial_categories` | `id`, `tenant_id`, `company_id`, `branch_id`, `code`, `name`, `direction` (`'PAYABLE'\|'RECEIVABLE'\|'BOTH'`), `is_active`, `created_by_id`, `created_at`, `updated_at`, `deleted_at` | `UNIQUE (tenant_id, company_id, code)` |
| **Centros de Custo** | `cost_centers` | `id`, `tenant_id`, `company_id`, `branch_id`, `code`, `name`, `is_active`, `created_by_id`, `created_at`, `updated_at`, `deleted_at` | `UNIQUE (tenant_id, company_id, code)` |
| **Contas Financeiras** | `financial_accounts` | `id`, `tenant_id`, `company_id`, `branch_id`, `code`, `name`, `type` (`'CASH'\|'CHECKING'\|'SAVINGS'\|'PAYMENT'\|'OTHER'`), `currency_code`, `is_active`, `created_by_id`, `created_at`, `updated_at`, `deleted_at` | `UNIQUE (tenant_id, company_id, code)` |

#### 3. Especificação Exata da Correção
1. **Queries de Inserção (`INSERT`):**
   - Sempre incluir `created_by_id = ctx.userId()`.
   - Usar cast explícito dos enums PostgreSQL nos binds SQL: `?::counterparty_kind`, `?::financial_direction`, `?::financial_account_type`.
   - Tratar `DuplicateKeyException` (violação das constraints `*_tenant_company_code_key`) convertendo para `ResourceConflictException("FINANCIAL_CATALOG_CONFLICT", "Já existe um cadastro financeiro com este código.")` (HTTP `409`), mantendo paridade com [`legacy/server/financial-catalogs.mjs#L88-L94`](../legacy/server/financial-catalogs.mjs#L88-L94).
2. **Compatibilidade de Contrato REST:**
   - Manter compatibilidade nos DTOs (`PartyView`, `CreatePartyRequest`, `FinancialCategoryView`, `CreateCategoryRequest`) mapeando de forma transparente:
     - Se `CreatePartyRequest` fornecer `code`, usá-lo; se não fornecer `code`, gerar código determinístico a partir de `documentNumber` ou `legalName` (ex.: `PTY-<digits>` ou slug), mapeando `name = legalName`, `tax_id = digits`, e `kind`: `CUSTOMER -> CUSTOMER`, `SUPPLIER -> SUPPLIER`, `BOTH/OTHER -> BOTH`.
     - Em `FinancialCategoryDomainPolicy`, aceitar tanto `REVENUE`/`EXPENSE` (convertendo `EXPENSE -> PAYABLE`, `REVENUE -> RECEIVABLE`) quanto `PAYABLE`/`RECEIVABLE`/`BOTH`, gravando no banco com `?::financial_direction`.
3. **Filtro por Filial e `deleted_at IS NULL` nas Listagens de Catálogo:**
   - Todas as queries de leitura de catálogos devem filtrar `WHERE tenant_id = ? AND company_id = ? AND deleted_at IS NULL AND is_active = true` e, quando `ctx.activeBranchId() != null`, permitir registros da filial ativa **ou** catálogos compartilhados da empresa (`AND (branch_id IS NULL OR branch_id = ?)`), alinhado com a policy `app.can_access_catalog(company_id, branch_id)` de `V6`.

---

### `FIX-05` — Alinhamento SQL de `FinancialRecordService` com `payables`, `receivables`, `payments` e `receipts` (`V1`, `V5`, `V6`)

#### 1. Diagnóstico do Problema Atual
Em [`FinancialRecordService.java`](../backend/src/main/java/br/com/mouraoclareza/financial/application/FinancialRecordService.java):
- Tenta consultar e inserir em `financial_records`, `financial_settlements` e `financial_events`.
- No banco de dados real (`V1`, `V5`, `V6`):
  - Títulos a pagar ficam na tabela **`payables`** e títulos a receber na tabela **`receivables`**.
  - Eventos de baixa de contas a pagar ficam em **`payments`** (e estornos em **`payment_reversals`**).
  - Eventos de baixa de contas a receber ficam em **`receipts`** (e estornos em **`receipt_reversals`**).
- **Regra Crítica de Trigger em `V6__phase_two_hardening.sql` (linhas 671–720):**
  - As triggers `payables_projection_guard` e `receivables_projection_guard` **bloqueiam qualquer `UPDATE` direto** nas colunas `amount`, `outstanding_amount`, `status` e `paid_at`/`received_at` quando `pg_trigger_depth() <= 1` (`RAISE EXCEPTION 'financial projections can only change through append-only events'`).
  - Isso significa que **na baixa (`settleRecord`)**, o serviço Java **NÃO PODE** executar `UPDATE payables SET outstanding_amount = ..., status = ...`! Basta fazer `INSERT INTO payments (...)` (ou `INSERT INTO receipts (...)`), e a trigger `payments_refresh_projection` / `receipts_refresh_projection` do próprio PostgreSQL (`app.refresh_payable_projection` / `app.refresh_receivable_projection`) recalcula e atualiza atomicamente `outstanding_amount`, `status` (`OPEN`, `PARTIAL`, `SETTLED`) e `paid_at`/`received_at`!

#### 2. Mapeamento Exato das Tabelas Financeiras (`V1`, `V5`, `V6`)

1. **`payables` / `receivables`:**
   - Colunas: `id`, `tenant_id`, `company_id`, `branch_id`, `counterparty_id`, `category_id`, `cost_center_id`, `counterparty_name`, `description`, `document_number`, `issue_date`, `due_date`, `amount` (`numeric(19,4)`), `outstanding_amount` (`numeric(19,4)`), `currency_code` (`char(3)`), `status` (`financial_status`: `'OPEN'`, `'PARTIAL'`, `'SETTLED'`, `'OVERDUE'`, `'CANCELLED'`), `paid_at` (em `payables`) / `received_at` (em `receivables`), `created_by_id`, `updated_by_id`, `created_at`, `updated_at`, `deleted_at`.
2. **`payments` / `receipts`:**
   - Colunas: `id`, `tenant_id`, `company_id`, `branch_id`, `payable_id` (ou `receivable_id`), `financial_account_id`, `principal_amount`, `interest_amount`, `fine_amount`, `discount_amount`, `cash_amount`, `occurred_at` (`timestamptz`), `note`, `created_by_id`, `created_at`.
   - Invariantes em `V5`/`V6`:
     - `principal_amount > 0`
     - `cash_amount = principal_amount + interest_amount + fine_amount - discount_amount`
     - `occurred_at <= clock_timestamp()`
     - `financial_account_id` deve pertencer à mesma `(tenant_id, company_id)`, estar ativa e ter `branch_id IS NULL OR branch_id = target_branch`.

#### 3. Especificação Exata da Correção em `FinancialRecordService`
1. **Unificação de Leitura (`payables` + `receivables`):**
   - Criar uma CTE/Subquery unificada (`UNION ALL`) para consultar títulos em `payables` (`'PAYABLE' AS record_type`) e `receivables` (`'RECEIVABLE' AS record_type`) fazendo `LEFT JOIN counterparties`, `LEFT JOIN financial_categories` e `LEFT JOIN cost_centers`:
     ```sql
     WITH unified_records AS (
       SELECT
         p.id, p.tenant_id, p.company_id, p.branch_id,
         p.counterparty_id AS party_id, p.counterparty_name AS party_name,
         p.category_id, p.cost_center_id,
         'PAYABLE'::text AS record_type,
         p.description, p.document_number,
         p.issue_date, p.due_date,
         p.amount, p.outstanding_amount,
         (p.amount - p.outstanding_amount) AS settled_amount,
         p.status::text AS persisted_status,
         p.paid_at AS settled_at,
         p.created_at
       FROM payables p
       WHERE p.deleted_at IS NULL
       UNION ALL
       SELECT
         r.id, r.tenant_id, r.company_id, r.branch_id,
         r.counterparty_id AS party_id, r.counterparty_name AS party_name,
         r.category_id, r.cost_center_id,
         'RECEIVABLE'::text AS record_type,
         r.description, r.document_number,
         r.issue_date, r.due_date,
         r.amount, r.outstanding_amount,
         (r.amount - r.outstanding_amount) AS settled_amount,
         r.status::text AS persisted_status,
         r.received_at AS settled_at,
         r.created_at
       FROM receivables r
       WHERE r.deleted_at IS NULL
     )
     ```
   - Mapear `PARTIAL` do banco para `PARTIALLY_SETTLED` (ou aceitar ambos em `FinancialRecordDomainPolicy` para 100% de compatibilidade com os testes unitários e com o enum `financial_status` do PostgreSQL).
2. **Criação de Título (`createRecord`):**
   - Buscar o `name` do participante em `counterparties` (caso `partyId` seja informado) para preencher `counterparty_name` (`NOT NULL`).
   - Se `recordType == "PAYABLE"`, inserir em `payables` com `amount = totalAmount`, `outstanding_amount = totalAmount`, `status = 'OPEN'::financial_status`, `created_by_id = ctx.userId()`.
   - Se `recordType == "RECEIVABLE"`, inserir em `receivables` com os mesmos campos.
   - As triggers `payables_catalog_scope_guard` e `receivables_catalog_scope_guard` de `V6` já validam automaticamente no PostgreSQL se `counterparty_id`, `category_id` e `cost_center_id` pertencem à mesma filial e direção!
3. **Baixa de Título (`settleRecord`):**
   - Resolver `financial_account_id`: se o request informar uma conta financeira, usá-la; caso não informe, buscar a conta padrão ativa da empresa/filial em `financial_accounts` (ou criar uma conta caixa padrão `'DEFAULT-CASH'` para a empresa caso ainda não exista nenhuma no contexto).
   - Inserir o evento em `payments` (para `PAYABLE`) ou `receipts` (para `RECEIVABLE`) com `principal_amount = settlementAmount`, `interest_amount = 0`, `fine_amount = 0`, `discount_amount = 0`, `cash_amount = settlementAmount`, `occurred_at = CURRENT_TIMESTAMP`.
   - **Não fazer `UPDATE` manual em `payables`/`receivables`** após o `INSERT`: a trigger `AFTER INSERT` (`payments_refresh_projection` / `receipts_refresh_projection`) já atualiza `outstanding_amount`, `status` e `paid_at`/`received_at`!
4. **Listagem de Eventos Financeiros (`listEvents`):**
   - Consultar via `UNION ALL` as tabelas append-only reais: `payments`, `payment_reversals`, `receipts` e `receipt_reversals`, ordenando por `occurred_at DESC, id DESC`.

---

### `FIX-06` — Correções de Performance e Paginação Keyset

#### 1. Aplicação Real do Cursor Decodificado (`BoundCursorCodec`) no `WHERE` SQL
Em todos os métodos paginados (`listParties`, `listCategories`, `listCostCenters`, `listRecords`, `listEvents`), usar o `Map<String, Object> cursorPos = BoundCursorCodec.decodeCursor(cursor, scope)` na cláusula `WHERE` usando comparação de tupla (*row-value comparison*) do PostgreSQL:

- **Para `listParties` (`ORDER BY name ASC, id ASC`):**
  ```sql
  AND (?::text IS NULL OR (name, id) > (?::text, ?::uuid))
  ```
- **Para `listCategories` e `listCostCenters` (`ORDER BY code ASC, id ASC`):**
  ```sql
  AND (?::text IS NULL OR (code, id) > (?::text, ?::uuid))
  ```
- **Para `listRecords` (`ORDER BY due_date ASC, id ASC`):**
  ```sql
  AND (?::date IS NULL OR (due_date, id) > (?::date, ?::uuid))
  ```
- **Para `listEvents` (`ORDER BY occurred_at DESC, id DESC`):**
  ```sql
  AND (?::timestamptz IS NULL OR (occurred_at, id) < (?::timestamptz, ?::uuid))
  ```

#### 2. Filtro de `status` (Incluindo `OVERDUE` Derivado em Tempo de Leitura) Diretamente no SQL
Remover o `.filter(...)` em memória Java de `FinancialRecordService.listRecords` e passá-lo para a cláusula `WHERE` do SQL usando a data de referência em `America/Manaus` (`manausToday`):
```sql
AND (
  ?::text IS NULL
  OR (? = 'OVERDUE' AND r.persisted_status = 'OPEN' AND r.due_date < ?::date)
  OR (? = 'OPEN' AND r.persisted_status = 'OPEN' AND r.due_date >= ?::date)
  OR (? IN ('PARTIAL', 'PARTIALLY_SETTLED') AND r.persisted_status = 'PARTIAL')
  OR (r.persisted_status = ?)
)
```
Isso garante que o `LIMIT (limit + 1)` sempre traga páginas completas e exatas direto do índice PostgreSQL.

#### 3. Reescrita de `FinancialRecordService.getSummary()` com Agregação SQL Nativa
Substituir a chamada `listRecords(null, null, null, 100)` por uma única query de agregação no PostgreSQL:
```sql
SELECT
  COALESCE(SUM(p.outstanding_amount) FILTER (WHERE p.status IN ('OPEN', 'PARTIAL')), 0.0000) AS open_payables,
  COALESCE(SUM(p.outstanding_amount) FILTER (WHERE p.status = 'OPEN' AND p.due_date < ?::date), 0.0000) AS overdue_payables,
  COALESCE(SUM(p.amount - p.outstanding_amount) FILTER (WHERE p.status IN ('PARTIAL', 'SETTLED')), 0.0000) AS settled_payables
FROM payables p
WHERE p.tenant_id = ?
  AND p.company_id = ?
  AND (?::uuid IS NULL OR p.branch_id = ?::uuid)
  AND p.deleted_at IS NULL
```
(E o equivalente para `receivables`, ou em uma única query com dois subselects). Complexidade cai de $O(N)$ na memória da JVM (com truncamento em 100 registros) para $O(1)$ de tráfego de rede e agregação exata via Index Scan no banco.

#### 4. Remoção de `FOR UPDATE` nas Tabelas de Catálogo durante Criação de Título
Em `FinancialRecordService`, remover `FOR UPDATE` das consultas em `counterparties`, `financial_categories` e `cost_centers`. A validação de escopo e existência já é garantida pelas FKs compostas e pela trigger `assert_financial_record_catalog_scope()` de `V6__phase_two_hardening.sql` sem bloquear outras transações concorrentes que usem a mesma categoria.

---

### `FIX-07` — Hardening de Engenharia de Software, `pom.xml`, `Clock` e Testes de Integração

1. **Limpeza e Adição de Dependências de Teste no [`backend/pom.xml`](../backend/pom.xml):**
   - Remover `spring-boot-starter-data-jpa` (desnecessário, pois o projeto utiliza `spring-boot-starter-jdbc` + `JdbcTemplate` puro, evitando inicialização do Hibernate).
   - Remover `spring.jpa.*` de [`backend/src/main/resources/application.yml`](../backend/src/main/resources/application.yml#L16-L23).
   - Adicionar no escopo `test` do `pom.xml`:
     - `org.testcontainers:junit-jupiter`
     - `org.testcontainers:postgresql`
     - `com.tngtech.archunit:archunit-junit5:1.3.0`
2. **Injeção de `@Bean Clock`:**
   - Declarar `@Bean public Clock applicationClock() { return Clock.systemUTC(); }` em `ClarezaApplication` (ou classe `@Configuration` em `shared`).
   - Receber `Clock` no construtor de `FinancialRecordService` (com construtor sobrecarregado ou `@Autowired`), permitindo testes de virada de dia em `America/Manaus` com `Clock.fixed(...)`.
3. **Substituição da API Depreciada do Jackson 2.17:**
   - Em `IdempotencyExecutor` e `AuditTrailService`, substituir `objectMapper.copy().configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)` por:
     ```java
     JsonMapper.builder()
             .findAndAddModules()
             .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
             .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
             .build();
     ```
   - Isso elimina 100% dos warnings de compilação `[-Xlint:deprecation]`.
4. **Criação de Testes de Integração Reais e ArchUnit (`backend/src/test/java/`):**
   - **`HexagonalArchitectureTest` (ArchUnit):** Verifica que classes em `..domain..` não dependem de `org.springframework..` ou `jakarta.servlet..`.
   - **`PostgreSqlSchemaAndRlsIntegrationTest` (Testcontainers PostgreSQL 16):**
     - Sobe PostgreSQL via Testcontainers, roda Flyway `V1` a `V8` + seeds, e testa de ponta a ponta:
       1. `IdentityAccessDirectoryService.fetchAccessRowsByEmail` contra `app.resolve_identity_access`.
       2. `IdempotencyExecutor` e `AuditTrailService` gravando em `idempotency_records` e `audit_logs`.
       3. `FinancialCatalogService` e `FinancialRecordService` criando catálogos, títulos (`payables`/`receivables`), baixas (`payments`/`receipts` acionando triggers de projeção), paginação por cursor real e `getSummary()`.

---

## Checklist de Verificação (Definition of Done para o Agente Executor)

O agente responsável por executar este plano só deverá considerar a tarefa concluída quando todos os itens abaixo forem verdadeiros:

- [x] Nenhuma query SQL em `backend/src/main/java` referencia tabelas inexistentes (`parties`, `financial_records`, `financial_settlements`, `financial_events`).
- [x] Todas as queries SQL batem 100% com as colunas de `V1__init.sql` a `V8__user_sessions_and_auth_hardening.sql`.
- [x] `BoundCursorCodec.decodeCursor` tem seu retorno aplicado na cláusula `WHERE` de todas as listagens paginadas.
- [x] `FinancialRecordService.getSummary()` calcula os somatórios via `SUM(...) FILTER (WHERE ...)` no SQL sem truncar em 100 registros.
- [x] `IdempotencyExecutor` valida `^[A-Za-z0-9._:-]{8,128}$` e os controllers enviam o header `Idempotency-Replayed`.
- [x] Compilação limpa sem warnings de depreciação (`JAVA_HOME=/opt/homebrew/opt/openjdk /opt/homebrew/bin/mvn -f backend/pom.xml test-compile`).
- [x] Todos os testes unitários e de integração passam (`JAVA_HOME=/opt/homebrew/opt/openjdk /opt/homebrew/bin/mvn -f backend/pom.xml test`).
