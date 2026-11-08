import assert from "node:assert/strict";
import test from "node:test";

import {
  createFinancialRecord,
  financialBusinessDate,
  financialStatusForDisplay,
  FinancialValidationError,
  IdempotencyConflictError,
  listFinancialRecords,
} from "../server/financial-records.mjs";

const TENANT_ID = "10000000-0000-4000-8000-000000000001";
const COMPANY_ID = "20000000-0000-4000-8000-000000000001";
const USER_ID = "40000000-0000-4000-8000-000000000001";
const RECORD_ID = "80000000-0000-4000-8000-000000000001";

function verifiedSession(permissions) {
  return Object.freeze({
    tenantId: TENANT_ID,
    userId: USER_ID,
    companyIds: Object.freeze([COMPANY_ID]),
    branchCompanyIds: Object.freeze([]),
    branchIds: Object.freeze([]),
    activeCompanyId: COMPANY_ID,
    activeBranchId: null,
    tenantWideAccess: false,
    permissions: Object.freeze(permissions),
    authentication: Object.freeze({
      verified: true,
      source: "test",
      sessionId: "97000000-0000-4000-8000-000000000001",
      expiresAt: new Date(Date.now() + 60_000).toISOString(),
    }),
  });
}

function record(overrides = {}) {
  return {
    id: RECORD_ID,
    tenantId: TENANT_ID,
    companyId: COMPANY_ID,
    branchId: null,
    counterpartyName: "Fornecedor Teste",
    description: "Serviço mensal",
    documentNumber: "DOC-1",
    issueDate: new Date("2026-07-20T00:00:00.000Z"),
    dueDate: new Date("2026-08-20T00:00:00.000Z"),
    amount: "1500.2500",
    outstandingAmount: "1500.2500",
    currencyCode: "BRL",
    status: "OPEN",
    paidAt: null,
    receivedAt: null,
    createdAt: new Date("2026-07-24T10:00:00.000Z"),
    updatedAt: new Date("2026-07-24T10:00:00.000Z"),
    ...overrides,
  };
}

function prismaMock({ existingIdempotency = null, records = [record()] } = {}) {
  const calls = [];
  const transaction = {
    async $executeRawUnsafe(sql, ...parameters) {
      calls.push({ target: "sql", sql, parameters });
      return 0;
    },
    async $queryRawUnsafe(sql, ...parameters) {
      calls.push({ target: "sql", sql, parameters });
      return [];
    },
    idempotencyRecord: {
      async findUnique(args) {
        calls.push({ target: "idempotency.findUnique", args });
        return existingIdempotency;
      },
      async create(args) {
        calls.push({ target: "idempotency.create", args });
        return args.data;
      },
    },
    payable: {
      async create(args) {
        calls.push({ target: "payable.create", args });
        return record(args.data);
      },
      async findMany(args) {
        calls.push({ target: "payable.findMany", args });
        return records;
      },
    },
    receivable: {
      async create(args) {
        calls.push({ target: "receivable.create", args });
        return record(args.data);
      },
      async findMany(args) {
        calls.push({ target: "receivable.findMany", args });
        return records;
      },
    },
    auditLog: {
      async create(args) {
        calls.push({ target: "auditLog.create", args });
        return args.data;
      },
    },
  };

  return {
    calls,
    prisma: {
      async $transaction(operation) {
        return operation(transaction);
      },
    },
  };
}

const validInput = {
  counterpartyName: "Fornecedor Teste",
  description: "Serviço mensal",
  documentNumber: "DOC-1",
  issueDate: "2026-07-20",
  dueDate: "2026-08-20",
  amount: "1500.2500",
  currencyCode: "BRL",
};

test("vencimento usa a virada do dia financeiro de Manaus sem persistir estado temporal", () => {
  const beforeMidnight = new Date("2026-07-29T03:59:59.999Z");
  const atMidnight = new Date("2026-07-29T04:00:00.000Z");
  const openRecord = record({
    dueDate: new Date("2026-07-28T00:00:00.000Z"),
    status: "OPEN",
  });

  assert.equal(financialBusinessDate(beforeMidnight), "2026-07-28");
  assert.equal(financialBusinessDate(atMidnight), "2026-07-29");
  assert.equal(financialStatusForDisplay(openRecord, beforeMidnight), "OPEN");
  assert.equal(financialStatusForDisplay(openRecord, atMidnight), "OVERDUE");
  assert.equal(
    financialStatusForDisplay({ ...openRecord, status: "PARTIAL" }, atMidnight),
    "PARTIAL",
  );
});

test("criação de conta a pagar deriva tenant, empresa e ator da sessão", async () => {
  const mock = prismaMock();
  const result = await createFinancialRecord({
    prisma: mock.prisma,
    session: verifiedSession(["payables.manage"]),
    kind: "payable",
    input: validInput,
    idempotencyKey: "payable-test-001",
  });

  assert.equal(result.status, 201);
  assert.equal(result.replayed, false);
  const createCall = mock.calls.find((call) => call.target === "payable.create");
  assert.equal(createCall.args.data.tenantId, TENANT_ID);
  assert.equal(createCall.args.data.companyId, COMPANY_ID);
  assert.equal(createCall.args.data.createdById, USER_ID);
  assert.equal(createCall.args.data.outstandingAmount, "1500.2500");
  assert.equal(createCall.args.data.status, "OPEN");
  const lockCall = mock.calls.find(
    (call) =>
      call.target === "sql" &&
      call.sql.includes("pg_advisory_xact_lock"),
  );
  assert.ok(lockCall);
  assert.equal(lockCall.parameters.length, 1);
  const auditCall = mock.calls.find((call) => call.target === "auditLog.create");
  assert.match(auditCall.args.data.integrityHash, /^[0-9a-f]{64}$/);
  assert.deepEqual(auditCall.args.data.redactedFields, ["counterpartyName"]);
  assert.ok(mock.calls.some((call) => call.target === "idempotency.create"));
});

test("payload não pode substituir o escopo autenticado", async () => {
  const mock = prismaMock();
  await assert.rejects(
    createFinancialRecord({
      prisma: mock.prisma,
      session: verifiedSession(["payables.manage"]),
      kind: "payable",
      input: { ...validInput, tenantId: "10000000-0000-4000-8000-000000000002" },
      idempotencyKey: "payable-test-002",
    }),
    FinancialValidationError,
  );
  assert.equal(
    mock.calls.some((call) => call.target === "payable.create"),
    false,
  );
});

test("retry idempotente devolve a resposta armazenada sem nova gravação", async () => {
  const firstMock = prismaMock();
  const first = await createFinancialRecord({
    prisma: firstMock.prisma,
    session: verifiedSession(["payables.manage"]),
    kind: "payable",
    input: validInput,
    idempotencyKey: "payable-test-003",
  });
  const saved = firstMock.calls.find(
    (call) => call.target === "idempotency.create",
  ).args.data;

  const replayMock = prismaMock({ existingIdempotency: saved });
  const replay = await createFinancialRecord({
    prisma: replayMock.prisma,
    session: verifiedSession(["payables.manage"]),
    kind: "payable",
    input: validInput,
    idempotencyKey: "payable-test-003",
  });

  assert.equal(replay.replayed, true);
  assert.deepEqual(replay.body, first.body);
  assert.equal(
    replayMock.calls.some((call) => call.target === "payable.create"),
    false,
  );
});

test("reutilizar a chave com outro conteúdo gera conflito", async () => {
  const existing = {
    requestHash: "0".repeat(64),
    responseStatus: 201,
    responseBody: { data: { id: RECORD_ID } },
  };
  const mock = prismaMock({ existingIdempotency: existing });

  await assert.rejects(
    createFinancialRecord({
      prisma: mock.prisma,
      session: verifiedSession(["payables.manage"]),
      kind: "payable",
      input: validInput,
      idempotencyKey: "payable-test-004",
    }),
    IdempotencyConflictError,
  );
});

test("listagem aplica empresa autenticada, permissão e paginação", async () => {
  const mock = prismaMock({ records: [record()] });
  const result = await listFinancialRecords({
    prisma: mock.prisma,
    session: verifiedSession(["payables.view"]),
    kind: "payable",
    filters: { limit: "25", status: "OPEN" },
  });

  assert.equal(result.data.length, 1);
  assert.equal(result.page.hasMore, false);
  const listCall = mock.calls.find((call) => call.target === "payable.findMany");
  assert.equal(listCall.args.where.tenantId, TENANT_ID);
  assert.equal(listCall.args.where.companyId, COMPANY_ID);
  assert.equal(listCall.args.where.status, "OPEN");
  assert.equal(listCall.args.take, 26);
});
