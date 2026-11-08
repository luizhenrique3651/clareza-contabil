import { createHash } from "node:crypto";
import { z } from "zod";

import {
  financialStatusForDisplay,
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
const MONEY_PATTERN = /^\d{1,15}(?:\.\d{1,4})?$/;
const SCALE = 10_000n;
const MAX_MONEY_UNITS = 9_999_999_999_999_999_999n;

const settlementSchema = z
  .object({
    recordId: z.string().regex(UUID_PATTERN),
    financialAccountId: z.string().regex(UUID_PATTERN),
    amount: z.string().regex(MONEY_PATTERN),
    interestAmount: z.string().regex(MONEY_PATTERN).default("0"),
    fineAmount: z.string().regex(MONEY_PATTERN).default("0"),
    discountAmount: z.string().regex(MONEY_PATTERN).default("0"),
    occurredAt: z.string().datetime({ offset: true }).optional(),
    note: z.string().trim().min(2).max(500).optional(),
  })
  .strict();

const reversalSchema = z
  .object({
    recordId: z.string().regex(UUID_PATTERN),
    eventId: z.string().regex(UUID_PATTERN),
    occurredAt: z.string().datetime({ offset: true }).optional(),
    reason: z.string().trim().min(4).max(500),
  })
  .strict();

export class FinancialNotFoundError extends Error {
  constructor(message = "O registro financeiro não foi encontrado neste contexto.") {
    super(message);
    this.name = "FinancialNotFoundError";
    this.code = "NOT_FOUND";
  }
}

export class FinancialStateConflictError extends Error {
  constructor(message) {
    super(message);
    this.name = "FinancialStateConflictError";
    this.code = "FINANCIAL_STATE_CONFLICT";
  }
}

function configuration(kind) {
  if (kind === "payable") {
    return Object.freeze({
      permissionPrefix: "payables",
      recordDelegate: "payable",
      eventDelegate: "payment",
      reversalDelegate: "paymentReversal",
      recordIdField: "payableId",
      settledAtField: "paidAt",
      eventType: "Payment",
      reversalType: "PaymentReversal",
      settlementAction: "payable.payment.created",
      reversalAction: "payable.payment.reversed",
      table: "payables",
    });
  }
  if (kind === "receivable") {
    return Object.freeze({
      permissionPrefix: "receivables",
      recordDelegate: "receivable",
      eventDelegate: "receipt",
      reversalDelegate: "receiptReversal",
      recordIdField: "receivableId",
      settledAtField: "receivedAt",
      eventType: "Receipt",
      reversalType: "ReceiptReversal",
      settlementAction: "receivable.receipt.created",
      reversalAction: "receivable.receipt.reversed",
      table: "receivables",
    });
  }
  throw new TypeError("Tipo financeiro inválido.");
}

function parseInput(schema, input) {
  const parsed = schema.safeParse(input);
  if (parsed.success) return parsed.data;
  throw new FinancialValidationError(
    "Revise os campos informados.",
    parsed.error.issues.map((issue) => ({
      field: issue.path.join(".") || "body",
      code: issue.code.toUpperCase(),
      message: issue.message,
    })),
  );
}

export function financialOccurredAt(value) {
  const occurredAt = value ? new Date(value) : new Date();
  if (value && occurredAt.valueOf() > Date.now()) {
    throw new FinancialValidationError(
      "A data do evento financeiro não pode estar no futuro.",
      [
        {
          field: "occurredAt",
          code: "FUTURE_FINANCIAL_EVENT",
          message: "Informe uma data e hora igual ou anterior ao instante atual.",
        },
      ],
    );
  }
  return occurredAt;
}

function toUnits(value) {
  const [integer, fraction = ""] = String(value).split(".");
  return BigInt(integer) * SCALE + BigInt(fraction.padEnd(4, "0"));
}

function fromUnits(value) {
  const sign = value < 0n ? "-" : "";
  const absolute = value < 0n ? -value : value;
  const integer = absolute / SCALE;
  const fraction = String(absolute % SCALE).padStart(4, "0");
  return `${sign}${integer}.${fraction}`;
}

function validateSettlementAmounts(input) {
  const principal = toUnits(input.amount);
  const interest = toUnits(input.interestAmount);
  const fine = toUnits(input.fineAmount);
  const discount = toUnits(input.discountAmount);
  const cash = principal + interest + fine - discount;

  if (principal <= 0n) {
    throw new FinancialValidationError("O valor principal deve ser maior que zero.", [
      {
        field: "amount",
        code: "AMOUNT_NOT_POSITIVE",
        message: "Informe um valor maior que zero.",
      },
    ]);
  }
  if (cash < 0n) {
    throw new FinancialValidationError("O desconto excede o valor do evento.", [
      {
        field: "discountAmount",
        code: "DISCOUNT_EXCEEDS_TOTAL",
        message: "O desconto não pode tornar o movimento de caixa negativo.",
      },
    ]);
  }
  if (cash > MAX_MONEY_UNITS) {
    throw new FinancialValidationError(
      "O valor de caixa excede o limite monetário suportado.",
      [
        {
          field: "amounts",
          code: "MONEY_OVERFLOW",
          message:
            "Reduza principal, juros ou multa para respeitar 15 dígitos inteiros.",
        },
      ],
    );
  }

  return Object.freeze({
    principal,
    interest,
    fine,
    discount,
    cash,
  });
}

function eventModel(transaction, config) {
  const model = transaction[config.eventDelegate];
  if (!model) throw new TypeError(`Adapter Prisma sem ${config.eventDelegate}.`);
  return model;
}

function reversalModel(transaction, config) {
  const model = transaction[config.reversalDelegate];
  if (!model) throw new TypeError(`Adapter Prisma sem ${config.reversalDelegate}.`);
  return model;
}

function recordModel(transaction, config) {
  const model = transaction[config.recordDelegate];
  if (!model) throw new TypeError(`Adapter Prisma sem ${config.recordDelegate}.`);
  return model;
}

function iso(value) {
  return new Date(value).toISOString();
}

function recordSummary(record, config) {
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
    settledAt: record[config.settledAtField]
      ? iso(record[config.settledAtField])
      : null,
    createdAt: iso(record.createdAt),
    updatedAt: iso(record.updatedAt),
  });
}

function eventSummary(event) {
  return Object.freeze({
    id: event.id,
    type: event.reversal ? "REVERSED_SETTLEMENT" : "SETTLEMENT",
    principalAmount: String(event.principalAmount),
    interestAmount: String(event.interestAmount),
    fineAmount: String(event.fineAmount),
    discountAmount: String(event.discountAmount),
    cashAmount: String(event.cashAmount),
    occurredAt: iso(event.occurredAt),
    note: event.note ?? null,
    financialAccount: Object.freeze({
      id: event.financialAccount.id,
      name: event.financialAccount.name,
      type: event.financialAccount.type,
    }),
    actor: Object.freeze({
      id: event.createdBy.id,
      displayName: event.createdBy.displayName,
    }),
    reversal: event.reversal
      ? Object.freeze({
          id: event.reversal.id,
          reason: event.reversal.reason,
          occurredAt: iso(event.reversal.occurredAt),
          actor: Object.freeze({
            id: event.reversal.createdBy.id,
            displayName: event.reversal.createdBy.displayName,
          }),
        })
      : null,
  });
}

function settlementTimelineItem(event) {
  const summary = eventSummary(event);
  return Object.freeze({
    ...summary,
    type: "SETTLEMENT",
    reversed: Boolean(event.reversal),
    reversalId: event.reversal?.id ?? null,
    reversal: undefined,
  });
}

function reversalTimelineItem(event) {
  const reversal = event.reversal;
  if (!reversal) return null;
  return Object.freeze({
    id: reversal.id,
    type: "REVERSAL",
    reversesEventId: event.id,
    principalAmount: String(event.principalAmount),
    interestAmount: String(event.interestAmount),
    fineAmount: String(event.fineAmount),
    discountAmount: String(event.discountAmount),
    cashAmount: String(event.cashAmount),
    occurredAt: iso(reversal.occurredAt),
    note: null,
    reason: reversal.reason,
    financialAccount: Object.freeze({
      id: event.financialAccount.id,
      name: event.financialAccount.name,
      type: event.financialAccount.type,
    }),
    actor: Object.freeze({
      id: reversal.createdBy.id,
      displayName: reversal.createdBy.displayName,
    }),
    originalActor: Object.freeze({
      id: event.createdBy.id,
      displayName: event.createdBy.displayName,
    }),
    reversed: false,
    reversalId: null,
  });
}

function financialTimeline(events) {
  return events
    .flatMap((event) => [
      settlementTimelineItem(event),
      reversalTimelineItem(event),
    ])
    .filter(Boolean)
    .sort((left, right) => {
      const timeDifference =
        new Date(right.occurredAt).valueOf() -
        new Date(left.occurredAt).valueOf();
      if (timeDifference) return timeDifference;
      if (left.type !== right.type) {
        return left.type === "REVERSAL" ? -1 : 1;
      }
      return right.id.localeCompare(left.id);
    });
}

async function lockRecord(transaction, context, config, recordId) {
  const rows = await transaction.$queryRawUnsafe(
    `SELECT id::text AS id
       FROM ${config.table}
      WHERE tenant_id = $1::uuid
        AND company_id = $2::uuid
        AND id = $3::uuid
        AND ($4::uuid IS NULL OR branch_id = $4::uuid)
        AND deleted_at IS NULL
      FOR UPDATE`,
    context.tenantId,
    context.activeCompanyId,
    recordId,
    context.activeBranchId,
  );
  if (!rows.length) throw new FinancialNotFoundError();
}

async function loadRecord(transaction, context, config, recordId) {
  const record = await recordModel(transaction, config).findFirst({
    where: companyScopedWhere({
      id: recordId,
      deletedAt: null,
      ...(context.activeBranchId ? { branchId: context.activeBranchId } : {}),
    }),
  });
  if (!record) throw new FinancialNotFoundError();
  return record;
}

async function loadEvents(transaction, config, recordId) {
  return eventModel(transaction, config).findMany({
    where: companyScopedWhere({ [config.recordIdField]: recordId }),
    include: {
      reversal: {
        include: {
          createdBy: { select: { id: true, displayName: true } },
        },
      },
      financialAccount: { select: { id: true, name: true, type: true } },
      createdBy: { select: { id: true, displayName: true } },
    },
    orderBy: [{ occurredAt: "asc" }, { id: "asc" }],
  });
}

function projectionFor(record, events, config) {
  const amount = toUnits(String(record.amount));
  const activeEvents = events.filter((event) => !event.reversal);
  const settled = activeEvents.reduce(
    (sum, event) => sum + toUnits(String(event.principalAmount)),
    0n,
  );
  const outstanding = amount - settled;
  if (outstanding < 0n) {
    throw new FinancialStateConflictError(
      "Os eventos excedem o valor principal do título.",
    );
  }

  const status =
    record.status === "CANCELLED"
      ? "CANCELLED"
      : outstanding === 0n
      ? "SETTLED"
      : outstanding < amount
        ? "PARTIAL"
        : "OPEN";
  const settledAt =
    outstanding === 0n
      ? activeEvents
          .map((event) => new Date(event.occurredAt))
          .sort((left, right) => right.valueOf() - left.valueOf())[0] ?? null
      : null;

  return Object.freeze({
    outstanding,
    status,
    settledAt,
    settledAtField: config.settledAtField,
  });
}

async function refreshProjection(
  transaction,
  context,
  config,
  record,
  events,
) {
  const projection = projectionFor(record, events, config);
  const persisted = await loadRecord(
    transaction,
    context,
    config,
    record.id,
  );
  const expectedSettledAt = projection.settledAt?.toISOString() ?? null;
  const storedSettledAt = persisted[config.settledAtField]
    ? new Date(persisted[config.settledAtField]).toISOString()
    : null;
  if (
    fromUnits(projection.outstanding) !==
      fromUnits(toUnits(String(persisted.outstandingAmount))) ||
    projection.status !== persisted.status ||
    expectedSettledAt !== storedSettledAt
  ) {
    throw new FinancialStateConflictError(
      "O banco não confirmou a projeção derivada dos eventos financeiros.",
    );
  }
  return persisted;
}

function mutationHash(operation, context, input) {
  return createHash("sha256")
    .update(
      JSON.stringify({
        operation,
        tenantId: context.tenantId,
        companyId: context.activeCompanyId,
        branchId: context.activeBranchId,
        input,
      }),
    )
    .digest("hex");
}

async function idempotentMutation({
  transaction,
  context,
  operation,
  idempotencyKey,
  input,
  resourceType,
  execute,
}) {
  const key = validateIdempotencyKey(idempotencyKey);
  const hash = mutationHash(operation, context, input);
  const lockScope = [context.tenantId, context.userId, operation, key].join(":");
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
        key,
      },
    },
  });
  if (existing) {
    if (existing.requestHash !== hash) throw new IdempotencyConflictError();
    return Object.freeze({
      status: existing.responseStatus,
      body: existing.responseBody,
      replayed: true,
    });
  }

  const result = await execute();
  await transaction.idempotencyRecord.create({
    data: {
      tenantId: context.tenantId,
      userId: context.userId,
      operation,
      key,
      requestHash: hash,
      resourceType,
      resourceId: result.resourceId,
      responseStatus: result.status,
      responseBody: result.body,
      expiresAt: new Date(Date.now() + 24 * 60 * 60_000),
    },
  });
  return Object.freeze({ ...result, replayed: false });
}

async function writeAudit({
  transaction,
  context,
  action,
  entityType,
  entityId,
  beforeData,
  afterData,
  metadata,
  occurredAt,
}) {
  const redactedFields = [];
  const payload = {
    tenantId: context.tenantId,
    companyId: context.activeCompanyId,
    branchId: afterData.branchId ?? null,
    actorUserId: context.userId,
    sessionId: context.sessionId,
    action,
    entityType,
    entityId,
    beforeData,
    afterData,
    metadata,
    redactedFields,
    occurredAt: occurredAt.toISOString(),
  };
  const integrityHash = createHash("sha256")
    .update(JSON.stringify(payload))
    .digest("hex");

  await transaction.auditLog.create({
    data: {
      tenantId: context.tenantId,
      companyId: context.activeCompanyId,
      branchId: afterData.branchId ?? null,
      actorUserId: context.userId,
      sessionId: context.sessionId,
      action,
      entityType,
      entityId,
      beforeData,
      afterData,
      metadata,
      redactedFields,
      integrityHash,
      occurredAt,
    },
  });
}

export async function createSettlementEvent({
  prisma,
  session,
  kind,
  input,
  idempotencyKey,
}) {
  const config = configuration(kind);
  const validated = parseInput(settlementSchema, input);
  const amounts = validateSettlementAmounts(validated);
  const occurredAt = financialOccurredAt(validated.occurredAt);

  return runWithTenantContext(session, async () => {
    requirePermission(`${config.permissionPrefix}.manage`);
    return withTenantTransaction(prisma, async (transaction, context) =>
      idempotentMutation({
        transaction,
        context,
        operation: `${kind}.settlement.create`,
        idempotencyKey,
        input: validated,
        resourceType: config.eventType,
        execute: async () => {
          await lockRecord(
            transaction,
            context,
            config,
            validated.recordId,
          );
          const record = await loadRecord(
            transaction,
            context,
            config,
            validated.recordId,
          );
          if (record.status === "CANCELLED") {
            throw new FinancialStateConflictError(
              "Um título cancelado não pode receber baixa.",
            );
          }

          const currentEvents = await loadEvents(
            transaction,
            config,
            validated.recordId,
          );
          const beforeProjection = projectionFor(record, currentEvents, config);
          if (amounts.principal > beforeProjection.outstanding) {
            throw new FinancialStateConflictError(
              "O valor principal da baixa excede o saldo em aberto.",
            );
          }

          const account = await transaction.financialAccount.findFirst({
            where: companyScopedWhere({
              id: validated.financialAccountId,
              isActive: true,
              deletedAt: null,
            }),
          });
          if (!account) {
            throw new FinancialValidationError(
              "A conta financeira não está disponível neste contexto.",
              [
                {
                  field: "financialAccountId",
                  code: "FINANCIAL_ACCOUNT_NOT_AVAILABLE",
                  message: "Selecione uma conta financeira ativa e autorizada.",
                },
              ],
            );
          }
          if (
            (account.branchId &&
              account.branchId !== record.branchId) ||
            account.currencyCode !== record.currencyCode
          ) {
            throw new FinancialValidationError(
              "A conta financeira é incompatível com o título.",
              [
                {
                  field: "financialAccountId",
                  code: "FINANCIAL_ACCOUNT_SCOPE_MISMATCH",
                  message: "Unidade e moeda devem ser compatíveis.",
                },
              ],
            );
          }

          const event = await eventModel(transaction, config).create({
            data: companyScopedData({
              branchId: record.branchId,
              [config.recordIdField]: record.id,
              financialAccountId: account.id,
              principalAmount: fromUnits(amounts.principal),
              interestAmount: fromUnits(amounts.interest),
              fineAmount: fromUnits(amounts.fine),
              discountAmount: fromUnits(amounts.discount),
              cashAmount: fromUnits(amounts.cash),
              occurredAt,
              note: validated.note ?? null,
              createdById: context.userId,
            }),
          });
          const events = await loadEvents(transaction, config, record.id);
          const updated = await refreshProjection(
            transaction,
            context,
            config,
            record,
            events,
          );
          const body = {
            data: {
              record: recordSummary(updated, config),
              event: eventSummary(
                events.find((candidate) => candidate.id === event.id),
              ),
            },
          };

          await writeAudit({
            transaction,
            context,
            action: config.settlementAction,
            entityType: config.eventType,
            entityId: event.id,
            beforeData: {
              outstandingAmount: fromUnits(beforeProjection.outstanding),
              status: beforeProjection.status,
            },
            afterData: {
              branchId: record.branchId,
              recordId: record.id,
              principalAmount: fromUnits(amounts.principal),
              interestAmount: fromUnits(amounts.interest),
              fineAmount: fromUnits(amounts.fine),
              discountAmount: fromUnits(amounts.discount),
              cashAmount: fromUnits(amounts.cash),
              outstandingAmount: String(updated.outstandingAmount),
              status: updated.status,
            },
            metadata: {
              source: "api",
              financialAccountId: account.id,
            },
            occurredAt,
          });

          return {
            status: 201,
            body,
            resourceId: event.id,
          };
        },
      }),
    );
  });
}

export async function reverseSettlementEvent({
  prisma,
  session,
  kind,
  input,
  idempotencyKey,
}) {
  const config = configuration(kind);
  const validated = parseInput(reversalSchema, input);
  const occurredAt = financialOccurredAt(validated.occurredAt);

  return runWithTenantContext(session, async () => {
    requirePermission(`${config.permissionPrefix}.manage`);
    return withTenantTransaction(prisma, async (transaction, context) =>
      idempotentMutation({
        transaction,
        context,
        operation: `${kind}.settlement.reverse`,
        idempotencyKey,
        input: validated,
        resourceType: config.reversalType,
        execute: async () => {
          await lockRecord(
            transaction,
            context,
            config,
            validated.recordId,
          );
          const record = await loadRecord(
            transaction,
            context,
            config,
            validated.recordId,
          );
          const currentEvents = await loadEvents(
            transaction,
            config,
            validated.recordId,
          );
          const event = currentEvents.find(
            (candidate) => candidate.id === validated.eventId,
          );
          if (!event) throw new FinancialNotFoundError("A baixa não foi encontrada.");
          if (event.reversal) {
            throw new FinancialStateConflictError("A baixa já foi estornada.");
          }
          if (occurredAt.valueOf() < new Date(event.occurredAt).valueOf()) {
            throw new FinancialValidationError(
              "O estorno não pode ocorrer antes da baixa.",
              [
                {
                  field: "occurredAt",
                  code: "REVERSAL_BEFORE_SETTLEMENT",
                  message:
                    "Informe uma data e hora igual ou posterior à baixa original.",
                },
              ],
            );
          }
          const beforeProjection = projectionFor(record, currentEvents, config);

          const reversal = await reversalModel(transaction, config).create({
            data: companyScopedData({
              branchId: event.branchId,
              [`${config.eventDelegate}Id`]: event.id,
              reason: validated.reason,
              occurredAt,
              createdById: context.userId,
            }),
          });
          const events = await loadEvents(transaction, config, record.id);
          const updated = await refreshProjection(
            transaction,
            context,
            config,
            record,
            events,
          );
          const body = {
            data: {
              record: recordSummary(updated, config),
              event: eventSummary(
                events.find((candidate) => candidate.id === event.id),
              ),
            },
          };

          await writeAudit({
            transaction,
            context,
            action: config.reversalAction,
            entityType: config.reversalType,
            entityId: reversal.id,
            beforeData: {
              outstandingAmount: fromUnits(beforeProjection.outstanding),
              status: beforeProjection.status,
            },
            afterData: {
              branchId: record.branchId,
              recordId: record.id,
              reversedEventId: event.id,
              outstandingAmount: String(updated.outstandingAmount),
              status: updated.status,
            },
            metadata: { source: "api", reason: validated.reason },
            occurredAt,
          });

          return {
            status: 201,
            body,
            resourceId: reversal.id,
          };
        },
      }),
    );
  });
}

export async function getFinancialHistory({
  prisma,
  session,
  kind,
  recordId,
}) {
  const config = configuration(kind);
  if (!UUID_PATTERN.test(String(recordId ?? ""))) {
    throw new FinancialValidationError("Identificador inválido.", [
      {
        field: "recordId",
        code: "INVALID_ID",
        message: "Informe um identificador válido.",
      },
    ]);
  }

  return runWithTenantContext(session, async () => {
    requirePermission(`${config.permissionPrefix}.view`);
    return withTenantTransaction(prisma, async (transaction, context) => {
      const record = await loadRecord(transaction, context, config, recordId);
      const events = await loadEvents(transaction, config, recordId);
      const projection = projectionFor(record, events, config);
      const expectedSettledAt = projection.settledAt?.toISOString() ?? null;
      const storedSettledAt = record[config.settledAtField]
        ? new Date(record[config.settledAtField]).toISOString()
        : null;
      if (
        fromUnits(projection.outstanding) !==
          fromUnits(toUnits(String(record.outstandingAmount))) ||
        projection.status !== record.status ||
        expectedSettledAt !== storedSettledAt
      ) {
        throw new FinancialStateConflictError(
          "A projeção do título diverge dos eventos financeiros.",
        );
      }
      const timeline = financialTimeline(events);
      const reversalCount = events.filter((event) => event.reversal).length;
      return {
        data: {
          record: recordSummary(record, config),
          events: timeline,
          audit: {
            appendOnly: true,
            eventCount: timeline.length,
            settlementCount: events.length,
            reversalCount,
            projectionVerified: true,
          },
        },
      };
    });
  });
}
