import assert from "node:assert/strict";
import test from "node:test";

import {
  BankImportConflictError,
  createBankImport,
  listBankImports,
  listBankTransactions,
} from "../server/bank-service.mjs";
import { FinancialValidationError } from "../server/financial-records.mjs";

const TENANT_ID = "10000000-0000-4000-8000-000000000001";
const COMPANY_ID = "20000000-0000-4000-8000-000000000001";
const USER_ID = "40000000-0000-4000-8000-000000000001";
const ACCOUNT_ID = "85000000-0000-4000-8000-000000000001";
const OTHER_ACCOUNT_ID = "85000000-0000-4000-8000-000000000002";
const BRANCH_A = "30000000-0000-4000-8000-000000000001";
const BRANCH_B = "30000000-0000-4000-8000-000000000002";
const IMPORT_ID = "91000000-0000-4000-8000-000000000001";
const TRANSACTION_ID = "92000000-0000-4000-8000-000000000001";

function verifiedSession(
  permissions,
  {
    companyIds = [COMPANY_ID],
    branchCompanyIds = [],
    branchIds = [],
    activeBranchId = null,
  } = {},
) {
  return Object.freeze({
    tenantId: TENANT_ID,
    userId: USER_ID,
    companyIds: Object.freeze(companyIds),
    branchCompanyIds: Object.freeze(branchCompanyIds),
    branchIds: Object.freeze(branchIds),
    activeCompanyId: COMPANY_ID,
    activeBranchId,
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

const input = Object.freeze({
  financialAccountId: ACCOUNT_ID,
  format: "CSV",
  sourceName: "extrato-julho.csv",
  content: [
    "date,amount,description,id",
    "2026-07-20,-42.50,Tarifa,bank-1",
    "2026-07-21,150.00,Recebimento,bank-2",
  ].join("\n"),
});

function importRecord(overrides = {}) {
  return {
    id: IMPORT_ID,
    tenantId: TENANT_ID,
    companyId: COMPANY_ID,
    branchId: null,
    financialAccountId: ACCOUNT_ID,
    format: "CSV",
    contentHash: "a".repeat(64),
    sourceName: "extrato-julho.csv",
    totalRows: 2,
    importedRows: 2,
    possibleDuplicates: 0,
    createdAt: new Date("2026-07-29T12:00:00.000Z"),
    ...overrides,
  };
}

function transactionRecord(overrides = {}) {
  return {
    id: TRANSACTION_ID,
    tenantId: TENANT_ID,
    companyId: COMPANY_ID,
    branchId: null,
    bankImportId: IMPORT_ID,
    financialAccountId: ACCOUNT_ID,
    sourceLine: 2,
    externalId: "bank-1",
    fingerprint: "b".repeat(64),
    bookedAt: new Date("2026-07-20T00:00:00.000Z"),
    amount: "-42.5000",
    currencyCode: "BRL",
    description: "Tarifa",
    memo: null,
    createdAt: new Date("2026-07-29T12:00:00.000Z"),
    ...overrides,
  };
}

function prismaMock({
  existingIdempotency = null,
  existingContent = null,
  existingExternal = null,
  existingFingerprints = [],
  accountBranchId = null,
  imports = [importRecord()],
  transactions = [transactionRecord()],
} = {}) {
  const calls = [];
  const account = {
    id: ACCOUNT_ID,
    tenantId: TENANT_ID,
    companyId: COMPANY_ID,
    branchId: accountBranchId,
    type: "CHECKING",
    currencyCode: "BRL",
    isActive: true,
    deletedAt: null,
  };
  const transaction = {
    async $executeRawUnsafe(sql, ...parameters) {
      calls.push({ target: "sql", sql, parameters });
      return 0;
    },
    async $queryRawUnsafe(sql, ...parameters) {
      calls.push({ target: "sql", sql, parameters });
      return [];
    },
    financialAccount: {
      async findFirst(args) {
        calls.push({ target: "financialAccount.findFirst", args });
        if (
          args.where.branchId !== undefined &&
          args.where.branchId !== account.branchId
        ) {
          return null;
        }
        return account;
      },
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
    bankImport: {
      async findFirst(args) {
        calls.push({ target: "bankImport.findFirst", args });
        return existingContent;
      },
      async create(args) {
        calls.push({ target: "bankImport.create", args });
        return importRecord({
          ...args.data,
          contentHash: args.data.contentHash,
        });
      },
      async findMany(args) {
        calls.push({ target: "bankImport.findMany", args });
        return imports;
      },
    },
    bankTransaction: {
      async findFirst(args) {
        calls.push({ target: "bankTransaction.findFirst", args });
        return existingExternal;
      },
      async findMany(args) {
        calls.push({ target: "bankTransaction.findMany", args });
        return args.select ? existingFingerprints : transactions;
      },
      async createMany(args) {
        calls.push({ target: "bankTransaction.createMany", args });
        return { count: args.data.length };
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

test("importação bancária grava lote e linhas atomicamente com auditoria", async () => {
  const firstParsedFingerprint = (
    await import("../server/bank-imports.mjs")
  ).parseBankImport(input).entries[0].fingerprint;
  const mock = prismaMock({
    existingFingerprints: [{ fingerprint: firstParsedFingerprint }],
  });
  const result = await createBankImport({
    prisma: mock.prisma,
    session: verifiedSession(["financial.manage"]),
    input,
    idempotencyKey: "bank-import-001",
  });

  assert.equal(result.status, 201);
  assert.equal(result.replayed, false);
  const importCall = mock.calls.find(
    (call) => call.target === "bankImport.create",
  );
  assert.equal(importCall.args.data.tenantId, TENANT_ID);
  assert.equal(importCall.args.data.companyId, COMPANY_ID);
  assert.equal(importCall.args.data.financialAccountId, ACCOUNT_ID);
  assert.equal(importCall.args.data.totalRows, 2);
  assert.equal(importCall.args.data.importedRows, 2);
  assert.equal(importCall.args.data.possibleDuplicates, 1);

  const transactionCall = mock.calls.find(
    (call) => call.target === "bankTransaction.createMany",
  );
  assert.equal(transactionCall.args.data.length, 2);
  assert.equal(transactionCall.args.data[0].currencyCode, "BRL");
  assert.equal(transactionCall.args.data[0].createdById, undefined);
  assert.ok(
    mock.calls.some(
      (call) =>
        call.target === "sql" &&
        call.sql.includes("pg_advisory_xact_lock"),
    ),
  );
  const audit = mock.calls.find((call) => call.target === "auditLog.create");
  assert.equal(audit.args.data.action, "bank.import.created");
  assert.match(audit.args.data.integrityHash, /^[0-9a-f]{64}$/);
  assert.ok(mock.calls.some((call) => call.target === "idempotency.create"));
});

test("retry de importação devolve resposta persistida sem nova gravação", async () => {
  const first = prismaMock();
  const created = await createBankImport({
    prisma: first.prisma,
    session: verifiedSession(["financial.manage"]),
    input,
    idempotencyKey: "bank-import-002",
  });
  const saved = first.calls.find(
    (call) => call.target === "idempotency.create",
  ).args.data;

  const replay = prismaMock({ existingIdempotency: saved });
  const replayed = await createBankImport({
    prisma: replay.prisma,
    session: verifiedSession(["financial.manage"]),
    input,
    idempotencyKey: "bank-import-002",
  });
  assert.equal(replayed.replayed, true);
  assert.deepEqual(replayed.body, created.body);
  assert.equal(
    replay.calls.some((call) => call.target === "bankImport.create"),
    false,
  );
});

test("identificador externo já importado rejeita o lote antes de gravar", async () => {
  const mock = prismaMock({ existingExternal: { id: TRANSACTION_ID } });
  await assert.rejects(
    createBankImport({
      prisma: mock.prisma,
      session: verifiedSession(["financial.manage"]),
      input,
      idempotencyKey: "bank-import-003",
    }),
    BankImportConflictError,
  );
  assert.equal(
    mock.calls.some((call) => call.target === "bankImport.create"),
    false,
  );
});

test("conteúdo bancário repetido é recusado sem duplicar transações", async () => {
  const mock = prismaMock({ existingContent: { id: IMPORT_ID } });
  await assert.rejects(
    createBankImport({
      prisma: mock.prisma,
      session: verifiedSession(["financial.manage"]),
      input,
      idempotencyKey: "bank-import-004",
    }),
    BankImportConflictError,
  );
  assert.equal(
    mock.calls.some((call) => call.target === "bankTransaction.createMany"),
    false,
  );
});

test("listagens bancárias aplicam empresa, filtros e paginação", async () => {
  const mock = prismaMock();
  const imports = await listBankImports({
    prisma: mock.prisma,
    session: verifiedSession(["financial.view"]),
    filters: { financialAccountId: ACCOUNT_ID, limit: "25" },
  });
  const transactions = await listBankTransactions({
    prisma: mock.prisma,
    session: verifiedSession(["financial.view"]),
    filters: { bankImportId: IMPORT_ID, limit: "25" },
  });

  assert.equal(imports.data.length, 1);
  assert.equal(transactions.data.length, 1);
  assert.equal(transactions.data[0].amount, "-42.5000");
  const importList = mock.calls.find(
    (call) => call.target === "bankImport.findMany",
  );
  assert.equal(importList.args.where.tenantId, TENANT_ID);
  assert.equal(importList.args.where.companyId, COMPANY_ID);
  assert.equal(importList.args.where.financialAccountId, ACCOUNT_ID);
  assert.equal(importList.args.take, 26);
  const transactionList = mock.calls.find(
    (call) =>
      call.target === "bankTransaction.findMany" && !call.args.select,
  );
  assert.equal(transactionList.args.where.bankImportId, IMPORT_ID);
  assert.equal(transactionList.args.take, 26);
});

test("unidade ativa limita a conta importada e todas as listagens", async () => {
  const restrictedSession = verifiedSession(["financial.manage"], {
    companyIds: [],
    branchCompanyIds: [COMPANY_ID],
    branchIds: [BRANCH_A, BRANCH_B],
    activeBranchId: BRANCH_A,
  });
  const wrongBranch = prismaMock({ accountBranchId: BRANCH_B });

  await assert.rejects(
    createBankImport({
      prisma: wrongBranch.prisma,
      session: restrictedSession,
      input,
      idempotencyKey: "bank-import-005",
    }),
    FinancialValidationError,
  );
  const accountLookup = wrongBranch.calls.find(
    (call) => call.target === "financialAccount.findFirst",
  );
  assert.equal(accountLookup.args.where.branchId, BRANCH_A);
  assert.equal(
    wrongBranch.calls.some((call) => call.target === "bankImport.create"),
    false,
  );

  const listMock = prismaMock();
  await listBankImports({
    prisma: listMock.prisma,
    session: verifiedSession(["financial.view"], {
      companyIds: [],
      branchCompanyIds: [COMPANY_ID],
      branchIds: [BRANCH_A, BRANCH_B],
      activeBranchId: BRANCH_A,
    }),
  });
  const listCall = listMock.calls.find(
    (call) => call.target === "bankImport.findMany",
  );
  assert.equal(listCall.args.where.branchId, BRANCH_A);
});

test("cursor bancário não pode ser reutilizado com outros filtros", async () => {
  const secondImport = importRecord({
    id: "91000000-0000-4000-8000-000000000002",
  });
  const mock = prismaMock({ imports: [importRecord(), secondImport] });
  const firstPage = await listBankImports({
    prisma: mock.prisma,
    session: verifiedSession(["financial.view"]),
    filters: { financialAccountId: ACCOUNT_ID, limit: "1" },
  });
  assert.equal(firstPage.page.hasMore, true);
  assert.ok(firstPage.page.nextCursor);

  await assert.rejects(
    listBankImports({
      prisma: mock.prisma,
      session: verifiedSession(["financial.view"]),
      filters: {
        financialAccountId: OTHER_ACCOUNT_ID,
        cursor: firstPage.page.nextCursor,
        limit: "1",
      },
    }),
    FinancialValidationError,
  );
});

test("importação serializa a conta antes de consultar fingerprints", async () => {
  const mock = prismaMock();
  await createBankImport({
    prisma: mock.prisma,
    session: verifiedSession(["financial.manage"]),
    input,
    idempotencyKey: "bank-import-006",
  });

  const accountLockIndex = mock.calls.findIndex(
    (call) =>
      call.target === "sql" &&
      call.parameters.some((parameter) =>
        String(parameter).includes("bank.import.account"),
      ),
  );
  const fingerprintReadIndex = mock.calls.findIndex(
    (call) =>
      call.target === "bankTransaction.findMany" && call.args.select,
  );
  assert.ok(accountLockIndex >= 0);
  assert.ok(fingerprintReadIndex > accountLockIndex);
});
