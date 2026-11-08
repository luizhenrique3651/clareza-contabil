import assert from "node:assert/strict";
import test, { after, before } from "node:test";

import pg from "pg";

const { Pool } = pg;

const ADMIN_URL = process.env.DIRECT_DATABASE_URL;
const RUNTIME_URL = process.env.DATABASE_URL;

if (!ADMIN_URL || !RUNTIME_URL) {
  throw new Error(
    "DIRECT_DATABASE_URL e DATABASE_URL são obrigatórias para os testes PostgreSQL.",
  );
}

const EXPECTED_DATABASE = decodeURIComponent(
  new URL(ADMIN_URL).pathname.replace(/^\/+/, ""),
);
if (!EXPECTED_DATABASE) {
  throw new Error("DIRECT_DATABASE_URL precisa identificar o banco de testes.");
}
const INTEGRATION_TEST_TIMEOUT_MS = Math.max(
  20_000,
  Number(process.env.INTEGRATION_TEST_TIMEOUT_MS) || 0,
);

const TENANT_A = "10000000-0000-4000-8000-000000000001";
const TENANT_B = "10000000-0000-4000-8000-000000000002";
const COMPANY_A1 = "20000000-0000-4000-8000-000000000001";
const COMPANY_B1 = "20000000-0000-4000-8000-000000000002";
const COMPANY_A2 = "20000000-0000-4000-8000-000000000003";
const BRANCH_A1_NORTH = "30000000-0000-4000-8000-000000000001";
const BRANCH_B1_NORTH = "30000000-0000-4000-8000-000000000002";
const BRANCH_A1_SOUTH = "30000000-0000-4000-8000-000000000003";
const BRANCH_A2_MAIN = "30000000-0000-4000-8000-000000000004";
const BRANCH_NONEXISTENT = "30000000-0000-4000-8000-000000000099";
const USER_A_ADMIN = "40000000-0000-4000-8000-000000000001";
const USER_B_ADMIN = "40000000-0000-4000-8000-000000000002";
const USER_A_READER = "40000000-0000-4000-8000-000000000003";
const USER_A_BRANCH_WRITER = "40000000-0000-4000-8000-000000000004";

const adminPool = new Pool({ connectionString: ADMIN_URL, max: 5 });
const runtimePool = new Pool({ connectionString: RUNTIME_URL, max: 5 });
const singleConnectionPool = new Pool({
  connectionString: RUNTIME_URL,
  max: 1,
  idleTimeoutMillis: 30_000,
});

let worker;

before(async () => {
  const version = await adminPool.query(
    "SELECT current_database() AS database, current_setting('server_version') AS version",
  );
  assert.equal(version.rows[0].database, EXPECTED_DATABASE);

  const workerUrl = new URL("../../dist/server/index.js", import.meta.url);
  workerUrl.searchParams.set("phase-zero-real-db", `${process.pid}-${Date.now()}`);
  ({ default: worker } = await import(workerUrl.href));
});

after(async () => {
  if (globalThis.clarezaPrisma) {
    await globalThis.clarezaPrisma.$disconnect();
  }
  await Promise.all([
    adminPool.end(),
    runtimePool.end(),
    singleConnectionPool.end(),
  ]);
});

function requestEnvironment() {
  return {
    ASSETS: {
      fetch: async () => new Response("Not found", { status: 404 }),
    },
  };
}

function requestContext() {
  return {
    waitUntil() {},
    passThroughOnException() {},
  };
}

async function apiRequest(
  path,
  {
    method = "GET",
    email,
    companyId,
    branchId,
    idempotencyKey,
    body,
  } = {},
) {
  const headers = new Headers();
  if (email) headers.set("oai-authenticated-user-email", email);
  if (companyId) headers.set("x-clareza-company-id", companyId);
  if (branchId) headers.set("x-clareza-branch-id", branchId);
  if (idempotencyKey) headers.set("idempotency-key", idempotencyKey);
  if (body !== undefined) headers.set("content-type", "application/json");

  const response = await worker.fetch(
    new Request(`http://localhost${path}`, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    }),
    requestEnvironment(),
    requestContext(),
  );

  return {
    response,
    body: await response.json(),
  };
}

async function withContext(
  {
    tenantId,
    userId,
    companyIds = [],
    branchIds = [],
    tenantWideAccess = false,
  },
  operation,
) {
  const client = await runtimePool.connect();
  try {
    await client.query("BEGIN");
    await client.query("SET LOCAL ROLE clarity_app");
    await client.query(
      `SELECT
         set_config('app.tenant_id', $1, true),
         set_config('app.user_id', $2, true),
         set_config('app.company_ids', $3, true),
         set_config('app.branch_ids', $4, true),
         set_config('app.tenant_wide_access', $5, true)`,
      [
        tenantId,
        userId,
        companyIds.join(","),
        branchIds.join(","),
        String(tenantWideAccess),
      ],
    );
    const result = await operation(client);
    await client.query("ROLLBACK");
    return result;
  } catch (error) {
    await client.query("ROLLBACK").catch(() => undefined);
    throw error;
  } finally {
    client.release();
  }
}

async function assertForeignKeyRejected(sql, parameters) {
  const client = await adminPool.connect();
  try {
    await client.query("BEGIN");
    await assert.rejects(
      client.query(sql, parameters),
      (error) => error?.code === "23503",
    );
  } finally {
    await client.query("ROLLBACK").catch(() => undefined);
    client.release();
  }
}

const validPayable = {
  counterpartyName: "Fornecedor de Integração (Fictício)",
  description: "Validação PostgreSQL real da Fase 0",
  documentNumber: "FASE0-001",
  issueDate: "2026-07-24",
  dueDate: "2026-08-24",
  amount: "1234.5600",
  currencyCode: "BRL",
};

test("catálogo comprova migrations, estruturas, RLS e role sem BYPASSRLS", async () => {
  const migrations = await adminPool.query(
    `SELECT migration_name
       FROM _prisma_migrations
      WHERE finished_at IS NOT NULL AND rolled_back_at IS NULL
      ORDER BY migration_name`,
  );
  assert.deepEqual(
    migrations.rows.map((row) => row.migration_name),
    [
      "0001_init",
      "0002_identity_bootstrap_and_branch_rls",
      "0003_financial_api_foundation",
      "0004_identity_branch_directory",
      "0005_financial_operations",
      "0006_phase_two_hardening",
    ],
  );

  const role = await adminPool.query(
    `SELECT rolname, rolsuper, rolcreaterole, rolcreatedb, rolcanlogin, rolbypassrls
       FROM pg_roles
      WHERE rolname = 'clarity_app'`,
  );
  assert.deepEqual(role.rows, [
    {
      rolname: "clarity_app",
      rolsuper: false,
      rolcreaterole: false,
      rolcreatedb: false,
      rolcanlogin: false,
      rolbypassrls: false,
    },
  ]);

  const auxiliarySchema = await adminPool.query(
    "SELECT count(*)::int AS count FROM pg_namespace WHERE nspname = 'app'",
  );
  assert.equal(auxiliarySchema.rows[0].count, 1);

  const enums = await adminPool.query(
    `SELECT t.typname
       FROM pg_type t
       JOIN pg_namespace n ON n.oid = t.typnamespace
      WHERE n.nspname = 'public' AND t.typtype = 'e'
      ORDER BY t.typname`,
  );
  assert.deepEqual(
    enums.rows.map((row) => row.typname),
    [
      "action_plan_status",
      "company_status",
      "counterparty_kind",
      "financial_account_type",
      "financial_direction",
      "financial_status",
      "glossary_category",
      "journal_entry_status",
      "membership_scope",
      "priority",
      "risk_level",
      "risk_status",
      "tenant_status",
      "work_status",
    ],
  );

  const indexes = await adminPool.query(
    `SELECT indexname
       FROM pg_indexes
      WHERE schemaname = 'public'
        AND indexname = ANY($1::text[])`,
    [
      [
        "memberships_tenant_scope_uq",
        "memberships_company_scope_uq",
        "memberships_branch_scope_uq",
        "payables_status_due_idx",
        "receivables_status_due_idx",
        "audit_logs_entity_time_idx",
        "idempotency_records_expiry_idx",
      ],
    ],
  );
  assert.equal(indexes.rowCount, 7);

  const constraints = await adminPool.query(
    `SELECT conname
       FROM pg_constraint
      WHERE conname = ANY($1::text[])`,
    [
      [
        "payables_company_fk",
        "payables_branch_fk",
        "receivables_company_fk",
        "audit_logs_company_fk",
        "idempotency_records_user_fk",
      ],
    ],
  );
  assert.equal(constraints.rowCount, 5);

  const appFunctions = await adminPool.query(
    `SELECT DISTINCT p.proname
       FROM pg_proc p
       JOIN pg_namespace n ON n.oid = p.pronamespace
      WHERE n.nspname = 'app'
        AND p.proname = ANY($1::text[])`,
    [
      [
        "current_tenant_id",
        "current_user_id",
        "can_access_company",
        "can_access_branch",
        "can_access_record",
        "resolve_identity_access",
        "prevent_audit_log_mutation",
        "assert_payment_scope",
        "assert_receipt_scope",
        "prevent_financial_event_mutation",
        "can_access_catalog",
        "assert_financial_record_catalog_scope",
        "refresh_payable_projection",
        "refresh_receivable_projection",
        "refresh_payment_projection_after_event",
        "refresh_receipt_projection_after_event",
        "prevent_financial_projection_mutation",
      ],
    ],
  );
  assert.equal(appFunctions.rowCount, 17);

  const triggers = await adminPool.query(
    `SELECT tg.tgname
       FROM pg_trigger tg
       JOIN pg_class c ON c.oid = tg.tgrelid
       JOIN pg_namespace n ON n.oid = c.relnamespace
      WHERE n.nspname = 'public'
        AND NOT tg.tgisinternal
        AND tg.tgname = ANY($1::text[])`,
    [
      [
        "audit_logs_immutable",
        "journal_entry_lines_guard",
        "journal_entries_immutability_guard",
        "payments_scope_guard",
        "receipts_scope_guard",
        "payment_reversals_scope_guard",
        "receipt_reversals_scope_guard",
        "payments_immutable",
        "receipts_immutable",
        "payment_reversals_immutable",
        "receipt_reversals_immutable",
        "payables_catalog_scope_guard",
        "receivables_catalog_scope_guard",
        "payables_projection_guard",
        "receivables_projection_guard",
        "payments_refresh_projection",
        "payment_reversals_refresh_projection",
        "receipts_refresh_projection",
        "receipt_reversals_refresh_projection",
      ],
    ],
  );
  assert.equal(triggers.rowCount, 19);

  const expectedTables = [
    "action_plans",
    "audit_logs",
    "auth_identities",
    "branches",
    "companies",
    "cost_centers",
    "counterparties",
    "financial_accounts",
    "financial_categories",
    "glossary_terms",
    "idempotency_records",
    "journal_entries",
    "journal_entry_lines",
    "memberships",
    "payables",
    "payment_reversals",
    "payments",
    "pending_items",
    "permissions",
    "receipt_reversals",
    "receipts",
    "receivables",
    "risks",
    "role_permissions",
    "roles",
    "tenants",
    "users",
  ];
  const tables = await adminPool.query(
    `SELECT tablename
       FROM pg_tables
      WHERE schemaname = 'public' AND tablename <> '_prisma_migrations'
      ORDER BY tablename`,
  );
  assert.deepEqual(
    tables.rows.map((row) => row.tablename),
    expectedTables,
  );

  const unprotected = await adminPool.query(
    `SELECT c.relname
       FROM pg_class c
       JOIN pg_namespace n ON n.oid = c.relnamespace
      WHERE n.nspname = 'public'
        AND c.relkind = 'r'
        AND c.relname NOT IN ('_prisma_migrations', 'auth_identities')
        AND (NOT c.relrowsecurity OR NOT c.relforcerowsecurity)
      ORDER BY c.relname`,
  );
  assert.deepEqual(unprotected.rows, []);

  const policyTables = await adminPool.query(
    `SELECT DISTINCT tablename
       FROM pg_policies
      WHERE schemaname = 'public'
      ORDER BY tablename`,
  );
  assert.deepEqual(
    policyTables.rows.map((row) => row.tablename),
    expectedTables.filter((name) => name !== "auth_identities"),
  );

  const catalogPolicies = await adminPool.query(
    `SELECT tablename, policyname
       FROM pg_policies
      WHERE schemaname = 'public'
        AND policyname LIKE 'catalog_%'
      ORDER BY tablename`,
  );
  assert.equal(catalogPolicies.rowCount, 16);
  for (const tableName of [
    "cost_centers",
    "counterparties",
    "financial_accounts",
    "financial_categories",
  ]) {
    assert.deepEqual(
      catalogPolicies.rows
        .filter((row) => row.tablename === tableName)
        .map((row) => row.policyname)
        .sort(),
      [
        "catalog_delete",
        "catalog_insert",
        "catalog_select",
        "catalog_update",
      ],
    );
  }

  const identityGrant = await adminPool.query(
    `SELECT has_table_privilege(
       'clarity_app',
       'public.auth_identities',
       'SELECT'
     ) AS can_select`,
  );
  assert.equal(identityGrant.rows[0].can_select, false);

  const financialDeleteGrant = await adminPool.query(
    `SELECT
       has_table_privilege('clarity_app', 'public.payables', 'DELETE') AS payable,
       has_table_privilege('clarity_app', 'public.receivables', 'DELETE') AS receivable`,
  );
  assert.deepEqual(financialDeleteGrant.rows[0], {
    payable: false,
    receivable: false,
  });
});

test("seed cobre tenants, empresas, unidades e perfis exigidos", async () => {
  const organization = await adminPool.query(
    `SELECT
       (SELECT count(*)::int FROM tenants) AS tenants,
       (SELECT count(*)::int FROM companies WHERE tenant_id = $1) AS tenant_a_companies,
       (SELECT count(*)::int FROM branches WHERE tenant_id = $1 AND company_id = $2) AS a1_branches,
       (SELECT count(*)::int FROM branches WHERE tenant_id = $1 AND company_id = $3) AS a2_branches,
       (SELECT count(*)::int FROM branches WHERE tenant_id = $4 AND company_id = $5) AS b1_branches`,
    [TENANT_A, COMPANY_A1, COMPANY_A2, TENANT_B, COMPANY_B1],
  );
  assert.deepEqual(organization.rows[0], {
    tenants: 2,
    tenant_a_companies: 2,
    a1_branches: 2,
    a2_branches: 2,
    b1_branches: 2,
  });

  const scenarios = await adminPool.query(
    `SELECT
       count(*) FILTER (WHERE scope = 'TENANT' AND is_active)::int AS tenant_scope,
       count(*) FILTER (WHERE scope = 'COMPANY' AND is_active)::int AS company_scope,
       count(*) FILTER (WHERE scope = 'BRANCH' AND is_active)::int AS branch_scope,
       count(*) FILTER (WHERE NOT is_active)::int AS inactive
     FROM memberships
     WHERE tenant_id = $1`,
    [TENANT_A],
  );
  assert.equal(scenarios.rows[0].tenant_scope, 1);
  assert.ok(scenarios.rows[0].company_scope >= 3);
  assert.equal(scenarios.rows[0].branch_scope, 1);
  assert.equal(scenarios.rows[0].inactive, 1);

  const withoutMembership = await adminPool.query(
    `SELECT count(*)::int AS count
       FROM memberships
      WHERE tenant_id = $1
        AND user_id = '40000000-0000-4000-8000-000000000005'`,
    [TENANT_A],
  );
  assert.equal(withoutMembership.rows[0].count, 0);
});

test("contexto ausente ou incompleto falha fechado", async () => {
  const client = await runtimePool.connect();
  const variants = [
    {
      tenantId: "",
      userId: USER_A_ADMIN,
      companyIds: COMPANY_A1,
      branchIds: "",
    },
    {
      tenantId: TENANT_A,
      userId: "",
      companyIds: COMPANY_A1,
      branchIds: "",
    },
    {
      tenantId: TENANT_A,
      userId: USER_A_ADMIN,
      companyIds: "",
      branchIds: "",
    },
    {
      tenantId: TENANT_A,
      userId: USER_A_BRANCH_WRITER,
      companyIds: "",
      branchIds: "",
    },
  ];

  try {
    for (const variant of variants) {
      await client.query("BEGIN");
      await client.query("SET LOCAL ROLE clarity_app");
      await client.query(
        `SELECT
           set_config('app.tenant_id', $1, true),
           set_config('app.user_id', $2, true),
           set_config('app.company_ids', $3, true),
           set_config('app.branch_ids', $4, true),
           set_config('app.tenant_wide_access', 'false', true)`,
        [
          variant.tenantId,
          variant.userId,
          variant.companyIds,
          variant.branchIds,
        ],
      );
      const visible = await client.query(
        "SELECT count(*)::int AS count FROM payables",
      );
      assert.equal(visible.rows[0].count, 0);
      await client.query("ROLLBACK");
    }

    await client.query("BEGIN");
    await client.query("SET LOCAL ROLE clarity_app");
    await assert.rejects(
      client.query(
        `INSERT INTO tenants (id, name, slug)
         VALUES (gen_random_uuid(), 'Bloqueado', 'bloqueado-sem-contexto')`,
      ),
      (error) => error?.code === "42501",
    );
    await client.query("ROLLBACK");
  } finally {
    client.release();
  }
});

test("Tenant A não lê nem atualiza registros do Tenant B e não possui DELETE", async () => {
  await withContext(
    {
      tenantId: TENANT_A,
      userId: USER_A_ADMIN,
      companyIds: [COMPANY_A1],
    },
    async (client) => {
      const read = await client.query(
        "SELECT count(*)::int AS count FROM payables WHERE tenant_id = $1",
        [TENANT_B],
      );
      assert.equal(read.rows[0].count, 0);

      const update = await client.query(
        "UPDATE payables SET description = 'VIOLAÇÃO' WHERE tenant_id = $1",
        [TENANT_B],
      );
      assert.equal(update.rowCount, 0);

      await assert.rejects(
        client.query("DELETE FROM payables WHERE tenant_id = $1", [TENANT_B]),
        (error) => error?.code === "42501",
      );
    },
  );
});

test("Tenant A não cria registro para o Tenant B", async () => {
  await withContext(
    {
      tenantId: TENANT_A,
      userId: USER_A_ADMIN,
      companyIds: [COMPANY_A1],
    },
    async (client) => {
      await assert.rejects(
        client.query(
          `INSERT INTO payables (
             tenant_id, company_id, branch_id, counterparty_name, description,
             due_date, amount, outstanding_amount, created_by_id
           )
           VALUES ($1, $2, $3, 'Bloqueado', 'Tentativa cruzada', DATE '2026-08-01', 1, 1, $4)`,
          [TENANT_B, COMPANY_B1, BRANCH_B1_NORTH, USER_B_ADMIN],
        ),
        (error) => ["42501", "23503"].includes(error?.code),
      );
    },
  );
});

test("usuário da Empresa A1 não lê nem grava na Empresa A2", async () => {
  await withContext(
    {
      tenantId: TENANT_A,
      userId: USER_A_READER,
      companyIds: [COMPANY_A1],
    },
    async (client) => {
      const read = await client.query(
        "SELECT count(*)::int AS count FROM payables WHERE company_id = $1",
        [COMPANY_A2],
      );
      assert.equal(read.rows[0].count, 0);

      await assert.rejects(
        client.query(
          `INSERT INTO payables (
             tenant_id, company_id, branch_id, counterparty_name, description,
             due_date, amount, outstanding_amount, created_by_id
           )
           VALUES ($1, $2, $3, 'Bloqueado', 'Empresa cruzada', DATE '2026-08-01', 1, 1, $4)`,
          [TENANT_A, COMPANY_A2, BRANCH_A2_MAIN, USER_A_READER],
        ),
        (error) => error?.code === "42501",
      );
    },
  );
});

test("usuário da Unidade A não lê nem grava na Unidade B da mesma empresa", async () => {
  await withContext(
    {
      tenantId: TENANT_A,
      userId: USER_A_BRANCH_WRITER,
      branchIds: [BRANCH_A1_NORTH],
    },
    async (client) => {
      const visible = await client.query(
        "SELECT id, branch_id FROM payables ORDER BY id",
      );
      assert.ok(visible.rows.length >= 1);
      assert.ok(
        visible.rows.every((row) => row.branch_id === BRANCH_A1_NORTH),
      );

      const otherBranch = await client.query(
        "SELECT count(*)::int AS count FROM payables WHERE branch_id = $1",
        [BRANCH_A1_SOUTH],
      );
      assert.equal(otherBranch.rows[0].count, 0);

      const companyWide = await client.query(
        "SELECT count(*)::int AS count FROM payables WHERE branch_id IS NULL",
      );
      assert.equal(companyWide.rows[0].count, 0);

      await assert.rejects(
        client.query(
          `INSERT INTO payables (
             tenant_id, company_id, branch_id, counterparty_name, description,
             due_date, amount, outstanding_amount, created_by_id
           )
           VALUES ($1, $2, $3, 'Bloqueado', 'Unidade cruzada', DATE '2026-08-01', 1, 1, $4)`,
          [TENANT_A, COMPANY_A1, BRANCH_A1_SOUTH, USER_A_BRANCH_WRITER],
        ),
        (error) => error?.code === "42501",
      );
    },
  );
});

test("constraints compostas bloqueiam todas as combinações de IDs cruzados", async () => {
  const insert = `
    INSERT INTO payables (
      id, tenant_id, company_id, branch_id, counterparty_name, description,
      due_date, amount, outstanding_amount, created_by_id
    )
    VALUES ($1, $2, $3, $4, 'Bloqueado', 'Constraint cruzada', DATE '2026-08-01', 1, 1, $5)
  `;

  await assertForeignKeyRejected(insert, [
    crypto.randomUUID(),
    TENANT_A,
    COMPANY_B1,
    BRANCH_B1_NORTH,
    USER_A_ADMIN,
  ]);
  await assertForeignKeyRejected(insert, [
    crypto.randomUUID(),
    TENANT_A,
    COMPANY_A1,
    BRANCH_A2_MAIN,
    USER_A_ADMIN,
  ]);
  await assertForeignKeyRejected(insert, [
    crypto.randomUUID(),
    TENANT_A,
    COMPANY_A1,
    BRANCH_A1_NORTH,
    USER_B_ADMIN,
  ]);
  await assertForeignKeyRejected(insert, [
    crypto.randomUUID(),
    TENANT_A,
    COMPANY_A1,
    BRANCH_B1_NORTH,
    USER_A_ADMIN,
  ]);
});

test("pool reutiliza a mesma conexão sem vazar contexto entre requisições", async () => {
  const first = await singleConnectionPool.connect();
  let backendPid;
  try {
    await first.query("BEGIN");
    await first.query(
      `SELECT
         set_config('app.tenant_id', $1, true),
         set_config('app.user_id', $2, true),
         set_config('app.company_ids', $3, true),
         set_config('app.branch_ids', '', true),
         set_config('app.tenant_wide_access', 'false', true)`,
      [TENANT_A, USER_A_ADMIN, COMPANY_A1],
    );
    const seen = await first.query(
      `SELECT pg_backend_pid() AS pid,
              current_setting('app.tenant_id', true) AS tenant`,
    );
    backendPid = seen.rows[0].pid;
    assert.equal(seen.rows[0].tenant, TENANT_A);
    await first.query("COMMIT");
  } finally {
    first.release();
  }

  const second = await singleConnectionPool.connect();
  try {
    const cleared = await second.query(
      `SELECT pg_backend_pid() AS pid,
              current_user,
              current_setting('app.tenant_id', true) AS tenant,
              current_setting('app.user_id', true) AS app_user`,
    );
    assert.equal(cleared.rows[0].pid, backendPid);
    assert.equal(cleared.rows[0].current_user, "clarity_app");
    assert.equal(cleared.rows[0].tenant, "");
    assert.equal(cleared.rows[0].app_user, "");

    const noContext = await second.query(
      "SELECT count(*)::int AS count FROM payables",
    );
    assert.equal(noContext.rows[0].count, 0);

    await second.query("BEGIN");
    await second.query(
      `SELECT
         set_config('app.tenant_id', $1, true),
         set_config('app.user_id', $2, true),
         set_config('app.company_ids', $3, true),
         set_config('app.branch_ids', '', true),
         set_config('app.tenant_wide_access', 'false', true)`,
      [TENANT_B, USER_B_ADMIN, COMPANY_B1],
    );
    const tenantB = await second.query(
      "SELECT DISTINCT tenant_id FROM payables ORDER BY tenant_id",
    );
    assert.deepEqual(tenantB.rows, [{ tenant_id: TENANT_B }]);
    await second.query("COMMIT");
  } finally {
    second.release();
  }
});

test("identidade inexistente, sem membership e membership inativa falham fechado", async () => {
  for (const email of [
    "inexistente@example.invalid",
    "eva.sem-acesso@example.invalid",
    "fabio.inativo@example.invalid",
  ]) {
    const { response, body } = await apiRequest("/api/v1/me", { email });
    assert.equal(response.status, 403);
    assert.equal(body.code, "MEMBERSHIP_REQUIRED");
  }

  const permissionless = await runtimePool.query(
    "SELECT * FROM app.resolve_identity_access($1)",
    ["gabi.sem-permissao@example.invalid"],
  );
  assert.deepEqual(
    permissionless.rows
      .map((row) => row.branch_id)
      .sort(),
    [BRANCH_A1_NORTH, BRANCH_A1_SOUTH],
  );
  assert.ok(
    permissionless.rows.every((row) => row.permissions.length === 0),
  );

  const tenantWide = await runtimePool.query(
    "SELECT DISTINCT company_id FROM app.resolve_identity_access($1) ORDER BY company_id",
    ["tiago.tenant@example.invalid"],
  );
  assert.deepEqual(
    tenantWide.rows.map((row) => row.company_id),
    [COMPANY_A1, COMPANY_A2],
  );
});

test("GET /api/v1/me expõe empresas, unidades e permissões reais por escopo", async () => {
  const reader = await apiRequest("/api/v1/me", {
    email: "carla.leitura@example.invalid",
  });
  assert.equal(reader.response.status, 200);
  const readerCompany = reader.body.data.tenants[0].companies.find(
    (company) => company.id === COMPANY_A1,
  );
  assert.ok(readerCompany);
  assert.ok(readerCompany.permissions.includes("payables.view"));
  assert.equal(readerCompany.permissions.includes("payables.manage"), false);
  assert.deepEqual(
    readerCompany.branches.map((branch) => branch.id),
    [BRANCH_A1_NORTH, BRANCH_A1_SOUTH],
  );
  assert.ok(
    readerCompany.branches.every((branch) =>
      branch.permissions.includes("payables.view"),
    ),
  );

  const tenantWide = await apiRequest("/api/v1/me", {
    email: "tiago.tenant@example.invalid",
  });
  assert.equal(tenantWide.response.status, 200);
  assert.deepEqual(
    tenantWide.body.data.tenants[0].companies.map((company) => ({
      id: company.id,
      branches: company.branches.map((branch) => branch.id),
    })),
    [
      {
        id: COMPANY_A1,
        branches: [BRANCH_A1_NORTH, BRANCH_A1_SOUTH],
      },
      {
        id: COMPANY_A2,
        branches: [BRANCH_A2_MAIN, "30000000-0000-4000-8000-000000000005"],
      },
    ],
  );

  const branchWriter = await apiRequest("/api/v1/me", {
    email: "diego.unidade@example.invalid",
  });
  assert.equal(branchWriter.response.status, 200);
  const branchCompany = branchWriter.body.data.tenants[0].companies.find(
    (company) => company.id === COMPANY_A1,
  );
  assert.deepEqual(branchCompany.scopes, ["BRANCH"]);
  assert.deepEqual(branchCompany.permissions, []);
  assert.deepEqual(
    branchCompany.branches.map((branch) => branch.id),
    [BRANCH_A1_NORTH],
  );
  assert.ok(
    branchCompany.branches[0].permissions.includes("payables.manage"),
  );

  const permissionless = await apiRequest("/api/v1/me", {
    email: "gabi.sem-permissao@example.invalid",
  });
  assert.equal(permissionless.response.status, 200);
  assert.deepEqual(
    permissionless.body.data.tenants[0].companies[0].permissions,
    [],
  );
});

test("RBAC nega escrita ao leitor e listagem ao usuário sem permissão", async () => {
  const readOnlyPost = await apiRequest("/api/v1/payables", {
    method: "POST",
    email: "carla.leitura@example.invalid",
    companyId: COMPANY_A1,
    idempotencyKey: `reader-${crypto.randomUUID()}`,
    body: validPayable,
  });
  assert.equal(readOnlyPost.response.status, 403);
  assert.equal(readOnlyPost.body.code, "FORBIDDEN");

  const noPermission = await apiRequest("/api/v1/payables", {
    email: "gabi.sem-permissao@example.invalid",
    companyId: COMPANY_A1,
  });
  assert.equal(noPermission.response.status, 403);
  assert.equal(noPermission.body.code, "FORBIDDEN");

  const inactive = await apiRequest("/api/v1/payables", {
    email: "fabio.inativo@example.invalid",
    companyId: COMPANY_A1,
  });
  assert.equal(inactive.response.status, 403);
});

test("API rejeita empresa e unidade fora do escopo autorizado", async () => {
  const wrongCompany = await apiRequest("/api/v1/payables", {
    email: "carla.leitura@example.invalid",
    companyId: COMPANY_A2,
  });
  assert.equal(wrongCompany.response.status, 403);

  const wrongBranch = await apiRequest("/api/v1/payables", {
    email: "diego.unidade@example.invalid",
    companyId: COMPANY_A1,
    branchId: BRANCH_A1_SOUTH,
  });
  assert.equal(wrongBranch.response.status, 403);

  for (const branchId of [BRANCH_B1_NORTH, BRANCH_NONEXISTENT]) {
    const tamperedWideBranch = await apiRequest("/api/v1/payables", {
      email: "carla.leitura@example.invalid",
      companyId: COMPANY_A1,
      branchId,
    });
    assert.equal(tamperedWideBranch.response.status, 403);
    assert.equal(tamperedWideBranch.body.code, "FORBIDDEN");
  }

  const allowedWideBranch = await apiRequest("/api/v1/payables", {
    email: "carla.leitura@example.invalid",
    companyId: COMPANY_A1,
    branchId: BRANCH_A1_NORTH,
  });
  assert.equal(allowedWideBranch.response.status, 200);
  assert.ok(
    allowedWideBranch.body.data.every(
      (record) => record.branchId === BRANCH_A1_NORTH,
    ),
  );

  const allowedBranch = await apiRequest("/api/v1/payables", {
    email: "diego.unidade@example.invalid",
    companyId: COMPANY_A1,
    branchId: BRANCH_A1_NORTH,
  });
  assert.equal(allowedBranch.response.status, 200);
  assert.ok(
    allowedBranch.body.data.every(
      (record) => record.branchId === BRANCH_A1_NORTH,
    ),
  );
});

test("GET payables valida filtros e paginação contra PostgreSQL real", async () => {
  const firstPage = await apiRequest("/api/v1/payables?status=OPEN&limit=1", {
    email: "ana.horizonte@example.invalid",
    companyId: COMPANY_A1,
  });
  assert.equal(firstPage.response.status, 200);
  assert.equal(firstPage.body.data.length, 1);
  assert.equal(firstPage.body.data[0].status, "OPEN");
  assert.equal(firstPage.body.page.hasMore, true);
  assert.ok(firstPage.body.page.nextCursor);

  const secondPage = await apiRequest(
    `/api/v1/payables?status=OPEN&limit=1&cursor=${encodeURIComponent(firstPage.body.page.nextCursor)}`,
    {
      email: "ana.horizonte@example.invalid",
      companyId: COMPANY_A1,
    },
  );
  assert.equal(secondPage.response.status, 200);
  assert.notEqual(secondPage.body.data[0].id, firstPage.body.data[0].id);

  const invalid = await apiRequest("/api/v1/payables?status=UNKNOWN", {
    email: "ana.horizonte@example.invalid",
    companyId: COMPANY_A1,
  });
  assert.equal(invalid.response.status, 422);
  assert.equal(invalid.body.code, "VALIDATION_ERROR");
});

test("GET receivables aplica filtros e contexto real", async () => {
  const result = await apiRequest(
    "/api/v1/receivables?status=OPEN&dueFrom=2026-07-01&dueTo=2026-12-31&limit=10",
    {
      email: "tiago.tenant@example.invalid",
      companyId: COMPANY_A2,
    },
  );
  assert.equal(result.response.status, 200);
  assert.ok(result.body.data.length >= 1);
  assert.ok(result.body.data.every((record) => record.status === "OPEN"));

  const invalidDate = await apiRequest(
    "/api/v1/receivables?dueFrom=2026-02-30",
    {
      email: "tiago.tenant@example.invalid",
      companyId: COMPANY_A2,
    },
  );
  assert.equal(invalidDate.response.status, 422);
});

test("POST financeiro valida payload, ignora escopo do cliente e persiste auditoria íntegra", async () => {
  const injected = await apiRequest("/api/v1/payables", {
    method: "POST",
    email: "ana.horizonte@example.invalid",
    companyId: COMPANY_A1,
    idempotencyKey: `inject-${crypto.randomUUID()}`,
    body: {
      ...validPayable,
      tenantId: TENANT_B,
      companyId: COMPANY_B1,
      branchId: BRANCH_B1_NORTH,
      createdById: USER_B_ADMIN,
    },
  });
  assert.equal(injected.response.status, 422);

  const description = `Auditoria real ${crypto.randomUUID()}`;
  const created = await apiRequest("/api/v1/receivables", {
    method: "POST",
    email: "diego.unidade@example.invalid",
    companyId: COMPANY_A1,
    branchId: BRANCH_A1_NORTH,
    idempotencyKey: `receivable-${crypto.randomUUID()}`,
    body: { ...validPayable, description },
  });
  assert.equal(created.response.status, 201);
  assert.equal(created.body.data.branchId, BRANCH_A1_NORTH);

  const stored = await adminPool.query(
    `SELECT r.tenant_id, r.company_id, r.branch_id, r.created_by_id,
            a.actor_user_id, a.action, a.entity_type, a.entity_id,
            a.before_data, a.after_data, a.metadata, a.redacted_fields,
            a.integrity_hash
       FROM receivables r
       JOIN audit_logs a
         ON a.tenant_id = r.tenant_id
        AND a.entity_id = r.id
      WHERE r.id = $1`,
    [created.body.data.id],
  );
  assert.equal(stored.rows.length, 1);
  assert.equal(stored.rows[0].tenant_id, TENANT_A);
  assert.equal(stored.rows[0].company_id, COMPANY_A1);
  assert.equal(stored.rows[0].branch_id, BRANCH_A1_NORTH);
  assert.equal(stored.rows[0].created_by_id, USER_A_BRANCH_WRITER);
  assert.equal(stored.rows[0].actor_user_id, USER_A_BRANCH_WRITER);
  assert.equal(stored.rows[0].action, "receivable.created");
  assert.equal(stored.rows[0].entity_type, "Receivable");
  assert.equal(stored.rows[0].entity_id, created.body.data.id);
  assert.equal(stored.rows[0].before_data, null);
  assert.equal(stored.rows[0].metadata.source, "api");
  assert.match(stored.rows[0].metadata.idempotencyKeyHash, /^[0-9a-f]{64}$/);
  assert.deepEqual(stored.rows[0].redacted_fields, ["counterpartyName"]);
  assert.match(stored.rows[0].integrity_hash, /^[0-9a-f]{64}$/);
  assert.equal(JSON.stringify(stored.rows[0]).includes(description), false);
});

test(
  "idempotência concorrente persiste uma operação, repete resposta e detecta conflito",
  { timeout: INTEGRATION_TEST_TIMEOUT_MS },
  async () => {
    const idempotencyKey = `concurrent-${crypto.randomUUID()}`;
    const description = `Concorrência real ${crypto.randomUUID()}`;
    const options = {
      method: "POST",
      email: "ana.horizonte@example.invalid",
      companyId: COMPANY_A1,
      idempotencyKey,
      body: { ...validPayable, description },
    };

    const [first, second] = await Promise.all([
      apiRequest("/api/v1/payables", options),
      apiRequest("/api/v1/payables", options),
    ]);

    assert.equal(first.response.status, 201);
    assert.equal(second.response.status, 201);
    assert.deepEqual(first.body.data, second.body.data);
    assert.deepEqual(
      [
        first.response.headers.get("idempotency-replayed"),
        second.response.headers.get("idempotency-replayed"),
      ].sort(),
      [null, "true"],
    );

    const persisted = await adminPool.query(
      `SELECT
         (SELECT count(*)::int FROM payables WHERE description = $1) AS financial,
         (SELECT count(*)::int FROM audit_logs WHERE entity_id = $2) AS audit,
         (SELECT count(*)::int
            FROM idempotency_records
           WHERE resource_id = $2 AND key = $3) AS idempotency`,
      [description, first.body.data.id, idempotencyKey],
    );
    assert.deepEqual(persisted.rows[0], {
      financial: 1,
      audit: 1,
      idempotency: 1,
    });

    const conflict = await apiRequest("/api/v1/payables", {
      ...options,
      body: { ...options.body, amount: "9999.0000" },
    });
    assert.equal(conflict.response.status, 409);
    assert.equal(conflict.body.code, "IDEMPOTENCY_CONFLICT");
  },
);

test("falha forçada de auditoria reverte financeiro e idempotência", async () => {
  await adminPool.query(`
    CREATE OR REPLACE FUNCTION app.phase_zero_fail_audit()
    RETURNS trigger
    LANGUAGE plpgsql
    AS $function$
    BEGIN
      RAISE EXCEPTION 'phase zero forced audit failure';
    END
    $function$;
    CREATE TRIGGER phase_zero_fail_audit
      BEFORE INSERT ON audit_logs
      FOR EACH ROW EXECUTE FUNCTION app.phase_zero_fail_audit();
  `);

  const description = `Rollback auditoria ${crypto.randomUUID()}`;
  const key = `audit-failure-${crypto.randomUUID()}`;
  try {
    const failed = await apiRequest("/api/v1/payables", {
      method: "POST",
      email: "ana.horizonte@example.invalid",
      companyId: COMPANY_A1,
      idempotencyKey: key,
      body: { ...validPayable, description },
    });
    assert.equal(failed.response.status, 503);
  } finally {
    await adminPool.query(
      "DROP TRIGGER IF EXISTS phase_zero_fail_audit ON audit_logs; DROP FUNCTION IF EXISTS app.phase_zero_fail_audit()",
    );
  }

  const remains = await adminPool.query(
    `SELECT
       (SELECT count(*)::int FROM payables WHERE description = $1) AS financial,
       (SELECT count(*)::int FROM audit_logs WHERE after_data->>'description' = $1) AS audit,
       (SELECT count(*)::int FROM idempotency_records WHERE key = $2) AS idempotency`,
    [description, key],
  );
  assert.deepEqual(remains.rows[0], {
    financial: 0,
    audit: 0,
    idempotency: 0,
  });
});

test("falha forçada de idempotência reverte financeiro e auditoria", async () => {
  await adminPool.query(`
    CREATE OR REPLACE FUNCTION app.phase_zero_fail_idempotency()
    RETURNS trigger
    LANGUAGE plpgsql
    AS $function$
    BEGIN
      RAISE EXCEPTION 'phase zero forced idempotency failure';
    END
    $function$;
    CREATE TRIGGER phase_zero_fail_idempotency
      BEFORE INSERT ON idempotency_records
      FOR EACH ROW EXECUTE FUNCTION app.phase_zero_fail_idempotency();
  `);

  const description = `Rollback idempotência ${crypto.randomUUID()}`;
  const key = `idempotency-failure-${crypto.randomUUID()}`;
  try {
    const failed = await apiRequest("/api/v1/receivables", {
      method: "POST",
      email: "ana.horizonte@example.invalid",
      companyId: COMPANY_A1,
      idempotencyKey: key,
      body: { ...validPayable, description },
    });
    assert.equal(failed.response.status, 503);
  } finally {
    await adminPool.query(
      "DROP TRIGGER IF EXISTS phase_zero_fail_idempotency ON idempotency_records; DROP FUNCTION IF EXISTS app.phase_zero_fail_idempotency()",
    );
  }

  const remains = await adminPool.query(
    `SELECT
       (SELECT count(*)::int FROM receivables WHERE description = $1) AS financial,
       (SELECT count(*)::int FROM audit_logs WHERE entity_id IN (
          SELECT id FROM receivables WHERE description = $1
        )) AS audit,
       (SELECT count(*)::int FROM idempotency_records WHERE key = $2) AS idempotency`,
    [description, key],
  );
  assert.deepEqual(remains.rows[0], {
    financial: 0,
    audit: 0,
    idempotency: 0,
  });
});

test("auditoria é append-only inclusive para o owner", async () => {
  const audit = await adminPool.query(
    "SELECT id FROM audit_logs ORDER BY occurred_at LIMIT 1",
  );
  assert.equal(audit.rows.length, 1);

  await assert.rejects(
    adminPool.query(
      "UPDATE audit_logs SET action = 'tampered' WHERE id = $1",
      [audit.rows[0].id],
    ),
    (error) => error?.code === "23000",
  );
  await assert.rejects(
    adminPool.query("DELETE FROM audit_logs WHERE id = $1", [
      audit.rows[0].id,
    ]),
    (error) => error?.code === "23000",
  );
});
