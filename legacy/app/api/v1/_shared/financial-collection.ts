import { chatGPTUserFromHeaders } from "../../../chatgpt-auth";
import {
  InvalidContextSelectionError,
  resolveIdentityAccess,
  verifiedSessionForSelection,
} from "../../../../server/auth-context.mjs";
import {
  createFinancialRecord,
  FinancialValidationError,
  IdempotencyConflictError,
  listFinancialRecords,
} from "../../../../server/financial-records.mjs";
import {
  createFinancialCatalog,
  FinancialCatalogConflictError,
  listFinancialCatalogs,
} from "../../../../server/financial-catalogs.mjs";
import {
  createSettlementEvent,
  FinancialNotFoundError,
  FinancialStateConflictError,
  getFinancialHistory,
  reverseSettlementEvent,
} from "../../../../server/financial-events.mjs";
import {
  DatabaseConfigurationError,
  getPrisma,
} from "../../../../server/prisma";

type FinancialKind = "payable" | "receivable";
const MAX_JSON_BODY_BYTES = 64 * 1024;
type RequestContextResult =
  | {
      error:
        | "AUTHENTICATION_REQUIRED"
        | "COMPANY_CONTEXT_REQUIRED"
        | "MEMBERSHIP_REQUIRED";
    }
  | {
      prisma: ReturnType<typeof getPrisma>;
      session: ReturnType<typeof verifiedSessionForSelection>;
    };

export function problem(
  status: number,
  code: string,
  title: string,
  requestId: string,
  errors?: unknown[],
) {
  return Response.json(
    {
      type: `https://clareza.example/problems/${code.toLowerCase()}`,
      title,
      status,
      code,
      requestId,
      ...(errors?.length ? { errors } : {}),
    },
    { status },
  );
}

export async function requestContext(
  request: Request,
): Promise<RequestContextResult> {
  const identity = chatGPTUserFromHeaders(request.headers);
  if (!identity) {
    return { error: "AUTHENTICATION_REQUIRED" as const };
  }

  const companyId = request.headers.get("x-clareza-company-id");
  if (!companyId) {
    return { error: "COMPANY_CONTEXT_REQUIRED" as const };
  }

  const prisma = getPrisma();
  const access = await resolveIdentityAccess(prisma, identity.email);
  if (access.length === 0) {
    return { error: "MEMBERSHIP_REQUIRED" as const };
  }

  const branchId = request.headers.get("x-clareza-branch-id");
  const session = verifiedSessionForSelection(access, { companyId, branchId });
  return { prisma, session };
}

function contextProblem(code: string, requestId: string) {
  if (code === "AUTHENTICATION_REQUIRED") {
    return problem(401, code, "Entre para acessar o Mourão Clareza.", requestId);
  }
  if (code === "COMPANY_CONTEXT_REQUIRED") {
    return problem(409, code, "Selecione uma empresa autorizada.", requestId);
  }
  return problem(
    403,
    "MEMBERSHIP_REQUIRED",
    "Sua identidade não possui acesso a este contexto.",
    requestId,
  );
}

export async function parseJsonInput(
  request: Request,
  requestId: string,
  maxBytes = MAX_JSON_BODY_BYTES,
) {
  const payloadTooLarge = () =>
    problem(
      413,
      "PAYLOAD_TOO_LARGE",
      "O conteúdo excede o limite permitido.",
      requestId,
    );
  const declaredLength = request.headers.get("content-length");
  const contentLength = declaredLength === null ? null : Number(declaredLength);
  if (
    contentLength !== null &&
    (!/^\d+$/.test(declaredLength ?? "") ||
      !Number.isSafeInteger(contentLength) ||
      contentLength < 0)
  ) {
    return {
      response: problem(
        400,
        "INVALID_CONTENT_LENGTH",
        "O tamanho declarado do conteúdo é inválido.",
        requestId,
      ),
    } as const;
  }
  if (contentLength !== null && contentLength > maxBytes) {
    return { response: payloadTooLarge() } as const;
  }

  const reader = request.body?.getReader();
  const decoder = new TextDecoder();
  let totalBytes = 0;
  let text = "";
  if (reader) {
    try {
      while (true) {
        const chunk = await reader.read();
        if (chunk.done) break;
        totalBytes += chunk.value.byteLength;
        if (totalBytes > maxBytes) {
          await reader.cancel();
          return { response: payloadTooLarge() } as const;
        }
        text += decoder.decode(chunk.value, { stream: true });
      }
      text += decoder.decode();
    } finally {
      reader.releaseLock();
    }
  }
  try {
    return { input: JSON.parse(text) as unknown } as const;
  } catch {
    return {
      response: problem(
        400,
        "INVALID_JSON",
        "Envie um JSON válido.",
        requestId,
      ),
    } as const;
  }
}

export async function handleFinancialGet(
  request: Request,
  kind: FinancialKind,
) {
  const requestId = crypto.randomUUID();
  try {
    const context = await requestContext(request);
    if ("error" in context) return contextProblem(context.error, requestId);

    const url = new URL(request.url);
    const result = await listFinancialRecords({
      prisma: context.prisma,
      session: context.session,
      kind,
      filters: {
        status: url.searchParams.get("status") ?? undefined,
        dueFrom: url.searchParams.get("dueFrom") ?? undefined,
        dueTo: url.searchParams.get("dueTo") ?? undefined,
        cursor: url.searchParams.get("cursor") ?? undefined,
        limit: url.searchParams.get("limit") ?? undefined,
      },
    });
    return Response.json({ ...result, meta: { requestId } });
  } catch (error) {
    return financialError(error, requestId);
  }
}

export async function handleFinancialPost(
  request: Request,
  kind: FinancialKind,
) {
  const requestId = crypto.randomUUID();
  try {
    const context = await requestContext(request);
    if ("error" in context) return contextProblem(context.error, requestId);

    const parsed = await parseJsonInput(request, requestId);
    if ("response" in parsed) return parsed.response;

    const result = await createFinancialRecord({
      prisma: context.prisma,
      session: context.session,
      kind,
      input: parsed.input,
      idempotencyKey: request.headers.get("idempotency-key"),
    });

    return Response.json(
      { ...result.body, meta: { requestId } },
      {
        status: result.status,
        headers: result.replayed ? { "Idempotency-Replayed": "true" } : undefined,
      },
    );
  } catch (error) {
    return financialError(error, requestId);
  }
}

async function handleFinancialEventPost(
  request: Request,
  kind: FinancialKind,
  operation: "settlement" | "reversal",
) {
  const requestId = crypto.randomUUID();
  try {
    const context = await requestContext(request);
    if ("error" in context) return contextProblem(context.error, requestId);

    const parsed = await parseJsonInput(request, requestId);
    if ("response" in parsed) return parsed.response;

    const mutation =
      operation === "settlement"
        ? createSettlementEvent
        : reverseSettlementEvent;
    const result = await mutation({
      prisma: context.prisma,
      session: context.session,
      kind,
      input: parsed.input,
      idempotencyKey: request.headers.get("idempotency-key"),
    });
    return Response.json(
      { ...result.body, meta: { requestId } },
      {
        status: result.status,
        headers: result.replayed
          ? { "Idempotency-Replayed": "true" }
          : undefined,
      },
    );
  } catch (error) {
    return financialError(error, requestId);
  }
}

export function handleFinancialSettlementPost(
  request: Request,
  kind: FinancialKind,
) {
  return handleFinancialEventPost(request, kind, "settlement");
}

export function handleFinancialReversalPost(
  request: Request,
  kind: FinancialKind,
) {
  return handleFinancialEventPost(request, kind, "reversal");
}

export async function handleFinancialHistoryGet(
  request: Request,
  kind: FinancialKind,
) {
  const requestId = crypto.randomUUID();
  try {
    const context = await requestContext(request);
    if ("error" in context) return contextProblem(context.error, requestId);
    const result = await getFinancialHistory({
      prisma: context.prisma,
      session: context.session,
      kind,
      recordId: new URL(request.url).searchParams.get("recordId"),
    });
    return Response.json({ ...result, meta: { requestId } });
  } catch (error) {
    return financialError(error, requestId);
  }
}

export async function handleFinancialCatalogsGet(request: Request) {
  const requestId = crypto.randomUUID();
  try {
    const context = await requestContext(request);
    if ("error" in context) return contextProblem(context.error, requestId);
    const result = await listFinancialCatalogs({
      prisma: context.prisma,
      session: context.session,
    });
    return Response.json({ ...result, meta: { requestId } });
  } catch (error) {
    return financialError(error, requestId);
  }
}

export async function handleFinancialCatalogsPost(request: Request) {
  const requestId = crypto.randomUUID();
  try {
    const context = await requestContext(request);
    if ("error" in context) return contextProblem(context.error, requestId);
    const parsed = await parseJsonInput(request, requestId);
    if ("response" in parsed) return parsed.response;
    const result = await createFinancialCatalog({
      prisma: context.prisma,
      session: context.session,
      input: parsed.input,
      idempotencyKey: request.headers.get("idempotency-key"),
    });
    return Response.json(
      { ...result.body, meta: { requestId } },
      {
        status: result.status,
        headers: result.replayed
          ? { "Idempotency-Replayed": "true" }
          : undefined,
      },
    );
  } catch (error) {
    return financialError(error, requestId);
  }
}

export function financialError(error: unknown, requestId: string) {
  if (error instanceof FinancialValidationError) {
    return problem(422, error.code, error.message, requestId, error.errors);
  }
  if (error instanceof IdempotencyConflictError) {
    return problem(409, error.code, error.message, requestId);
  }
  if (
    error instanceof FinancialStateConflictError ||
    error instanceof FinancialCatalogConflictError
  ) {
    return problem(409, error.code, error.message, requestId);
  }
  if (error instanceof FinancialNotFoundError) {
    return problem(404, error.code, error.message, requestId);
  }
  if (error instanceof InvalidContextSelectionError) {
    return problem(400, error.code, error.message, requestId, [
      {
        field: error.field,
        code: "INVALID_UUID",
        message: "Informe um identificador UUID válido.",
      },
    ]);
  }
  if (error instanceof DatabaseConfigurationError) {
    return problem(
      503,
      "DATABASE_UNAVAILABLE",
      "O banco do Mourão Clareza ainda não está configurado neste ambiente.",
      requestId,
    );
  }
  if (
    error &&
    typeof error === "object" &&
    "code" in error &&
    error.code === "FORBIDDEN"
  ) {
    return problem(403, "FORBIDDEN", "Permissão insuficiente para esta operação.", requestId);
  }
  if (
    error instanceof Error &&
    /empresa solicitada|unidade autorizada|contexto autenticado/i.test(error.message)
  ) {
    return problem(403, "FORBIDDEN", "O contexto solicitado não está autorizado.", requestId);
  }
  return problem(503, "SERVICE_UNAVAILABLE", "Não foi possível concluir a operação agora.", requestId);
}
