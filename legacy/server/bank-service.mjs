import { createHash } from "node:crypto";
import { z } from "zod";

import { parseBankImport } from "./bank-imports.mjs";
import {
  FinancialValidationError,
  IdempotencyConflictError,
  validateIdempotencyKey,
} from "./financial-records.mjs";
import {
  companyScopedData,
  companyScopedWhere,
  requirePermission,
  runWithTenantContext,
  withTenantTransaction,
} from "./tenant-context.mjs";

const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const BANK_ACCOUNT_TYPES = new Set(["CHECKING", "SAVINGS", "PAYMENT"]);

const importInputSchema = z
  .object({
    financialAccountId: z
      .string()
      .regex(UUID_PATTERN)
      .transform((value) => value.toLowerCase()),
    format: z.enum(["CSV", "OFX"]).or(
      z
        .string()
        .transform((value) => value.toUpperCase())
        .pipe(z.enum(["CSV", "OFX"])),
    ),
    content: z.string(),
    sourceName: z.string().trim().min(1).max(255).optional(),
  })
  .strict();

export class BankImportConflictError extends Error {
  constructor(
    message =
      "Este arquivo ou uma de suas transações bancárias já foi importado.",
  ) {
    super(message);
    this.name = "BankImportConflictError";
    this.code = "BANK_IMPORT_CONFLICT";
  }
}

function parseImportInput(input) {
  const parsed = importInputSchema.safeParse(input);
  if (parsed.success) return Object.freeze(parsed.data);
  throw new FinancialValidationError(
    "Revise os dados da importação bancária.",
    parsed.error.issues.map((issue) => ({
      field: issue.path.join(".") || "body",
      code: issue.code.toUpperCase(),
      message: issue.message,
    })),
  );
}

function isUniqueConstraintError(error) {
  return (
    error?.code === "P2002" ||
    error?.code === "23505" ||
    error?.cause?.code === "23505"
  );
}

function requestHash(context, input, parsedImport) {
  return createHash("sha256")
    .update(
      JSON.stringify({
        tenantId: context.tenantId,
        companyId: context.activeCompanyId,
        branchId: context.activeBranchId,
        financialAccountId: input.financialAccountId,
        format: parsedImport.format,
        contentHash: parsedImport.contentHash,
        sourceName: input.sourceName ?? null,
      }),
    )
    .digest("hex");
}

function serializeImport(record) {
  return Object.freeze({
    id: record.id,
    branchId: record.branchId ?? null,
    financialAccountId: record.financialAccountId,
    format: record.format,
    contentHash: record.contentHash,
    sourceName: record.sourceName ?? null,
    totalRows: record.totalRows,
    importedRows: record.importedRows,
    possibleDuplicates: record.possibleDuplicates,
    createdAt: new Date(record.createdAt).toISOString(),
  });
}

function serializeTransaction(record) {
  return Object.freeze({
    id: record.id,
    branchId: record.branchId ?? null,
    bankImportId: record.bankImportId,
    financialAccountId: record.financialAccountId,
    sourceLine: record.sourceLine,
    externalId: record.externalId ?? null,
    fingerprint: record.fingerprint,
    bookedAt: new Date(record.bookedAt).toISOString(),
    amount: String(record.amount),
    currencyCode: record.currencyCode,
    description: record.description,
    memo: record.memo ?? null,
    createdAt: new Date(record.createdAt).toISOString(),
  });
}

function cursorBinding(scope) {
  return createHash("sha256")
    .update(JSON.stringify(scope))
    .digest("hex");
}

function encodeCursor(id, binding) {
  return Buffer.from(
    JSON.stringify({ version: 1, id, binding }),
    "utf8",
  ).toString("base64url");
}

function decodeCursor(cursor, expectedBinding) {
  if (!cursor) return null;
  try {
    const parsed = JSON.parse(
      Buffer.from(cursor, "base64url").toString("utf8"),
    );
    if (
      parsed.version !== 1 ||
      !UUID_PATTERN.test(parsed.id) ||
      parsed.binding !== expectedBinding
    ) {
      throw new Error("invalid");
    }
    return parsed.id;
  } catch {
    throw new FinancialValidationError("Cursor inválido.", [
      {
        field: "cursor",
        code: "INVALID_CURSOR",
        message: "Reinicie a paginação.",
      },
    ]);
  }
}

function optionalUuid(value, field) {
  if (value == null || value === "") return undefined;
  if (!UUID_PATTERN.test(String(value))) {
    throw new FinancialValidationError("Filtro inválido.", [
      {
        field,
        code: "INVALID_UUID",
        message: "Informe um identificador UUID válido.",
      },
    ]);
  }
  return String(value).toLowerCase();
}

function pageLimit(value) {
  const parsed = value == null || value === "" ? 50 : Number(value);
  if (!Number.isInteger(parsed) || parsed < 1 || parsed > 100) {
    throw new FinancialValidationError("Limite de paginação inválido.", [
      {
        field: "limit",
        code: "INVALID_LIMIT",
        message: "Use um limite inteiro entre 1 e 100.",
      },
    ]);
  }
  return parsed;
}

function pageResult(records, limit, serialize, binding) {
  const hasMore = records.length > limit;
  const pageRecords = hasMore ? records.slice(0, limit) : records;
  return Object.freeze({
    data: Object.freeze(pageRecords.map(serialize)),
    page: Object.freeze({
      hasMore,
      nextCursor: hasMore
        ? encodeCursor(pageRecords[pageRecords.length - 1].id, binding)
        : null,
    }),
  });
}

function bankRecordVisibility(context) {
  return context.activeBranchId
    ? { branchId: context.activeBranchId }
    : {};
}

function listCursorBinding(context, resource, filters, order) {
  return cursorBinding({
    resource,
    tenantId: context.tenantId,
    companyId: context.activeCompanyId,
    branchId: context.activeBranchId ?? null,
    financialAccountId: filters.financialAccountId ?? null,
    bankImportId: filters.bankImportId ?? null,
    order,
  });
}

function duplicateExternalId(entries) {
  const seen = new Set();
  for (const entry of entries) {
    if (!entry.externalId) continue;
    if (seen.has(entry.externalId)) return entry.externalId;
    seen.add(entry.externalId);
  }
  return null;
}

function possibleDuplicateCount(entries, existingFingerprints) {
  const seen = new Set(existingFingerprints);
  let count = 0;
  for (const entry of entries) {
    if (seen.has(entry.fingerprint)) count += 1;
    seen.add(entry.fingerprint);
  }
  return count;
}

async function writeImportAudit(
  transaction,
  context,
  account,
  record,
  parsedImport,
) {
  const occurredAt = new Date();
  const afterData = {
    branchId: record.branchId ?? null,
    financialAccountId: account.id,
    format: record.format,
    totalRows: record.totalRows,
    importedRows: record.importedRows,
    possibleDuplicates: record.possibleDuplicates,
  };
  const metadata = {
    source: "api",
    contentHash: parsedImport.contentHash,
  };
  const redactedFields = [];
  const payload = {
    tenantId: context.tenantId,
    companyId: context.activeCompanyId,
    branchId: record.branchId ?? null,
    actorUserId: context.userId,
    sessionId: context.sessionId,
    action: "bank.import.created",
    entityType: "BankImport",
    entityId: record.id,
    afterData,
    metadata,
    redactedFields,
    occurredAt: occurredAt.toISOString(),
  };
  await transaction.auditLog.create({
    data: {
      ...payload,
      integrityHash: createHash("sha256")
        .update(JSON.stringify(payload))
        .digest("hex"),
      occurredAt,
    },
  });
}

export async function createBankImport({
  prisma,
  session,
  input,
  idempotencyKey,
}) {
  const validated = parseImportInput(input);
  const parsedImport = parseBankImport(validated);
  const key = validateIdempotencyKey(idempotencyKey);
  const repeatedExternalId = duplicateExternalId(parsedImport.entries);
  if (repeatedExternalId) {
    throw new BankImportConflictError(
      "O arquivo repete o mesmo identificador externo em mais de uma linha.",
    );
  }

  return runWithTenantContext(session, async () => {
    requirePermission("financial.manage");
    try {
      return await withTenantTransaction(
        prisma,
        async (transaction, context) => {
          const operation = "bank.import.create";
          const hash = requestHash(context, validated, parsedImport);
          const lockScope = [
            context.tenantId,
            context.userId,
            operation,
            key,
          ].join(":");
          await transaction.$queryRawUnsafe(
            "SELECT pg_advisory_xact_lock(hashtextextended($1, 0))::text AS locked",
            lockScope,
          );

          const existingIdempotency =
            await transaction.idempotencyRecord.findUnique({
              where: {
                tenantId_userId_operation_key: {
                  tenantId: context.tenantId,
                  userId: context.userId,
                  operation,
                  key,
                },
              },
            });
          if (existingIdempotency) {
            if (existingIdempotency.requestHash !== hash) {
              throw new IdempotencyConflictError();
            }
            return Object.freeze({
              status: existingIdempotency.responseStatus,
              body: existingIdempotency.responseBody,
              replayed: true,
            });
          }

          const account = await transaction.financialAccount.findFirst({
            where: companyScopedWhere({
              id: validated.financialAccountId,
              ...(context.activeBranchId
                ? { branchId: context.activeBranchId }
                : {}),
              isActive: true,
              deletedAt: null,
            }),
          });
          if (!account || !BANK_ACCOUNT_TYPES.has(account.type)) {
            throw new FinancialValidationError(
              "A conta não está disponível para importação bancária.",
              [
                {
                  field: "financialAccountId",
                  code: "BANK_ACCOUNT_NOT_AVAILABLE",
                  message:
                    "Selecione uma conta corrente, poupança ou conta de pagamento ativa e autorizada.",
                },
              ],
            );
          }

          const accountLockScope = [
            context.tenantId,
            context.activeCompanyId,
            "bank.import.account",
            account.id,
          ].join(":");
          await transaction.$queryRawUnsafe(
            "SELECT pg_advisory_xact_lock(hashtextextended($1, 0))::text AS locked",
            accountLockScope,
          );

          const existingContent = await transaction.bankImport.findFirst({
            where: companyScopedWhere({
              financialAccountId: account.id,
              contentHash: parsedImport.contentHash,
            }),
            select: { id: true },
          });
          if (existingContent) {
            throw new BankImportConflictError(
              "Este conteúdo bancário já foi importado para a conta selecionada.",
            );
          }

          const externalIds = parsedImport.entries
            .map((entry) => entry.externalId)
            .filter(Boolean);
          if (externalIds.length > 0) {
            const existingExternal = await transaction.bankTransaction.findFirst(
              {
                where: companyScopedWhere({
                  financialAccountId: account.id,
                  externalId: { in: externalIds },
                }),
                select: { id: true },
              },
            );
            if (existingExternal) {
              throw new BankImportConflictError(
                "Uma transação com o mesmo identificador externo já foi importada.",
              );
            }
          }

          const fingerprints = [
            ...new Set(
              parsedImport.entries.map((entry) => entry.fingerprint),
            ),
          ];
          const existingFingerprintRows =
            fingerprints.length === 0
              ? []
              : await transaction.bankTransaction.findMany({
                  where: companyScopedWhere({
                    financialAccountId: account.id,
                    fingerprint: { in: fingerprints },
                  }),
                  select: { fingerprint: true },
                });
          const possibleDuplicates = possibleDuplicateCount(
            parsedImport.entries,
            existingFingerprintRows.map((row) => row.fingerprint),
          );

          const bankImport = await transaction.bankImport.create({
            data: companyScopedData({
              branchId: account.branchId ?? null,
              financialAccountId: account.id,
              format: parsedImport.format,
              contentHash: parsedImport.contentHash,
              sourceName: validated.sourceName ?? null,
              totalRows: parsedImport.entries.length,
              importedRows: parsedImport.entries.length,
              possibleDuplicates,
              createdById: context.userId,
            }),
          });

          const transactions = parsedImport.entries.map((entry) =>
            companyScopedData({
              branchId: account.branchId ?? null,
              bankImportId: bankImport.id,
              financialAccountId: account.id,
              sourceLine: entry.sourceLine,
              externalId: entry.externalId,
              fingerprint: entry.fingerprint,
              bookedAt: new Date(entry.bookedAt),
              amount: entry.amount,
              currencyCode: account.currencyCode,
              description: entry.description,
              memo: entry.memo,
            }),
          );
          const inserted = await transaction.bankTransaction.createMany({
            data: transactions,
          });
          if (inserted.count !== parsedImport.entries.length) {
            throw new BankImportConflictError(
              "A importação não gravou todas as transações de forma atômica.",
            );
          }

          const body = { data: serializeImport(bankImport) };
          await writeImportAudit(
            transaction,
            context,
            account,
            bankImport,
            parsedImport,
          );
          await transaction.idempotencyRecord.create({
            data: {
              tenantId: context.tenantId,
              userId: context.userId,
              operation,
              key,
              requestHash: hash,
              resourceType: "BankImport",
              resourceId: bankImport.id,
              responseStatus: 201,
              responseBody: body,
              expiresAt: new Date(Date.now() + 24 * 60 * 60_000),
            },
          });

          return Object.freeze({ status: 201, body, replayed: false });
        },
      );
    } catch (error) {
      if (isUniqueConstraintError(error)) {
        throw new BankImportConflictError();
      }
      throw error;
    }
  });
}

export async function listBankImports({
  prisma,
  session,
  filters = {},
}) {
  const limit = pageLimit(filters.limit);
  const financialAccountId = optionalUuid(
    filters.financialAccountId,
    "financialAccountId",
  );

  return runWithTenantContext(session, async () => {
    requirePermission("financial.view");
    return withTenantTransaction(prisma, async (transaction, context) => {
      const binding = listCursorBinding(
        context,
        "bank-imports",
        { financialAccountId },
        "createdAt:desc,id:desc",
      );
      const cursorId = decodeCursor(filters.cursor, binding);
      const records = await transaction.bankImport.findMany({
        where: companyScopedWhere({
          ...bankRecordVisibility(context),
          ...(financialAccountId ? { financialAccountId } : {}),
        }),
        orderBy: [{ createdAt: "desc" }, { id: "desc" }],
        take: limit + 1,
        ...(cursorId ? { cursor: { id: cursorId }, skip: 1 } : {}),
      });
      return pageResult(records, limit, serializeImport, binding);
    });
  });
}

export async function listBankTransactions({
  prisma,
  session,
  filters = {},
}) {
  const limit = pageLimit(filters.limit);
  const financialAccountId = optionalUuid(
    filters.financialAccountId,
    "financialAccountId",
  );
  const bankImportId = optionalUuid(filters.bankImportId, "bankImportId");

  return runWithTenantContext(session, async () => {
    requirePermission("financial.view");
    return withTenantTransaction(prisma, async (transaction, context) => {
      const binding = listCursorBinding(
        context,
        "bank-transactions",
        { financialAccountId, bankImportId },
        "bookedAt:desc,id:desc",
      );
      const cursorId = decodeCursor(filters.cursor, binding);
      const records = await transaction.bankTransaction.findMany({
        where: companyScopedWhere({
          ...bankRecordVisibility(context),
          ...(financialAccountId ? { financialAccountId } : {}),
          ...(bankImportId ? { bankImportId } : {}),
        }),
        orderBy: [{ bookedAt: "desc" }, { id: "desc" }],
        take: limit + 1,
        ...(cursorId ? { cursor: { id: cursorId }, skip: 1 } : {}),
      });
      return pageResult(records, limit, serializeTransaction, binding);
    });
  });
}
