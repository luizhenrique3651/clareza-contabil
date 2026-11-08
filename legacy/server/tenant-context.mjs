import { AsyncLocalStorage } from "node:async_hooks";

const tenantStorage = new AsyncLocalStorage();
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const PERMISSION_PATTERN = /^[a-z][a-z0-9_]*\.[a-z][a-z0-9_]*$/;

export class AuthenticationRequiredError extends Error {
  constructor(message = "Uma sessão autenticada e verificada é obrigatória.") {
    super(message);
    this.name = "AuthenticationRequiredError";
    this.code = "AUTHENTICATION_REQUIRED";
  }
}

export class TenantIsolationError extends Error {
  constructor(message = "A operação viola o isolamento de tenant ou empresa.") {
    super(message);
    this.name = "TenantIsolationError";
    this.code = "TENANT_ISOLATION_VIOLATION";
  }
}

export class AuthorizationError extends Error {
  constructor(message = "Permissão insuficiente para a operação.") {
    super(message);
    this.name = "AuthorizationError";
    this.code = "FORBIDDEN";
  }
}

function requiredUuid(value, fieldName) {
  if (typeof value !== "string" || !UUID_PATTERN.test(value)) {
    throw new AuthenticationRequiredError(`${fieldName} ausente ou inválido na sessão verificada.`);
  }
  return value.toLowerCase();
}

function optionalUuid(value, fieldName) {
  return value == null ? null : requiredUuid(value, fieldName);
}

function verifiedPrincipalFromSession(session, activeCompanyOverride) {
  // `session` must be produced by server-side signature/session-store validation.
  // This module intentionally never reads cookies, headers, query strings or bodies.
  if (!session || session.authentication?.verified !== true) {
    throw new AuthenticationRequiredError();
  }

  const expiresAt = Date.parse(session.authentication.expiresAt);
  if (!Number.isFinite(expiresAt) || expiresAt <= Date.now() || session.authentication.revokedAt) {
    throw new AuthenticationRequiredError("A sessão expirou ou foi revogada.");
  }

  const tenantId = requiredUuid(session.tenantId, "tenantId");
  const userId = requiredUuid(session.userId, "userId");
  const sessionId = requiredUuid(session.authentication.sessionId, "sessionId");
  const tenantWideAccess = session.tenantWideAccess === true;
  const companyIds = Object.freeze([
    ...new Set((session.companyIds ?? []).map((id) => requiredUuid(id, "companyIds"))),
  ]);
  const branchCompanyIds = Object.freeze([
    ...new Set(
      (session.branchCompanyIds ?? []).map((id) =>
        requiredUuid(id, "branchCompanyIds"),
      ),
    ),
  ]);
  const branchIds = Object.freeze([
    ...new Set((session.branchIds ?? []).map((id) => requiredUuid(id, "branchIds"))),
  ]);

  if (
    !tenantWideAccess &&
    companyIds.length === 0 &&
    branchCompanyIds.length === 0
  ) {
    throw new AuthorizationError("A sessão não possui nenhuma empresa autorizada.");
  }

  const requestedActiveCompany = optionalUuid(
    activeCompanyOverride ?? session.activeCompanyId ?? companyIds[0],
    "activeCompanyId",
  );
  const requestedActiveBranch = optionalUuid(
    session.activeBranchId,
    "activeBranchId",
  );

  if (
    requestedActiveCompany &&
    !tenantWideAccess &&
    !companyIds.includes(requestedActiveCompany)
    && !branchCompanyIds.includes(requestedActiveCompany)
  ) {
    throw new TenantIsolationError("A empresa ativa não pertence às empresas autorizadas da sessão.");
  }

  if (
    requestedActiveBranch &&
    !tenantWideAccess &&
    !companyIds.includes(requestedActiveCompany) &&
    !branchIds.includes(requestedActiveBranch)
  ) {
    throw new TenantIsolationError("A unidade ativa não pertence às unidades autorizadas da sessão.");
  }

  const permissions = Object.freeze(
    [...new Set(session.permissions ?? [])].filter(
      (permission) => typeof permission === "string" && PERMISSION_PATTERN.test(permission),
    ),
  );

  return Object.freeze({
    tenantId,
    userId,
    sessionId,
    companyIds,
    branchCompanyIds,
    branchIds,
    activeCompanyId: requestedActiveCompany,
    activeBranchId: requestedActiveBranch,
    tenantWideAccess,
    permissions,
    authenticationSource: String(session.authentication.source ?? "verified-session"),
  });
}

/**
 * Establishes request-local scope from a session already verified by trusted
 * authentication middleware. A company selected by the request may be passed as
 * `activeCompanyId`, but it is checked against the verified membership claims.
 */
export function runWithTenantContext(session, operation, options = {}) {
  if (typeof operation !== "function") {
    throw new TypeError("operation deve ser uma função.");
  }
  const context = verifiedPrincipalFromSession(session, options.activeCompanyId);
  return tenantStorage.run(context, operation);
}

export function getTenantContext() {
  const context = tenantStorage.getStore();
  if (!context) {
    throw new AuthenticationRequiredError("Não existe contexto autenticado nesta execução.");
  }
  return context;
}

export function requirePermission(permission) {
  if (!PERMISSION_PATTERN.test(permission)) {
    throw new TypeError("Código de permissão inválido.");
  }
  const context = getTenantContext();
  if (!context.permissions.includes(permission)) {
    throw new AuthorizationError(`Permissão obrigatória: ${permission}.`);
  }
  return context;
}

function rejectConflictingScope(data, field, expected) {
  if (Object.hasOwn(data, field) && data[field] !== undefined && data[field] !== expected) {
    throw new TenantIsolationError(`${field} não pode substituir o contexto autenticado.`);
  }
}

export function tenantScopedWhere(where = {}) {
  if (!where || typeof where !== "object" || Array.isArray(where)) {
    throw new TypeError("where deve ser um objeto.");
  }
  const context = getTenantContext();
  rejectConflictingScope(where, "tenantId", context.tenantId);
  return { ...where, tenantId: context.tenantId };
}

export function companyScopedWhere(where = {}) {
  if (!where || typeof where !== "object" || Array.isArray(where)) {
    throw new TypeError("where deve ser um objeto.");
  }
  const context = getTenantContext();
  if (!context.activeCompanyId) {
    throw new AuthorizationError("Selecione uma empresa autorizada antes de consultar estes dados.");
  }
  rejectConflictingScope(where, "tenantId", context.tenantId);
  rejectConflictingScope(where, "companyId", context.activeCompanyId);
  return {
    ...where,
    tenantId: context.tenantId,
    companyId: context.activeCompanyId,
  };
}

export function tenantScopedData(data = {}) {
  if (!data || typeof data !== "object" || Array.isArray(data)) {
    throw new TypeError("data deve ser um objeto.");
  }
  const context = getTenantContext();
  rejectConflictingScope(data, "tenantId", context.tenantId);
  return { ...data, tenantId: context.tenantId };
}

export function companyScopedData(data = {}) {
  if (!data || typeof data !== "object" || Array.isArray(data)) {
    throw new TypeError("data deve ser um objeto.");
  }
  const context = getTenantContext();
  if (!context.activeCompanyId) {
    throw new AuthorizationError("Selecione uma empresa autorizada antes de gravar estes dados.");
  }
  rejectConflictingScope(data, "tenantId", context.tenantId);
  rejectConflictingScope(data, "companyId", context.activeCompanyId);
  return {
    ...data,
    tenantId: context.tenantId,
    companyId: context.activeCompanyId,
  };
}

export function assertRecordAccess(record) {
  if (!record || typeof record !== "object") {
    throw new TenantIsolationError("Registro ausente ou inválido.");
  }
  const context = getTenantContext();
  if (record.tenantId !== context.tenantId) {
    throw new TenantIsolationError("O registro pertence a outro tenant.");
  }
  if (
    record.companyId != null &&
    !context.tenantWideAccess &&
    !context.companyIds.includes(record.companyId)
  ) {
    throw new TenantIsolationError("O registro pertence a uma empresa não autorizada.");
  }
  return record;
}

/**
 * Runs database work in one transaction, assumes the non-BYPASSRLS runtime role
 * and sets PostgreSQL RLS variables using bound parameters. Calling Prisma
 * outside this wrapper for tenant-owned records is a programming error.
 */
export function withTenantTransaction(prisma, operation) {
  const context = getTenantContext();
  if (!prisma || typeof prisma.$transaction !== "function" || typeof operation !== "function") {
    throw new TypeError("Um cliente transacional e uma operação são obrigatórios.");
  }

  return prisma.$transaction(async (transaction) => {
    if (
      typeof transaction.$executeRawUnsafe !== "function" ||
      typeof transaction.$queryRawUnsafe !== "function"
    ) {
      throw new TypeError("O cliente não oferece as primitivas transacionais esperadas.");
    }

    // Constant SQL only: no request value is interpolated into either statement.
    await transaction.$executeRawUnsafe("SET LOCAL ROLE clarity_app");
    await transaction.$queryRawUnsafe(
      `SELECT
         set_config('app.tenant_id', $1, true),
         set_config('app.user_id', $2, true),
         set_config('app.company_ids', $3, true),
         set_config('app.branch_ids', $4, true),
         set_config('app.tenant_wide_access', $5, true)`,
      context.tenantId,
      context.userId,
      context.companyIds.join(","),
      context.branchIds.join(","),
      String(context.tenantWideAccess),
    );

    return operation(transaction, context);
  });
}
