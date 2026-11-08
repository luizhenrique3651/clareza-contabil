import { randomUUID } from "node:crypto";

const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

export class InvalidContextSelectionError extends Error {
  constructor(field) {
    super(`${field} inválido no contexto solicitado.`);
    this.name = "InvalidContextSelectionError";
    this.code = "INVALID_CONTEXT_SELECTION";
    this.field = field;
  }
}

function normalizeEmail(email) {
  const normalized = String(email ?? "").trim().toLowerCase();
  if (!EMAIL_PATTERN.test(normalized) || normalized.length > 254) {
    throw new TypeError("A identidade autenticada não contém um e-mail válido.");
  }
  return normalized;
}

function uuid(value, field) {
  const normalized = String(value ?? "").toLowerCase();
  if (!UUID_PATTERN.test(normalized)) {
    throw new TypeError(`${field} inválido retornado pelo banco.`);
  }
  return normalized;
}

function selectionUuid(value, field) {
  try {
    return uuid(value, field);
  } catch {
    throw new InvalidContextSelectionError(field);
  }
}

function mapAccessRow(row) {
  return Object.freeze({
    tenantId: uuid(row.tenant_id, "tenant_id"),
    tenantName: String(row.tenant_name),
    userId: uuid(row.user_id, "user_id"),
    displayName: String(row.display_name),
    email: normalizeEmail(row.email),
    scope: String(row.scope),
    companyId: uuid(row.company_id, "company_id"),
    companyName: String(row.company_name),
    branchId: row.branch_id ? uuid(row.branch_id, "branch_id") : null,
    branchName: row.branch_name ? String(row.branch_name) : null,
    permissions: Object.freeze(
      [...new Set(Array.isArray(row.permissions) ? row.permissions.map(String) : [])].sort(),
    ),
  });
}

export async function resolveIdentityAccess(prisma, email) {
  if (!prisma || typeof prisma.$queryRawUnsafe !== "function") {
    throw new TypeError("Cliente Prisma inválido.");
  }

  const normalizedEmail = normalizeEmail(email);
  const rows = await prisma.$queryRawUnsafe(
    "SELECT * FROM app.resolve_identity_access($1)",
    normalizedEmail,
  );

  if (!Array.isArray(rows)) {
    throw new TypeError("O banco retornou um diretório de acesso inválido.");
  }

  return Object.freeze(rows.map(mapAccessRow));
}

export function summarizeIdentityAccess(rows, identity) {
  const normalizedEmail = normalizeEmail(identity?.email);
  const matchingRows = rows.filter((row) => row.email === normalizedEmail);
  const tenantMap = new Map();

  for (const row of matchingRows) {
    let tenant = tenantMap.get(row.tenantId);
    if (!tenant) {
      tenant = {
        id: row.tenantId,
        name: row.tenantName,
        companies: new Map(),
      };
      tenantMap.set(row.tenantId, tenant);
    }

    let company = tenant.companies.get(row.companyId);
    if (!company) {
      company = {
        id: row.companyId,
        name: row.companyName,
        scopes: new Set(),
        permissions: new Set(),
        branches: new Map(),
      };
      tenant.companies.set(row.companyId, company);
    }

    company.scopes.add(row.scope);
    if (row.scope === "TENANT" || row.scope === "COMPANY") {
      for (const permission of row.permissions) {
        company.permissions.add(permission);
      }
    }

    if (row.branchId) {
      let branch = company.branches.get(row.branchId);
      if (!branch) {
        branch = {
          id: row.branchId,
          name: row.branchName,
          permissions: new Set(),
        };
        company.branches.set(row.branchId, branch);
      }
      for (const permission of row.permissions) {
        branch.permissions.add(permission);
      }
    }
  }

  const tenants = [...tenantMap.values()]
    .sort((left, right) => left.id.localeCompare(right.id))
    .map((tenant) => {
      const companies = [...tenant.companies.values()]
        .sort((left, right) => left.id.localeCompare(right.id))
        .map((company) => {
          const branches = [...company.branches.values()]
            .sort((left, right) => left.id.localeCompare(right.id))
            .map((branch) =>
              Object.freeze({
                id: branch.id,
                name: branch.name,
                permissions: Object.freeze(
                  [
                    ...new Set([
                      ...company.permissions,
                      ...branch.permissions,
                    ]),
                  ].sort(),
                ),
              }),
            );

          return Object.freeze({
            id: company.id,
            name: company.name,
            scopes: Object.freeze([...company.scopes].sort()),
            permissions: Object.freeze([...company.permissions].sort()),
            branches: Object.freeze(branches),
          });
        });

      return Object.freeze({
        id: tenant.id,
        name: tenant.name,
        companies: Object.freeze(companies),
      });
    });

  return Object.freeze({
    email: normalizedEmail,
    displayName:
      matchingRows[0]?.displayName ??
      String(identity?.displayName ?? normalizedEmail),
    tenants: Object.freeze(tenants),
  });
}

/**
 * @param {readonly ReturnType<typeof mapAccessRow>[]} rows
 * @param {{
 *   companyId: string;
 *   branchId?: string | null;
 *   authenticationSource?: string;
 * }} selection
 */
export function verifiedSessionForSelection(
  rows,
  { companyId, branchId = null, authenticationSource = "sites-siwc" },
) {
  const normalizedCompanyId = selectionUuid(companyId, "companyId");
  const normalizedBranchId = branchId
    ? selectionUuid(branchId, "branchId")
    : null;
  const companyRows = rows.filter((row) => row.companyId === normalizedCompanyId);

  if (companyRows.length === 0) {
    throw new Error("A empresa solicitada não pertence à identidade autenticada.");
  }

  if (
    normalizedBranchId &&
    !companyRows.some((row) => row.branchId === normalizedBranchId)
  ) {
    throw new Error(
      "A unidade solicitada não é uma unidade autorizada para esta empresa.",
    );
  }

  const applicableRows = companyRows.filter(
    (row) =>
      row.scope === "TENANT" ||
      row.scope === "COMPANY" ||
      (row.scope === "BRANCH" && row.branchId === normalizedBranchId),
  );

  if (applicableRows.length === 0) {
    throw new Error("Selecione uma unidade autorizada para continuar.");
  }

  const tenantIds = new Set(applicableRows.map((row) => row.tenantId));
  const userIds = new Set(applicableRows.map((row) => row.userId));
  if (tenantIds.size !== 1 || userIds.size !== 1) {
    throw new Error("O contexto autenticado é ambíguo.");
  }

  const branchIds = companyRows
    .filter((row) => row.scope === "BRANCH" && row.branchId)
    .map((row) => row.branchId);
  const permissions = applicableRows.flatMap((row) => row.permissions);
  const hasCompanyAccess = applicableRows.some(
    (row) => row.scope === "TENANT" || row.scope === "COMPANY",
  );

  return Object.freeze({
    tenantId: applicableRows[0].tenantId,
    userId: applicableRows[0].userId,
    companyIds: Object.freeze(hasCompanyAccess ? [normalizedCompanyId] : []),
    branchCompanyIds: Object.freeze(branchIds.length ? [normalizedCompanyId] : []),
    branchIds: Object.freeze([...new Set(branchIds)]),
    activeCompanyId: normalizedCompanyId,
    activeBranchId: normalizedBranchId,
    tenantWideAccess: applicableRows.some((row) => row.scope === "TENANT"),
    permissions: Object.freeze([...new Set(permissions)].sort()),
    authentication: Object.freeze({
      verified: true,
      source: authenticationSource,
      sessionId: randomUUID(),
      expiresAt: new Date(Date.now() + 5 * 60_000).toISOString(),
    }),
  });
}
