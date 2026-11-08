import { createHash } from "node:crypto";
import { z } from "zod";

import {
  companyScopedData,
  companyScopedWhere,
  requirePermission,
  runWithTenantContext,
  withTenantTransaction,
} from "./tenant-context.mjs";

const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const MONEY_PATTERN = /^\d{1,15}(?:\.\d{1,4})?$/;
const DATE_PATTERN = /^\d{4}-\d{2}-\d{2}$/;
const IDEMPOTENCY_PATTERN = /^[A-Za-z0-9._:-]{8,128}$/;
const FINANCIAL_STATUSES = new Set([
  "OPEN",
  "PARTIAL",
  "SETTLED",
  "OVERDUE",
  "CANCELLED",
]);
const DEFAULT_FINANCIAL_TIME_ZONE = "America/Manaus";

const financialInputSchema = z
  .object({
    counterpartyId: z.string().regex(UUID_PATTERN).optional(),
    categoryId: z.string().regex(UUID_PATTERN).optional(),
    costCenterId: z.string().regex(UUID_PATTERN).optional(),
    counterpartyName: z.string().trim().min(2).max(180),
    description: z.string().trim().min(2).max(500),
    documentNumber: z.string().trim().min(1).max(80).optional(),
    issueDate: z.string().regex(DATE_PATTERN).optional(),
    dueDate: z.string().regex(DATE_PATTERN),
    amount: z.string().regex(MONEY_PATTERN),
    currencyCode: z.string().regex(/^[A-Z]{3}$/).default("BRL"),
  })
  .strict();

export class FinancialValidationError extends Error {
  constructor(message, errors = []) {
    super(message);
    this.name = "FinancialValidationError";
    this.code = "VALIDATION_ERROR";
    this.errors = errors;
  }
}

export class IdempotencyConflictError extends Error {
  constructor() {
    super("A chave de idempotência já foi usada com outro conteúdo.");
    this.name = "IdempotencyConflictError";
    this.code = "IDEMPOTENCY_CONFLICT";
  }
}

function parseDate(value, field) {
  if (!DATE_PATTERN.test(value)) {
    throw new FinancialValidationError("Data inválida.", [
      { field, code: "INVALID_DATE", message: "Use o formato AAAA-MM-DD." },
    ]);
  }
  const parsed = new Date(`${value}T00:00:00.000Z`);
  if (Number.isNaN(parsed.valueOf()) || parsed.toISOString().slice(0, 10) !== value) {
    throw new FinancialValidationError("Data inválida.", [
      { field, code: "INVALID_DATE", message: "Informe uma data existente." },
    ]);
  }
  return parsed;
}

function validateInput(input) {
  const parsed = financialInputSchema.safeParse(input);
  if (!parsed.success) {
    throw new FinancialValidationError(
      "Revise os campos informados.",
      parsed.error.issues.map((issue) => ({
        field: issue.path.join(".") || "body",
        code: issue.code.toUpperCase(),
        message: issue.message,
      })),
    );
  }

  if (/^0+(?:\.0+)?$/.test(parsed.data.amount)) {
    throw new FinancialValidationError("O valor deve ser maior que zero.", [
      { field: "amount", code: "AMOUNT_NOT_POSITIVE", message: "Informe um valor maior que zero." },
    ]);
  }

  return Object.freeze({
    ...parsed.data,
    issueDate: parsed.data.issueDate
      ? parseDate(parsed.data.issueDate, "issueDate")
      : null,
    dueDate: parseDate(parsed.data.dueDate, "dueDate"),
  });
}

function assertKind(kind) {
  if (kind !== "payable" && kind !== "receivable") {
    throw new TypeError("Tipo financeiro inválido.");
  }
  return kind;
}

function resourceConfiguration(kind) {
  assertKind(kind);
  return kind === "payable"
    ? {
        permissionPrefix: "payables",
        resourceType: "Payable",
        settledAtField: "paidAt",
      }
    : {
        permissionPrefix: "receivables",
        resourceType: "Receivable",
        settledAtField: "receivedAt",
      };
}

export function financialBusinessDate(
  now = new Date(),
  timeZone =
    process.env.CLAREZA_FINANCIAL_TIME_ZONE ??
    DEFAULT_FINANCIAL_TIME_ZONE,
) {
  const parts = new Intl.DateTimeFormat("en-US", {
    timeZone,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).formatToParts(now);
  const values = Object.fromEntries(
    parts.map((part) => [part.type, part.value]),
  );
  return `${values.year}-${values.month}-${values.day}`;
}

export function financialStatusForDisplay(record, now = new Date()) {
  const dueDate = new Date(record.dueDate).toISOString().slice(0, 10);
  return record.status === "OPEN" && dueDate < financialBusinessDate(now)
    ? "OVERDUE"
    : record.status;
}

function serializeRecord(record, kind) {
  const settledAtField =
    kind === "payable" ? "paidAt" : "receivedAt";
  const settledAt = record[settledAtField];

  return Object.freeze({
    id: record.id,
    branchId: record.branchId ?? null,
    counterpartyId: record.counterpartyId ?? null,
    categoryId: record.categoryId ?? null,
    costCenterId: record.costCenterId ?? null,
    counterpartyName: record.counterpartyName,
    description: record.description,
    documentNumber: record.documentNumber ?? null,
    issueDate: record.issueDate
      ? new Date(record.issueDate).toISOString().slice(0, 10)
      : null,
    dueDate: new Date(record.dueDate).toISOString().slice(0, 10),
    amount: String(record.amount),
    outstandingAmount: String(record.outstandingAmount),
    currencyCode: record.currencyCode,
    status: financialStatusForDisplay(record),
    settledAt: settledAt ? new Date(settledAt).toISOString() : null,
    createdAt: new Date(record.createdAt).toISOString(),
    updatedAt: new Date(record.updatedAt).toISOString(),
  });
}

function requestHash(kind, context, input) {
  return createHash("sha256")
    .update(
      JSON.stringify({
        kind,
        tenantId: context.tenantId,
        companyId: context.activeCompanyId,
        branchId: context.activeBranchId,
        input: {
          ...input,
          issueDate: input.issueDate?.toISOString().slice(0, 10) ?? null,
          dueDate: input.dueDate.toISOString().slice(0, 10),
        },
      }),
    )
    .digest("hex");
}

function modelFor(transaction, kind) {
  const model = kind === "payable" ? transaction.payable : transaction.receivable;
  if (!model) throw new TypeError(`Adapter Prisma sem modelo ${kind}.`);
  return model;
}

function catalogScopeMatches(catalog, activeBranchId) {
  return catalog.branchId == null || catalog.branchId === activeBranchId;
}

async function resolveCatalogReferences(transaction, context, kind, input) {
  const counterparty = input.counterpartyId
    ? await transaction.counterparty.findFirst({
        where: companyScopedWhere({
          id: input.counterpartyId,
          isActive: true,
          deletedAt: null,
        }),
      })
    : null;
  const category = input.categoryId
    ? await transaction.financialCategory.findFirst({
        where: companyScopedWhere({
          id: input.categoryId,
          isActive: true,
          deletedAt: null,
        }),
      })
    : null;
  const costCenter = input.costCenterId
    ? await transaction.costCenter.findFirst({
        where: companyScopedWhere({
          id: input.costCenterId,
          isActive: true,
          deletedAt: null,
        }),
      })
    : null;

  const errors = [];
  const expectedCounterpartyKind =
    kind === "payable" ? "SUPPLIER" : "CUSTOMER";
  const expectedDirection =
    kind === "payable" ? "PAYABLE" : "RECEIVABLE";

  if (
    input.counterpartyId &&
    (!counterparty ||
      !catalogScopeMatches(counterparty, context.activeBranchId) ||
      ![expectedCounterpartyKind, "BOTH"].includes(counterparty.kind))
  ) {
    errors.push({
      field: "counterpartyId",
      code: "COUNTERPARTY_NOT_AVAILABLE",
      message: "Selecione uma contraparte ativa, compatível e autorizada.",
    });
  }
  if (
    input.categoryId &&
    (!category ||
      !catalogScopeMatches(category, context.activeBranchId) ||
      ![expectedDirection, "BOTH"].includes(category.direction))
  ) {
    errors.push({
      field: "categoryId",
      code: "CATEGORY_NOT_AVAILABLE",
      message: "Selecione uma categoria ativa, compatível e autorizada.",
    });
  }
  if (
    input.costCenterId &&
    (!costCenter ||
      !catalogScopeMatches(costCenter, context.activeBranchId))
  ) {
    errors.push({
      field: "costCenterId",
      code: "COST_CENTER_NOT_AVAILABLE",
      message: "Selecione um centro de custo ativo e autorizado.",
    });
  }
  if (errors.length) {
    throw new FinancialValidationError(
      "Um ou mais cadastros financeiros não estão disponíveis.",
      errors,
    );
  }

  return Object.freeze({
    counterpartyId: counterparty?.id ?? null,
    counterpartyName: counterparty?.name ?? input.counterpartyName,
    categoryId: category?.id ?? null,
    costCenterId: costCenter?.id ?? null,
  });
}

function encodeCursor(id) {
  return Buffer.from(JSON.stringify({ id }), "utf8").toString("base64url");
}

function decodeCursor(cursor) {
  if (!cursor) return null;
  try {
    const parsed = JSON.parse(Buffer.from(cursor, "base64url").toString("utf8"));
    if (!UUID_PATTERN.test(parsed.id)) throw new Error("invalid");
    return parsed.id;
  } catch {
    throw new FinancialValidationError("Cursor inválido.", [
      { field: "cursor", code: "INVALID_CURSOR", message: "Reinicie a paginação." },
    ]);
  }
}

export function validateIdempotencyKey(value) {
  const key = String(value ?? "");
  if (!IDEMPOTENCY_PATTERN.test(key)) {
    throw new FinancialValidationError("Chave de idempotência inválida.", [
      {
        field: "Idempotency-Key",
        code: "INVALID_IDEMPOTENCY_KEY",
        message: "Use de 8 a 128 caracteres seguros.",
      },
    ]);
  }
  return key;
}

export async function createFinancialRecord({
  prisma,
  session,
  kind,
  input,
  idempotencyKey,
}) {
  const config = resourceConfiguration(kind);
  const validatedInput = validateInput(input);
  const safeKey = validateIdempotencyKey(idempotencyKey);

  return runWithTenantContext(session, async () => {
    requirePermission(`${config.permissionPrefix}.manage`);

    return withTenantTransaction(prisma, async (transaction, context) => {
      const operation = `${kind}.create`;
      const hash = requestHash(kind, context, validatedInput);
      const lockScope = [
        context.tenantId,
        context.userId,
        operation,
        safeKey,
      ].join(":");

      // Serialize only requests competing for the same authenticated
      // idempotency scope. After the first transaction commits, a concurrent
      // waiter observes and replays its stored response instead of creating a
      // second financial record.
      await transaction.$queryRawUnsafe(
        "SELECT pg_advisory_xact_lock(hashtextextended($1, 0))::text AS locked",
        lockScope,
      );

      const existing = await transaction.idempotencyRecord.findUnique({
        where: {
          tenantId_userId_operation_key: {
            tenantId: context.tenantId,
            userId: context.userId,
            operation,
            key: safeKey,
          },
        },
      });

      if (existing) {
        if (existing.requestHash !== hash) throw new IdempotencyConflictError();
        return {
          status: existing.responseStatus,
          body: existing.responseBody,
          replayed: true,
        };
      }

      const catalogReferences = await resolveCatalogReferences(
        transaction,
        context,
        kind,
        validatedInput,
      );
      const dueDate = validatedInput.dueDate.toISOString().slice(0, 10);
      const record = await modelFor(transaction, kind).create({
        data: companyScopedData({
          branchId: context.activeBranchId,
          ...catalogReferences,
          description: validatedInput.description,
          documentNumber: validatedInput.documentNumber ?? null,
          issueDate: validatedInput.issueDate,
          dueDate: validatedInput.dueDate,
          amount: validatedInput.amount,
          outstandingAmount: validatedInput.amount,
          currencyCode: validatedInput.currencyCode,
          status: "OPEN",
          createdById: context.userId,
        }),
      });
      const body = { data: serializeRecord(record, kind) };
      const occurredAt = new Date();
      const afterData = {
        status: record.status,
        amount: String(record.amount),
        dueDate,
      };
      const metadata = {
        source: "api",
        idempotencyKeyHash: createHash("sha256").update(safeKey).digest("hex"),
      };
      const redactedFields = ["counterpartyName"];
      const integrityHash = createHash("sha256")
        .update(
          JSON.stringify({
            tenantId: context.tenantId,
            companyId: context.activeCompanyId,
            branchId: context.activeBranchId,
            actorUserId: context.userId,
            sessionId: context.sessionId,
            action: `${kind}.created`,
            entityType: config.resourceType,
            entityId: record.id,
            afterData,
            metadata,
            redactedFields,
            occurredAt: occurredAt.toISOString(),
          }),
        )
        .digest("hex");

      await transaction.auditLog.create({
        data: {
          tenantId: context.tenantId,
          companyId: context.activeCompanyId,
          branchId: context.activeBranchId,
          actorUserId: context.userId,
          sessionId: context.sessionId,
          action: `${kind}.created`,
          entityType: config.resourceType,
          entityId: record.id,
          afterData,
          metadata,
          redactedFields,
          integrityHash,
          occurredAt,
        },
      });

      await transaction.idempotencyRecord.create({
        data: {
          tenantId: context.tenantId,
          userId: context.userId,
          operation,
          key: safeKey,
          requestHash: hash,
          resourceType: config.resourceType,
          resourceId: record.id,
          responseStatus: 201,
          responseBody: body,
          expiresAt: new Date(Date.now() + 24 * 60 * 60_000),
        },
      });

      return { status: 201, body, replayed: false };
    });
  });
}

export async function listFinancialRecords({
  prisma,
  session,
  kind,
  filters = {},
}) {
  const config = resourceConfiguration(kind);
  const limit = Math.min(Math.max(Number(filters.limit) || 50, 1), 100);
  const cursorId = decodeCursor(filters.cursor);

  if (filters.status && !FINANCIAL_STATUSES.has(filters.status)) {
    throw new FinancialValidationError("Status inválido.", [
      { field: "status", code: "INVALID_STATUS", message: "Informe um status financeiro válido." },
    ]);
  }

  const dueDate =
    filters.dueFrom || filters.dueTo
      ? {
          ...(filters.dueFrom
            ? { gte: parseDate(filters.dueFrom, "dueFrom") }
            : {}),
          ...(filters.dueTo ? { lte: parseDate(filters.dueTo, "dueTo") } : {}),
        }
      : undefined;
  const today = parseDate(financialBusinessDate(), "today");
  const temporalDueDate =
    filters.status === "OVERDUE"
      ? { lt: today }
      : filters.status === "OPEN"
        ? { gte: today }
        : undefined;
  const effectiveDueDate =
    temporalDueDate || dueDate
      ? { ...temporalDueDate, ...dueDate }
      : undefined;
  const statusWhere =
    filters.status === "OVERDUE" || filters.status === "OPEN"
      ? { status: "OPEN" }
        : filters.status
          ? { status: filters.status }
          : {};

  return runWithTenantContext(session, async () => {
    requirePermission(`${config.permissionPrefix}.view`);

    return withTenantTransaction(prisma, async (transaction, context) => {
      const records = await modelFor(transaction, kind).findMany({
        where: companyScopedWhere({
          deletedAt: null,
          ...(context.activeBranchId ? { branchId: context.activeBranchId } : {}),
          ...statusWhere,
          ...(effectiveDueDate ? { dueDate: effectiveDueDate } : {}),
        }),
        orderBy: [{ dueDate: "asc" }, { id: "asc" }],
        take: limit + 1,
        ...(cursorId ? { cursor: { id: cursorId }, skip: 1 } : {}),
      });

      const hasMore = records.length > limit;
      const pageRecords = hasMore ? records.slice(0, limit) : records;
      return {
        data: pageRecords.map((record) => serializeRecord(record, kind)),
        page: {
          hasMore,
          nextCursor: hasMore
            ? encodeCursor(pageRecords[pageRecords.length - 1].id)
            : null,
        },
      };
    });
  });
}
