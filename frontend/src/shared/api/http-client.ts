export interface ValidationErrorItem {
  field: string;
  code: string;
  message: string;
}

export interface ProblemDetails {
  type: string;
  title: string;
  status: number;
  detail: string;
  instance?: string;
  code: string;
  requestId?: string;
  errors?: ValidationErrorItem[];
}

export interface NavigationContextSelection {
  tenantId?: string | null;
  companyId?: string | null;
  branchId?: string | null;
}

export class ClarezaApiError extends Error {
  public readonly problem: ProblemDetails;

  constructor(problem: ProblemDetails) {
    super(problem.detail || problem.title);
    this.name = "ClarezaApiError";
    this.problem = problem;
  }
}

export function buildContextHeaders(
  selection?: NavigationContextSelection,
  idempotencyKey?: string,
): Record<string, string> {
  const headers: Record<string, string> = {
    Accept: "application/json, application/problem+json",
  };
  if (selection?.tenantId) {
    headers["X-Clareza-Tenant-Id"] = selection.tenantId;
  }
  if (selection?.companyId) {
    headers["X-Clareza-Company-Id"] = selection.companyId;
  }
  if (selection?.branchId) {
    headers["X-Clareza-Branch-Id"] = selection.branchId;
  }
  if (idempotencyKey) {
    headers["Idempotency-Key"] = idempotencyKey;
  }
  return headers;
}

export async function apiRequest<T>(
  path: string,
  options: {
    method?: string;
    body?: unknown;
    selection?: NavigationContextSelection;
    idempotencyKey?: string;
    signal?: AbortSignal;
  } = {},
): Promise<T> {
  const headers = buildContextHeaders(options.selection, options.idempotencyKey);
  if (options.body !== undefined) {
    headers["Content-Type"] = "application/json";
  }

  const response = await fetch(path, {
    method: options.method ?? "GET",
    headers,
    body: options.body !== undefined ? JSON.stringify(options.body) : undefined,
    credentials: "include",
    signal: options.signal,
  });

  if (!response.ok) {
    let problem: ProblemDetails;
    try {
      problem = (await response.json()) as ProblemDetails;
    } catch {
      problem = {
        type: "https://clareza.example/problems/unexpected-error",
        title: response.statusText || "Erro de comunicação",
        status: response.status,
        detail: "Não foi possível processar a resposta do servidor.",
        code: "UNEXPECTED_ERROR",
      };
    }
    throw new ClarezaApiError(problem);
  }

  return (await response.json()) as T;
}
