import { apiRequest, NavigationContextSelection } from "@/shared/api/http-client";

export const IDENTITY_ENDPOINT = "/api/v1/me";
const NAVIGATION_STORAGE_KEY = "clareza.navigation.context.v1";

export interface IdentitySummary {
  user: {
    id: string;
    email: string;
    name: string;
    status: string;
  };
  tenants: Array<{
    id: string;
    name: string;
    slug: string;
    roleCode: string;
    tenantWideAccess: boolean;
  }>;
  companies: Array<{
    id: string;
    tenantId: string;
    legalName: string;
    tradeName: string;
    taxId: string;
    roleCode: string;
  }>;
  branches: Array<{
    id: string;
    tenantId: string;
    companyId: string;
    name: string;
    code: string;
    taxId: string;
  }>;
  activeContext: {
    tenantId: string;
    tenantName: string;
    companyId: string;
    companyTradeName: string;
    branchId: string | null;
    branchName: string | null;
    roleCode: string;
    tenantWideAccess: boolean;
    allowedCompanyIds: string[];
    allowedBranchIds: string[];
    permissions: string[];
  };
  requiresSelection: boolean;
}

/**
 * Armazena apenas a preferência de navegação (tenantId, companyId, branchId) validada pelo servidor.
 * Jamais armazena tokens, papéis ou permissões em localStorage.
 */
export const safeNavigationStorage = {
  read(): NavigationContextSelection | null {
    try {
      const raw = window.localStorage.getItem(NAVIGATION_STORAGE_KEY);
      if (!raw) return null;
      const parsed = JSON.parse(raw) as NavigationContextSelection;
      return {
        tenantId: typeof parsed.tenantId === "string" ? parsed.tenantId : null,
        companyId: typeof parsed.companyId === "string" ? parsed.companyId : null,
        branchId: typeof parsed.branchId === "string" ? parsed.branchId : null,
      };
    } catch {
      return null;
    }
  },
  write(selection: NavigationContextSelection): void {
    try {
      window.localStorage.setItem(
        NAVIGATION_STORAGE_KEY,
        JSON.stringify({
          tenantId: selection.tenantId ?? null,
          companyId: selection.companyId ?? null,
          branchId: selection.branchId ?? null,
        }),
      );
    } catch {
      // Ignora indisponibilidade de storage no navegador
    }
  },
  clear(): void {
    try {
      window.localStorage.removeItem(NAVIGATION_STORAGE_KEY);
    } catch {
      // Ignora indisponibilidade de storage
    }
  },
};

export async function fetchIdentitySummary(
  selection?: NavigationContextSelection,
  signal?: AbortSignal,
): Promise<IdentitySummary> {
  return apiRequest<IdentitySummary>(IDENTITY_ENDPOINT, {
    method: "GET",
    selection,
    signal,
  });
}
