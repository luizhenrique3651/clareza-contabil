export const IDENTITY_ENDPOINT = "/api/v1/me";
export const INITIAL_SESSION_STATUS: SessionStatus = "loading";

export const NAVIGATION_STORAGE_KEYS = Object.freeze({
  companyId: "clareza.navigation.company.v1",
  branchId: "clareza.navigation.branch.v1",
});

export type AccessScope = "TENANT" | "COMPANY" | "BRANCH";
export type SessionStatus =
  | "loading"
  | "unauthenticated"
  | "forbidden"
  | "unavailable"
  | "error"
  | "empty"
  | "ready";

export type IdentityBranch = Readonly<{
  id: string;
  name: string;
  permissions: readonly string[];
}>;

export type IdentityCompany = Readonly<{
  id: string;
  name: string;
  scopes: readonly AccessScope[];
  permissions: readonly string[];
  branches: readonly IdentityBranch[];
}>;

export type IdentityTenant = Readonly<{
  id: string;
  name: string;
  companies: readonly IdentityCompany[];
}>;

export type IdentitySummary = Readonly<{
  email: string;
  displayName: string;
  tenants: readonly IdentityTenant[];
}>;

export type NavigationContext = Readonly<{
  tenantId: string;
  tenantName: string;
  company: IdentityCompany;
  branch: IdentityBranch | null;
  accessMode: "company" | "branch";
  permissions: readonly string[];
  fingerprint: string;
}>;

export type NavigationPreferences = Readonly<{
  companyId: string | null;
  branchId: string | null;
}>;

export type InitialNavigation = Readonly<{
  context: NavigationContext | null;
  discardedCompanyPreference: boolean;
  discardedBranchPreference: boolean;
}>;

export type StorageLike = Pick<
  Storage,
  "getItem" | "setItem" | "removeItem"
>;

export type StorageOwner = Readonly<{ localStorage: StorageLike }>;

export type LatestRequestGate = Readonly<{
  begin: () => string;
  isCurrent: (requestId: string) => boolean;
}>;

type IdentityEnvelope = {
  data?: unknown;
  code?: unknown;
  title?: unknown;
  requestId?: unknown;
};

const ACCESS_SCOPES = new Set<AccessScope>(["TENANT", "COMPANY", "BRANCH"]);
const PERMISSION_PATTERN = /^[a-z][a-z0-9_]*\.[a-z][a-z0-9_]*$/;

export class IdentityClientError extends Error {
  readonly status: number;
  readonly sessionStatus: Exclude<SessionStatus, "loading" | "empty" | "ready">;
  readonly code: string;
  readonly requestId: string | null;

  constructor({
    message,
    status = 0,
    sessionStatus = "error",
    code = "IDENTITY_UNEXPECTED_ERROR",
    requestId = null,
  }: {
    message: string;
    status?: number;
    sessionStatus?: Exclude<SessionStatus, "loading" | "empty" | "ready">;
    code?: string;
    requestId?: string | null;
  }) {
    super(message);
    this.name = "IdentityClientError";
    this.status = status;
    this.sessionStatus = sessionStatus;
    this.code = code;
    this.requestId = requestId;
  }
}

export class NavigationContextError extends Error {
  readonly code: "COMPANY_NOT_AUTHORIZED" | "BRANCH_NOT_AUTHORIZED";

  constructor(
    code: "COMPANY_NOT_AUTHORIZED" | "BRANCH_NOT_AUTHORIZED",
    message: string,
  ) {
    super(message);
    this.name = "NavigationContextError";
    this.code = code;
  }
}

function objectValue(value: unknown, field: string): Record<string, unknown> {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    throw new TypeError(`${field} inválido no retorno de identidade.`);
  }
  return value as Record<string, unknown>;
}

function stringValue(value: unknown, field: string): string {
  const result = typeof value === "string" ? value.trim() : "";
  if (!result) {
    throw new TypeError(`${field} inválido no retorno de identidade.`);
  }
  return result;
}

function stringArray(
  value: unknown,
  field: string,
  predicate: (item: string) => boolean,
): readonly string[] {
  if (!Array.isArray(value)) {
    throw new TypeError(`${field} inválido no retorno de identidade.`);
  }
  const items = value.map((item, index) =>
    stringValue(item, `${field}[${index}]`),
  );
  if (items.some((item) => !predicate(item))) {
    throw new TypeError(`${field} contém valor inválido.`);
  }
  return Object.freeze([...new Set(items)].sort());
}

function parseBranch(value: unknown, field: string): IdentityBranch {
  const branch = objectValue(value, field);
  return Object.freeze({
    id: stringValue(branch.id, `${field}.id`),
    name: stringValue(branch.name, `${field}.name`),
    permissions: stringArray(
      branch.permissions,
      `${field}.permissions`,
      (permission) => PERMISSION_PATTERN.test(permission),
    ),
  });
}

function parseCompany(value: unknown, field: string): IdentityCompany {
  const company = objectValue(value, field);
  if (!Array.isArray(company.branches)) {
    throw new TypeError(`${field}.branches inválido no retorno de identidade.`);
  }
  return Object.freeze({
    id: stringValue(company.id, `${field}.id`),
    name: stringValue(company.name, `${field}.name`),
    scopes: stringArray(
      company.scopes,
      `${field}.scopes`,
      (scope) => ACCESS_SCOPES.has(scope as AccessScope),
    ) as readonly AccessScope[],
    permissions: stringArray(
      company.permissions,
      `${field}.permissions`,
      (permission) => PERMISSION_PATTERN.test(permission),
    ),
    branches: Object.freeze(
      company.branches.map((branch, index) =>
        parseBranch(branch, `${field}.branches[${index}]`),
      ),
    ),
  });
}

function parseTenant(value: unknown, field: string): IdentityTenant {
  const tenant = objectValue(value, field);
  if (!Array.isArray(tenant.companies)) {
    throw new TypeError(`${field}.companies inválido no retorno de identidade.`);
  }
  return Object.freeze({
    id: stringValue(tenant.id, `${field}.id`),
    name: stringValue(tenant.name, `${field}.name`),
    companies: Object.freeze(
      tenant.companies.map((company, index) =>
        parseCompany(company, `${field}.companies[${index}]`),
      ),
    ),
  });
}

export function parseIdentityEnvelope(payload: unknown): IdentitySummary {
  const envelope = objectValue(payload, "resposta");
  const data = objectValue(envelope.data, "data");
  if (!Array.isArray(data.tenants)) {
    throw new TypeError("data.tenants inválido no retorno de identidade.");
  }

  return Object.freeze({
    email: stringValue(data.email, "data.email").toLowerCase(),
    displayName: stringValue(data.displayName, "data.displayName"),
    tenants: Object.freeze(
      data.tenants.map((tenant, index) =>
        parseTenant(tenant, `data.tenants[${index}]`),
      ),
    ),
  });
}

function problemDetails(payload: unknown): IdentityEnvelope {
  return payload && typeof payload === "object" && !Array.isArray(payload)
    ? (payload as IdentityEnvelope)
    : {};
}

function statusForResponse(
  status: number,
): Exclude<SessionStatus, "loading" | "empty" | "ready"> {
  if (status === 401) return "unauthenticated";
  if (status === 403) return "forbidden";
  if (status === 503) return "unavailable";
  return "error";
}

export async function fetchIdentity({
  fetcher = globalThis.fetch,
  signal,
}: {
  fetcher?: typeof fetch;
  signal?: AbortSignal;
} = {}): Promise<IdentitySummary> {
  let response: Response;
  try {
    response = await fetcher(IDENTITY_ENDPOINT, {
      method: "GET",
      credentials: "same-origin",
      cache: "no-store",
      headers: { accept: "application/json" },
      signal,
    });
  } catch (error) {
    if (error instanceof DOMException && error.name === "AbortError") throw error;
    throw new IdentityClientError({
      message: "Não foi possível alcançar o serviço de identidade.",
      sessionStatus: "unavailable",
      code: "IDENTITY_NETWORK_ERROR",
    });
  }

  let payload: unknown;
  try {
    payload = await response.json();
  } catch {
    if (!response.ok) {
      throw new IdentityClientError({
        message: "Não foi possível resolver a identidade.",
        status: response.status,
        sessionStatus: statusForResponse(response.status),
        code: "IDENTITY_REQUEST_FAILED",
      });
    }
    throw new IdentityClientError({
      message: "O serviço de identidade retornou uma resposta inválida.",
      status: response.status,
      sessionStatus: "error",
      code: "IDENTITY_INVALID_RESPONSE",
    });
  }

  if (!response.ok) {
    const problem = problemDetails(payload);
    throw new IdentityClientError({
      message:
        typeof problem.title === "string"
          ? problem.title
          : "Não foi possível resolver a identidade.",
      status: response.status,
      sessionStatus: statusForResponse(response.status),
      code:
        typeof problem.code === "string"
          ? problem.code
          : "IDENTITY_REQUEST_FAILED",
      requestId:
        typeof problem.requestId === "string" ? problem.requestId : null,
    });
  }

  try {
    return parseIdentityEnvelope(payload);
  } catch (error) {
    throw new IdentityClientError({
      message:
        error instanceof Error
          ? error.message
          : "O contrato de identidade é inválido.",
      status: response.status,
      sessionStatus: "error",
      code: "IDENTITY_INVALID_CONTRACT",
    });
  }
}

function allCompanies(identity: IdentitySummary) {
  return identity.tenants.flatMap((tenant) =>
    tenant.companies.map((company) => ({ tenant, company })),
  );
}

function hasCompanyWideAccess(company: IdentityCompany) {
  return (
    company.scopes.includes("TENANT") || company.scopes.includes("COMPANY")
  );
}

function contextFor(
  tenant: IdentityTenant,
  company: IdentityCompany,
  branch: IdentityBranch | null,
): NavigationContext {
  const accessMode = hasCompanyWideAccess(company) ? "company" : "branch";
  const permissions = Object.freeze(
    [
      ...new Set(
        branch ? branch.permissions : company.permissions,
      ),
    ].sort(),
  );
  return Object.freeze({
    tenantId: tenant.id,
    tenantName: tenant.name,
    company,
    branch,
    accessMode,
    permissions,
    fingerprint: `${tenant.id}:${company.id}:${branch?.id ?? "all"}`,
  });
}

export function resolveAuthorizedNavigation(
  identity: IdentitySummary,
  companyId: string,
  branchId: string | null = null,
): NavigationContext {
  const match = allCompanies(identity).find(
    ({ company }) => company.id === companyId,
  );
  if (!match) {
    throw new NavigationContextError(
      "COMPANY_NOT_AUTHORIZED",
      "A empresa solicitada não pertence à identidade atual.",
    );
  }

  const wideAccess = hasCompanyWideAccess(match.company);
  if (!branchId) {
    if (!wideAccess) {
      throw new NavigationContextError(
        "BRANCH_NOT_AUTHORIZED",
        "Uma unidade autorizada é obrigatória para esta empresa.",
      );
    }
    return contextFor(match.tenant, match.company, null);
  }

  const branch = match.company.branches.find((item) => item.id === branchId);
  if (!branch) {
    throw new NavigationContextError(
      "BRANCH_NOT_AUTHORIZED",
      "A unidade solicitada não pertence à empresa ativa.",
    );
  }
  return contextFor(match.tenant, match.company, branch);
}

export function defaultNavigationForCompany(
  identity: IdentitySummary,
  companyId: string,
): NavigationContext {
  const match = allCompanies(identity).find(
    ({ company }) => company.id === companyId,
  );
  if (!match) {
    throw new NavigationContextError(
      "COMPANY_NOT_AUTHORIZED",
      "A empresa solicitada não pertence à identidade atual.",
    );
  }
  if (hasCompanyWideAccess(match.company)) {
    return contextFor(match.tenant, match.company, null);
  }
  const branch = match.company.branches[0];
  if (!branch) {
    throw new NavigationContextError(
      "BRANCH_NOT_AUTHORIZED",
      "O acesso limitado exige uma unidade válida.",
    );
  }
  return contextFor(match.tenant, match.company, branch);
}

export function deriveInitialNavigation(
  identity: IdentitySummary,
  preferences: NavigationPreferences,
): InitialNavigation {
  const companies = allCompanies(identity);
  if (companies.length === 0) {
    return Object.freeze({
      context: null,
      discardedCompanyPreference: Boolean(preferences.companyId),
      discardedBranchPreference: Boolean(preferences.branchId),
    });
  }

  const preferredCompany = companies.find(
    ({ company }) => company.id === preferences.companyId,
  );
  const selected = preferredCompany ?? companies[0];
  const discardedCompanyPreference =
    Boolean(preferences.companyId) && !preferredCompany;
  const validPreferredBranch = selected.company.branches.find(
    (branch) => branch.id === preferences.branchId,
  );
  const wideAccess = hasCompanyWideAccess(selected.company);
  const branch = validPreferredBranch
    ? validPreferredBranch
    : wideAccess
      ? null
      : selected.company.branches[0] ?? null;

  if (!wideAccess && !branch) {
    throw new NavigationContextError(
      "BRANCH_NOT_AUTHORIZED",
      "O acesso limitado exige uma unidade válida.",
    );
  }

  return Object.freeze({
    context: contextFor(selected.tenant, selected.company, branch),
    discardedCompanyPreference,
    discardedBranchPreference:
      Boolean(preferences.branchId) && !validPreferredBranch,
  });
}

export function recoverAuthorizedNavigation(
  identity: IdentitySummary,
  previous: NavigationContext | null,
): NavigationContext | null {
  if (previous) {
    try {
      return resolveAuthorizedNavigation(
        identity,
        previous.company.id,
        previous.branch?.id ?? null,
      );
    } catch (error) {
      if (!(error instanceof NavigationContextError)) throw error;
    }
  }

  return deriveInitialNavigation(identity, {
    companyId: null,
    branchId: null,
  }).context;
}

export function createLatestRequestGate(
  createId: () => string = () => globalThis.crypto.randomUUID(),
): LatestRequestGate {
  let latestRequestId: string | null = null;
  return Object.freeze({
    begin() {
      latestRequestId = createId();
      return latestRequestId;
    },
    isCurrent(requestId) {
      return requestId === latestRequestId;
    },
  });
}

export function readNavigationPreferences(
  storage: StorageLike | null | undefined,
): NavigationPreferences {
  if (!storage) {
    return Object.freeze({ companyId: null, branchId: null });
  }
  try {
    return Object.freeze({
      companyId: storage.getItem(NAVIGATION_STORAGE_KEYS.companyId),
      branchId: storage.getItem(NAVIGATION_STORAGE_KEYS.branchId),
    });
  } catch {
    return Object.freeze({ companyId: null, branchId: null });
  }
}

export function safeNavigationStorage(
  owner: StorageOwner | null | undefined,
): StorageLike | null {
  if (!owner) return null;
  try {
    return owner.localStorage;
  } catch {
    return null;
  }
}

export function persistNavigationContext(
  storage: StorageLike | null | undefined,
  context: NavigationContext,
) {
  if (!storage) return;
  try {
    storage.setItem(NAVIGATION_STORAGE_KEYS.companyId, context.company.id);
    if (context.branch) {
      storage.setItem(NAVIGATION_STORAGE_KEYS.branchId, context.branch.id);
    } else {
      storage.removeItem(NAVIGATION_STORAGE_KEYS.branchId);
    }
  } catch {
    // Navegação continua funcional quando storage está indisponível.
  }
}

export function persistNavigationContextIfCurrent(
  gate: LatestRequestGate,
  requestId: string,
  storage: StorageLike | null | undefined,
  context: NavigationContext,
) {
  if (!gate.isCurrent(requestId)) return false;
  persistNavigationContext(storage, context);
  return true;
}

export function discardInvalidPreferences(
  storage: StorageLike | null | undefined,
  result: InitialNavigation,
) {
  if (!storage) return;
  try {
    if (result.discardedCompanyPreference) {
      storage.removeItem(NAVIGATION_STORAGE_KEYS.companyId);
    }
    if (
      result.discardedCompanyPreference ||
      result.discardedBranchPreference
    ) {
      storage.removeItem(NAVIGATION_STORAGE_KEYS.branchId);
    }
  } catch {
    // Storage é apenas preferência não confiável.
  }
}

export function contextRequestHeaders(
  context: NavigationContext,
  initial?: HeadersInit,
) {
  const headers = new Headers(initial);
  headers.delete("x-clareza-tenant-id");
  headers.delete("x-clareza-user-id");
  headers.delete("x-clareza-actor-id");
  headers.set("x-clareza-company-id", context.company.id);
  if (context.branch) {
    headers.set("x-clareza-branch-id", context.branch.id);
  } else {
    headers.delete("x-clareza-branch-id");
  }
  return headers;
}

export function permissionPresentation(
  permissions: readonly string[],
): "manage" | "read" | "none" {
  if (permissions.some((permission) => permission.endsWith(".manage"))) {
    return "manage";
  }
  if (permissions.some((permission) => permission.endsWith(".view"))) {
    return "read";
  }
  return "none";
}
