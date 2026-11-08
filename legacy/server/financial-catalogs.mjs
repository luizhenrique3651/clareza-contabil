import { createHash } from "node:crypto";
import { z } from "zod";

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

const CODE_PATTERN = /^[A-Za-z0-9._-]{1,40}$/;
const CURRENCY_PATTERN = /^[A-Z]{3}$/;

const codeField = z
  .string()
  .trim()
  .regex(CODE_PATTERN)
  .transform((value) => value.toUpperCase());
const nameField = z.string().trim().min(2).max(180);

const catalogInputSchema = z.discriminatedUnion("resource", [
  z
    .object({
      resource: z.literal("counterparty"),
      code: codeField,
      name: nameField,
      taxId: z.string().trim().min(5).max(24).optional(),
      kind: z.enum(["SUPPLIER", "CUSTOMER", "BOTH"]).default("BOTH"),
    })
    .strict(),
  z
    .object({
      resource: z.literal("category"),
      code: codeField,
      name: nameField,
      direction: z.enum(["PAYABLE", "RECEIVABLE", "BOTH"]).default("BOTH"),
    })
    .strict(),
  z
    .object({
      resource: z.literal("costCenter"),
      code: codeField,
      name: nameField,
    })
    .strict(),
  z
    .object({
      resource: z.literal("financialAccount"),
      code: codeField,
      name: nameField,
      type: z
        .enum(["CASH", "CHECKING", "SAVINGS", "PAYMENT", "OTHER"])
        .default("CHECKING"),
      currencyCode: z.string().regex(CURRENCY_PATTERN).default("BRL"),
    })
    .strict(),
]);

const CONFIGURATION = Object.freeze({
  counterparty: Object.freeze({
    delegate: "counterparty",
    entityType: "Counterparty",
    action: "financial.counterparty.created",
  }),
  category: Object.freeze({
    delegate: "financialCategory",
    entityType: "FinancialCategory",
    action: "financial.category.created",
  }),
  costCenter: Object.freeze({
    delegate: "costCenter",
    entityType: "CostCenter",
    action: "financial.cost_center.created",
  }),
  financialAccount: Object.freeze({
    delegate: "financialAccount",
    entityType: "FinancialAccount",
    action: "financial.account.created",
  }),
});

export class FinancialCatalogConflictError extends Error {
  constructor(message = "Já existe um cadastro financeiro com este código.") {
    super(message);
    this.name = "FinancialCatalogConflictError";
    this.code = "FINANCIAL_CATALOG_CONFLICT";
  }
}

function parseInput(input) {
  const parsed = catalogInputSchema.safeParse(input);
  if (parsed.success) return Object.freeze(parsed.data);
  throw new FinancialValidationError(
    "Revise os campos do cadastro financeiro.",
    parsed.error.issues.map((issue) => ({
      field: issue.path.join(".") || "body",
      code: issue.code.toUpperCase(),
      message: issue.message,
    })),
  );
}

function serializeCatalog(record, resource) {
  return Object.freeze({
    id: record.id,
    resource,
    branchId: record.branchId ?? null,
    code: record.code,
    name: record.name,
    isActive: record.isActive,
    ...(resource === "counterparty"
      ? { taxId: record.taxId ?? null, kind: record.kind }
      : {}),
    ...(resource === "category" ? { direction: record.direction } : {}),
    ...(resource === "financialAccount"
      ? { type: record.type, currencyCode: record.currencyCode }
      : {}),
    createdAt: new Date(record.createdAt).toISOString(),
  });
}

function writableData(input, context) {
  const shared = {
    branchId: context.activeBranchId,
    code: input.code,
    name: input.name,
    createdById: context.userId,
  };
  if (input.resource === "counterparty") {
    return { ...shared, taxId: input.taxId ?? null, kind: input.kind };
  }
  if (input.resource === "category") {
    return { ...shared, direction: input.direction };
  }
  if (input.resource === "financialAccount") {
    return {
      ...shared,
      type: input.type,
      currencyCode: input.currencyCode,
    };
  }
  return shared;
}

function requestHash(context, input) {
  return createHash("sha256")
    .update(
      JSON.stringify({
        tenantId: context.tenantId,
        companyId: context.activeCompanyId,
        branchId: context.activeBranchId,
        input,
      }),
    )
    .digest("hex");
}

function catalogVisibility(context) {
  return context.activeBranchId
    ? { OR: [{ branchId: null }, { branchId: context.activeBranchId }] }
    : {};
}

function isUniqueConstraintError(error) {
  return (
    error?.code === "P2002" ||
    error?.code === "23505" ||
    error?.cause?.code === "23505"
  );
}

async function listDelegate(transaction, context, resource) {
  const config = CONFIGURATION[resource];
  const records = await transaction[config.delegate].findMany({
    where: companyScopedWhere({
      deletedAt: null,
      isActive: true,
      ...catalogVisibility(context),
    }),
    orderBy: [{ name: "asc" }, { id: "asc" }],
  });
  return records.map((record) => serializeCatalog(record, resource));
}

async function writeAudit(transaction, context, config, record, input) {
  const occurredAt = new Date();
  const afterData = {
    branchId: record.branchId ?? null,
    code: record.code,
    name: record.name,
    resource: input.resource,
    ...(input.resource === "counterparty" ? { kind: input.kind } : {}),
    ...(input.resource === "category"
      ? { direction: input.direction }
      : {}),
    ...(input.resource === "financialAccount"
      ? {
          type: input.type,
          currencyCode: input.currencyCode,
        }
      : {}),
  };
  const metadata = { source: "api" };
  const redactedFields =
    input.resource === "counterparty" && input.taxId ? ["taxId"] : [];
  const payload = {
    tenantId: context.tenantId,
    companyId: context.activeCompanyId,
    branchId: record.branchId ?? null,
    actorUserId: context.userId,
    sessionId: context.sessionId,
    action: config.action,
    entityType: config.entityType,
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

export async function listFinancialCatalogs({ prisma, session }) {
  return runWithTenantContext(session, async () => {
    requirePermission("financial.view");
    return withTenantTransaction(prisma, async (transaction, context) => {
      const counterparties = await listDelegate(
        transaction,
        context,
        "counterparty",
      );
      const categories = await listDelegate(
        transaction,
        context,
        "category",
      );
      const costCenters = await listDelegate(
        transaction,
        context,
        "costCenter",
      );
      const financialAccounts = await listDelegate(
        transaction,
        context,
        "financialAccount",
      );
      return Object.freeze({
        data: Object.freeze({
          counterparties,
          categories,
          costCenters,
          financialAccounts,
        }),
      });
    });
  });
}

export async function createFinancialCatalog({
  prisma,
  session,
  input,
  idempotencyKey,
}) {
  const validated = parseInput(input);
  const safeKey = validateIdempotencyKey(idempotencyKey);
  const config = CONFIGURATION[validated.resource];

  return runWithTenantContext(session, async () => {
    requirePermission("financial.manage");
    try {
      return await withTenantTransaction(
        prisma,
        async (transaction, context) => {
          const operation = `financial.${validated.resource}.create`;
          const hash = requestHash(context, validated);
          const lockScope = [
            context.tenantId,
            context.userId,
            operation,
            safeKey,
          ].join(":");
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
            if (existing.requestHash !== hash) {
              throw new IdempotencyConflictError();
            }
            return Object.freeze({
              status: existing.responseStatus,
              body: existing.responseBody,
              replayed: true,
            });
          }

          const duplicate = await transaction[config.delegate].findFirst({
            where: companyScopedWhere({
              code: validated.code,
              deletedAt: null,
            }),
            select: { id: true },
          });
          if (duplicate) throw new FinancialCatalogConflictError();

          const record = await transaction[config.delegate].create({
            data: companyScopedData(writableData(validated, context)),
          });
          const body = {
            data: serializeCatalog(record, validated.resource),
          };
          await writeAudit(transaction, context, config, record, validated);
          await transaction.idempotencyRecord.create({
            data: {
              tenantId: context.tenantId,
              userId: context.userId,
              operation,
              key: safeKey,
              requestHash: hash,
              resourceType: config.entityType,
              resourceId: record.id,
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
        throw new FinancialCatalogConflictError();
      }
      throw error;
    }
  });
}
