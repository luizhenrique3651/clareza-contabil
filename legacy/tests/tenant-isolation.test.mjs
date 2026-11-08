import assert from "node:assert/strict";
import test from "node:test";

import {
  AuthenticationRequiredError,
  TenantIsolationError,
  assertRecordAccess,
  companyScopedData,
  companyScopedWhere,
  getTenantContext,
  runWithTenantContext,
  withTenantTransaction,
} from "../server/tenant-context.mjs";

const TENANT_A = "10000000-0000-4000-8000-000000000001";
const TENANT_B = "10000000-0000-4000-8000-000000000002";
const COMPANY_A = "20000000-0000-4000-8000-000000000001";
const COMPANY_A_SIBLING = "20000000-0000-4000-8000-000000000099";
const COMPANY_B = "20000000-0000-4000-8000-000000000002";
const BRANCH_A = "30000000-0000-4000-8000-000000000001";
const BRANCH_B = "30000000-0000-4000-8000-000000000002";
const USER_A = "40000000-0000-4000-8000-000000000001";
const USER_B = "40000000-0000-4000-8000-000000000002";

function verifiedSession({ tenantId, companyId, userId, sessionId }) {
  return Object.freeze({
    tenantId,
    userId,
    companyIds: Object.freeze([companyId]),
    branchCompanyIds: Object.freeze([]),
    branchIds: Object.freeze([]),
    activeCompanyId: companyId,
    activeBranchId: null,
    tenantWideAccess: false,
    permissions: Object.freeze(["payables.view"]),
    authentication: Object.freeze({
      verified: true,
      source: "database-session",
      sessionId,
      expiresAt: new Date(Date.now() + 60_000).toISOString(),
    }),
  });
}

const SESSION_A = verifiedSession({
  tenantId: TENANT_A,
  companyId: COMPANY_A,
  userId: USER_A,
  sessionId: "97000000-0000-4000-8000-000000000001",
});

const SESSION_B = verifiedSession({
  tenantId: TENANT_B,
  companyId: COMPANY_B,
  userId: USER_B,
  sessionId: "97000000-0000-4000-8000-000000000002",
});

const payables = [
  {
    id: "payable-a",
    tenantId: TENANT_A,
    companyId: COMPANY_A,
    description: "Somente Empresa A",
  },
  {
    id: "payable-b",
    tenantId: TENANT_B,
    companyId: COMPANY_B,
    description: "Confidencial Empresa B",
  },
  {
    id: "payable-a-sibling",
    tenantId: TENANT_A,
    companyId: COMPANY_A_SIBLING,
    description: "Outra empresa do mesmo tenant A",
  },
];

function listPayables(untrustedWhere = {}) {
  const where = companyScopedWhere(untrustedWhere);
  return payables.filter(
    (row) => row.tenantId === where.tenantId && row.companyId === where.companyId,
  );
}

test("falha de forma fechada sem sessão autenticada", () => {
  assert.throws(() => getTenantContext(), AuthenticationRequiredError);
  assert.throws(
    () => runWithTenantContext({ tenantId: TENANT_A }, () => null),
    AuthenticationRequiredError,
  );
});

test("tenant e empresa vêm da sessão verificada, não do filtro do frontend", async () => {
  await runWithTenantContext(SESSION_A, async () => {
    assert.deepEqual(listPayables().map((row) => row.id), ["payable-a"]);

    assert.throws(
      () => listPayables({ tenantId: TENANT_B, companyId: COMPANY_B }),
      TenantIsolationError,
    );

    assert.throws(
      () => companyScopedData({ tenantId: TENANT_B, description: "tentativa" }),
      TenantIsolationError,
    );

    assert.deepEqual(companyScopedData({ description: "válido" }), {
      description: "válido",
      tenantId: TENANT_A,
      companyId: COMPANY_A,
    });
  });
});

test("usuário da Empresa A nunca consegue ler registro da Empresa B", async () => {
  await runWithTenantContext(SESSION_A, async () => {
    const recordFromB = payables.find((row) => row.id === "payable-b");
    const recordFromSiblingCompany = payables.find((row) => row.id === "payable-a-sibling");
    assert.throws(() => assertRecordAccess(recordFromB), TenantIsolationError);
    assert.throws(() => assertRecordAccess(recordFromSiblingCompany), TenantIsolationError);
  });
});

test("seleção de empresa solicitada é validada contra as empresas da sessão", () => {
  assert.throws(
    () => runWithTenantContext(SESSION_A, () => null, { activeCompanyId: COMPANY_B }),
    TenantIsolationError,
  );
});

test("unidade solicitada é validada contra o contexto autenticado", async () => {
  const branchSession = Object.freeze({
    ...SESSION_A,
    companyIds: Object.freeze([]),
    branchCompanyIds: Object.freeze([COMPANY_A]),
    branchIds: Object.freeze([BRANCH_A]),
    activeBranchId: BRANCH_A,
  });

  await runWithTenantContext(branchSession, async () => {
    assert.deepEqual(getTenantContext().branchIds, [BRANCH_A]);
    assert.equal(getTenantContext().activeBranchId, BRANCH_A);
  });

  assert.throws(
    () =>
      runWithTenantContext(
        { ...branchSession, activeBranchId: BRANCH_B },
        () => null,
      ),
    TenantIsolationError,
  );
});

test("AsyncLocalStorage mantém solicitações concorrentes isoladas", async () => {
  const [seenByA, seenByB] = await Promise.all([
    runWithTenantContext(SESSION_A, async () => {
      await new Promise((resolve) => setImmediate(resolve));
      return [getTenantContext().tenantId, ...listPayables().map((row) => row.id)];
    }),
    runWithTenantContext(SESSION_B, async () => {
      await new Promise((resolve) => setImmediate(resolve));
      return [getTenantContext().tenantId, ...listPayables().map((row) => row.id)];
    }),
  ]);

  assert.deepEqual(seenByA, [TENANT_A, "payable-a"]);
  assert.deepEqual(seenByB, [TENANT_B, "payable-b"]);
});

test("transação PostgreSQL recebe contexto autenticado por parâmetros vinculados", async () => {
  const calls = [];
  const transaction = {
    async $executeRawUnsafe(sql, ...parameters) {
      calls.push({ kind: "execute", sql, parameters });
      return 0;
    },
    async $queryRawUnsafe(sql, ...parameters) {
      calls.push({ kind: "query", sql, parameters });
      return [];
    },
  };
  const prisma = {
    async $transaction(operation) {
      return operation(transaction);
    },
  };

  await runWithTenantContext(SESSION_A, () =>
    withTenantTransaction(prisma, async (_transaction, context) => context.tenantId),
  );

  assert.equal(calls[0].sql, "SET LOCAL ROLE clarity_app");
  assert.match(calls[1].sql, /set_config\('app\.tenant_id', \$1, true\)/);
  assert.deepEqual(calls[1].parameters, [
    TENANT_A,
    USER_A,
    COMPANY_A,
    "",
    "false",
  ]);
  assert.equal(calls[1].sql.includes(TENANT_A), false, "tenant não deve ser interpolado no SQL");
});
