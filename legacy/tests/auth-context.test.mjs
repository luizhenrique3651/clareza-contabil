import assert from "node:assert/strict";
import test from "node:test";

import {
  resolveIdentityAccess,
  summarizeIdentityAccess,
  verifiedSessionForSelection,
} from "../server/auth-context.mjs";

const accessRows = [
  {
    tenant_id: "10000000-0000-4000-8000-000000000001",
    tenant_name: "Grupo Horizonte",
    user_id: "40000000-0000-4000-8000-000000000001",
    display_name: "Ana Souza",
    email: "ana@example.com",
    scope: "COMPANY",
    company_id: "20000000-0000-4000-8000-000000000001",
    company_name: "Horizonte",
    branch_id: "30000000-0000-4000-8000-000000000002",
    branch_name: "Matriz",
    permissions: ["payables.view", "payables.manage"],
  },
  {
    tenant_id: "10000000-0000-4000-8000-000000000001",
    tenant_name: "Grupo Horizonte",
    user_id: "40000000-0000-4000-8000-000000000001",
    display_name: "Ana Souza",
    email: "ana@example.com",
    scope: "BRANCH",
    company_id: "20000000-0000-4000-8000-000000000002",
    company_name: "Horizonte Serviços",
    branch_id: "30000000-0000-4000-8000-000000000001",
    branch_name: "Unidade Norte",
    permissions: ["receivables.view"],
  },
];

test("bootstrap de identidade usa parâmetro vinculado e normaliza o retorno", async () => {
  const calls = [];
  const prisma = {
    async $queryRawUnsafe(sql, ...parameters) {
      calls.push({ sql, parameters });
      return accessRows;
    },
  };

  const rows = await resolveIdentityAccess(prisma, " ANA@EXAMPLE.COM ");
  assert.equal(rows.length, 2);
  assert.equal(calls[0].sql, "SELECT * FROM app.resolve_identity_access($1)");
  assert.deepEqual(calls[0].parameters, ["ana@example.com"]);
  assert.equal(calls[0].sql.includes("ana@example.com"), false);
});

test("resumo expõe somente os contextos autorizados da identidade", async () => {
  const rows = await resolveIdentityAccess(
    { async $queryRawUnsafe() { return [...accessRows].reverse(); } },
    "ana@example.com",
  );
  const summary = summarizeIdentityAccess(rows, {
    email: "ana@example.com",
    displayName: "Ana",
  });

  assert.equal(summary.tenants.length, 1);
  assert.equal(summary.tenants[0].companies.length, 2);
  assert.deepEqual(
    summary.tenants[0].companies.map((company) => company.id),
    [
      "20000000-0000-4000-8000-000000000001",
      "20000000-0000-4000-8000-000000000002",
    ],
  );
  assert.deepEqual(summary.tenants[0].companies[0].permissions, [
    "payables.manage",
    "payables.view",
  ]);
  assert.deepEqual(summary.tenants[0].companies[0].branches, [
    {
      id: "30000000-0000-4000-8000-000000000002",
      name: "Matriz",
      permissions: ["payables.manage", "payables.view"],
    },
  ]);
  assert.deepEqual(summary.tenants[0].companies[1].branches, [
    {
      id: "30000000-0000-4000-8000-000000000001",
      name: "Unidade Norte",
      permissions: ["receivables.view"],
    },
  ]);
  assert.deepEqual(summary.tenants[0].companies[1].permissions, []);
});

test("sessão verificada aplica permissões apenas ao escopo selecionado", async () => {
  const rows = await resolveIdentityAccess(
    { async $queryRawUnsafe() { return accessRows; } },
    "ana@example.com",
  );

  const companySession = verifiedSessionForSelection(rows, {
    companyId: "20000000-0000-4000-8000-000000000001",
  });
  assert.deepEqual(companySession.permissions, [
    "payables.manage",
    "payables.view",
  ]);

  const companyBranchSession = verifiedSessionForSelection(rows, {
    companyId: "20000000-0000-4000-8000-000000000001",
    branchId: "30000000-0000-4000-8000-000000000002",
  });
  assert.equal(
    companyBranchSession.activeBranchId,
    "30000000-0000-4000-8000-000000000002",
  );

  assert.throws(
    () =>
      verifiedSessionForSelection(rows, {
        companyId: "20000000-0000-4000-8000-000000000001",
        branchId: "30000000-0000-4000-8000-000000000099",
      }),
    /unidade autorizada/,
  );

  assert.throws(
    () =>
      verifiedSessionForSelection(rows, {
        companyId: "20000000-0000-4000-8000-000000000002",
      }),
    /Selecione uma unidade autorizada/,
  );

  const branchSession = verifiedSessionForSelection(rows, {
    companyId: "20000000-0000-4000-8000-000000000002",
    branchId: "30000000-0000-4000-8000-000000000001",
  });
  assert.deepEqual(branchSession.permissions, ["receivables.view"]);
  assert.deepEqual(branchSession.branchIds, [
    "30000000-0000-4000-8000-000000000001",
  ]);
  assert.deepEqual(branchSession.companyIds, []);
  assert.deepEqual(branchSession.branchCompanyIds, [
    "20000000-0000-4000-8000-000000000002",
  ]);
});
