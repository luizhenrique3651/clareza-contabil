import assert from "node:assert/strict";
import test, { after, before } from "node:test";

import pg from "pg";

const { Pool } = pg;
const ADMIN_URL = process.env.DIRECT_DATABASE_URL;
const RUNTIME_URL = process.env.DATABASE_URL;

if (!ADMIN_URL || !RUNTIME_URL) {
  throw new Error(
    "DIRECT_DATABASE_URL e DATABASE_URL sao obrigatorias para os testes PostgreSQL.",
  );
}

const INTEGRATION_TEST_TIMEOUT_MS = Math.max(
  20_000,
  Number(process.env.INTEGRATION_TEST_TIMEOUT_MS) || 0,
);

const TENANT_A = "10000000-0000-4000-8000-000000000001";
const TENANT_B = "10000000-0000-4000-8000-000000000002";
const COMPANY_A1 = "20000000-0000-4000-8000-000000000001";
const BRANCH_A1_NORTH = "30000000-0000-4000-8000-000000000001";
const BRANCH_A1_SOUTH = "30000000-0000-4000-8000-000000000003";
const USER_A_ADMIN = "40000000-0000-4000-8000-000000000001";
const USER_A_BRANCH = "40000000-0000-4000-8000-000000000004";
const COUNTERPARTY_A_SUPPLIER = "82000000-0000-4000-8000-000000000001";
const COUNTERPARTY_A_CUSTOMER = "82000000-0000-4000-8000-000000000002";
const COUNTERPARTY_A_SHARED = "82000000-0000-4000-8000-000000000003";
const COUNTERPARTY_B_SUPPLIER = "82000000-0000-4000-8000-000000000101";
const CATEGORY_A_PAYABLE = "83000000-0000-4000-8000-000000000001";
const CATEGORY_A_RECEIVABLE = "83000000-0000-4000-8000-000000000002";
const COST_CENTER_A = "84000000-0000-4000-8000-000000000001";
const FINANCIAL_ACCOUNT_A = "85000000-0000-4000-8000-000000000001";
const FINANCIAL_ACCOUNT_B = "85000000-0000-4000-8000-000000000101";

const adminPool = new Pool({ connectionString: ADMIN_URL, max: 5 });
let worker;

before(async () => {
  await adminPool.query(
    `INSERT INTO counterparties (
       id, tenant_id, company_id, branch_id, code, name, kind, created_by_id
     ) VALUES ($1, $2, $3, NULL, 'PARC-CORP', 'Parceiro compartilhado do gate', 'BOTH', $4)
     ON CONFLICT DO NOTHING`,
    [COUNTERPARTY_A_SHARED, TENANT_A, COMPANY_A1, USER_A_ADMIN],
  );
  const workerUrl = new URL("../../dist/server/index.js", import.meta.url);
  workerUrl.searchParams.set("phase-two-real-db", `${process.pid}-${Date.now()}`);
  ({ default: worker } = await import(workerUrl.href));
});

after(async () => {
  if (globalThis.clarezaPrisma) {
    await globalThis.clarezaPrisma.$disconnect();
  }
  await adminPool.end();
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

function titleBody(kind, amount = "1000.0000") {
  return {
    counterpartyId:
      kind === "payable"
        ? COUNTERPARTY_A_SUPPLIER
        : COUNTERPARTY_A_CUSTOMER,
    categoryId:
      kind === "payable" ? CATEGORY_A_PAYABLE : CATEGORY_A_RECEIVABLE,
    costCenterId: COST_CENTER_A,
    counterpartyName: "Nome enviado pelo cliente",
    description: `Gate ${kind} ${crypto.randomUUID()}`,
    dueDate: "2027-10-10",
    amount,
    currencyCode: "BRL",
  };
}

async function createTitle(kind, amount = "1000.0000") {
  return apiRequest(`/api/v1/${kind}s`, {
    method: "POST",
    email: "ana.horizonte@example.invalid",
    companyId: COMPANY_A1,
    branchId: BRANCH_A1_NORTH,
    idempotencyKey: `${kind}-title-${crypto.randomUUID()}`,
    body: titleBody(kind, amount),
  });
}

test("catalogos financeiros respeitam unidade, RBAC e idempotencia", async () => {
  const listed = await apiRequest("/api/v1/financial/catalogs", {
    email: "diego.unidade@example.invalid",
    companyId: COMPANY_A1,
    branchId: BRANCH_A1_NORTH,
  });
  assert.equal(listed.response.status, 200);
  assert.ok(
    listed.body.data.counterparties.some(
      (item) => item.id === COUNTERPARTY_A_SUPPLIER,
    ),
  );
  assert.ok(
    listed.body.data.counterparties.some(
      (item) => item.id === COUNTERPARTY_A_SHARED && item.branchId === null,
    ),
  );
  assert.ok(
    listed.body.data.financialAccounts.some(
      (item) => item.id === FINANCIAL_ACCOUNT_A,
    ),
  );
  assert.equal(
    JSON.stringify(listed.body.data).includes(COUNTERPARTY_B_SUPPLIER),
    false,
  );
  assert.ok(
    Object.values(listed.body.data)
      .flat()
      .every(
        (item) =>
          item.branchId === null || item.branchId === BRANCH_A1_NORTH,
      ),
  );

  const denied = await apiRequest("/api/v1/financial/catalogs", {
    method: "POST",
    email: "carla.leitura@example.invalid",
    companyId: COMPANY_A1,
    branchId: BRANCH_A1_NORTH,
    idempotencyKey: `catalog-reader-${crypto.randomUUID()}`,
    body: {
      resource: "costCenter",
      code: `READ-${crypto.randomUUID().slice(0, 8)}`,
      name: "Cadastro negado ao leitor",
    },
  });
  assert.equal(denied.response.status, 403);

  const idempotencyKey = `catalog-${crypto.randomUUID()}`;
  const code = `CC-${crypto.randomUUID().slice(0, 8)}`;
  const options = {
    method: "POST",
    email: "ana.horizonte@example.invalid",
    companyId: COMPANY_A1,
    branchId: BRANCH_A1_NORTH,
    idempotencyKey,
    body: {
      resource: "costCenter",
      code,
      name: "Centro de custo de integracao",
    },
  };
  const created = await apiRequest("/api/v1/financial/catalogs", options);
  const replayed = await apiRequest("/api/v1/financial/catalogs", options);
  assert.equal(created.response.status, 201);
  assert.equal(replayed.response.status, 201);
  assert.equal(replayed.response.headers.get("idempotency-replayed"), "true");
  assert.deepEqual(created.body.data, replayed.body.data);
  assert.equal(created.body.data.branchId, BRANCH_A1_NORTH);

  const persisted = await adminPool.query(
    `SELECT
       (SELECT count(*)::int FROM cost_centers WHERE code = $1) AS catalogs,
       (SELECT count(*)::int FROM audit_logs WHERE entity_id = $2) AS audits,
       (SELECT count(*)::int FROM idempotency_records WHERE key = $3) AS idempotency`,
    [code.toUpperCase(), created.body.data.id, idempotencyKey],
  );
  assert.deepEqual(persisted.rows[0], {
    catalogs: 1,
    audits: 1,
    idempotency: 1,
  });

  const companyWide = await apiRequest("/api/v1/financial/catalogs", {
    email: "ana.horizonte@example.invalid",
    companyId: COMPANY_A1,
  });
  assert.equal(companyWide.response.status, 200);
  assert.ok(
    companyWide.body.data.counterparties.some(
      (item) => item.id === COUNTERPARTY_A_SUPPLIER,
    ),
  );

  const branchClient = await adminPool.connect();
  try {
    await branchClient.query("BEGIN");
    await branchClient.query("SET LOCAL ROLE clarity_app");
    await branchClient.query(
      `SELECT
         set_config('app.tenant_id', $1, true),
         set_config('app.user_id', $2, true),
         set_config('app.tenant_wide_access', 'false', true),
         set_config('app.company_ids', '', true),
         set_config('app.branch_ids', $3, true)`,
      [TENANT_A, USER_A_BRANCH, BRANCH_A1_NORTH],
    );
    const sharedVisible = await branchClient.query(
      "SELECT count(*)::int AS count FROM counterparties WHERE id = $1",
      [COUNTERPARTY_A_SHARED],
    );
    assert.equal(sharedVisible.rows[0].count, 1);
    await assert.rejects(
      branchClient.query(
        `INSERT INTO cost_centers (
           id, tenant_id, company_id, branch_id, code, name, created_by_id
         ) VALUES ($1, $2, $3, NULL, $4, 'Compartilhado indevido', $5)`,
        [
          crypto.randomUUID(),
          TENANT_A,
          COMPANY_A1,
          `ESCALATE-${crypto.randomUUID().slice(0, 8)}`,
          USER_A_BRANCH,
        ],
      ),
      (error) => error?.code === "42501",
    );
  } finally {
    await branchClient.query("ROLLBACK");
    branchClient.release();
  }

  const conflictKey = `catalog-conflict-${crypto.randomUUID()}`;
  const conflict = await apiRequest("/api/v1/financial/catalogs", {
    ...options,
    idempotencyKey,
    body: { ...options.body, name: "Payload diferente" },
  });
  assert.equal(conflict.response.status, 409);
  assert.equal(conflict.body.code, "IDEMPOTENCY_CONFLICT");

  const concurrentCode = `RACE-${crypto.randomUUID().slice(0, 8)}`;
  const concurrentBody = {
    resource: "costCenter",
    code: concurrentCode,
    name: "Cadastro concorrente",
  };
  const [firstConcurrent, secondConcurrent] = await Promise.all([
    apiRequest("/api/v1/financial/catalogs", {
      ...options,
      idempotencyKey: `${conflictKey}-a`,
      body: concurrentBody,
    }),
    apiRequest("/api/v1/financial/catalogs", {
      ...options,
      idempotencyKey: `${conflictKey}-b`,
      body: concurrentBody,
    }),
  ]);
  assert.deepEqual(
    [firstConcurrent.response.status, secondConcurrent.response.status].sort(),
    [201, 409],
  );
});

test("titulos validam e derivam referencias dos catalogos", async () => {
  const created = await createTitle("payable", "125.5000");
  assert.equal(created.response.status, 201);
  assert.equal(created.body.data.counterpartyId, COUNTERPARTY_A_SUPPLIER);
  assert.equal(created.body.data.categoryId, CATEGORY_A_PAYABLE);
  assert.equal(created.body.data.costCenterId, COST_CENTER_A);
  assert.notEqual(
    created.body.data.counterpartyName,
    "Nome enviado pelo cliente",
  );

  for (const counterpartyId of [
    COUNTERPARTY_A_CUSTOMER,
    COUNTERPARTY_B_SUPPLIER,
  ]) {
    const rejected = await apiRequest("/api/v1/payables", {
      method: "POST",
      email: "ana.horizonte@example.invalid",
      companyId: COMPANY_A1,
      branchId: BRANCH_A1_NORTH,
      idempotencyKey: `catalog-invalid-${crypto.randomUUID()}`,
      body: { ...titleBody("payable", "50.0000"), counterpartyId },
    });
    assert.equal(rejected.response.status, 422);
    assert.equal(rejected.body.code, "VALIDATION_ERROR");
  }
});

test(
  "pagamento percorre parcial, quitacao, estorno e historico derivado",
  { timeout: INTEGRATION_TEST_TIMEOUT_MS },
  async () => {
    const title = await createTitle("payable");
    assert.equal(title.response.status, 201);
    const recordId = title.body.data.id;

    const partialKey = `payment-partial-${crypto.randomUUID()}`;
    const partialOptions = {
      method: "POST",
      email: "ana.horizonte@example.invalid",
      companyId: COMPANY_A1,
      branchId: BRANCH_A1_NORTH,
      idempotencyKey: partialKey,
      body: {
        recordId,
        financialAccountId: FINANCIAL_ACCOUNT_A,
        amount: "400.0000",
        interestAmount: "10.0000",
        fineAmount: "5.0000",
        discountAmount: "3.0000",
        occurredAt: "2026-07-10T09:00:00-04:00",
        note: "Primeira baixa parcial",
      },
    };
    const partial = await apiRequest(
      "/api/v1/payables/settlements",
      partialOptions,
    );
    const partialReplay = await apiRequest(
      "/api/v1/payables/settlements",
      partialOptions,
    );
    assert.equal(partial.response.status, 201);
    assert.equal(partialReplay.response.status, 201);
    assert.equal(
      partialReplay.response.headers.get("idempotency-replayed"),
      "true",
    );
    assert.equal(partial.body.data.record.status, "PARTIAL");
    assert.equal(Number(partial.body.data.record.outstandingAmount), 600);
    assert.equal(Number(partial.body.data.event.cashAmount), 412);
    const partialConflict = await apiRequest(
      "/api/v1/payables/settlements",
      {
        ...partialOptions,
        body: { ...partialOptions.body, amount: "399.0000" },
      },
    );
    assert.equal(partialConflict.response.status, 409);
    assert.equal(partialConflict.body.code, "IDEMPOTENCY_CONFLICT");

    const overpayment = await apiRequest("/api/v1/payables/settlements", {
      ...partialOptions,
      idempotencyKey: `payment-over-${crypto.randomUUID()}`,
      body: {
        ...partialOptions.body,
        amount: "600.0001",
        interestAmount: "0",
        fineAmount: "0",
        discountAmount: "0",
      },
    });
    assert.equal(overpayment.response.status, 409);
    assert.equal(overpayment.body.code, "FINANCIAL_STATE_CONFLICT");

    const settled = await apiRequest("/api/v1/payables/settlements", {
      ...partialOptions,
      idempotencyKey: `payment-full-${crypto.randomUUID()}`,
      body: {
        ...partialOptions.body,
        amount: "600.0000",
        interestAmount: "0",
        fineAmount: "0",
        discountAmount: "0",
        occurredAt: "2026-07-11T09:00:00-04:00",
        note: "Quitacao final",
      },
    });
    assert.equal(settled.response.status, 201);
    assert.equal(settled.body.data.record.status, "SETTLED");
    assert.equal(Number(settled.body.data.record.outstandingAmount), 0);
    assert.ok(settled.body.data.record.settledAt);

    const history = await apiRequest(
      `/api/v1/payables/history?recordId=${recordId}`,
      {
        email: "carla.leitura@example.invalid",
        companyId: COMPANY_A1,
        branchId: BRANCH_A1_NORTH,
      },
    );
    assert.equal(history.response.status, 200);
    assert.equal(history.body.data.events.length, 2);
    assert.deepEqual(history.body.data.audit, {
      appendOnly: true,
      eventCount: 2,
      settlementCount: 2,
      reversalCount: 0,
      projectionVerified: true,
    });

    const reversalKey = `payment-reversal-${crypto.randomUUID()}`;
    const reversalOptions = {
      method: "POST",
      email: "ana.horizonte@example.invalid",
      companyId: COMPANY_A1,
      branchId: BRANCH_A1_NORTH,
      idempotencyKey: reversalKey,
      body: {
        recordId,
        eventId: settled.body.data.event.id,
        occurredAt: "2026-07-12T09:00:00-04:00",
        reason: "Liquidacao registrada na conta incorreta",
      },
    };
    const reversed = await apiRequest(
      "/api/v1/payables/reversals",
      reversalOptions,
    );
    const reversalReplay = await apiRequest(
      "/api/v1/payables/reversals",
      reversalOptions,
    );
    assert.equal(reversed.response.status, 201);
    assert.equal(reversalReplay.response.status, 201);
    assert.equal(
      reversalReplay.response.headers.get("idempotency-replayed"),
      "true",
    );
    assert.equal(reversed.body.data.record.status, "PARTIAL");
    assert.equal(Number(reversed.body.data.record.outstandingAmount), 600);
    assert.ok(reversed.body.data.event.reversal);

    const reversedHistory = await apiRequest(
      `/api/v1/payables/history?recordId=${recordId}`,
      {
        email: "carla.leitura@example.invalid",
        companyId: COMPANY_A1,
        branchId: BRANCH_A1_NORTH,
      },
    );
    assert.equal(reversedHistory.response.status, 200);
    assert.deepEqual(
      reversedHistory.body.data.events.map((item) => item.type),
      ["REVERSAL", "SETTLEMENT", "SETTLEMENT"],
    );
    assert.deepEqual(reversedHistory.body.data.audit, {
      appendOnly: true,
      eventCount: 3,
      settlementCount: 2,
      reversalCount: 1,
      projectionVerified: true,
    });

    const duplicateReversal = await apiRequest("/api/v1/payables/reversals", {
      ...reversalOptions,
      idempotencyKey: `payment-reversal-duplicate-${crypto.randomUUID()}`,
    });
    assert.equal(duplicateReversal.response.status, 409);

    const audit = await adminPool.query(
      `SELECT action
         FROM audit_logs
        WHERE entity_id = ANY($1::uuid[])
        ORDER BY action`,
      [
        [
          partial.body.data.event.id,
          settled.body.data.event.id,
          reversed.body.data.event.reversal.id,
        ],
      ],
    );
    assert.deepEqual(
      audit.rows.map((row) => row.action),
      [
        "payable.payment.created",
        "payable.payment.created",
        "payable.payment.reversed",
      ],
    );
  },
);

test("eventos rejeitam permissao, escopo ativo, conta externa e payload injetado", async () => {
  const title = await createTitle("payable", "100.0000");
  assert.equal(title.response.status, 201);
  const recordId = title.body.data.id;

  const wrongActiveBranch = await apiRequest(
    `/api/v1/payables/history?recordId=${recordId}`,
    {
      email: "ana.horizonte@example.invalid",
      companyId: COMPANY_A1,
      branchId: BRANCH_A1_SOUTH,
    },
  );
  assert.equal(wrongActiveBranch.response.status, 404);

  const readerMutation = await apiRequest("/api/v1/payables/settlements", {
    method: "POST",
    email: "carla.leitura@example.invalid",
    companyId: COMPANY_A1,
    branchId: BRANCH_A1_NORTH,
    idempotencyKey: `reader-payment-${crypto.randomUUID()}`,
    body: {
      recordId,
      financialAccountId: FINANCIAL_ACCOUNT_A,
      amount: "1.0000",
    },
  });
  assert.equal(readerMutation.response.status, 403);

  const crossTenantAccount = await apiRequest(
    "/api/v1/payables/settlements",
    {
      method: "POST",
      email: "ana.horizonte@example.invalid",
      companyId: COMPANY_A1,
      branchId: BRANCH_A1_NORTH,
      idempotencyKey: `cross-account-${crypto.randomUUID()}`,
      body: {
        recordId,
        financialAccountId: FINANCIAL_ACCOUNT_B,
        amount: "1.0000",
      },
    },
  );
  assert.equal(crossTenantAccount.response.status, 422);

  const injectedScope = await apiRequest("/api/v1/payables/settlements", {
    method: "POST",
    email: "ana.horizonte@example.invalid",
    companyId: COMPANY_A1,
    branchId: BRANCH_A1_NORTH,
    idempotencyKey: `scope-payment-${crypto.randomUUID()}`,
    body: {
      recordId,
      financialAccountId: FINANCIAL_ACCOUNT_A,
      amount: "1.0000",
      tenantId: TENANT_B,
    },
  });
  assert.equal(injectedScope.response.status, 422);

  const malformedContext = await apiRequest("/api/v1/payables", {
    email: "ana.horizonte@example.invalid",
    companyId: "empresa-invalida",
  });
  assert.equal(malformedContext.response.status, 400);
  assert.equal(malformedContext.body.code, "INVALID_CONTEXT_SELECTION");

  const oversized = await apiRequest("/api/v1/payables", {
    method: "POST",
    email: "ana.horizonte@example.invalid",
    companyId: COMPANY_A1,
    branchId: BRANCH_A1_NORTH,
    idempotencyKey: `oversized-${crypto.randomUUID()}`,
    body: {
      ...titleBody("payable", "10.0000"),
      description: "x".repeat(70_000),
    },
  });
  assert.equal(oversized.response.status, 413);
  assert.equal(oversized.body.code, "PAYLOAD_TOO_LARGE");

  const overflow = await apiRequest("/api/v1/payables/settlements", {
    method: "POST",
    email: "ana.horizonte@example.invalid",
    companyId: COMPANY_A1,
    branchId: BRANCH_A1_NORTH,
    idempotencyKey: `overflow-${crypto.randomUUID()}`,
    body: {
      recordId,
      financialAccountId: FINANCIAL_ACCOUNT_A,
      amount: "1.0000",
      interestAmount: "999999999999999.9999",
    },
  });
  assert.equal(overflow.response.status, 422);
  assert.ok(
    overflow.body.errors.some((error) => error.code === "MONEY_OVERFLOW"),
  );

  const futureOccurredAt = new Date(Date.now() + 24 * 60 * 60_000).toISOString();
  const futurePayment = await apiRequest("/api/v1/payables/settlements", {
    method: "POST",
    email: "ana.horizonte@example.invalid",
    companyId: COMPANY_A1,
    branchId: BRANCH_A1_NORTH,
    idempotencyKey: `future-payment-${crypto.randomUUID()}`,
    body: {
      recordId,
      financialAccountId: FINANCIAL_ACCOUNT_A,
      amount: "1.0000",
      occurredAt: futureOccurredAt,
    },
  });
  assert.equal(futurePayment.response.status, 422);
  assert.ok(
    futurePayment.body.errors.some(
      (error) => error.code === "FUTURE_FINANCIAL_EVENT",
    ),
  );

  const datedPayment = await apiRequest("/api/v1/payables/settlements", {
    method: "POST",
    email: "ana.horizonte@example.invalid",
    companyId: COMPANY_A1,
    branchId: BRANCH_A1_NORTH,
    idempotencyKey: `dated-payment-${crypto.randomUUID()}`,
    body: {
      recordId,
      financialAccountId: FINANCIAL_ACCOUNT_A,
      amount: "1.0000",
      occurredAt: "2026-07-20T09:00:00-04:00",
    },
  });
  assert.equal(datedPayment.response.status, 201);
  const earlyReversal = await apiRequest("/api/v1/payables/reversals", {
    method: "POST",
    email: "ana.horizonte@example.invalid",
    companyId: COMPANY_A1,
    branchId: BRANCH_A1_NORTH,
    idempotencyKey: `early-reversal-${crypto.randomUUID()}`,
    body: {
      recordId,
      eventId: datedPayment.body.data.event.id,
      occurredAt: "2026-07-19T09:00:00-04:00",
      reason: "Data anterior para validação",
    },
  });
  assert.equal(earlyReversal.response.status, 422);
  assert.ok(
    earlyReversal.body.errors.some(
      (error) => error.code === "REVERSAL_BEFORE_SETTLEMENT",
    ),
  );

  const futureReversal = await apiRequest("/api/v1/payables/reversals", {
    method: "POST",
    email: "ana.horizonte@example.invalid",
    companyId: COMPANY_A1,
    branchId: BRANCH_A1_NORTH,
    idempotencyKey: `future-reversal-${crypto.randomUUID()}`,
    body: {
      recordId,
      eventId: datedPayment.body.data.event.id,
      occurredAt: futureOccurredAt,
      reason: "Data futura para validação",
    },
  });
  assert.equal(futureReversal.response.status, 422);
  assert.ok(
    futureReversal.body.errors.some(
      (error) => error.code === "FUTURE_FINANCIAL_EVENT",
    ),
  );
});

test("recebimento parcial, quitacao e estorno preservam a projecao", async () => {
  const title = await createTitle("receivable");
  assert.equal(title.response.status, 201);
  const recordId = title.body.data.id;
  const partial = await apiRequest("/api/v1/receivables/settlements", {
    method: "POST",
    email: "ana.horizonte@example.invalid",
    companyId: COMPANY_A1,
    branchId: BRANCH_A1_NORTH,
    idempotencyKey: `receipt-partial-${crypto.randomUUID()}`,
    body: {
      recordId,
      financialAccountId: FINANCIAL_ACCOUNT_A,
      amount: "250.0000",
      interestAmount: "2.0000",
      fineAmount: "1.0000",
      discountAmount: "0.5000",
    },
  });
  assert.equal(partial.response.status, 201);
  assert.equal(partial.body.data.record.status, "PARTIAL");
  assert.equal(Number(partial.body.data.event.cashAmount), 252.5);

  const settled = await apiRequest("/api/v1/receivables/settlements", {
    method: "POST",
    email: "ana.horizonte@example.invalid",
    companyId: COMPANY_A1,
    branchId: BRANCH_A1_NORTH,
    idempotencyKey: `receipt-full-${crypto.randomUUID()}`,
    body: {
      recordId,
      financialAccountId: FINANCIAL_ACCOUNT_A,
      amount: "750.0000",
    },
  });
  assert.equal(settled.response.status, 201);
  assert.equal(settled.body.data.record.status, "SETTLED");

  const reversed = await apiRequest("/api/v1/receivables/reversals", {
    method: "POST",
    email: "ana.horizonte@example.invalid",
    companyId: COMPANY_A1,
    branchId: BRANCH_A1_NORTH,
    idempotencyKey: `receipt-reversal-${crypto.randomUUID()}`,
    body: {
      recordId,
      eventId: partial.body.data.event.id,
      reason: "Recebimento duplicado no extrato de origem",
    },
  });
  assert.equal(reversed.response.status, 201);
  assert.equal(reversed.body.data.record.status, "PARTIAL");
  assert.equal(Number(reversed.body.data.record.outstandingAmount), 250);

  const history = await apiRequest(
    `/api/v1/receivables/history?recordId=${recordId}`,
    {
      email: "ana.horizonte@example.invalid",
      companyId: COMPANY_A1,
      branchId: BRANCH_A1_NORTH,
    },
  );
  assert.equal(history.response.status, 200);
  assert.equal(history.body.data.audit.projectionVerified, true);
  assert.equal(
    history.body.data.events.filter((item) => item.type === "REVERSAL").length,
    1,
  );
});

test(
  "idempotencia concorrente de baixa cria um unico evento",
  { timeout: INTEGRATION_TEST_TIMEOUT_MS },
  async () => {
    const title = await createTitle("payable", "100.0000");
    assert.equal(title.response.status, 201);
    const key = `concurrent-payment-${crypto.randomUUID()}`;
    const options = {
      method: "POST",
      email: "ana.horizonte@example.invalid",
      companyId: COMPANY_A1,
      branchId: BRANCH_A1_NORTH,
      idempotencyKey: key,
      body: {
        recordId: title.body.data.id,
        financialAccountId: FINANCIAL_ACCOUNT_A,
        amount: "40.0000",
      },
    };
    const [first, second] = await Promise.all([
      apiRequest("/api/v1/payables/settlements", options),
      apiRequest("/api/v1/payables/settlements", options),
    ]);
    assert.equal(first.response.status, 201);
    assert.equal(second.response.status, 201);
    assert.equal(first.body.data.event.id, second.body.data.event.id);
    assert.deepEqual(
      [
        first.response.headers.get("idempotency-replayed"),
        second.response.headers.get("idempotency-replayed"),
      ].sort(),
      [null, "true"],
    );

    const count = await adminPool.query(
      "SELECT count(*)::int AS count FROM payments WHERE payable_id = $1",
      [title.body.data.id],
    );
    assert.equal(count.rows[0].count, 1);
  },
);

test(
  "baixas concorrentes com chaves diferentes nao excedem o saldo",
  { timeout: INTEGRATION_TEST_TIMEOUT_MS },
  async () => {
    const title = await createTitle("payable", "100.0000");
    assert.equal(title.response.status, 201);
    const settlement = (key) =>
      apiRequest("/api/v1/payables/settlements", {
        method: "POST",
        email: "ana.horizonte@example.invalid",
        companyId: COMPANY_A1,
        branchId: BRANCH_A1_NORTH,
        idempotencyKey: key,
        body: {
          recordId: title.body.data.id,
          financialAccountId: FINANCIAL_ACCOUNT_A,
          amount: "70.0000",
        },
      });

    const results = await Promise.all([
      settlement(`concurrent-over-a-${crypto.randomUUID()}`),
      settlement(`concurrent-over-b-${crypto.randomUUID()}`),
    ]);
    assert.deepEqual(
      results.map((result) => result.response.status).sort(),
      [201, 409],
    );

    const state = await adminPool.query(
      `SELECT
         (SELECT count(*)::int FROM payments WHERE payable_id = $1) AS events,
         (SELECT outstanding_amount::text FROM payables WHERE id = $1) AS outstanding`,
      [title.body.data.id],
    );
    assert.equal(state.rows[0].events, 1);
    assert.equal(Number(state.rows[0].outstanding), 30);
  },
);

test("eventos e estornos sao append-only inclusive para o owner", async () => {
  const title = await createTitle("payable", "30.0000");
  assert.equal(title.response.status, 201);
  const payment = await apiRequest("/api/v1/payables/settlements", {
    method: "POST",
    email: "ana.horizonte@example.invalid",
    companyId: COMPANY_A1,
    branchId: BRANCH_A1_NORTH,
    idempotencyKey: `immutable-payment-${crypto.randomUUID()}`,
    body: {
      recordId: title.body.data.id,
      financialAccountId: FINANCIAL_ACCOUNT_A,
      amount: "10.0000",
    },
  });
  assert.equal(payment.response.status, 201);
  const reversed = await apiRequest("/api/v1/payables/reversals", {
    method: "POST",
    email: "ana.horizonte@example.invalid",
    companyId: COMPANY_A1,
    branchId: BRANCH_A1_NORTH,
    idempotencyKey: `immutable-reversal-${crypto.randomUUID()}`,
    body: {
      recordId: title.body.data.id,
      eventId: payment.body.data.event.id,
      reason: "Validacao de imutabilidade da trilha",
    },
  });
  assert.equal(reversed.response.status, 201);

  for (const operation of [
    () =>
      adminPool.query(
        "UPDATE payments SET note = 'alteracao proibida' WHERE id = $1",
        [payment.body.data.event.id],
      ),
    () =>
      adminPool.query("DELETE FROM payments WHERE id = $1", [
        payment.body.data.event.id,
      ]),
    () =>
      adminPool.query(
        "UPDATE payment_reversals SET reason = 'alteracao proibida' WHERE id = $1",
        [reversed.body.data.event.reversal.id],
      ),
  ]) {
    await assert.rejects(operation(), (error) => error?.code === "23000");
  }

  await assert.rejects(
    adminPool.query(
      `INSERT INTO payments (
         tenant_id, company_id, branch_id, payable_id,
         financial_account_id, principal_amount, cash_amount,
         occurred_at, created_by_id
       ) VALUES ($1, $2, $3, $4, $5, 1, 1, now(), $6)`,
      [
        TENANT_A,
        COMPANY_A1,
        BRANCH_A1_NORTH,
        title.body.data.id,
        FINANCIAL_ACCOUNT_B,
        USER_A_ADMIN,
      ],
    ),
    (error) => error?.code === "23514" || error?.code === "23503",
  );
  await assert.rejects(
    adminPool.query(
      `INSERT INTO payments (
         tenant_id, company_id, branch_id, payable_id,
         financial_account_id, principal_amount, cash_amount,
         occurred_at, created_by_id
       ) VALUES ($1, $2, $3, $4, $5, 1, 1, now() + interval '1 hour', $6)`,
      [
        TENANT_A,
        COMPANY_A1,
        BRANCH_A1_NORTH,
        title.body.data.id,
        FINANCIAL_ACCOUNT_A,
        USER_A_ADMIN,
      ],
    ),
    (error) => error?.code === "23514",
  );
  await assert.rejects(
    adminPool.query(
      `INSERT INTO payments (
         tenant_id, company_id, branch_id, payable_id,
         financial_account_id, principal_amount, cash_amount,
         occurred_at, created_by_id
       ) VALUES ($1, $2, $3, $4, $5, 1, 1, '-infinity', $6)`,
      [
        TENANT_A,
        COMPANY_A1,
        BRANCH_A1_NORTH,
        title.body.data.id,
        FINANCIAL_ACCOUNT_A,
        USER_A_ADMIN,
      ],
    ),
    (error) => error?.code === "23514",
  );
  await assert.rejects(
    adminPool.query(
      `INSERT INTO payment_reversals (
         tenant_id, company_id, branch_id, payment_id,
         reason, occurred_at, created_by_id
       ) VALUES ($1, $2, $3, $4, 'Estorno futuro direto', now() + interval '1 hour', $5)`,
      [
        TENANT_A,
        COMPANY_A1,
        BRANCH_A1_NORTH,
        payment.body.data.event.id,
        USER_A_ADMIN,
      ],
    ),
    (error) => error?.code === "23514",
  );

  const receivable = await createTitle("receivable", "20.0000");
  assert.equal(receivable.response.status, 201);
  const receipt = await apiRequest("/api/v1/receivables/settlements", {
    method: "POST",
    email: "ana.horizonte@example.invalid",
    companyId: COMPANY_A1,
    branchId: BRANCH_A1_NORTH,
    idempotencyKey: `immutable-receipt-${crypto.randomUUID()}`,
    body: {
      recordId: receivable.body.data.id,
      financialAccountId: FINANCIAL_ACCOUNT_A,
      amount: "5.0000",
    },
  });
  assert.equal(receipt.response.status, 201);
  const receiptReversal = await apiRequest("/api/v1/receivables/reversals", {
    method: "POST",
    email: "ana.horizonte@example.invalid",
    companyId: COMPANY_A1,
    branchId: BRANCH_A1_NORTH,
    idempotencyKey: `immutable-receipt-reversal-${crypto.randomUUID()}`,
    body: {
      recordId: receivable.body.data.id,
      eventId: receipt.body.data.event.id,
      reason: "Validação append-only do recebimento",
    },
  });
  assert.equal(receiptReversal.response.status, 201);
  await assert.rejects(
    adminPool.query("UPDATE receipts SET note = 'proibido' WHERE id = $1", [
      receipt.body.data.event.id,
    ]),
    (error) => error?.code === "23000",
  );
  await assert.rejects(
    adminPool.query("DELETE FROM receipt_reversals WHERE id = $1", [
      receiptReversal.body.data.event.reversal.id,
    ]),
    (error) => error?.code === "23000",
  );
  await assert.rejects(
    adminPool.query(
      `INSERT INTO receipts (
         tenant_id, company_id, branch_id, receivable_id,
         financial_account_id, principal_amount, cash_amount,
         occurred_at, created_by_id
       ) VALUES ($1, $2, $3, $4, $5, 1, 1, now() + interval '1 hour', $6)`,
      [
        TENANT_A,
        COMPANY_A1,
        BRANCH_A1_NORTH,
        receivable.body.data.id,
        FINANCIAL_ACCOUNT_A,
        USER_A_ADMIN,
      ],
    ),
    (error) => error?.code === "23514",
  );
  await assert.rejects(
    adminPool.query(
      `INSERT INTO receipt_reversals (
         tenant_id, company_id, branch_id, receipt_id,
         reason, occurred_at, created_by_id
       ) VALUES ($1, $2, $3, $4, 'Estorno futuro direto', now() + interval '1 hour', $5)`,
      [
        TENANT_A,
        COMPANY_A1,
        BRANCH_A1_NORTH,
        receipt.body.data.event.id,
        USER_A_ADMIN,
      ],
    ),
    (error) => error?.code === "23514",
  );
});

test("banco protege projecoes e referencias de catalogo mesmo fora da API", async () => {
  const title = await createTitle("payable", "80.0000");
  assert.equal(title.response.status, 201);
  await assert.rejects(
    adminPool.query(
      "UPDATE payables SET outstanding_amount = 1, status = 'PARTIAL' WHERE id = $1",
      [title.body.data.id],
    ),
    (error) => error?.code === "23000",
  );

  const southCounterpartyId = crypto.randomUUID();
  await adminPool.query(
    `INSERT INTO counterparties (
       id, tenant_id, company_id, branch_id, code, name, kind, created_by_id
     ) VALUES ($1, $2, $3, $4, $5, 'Fornecedor da filial Sul', 'SUPPLIER', $6)`,
    [
      southCounterpartyId,
      TENANT_A,
      COMPANY_A1,
      BRANCH_A1_SOUTH,
      `SOUTH-${crypto.randomUUID().slice(0, 8)}`,
      USER_A_ADMIN,
    ],
  );
  try {
    await assert.rejects(
      adminPool.query(
        "UPDATE payables SET counterparty_id = $1 WHERE id = $2",
        [southCounterpartyId, title.body.data.id],
      ),
      (error) => error?.code === "23514",
    );
    await assert.rejects(
      adminPool.query("UPDATE payables SET category_id = $1 WHERE id = $2", [
        CATEGORY_A_RECEIVABLE,
        title.body.data.id,
      ]),
      (error) => error?.code === "23514",
    );
  } finally {
    await adminPool.query("DELETE FROM counterparties WHERE id = $1", [
      southCounterpartyId,
    ]);
  }
});

test("falha de auditoria reverte evento, projecao e idempotencia", async () => {
  const title = await createTitle("payable", "50.0000");
  assert.equal(title.response.status, 201);
  const key = `rollback-event-${crypto.randomUUID()}`;

  await adminPool.query(`
    CREATE OR REPLACE FUNCTION app.phase_two_fail_audit()
    RETURNS trigger
    LANGUAGE plpgsql
    AS $function$
    BEGIN
      RAISE EXCEPTION 'phase two forced audit failure';
    END
    $function$;
    CREATE TRIGGER phase_two_fail_audit
      BEFORE INSERT ON audit_logs
      FOR EACH ROW EXECUTE FUNCTION app.phase_two_fail_audit();
  `);
  try {
    const failed = await apiRequest("/api/v1/payables/settlements", {
      method: "POST",
      email: "ana.horizonte@example.invalid",
      companyId: COMPANY_A1,
      branchId: BRANCH_A1_NORTH,
      idempotencyKey: key,
      body: {
        recordId: title.body.data.id,
        financialAccountId: FINANCIAL_ACCOUNT_A,
        amount: "20.0000",
      },
    });
    assert.equal(failed.response.status, 503);
  } finally {
    await adminPool.query(
      "DROP TRIGGER IF EXISTS phase_two_fail_audit ON audit_logs; DROP FUNCTION IF EXISTS app.phase_two_fail_audit()",
    );
  }

  const remains = await adminPool.query(
    `SELECT
       (SELECT outstanding_amount::text FROM payables WHERE id = $1) AS outstanding,
       (SELECT count(*)::int FROM payments WHERE payable_id = $1) AS events,
       (SELECT count(*)::int FROM idempotency_records WHERE key = $2) AS idempotency`,
    [title.body.data.id, key],
  );
  assert.equal(Number(remains.rows[0].outstanding), 50);
  assert.equal(remains.rows[0].events, 0);
  assert.equal(remains.rows[0].idempotency, 0);
});
