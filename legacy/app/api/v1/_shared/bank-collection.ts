import { BANK_IMPORT_LIMITS, BankImportValidationError } from "../../../../server/bank-imports.mjs";
import {
  BankImportConflictError,
  createBankImport,
  listBankImports,
  listBankTransactions,
} from "../../../../server/bank-service.mjs";
import {
  financialError,
  parseJsonInput,
  problem,
  requestContext,
} from "./financial-collection";

const MAX_BANK_IMPORT_BODY_BYTES =
  BANK_IMPORT_LIMITS.maxBytes * 6 + 4 * 1024;

function bankError(error: unknown, requestId: string) {
  if (error instanceof BankImportValidationError) {
    return problem(422, error.code, error.message, requestId, [
      ...error.errors,
    ]);
  }
  if (error instanceof BankImportConflictError) {
    return problem(409, error.code, error.message, requestId);
  }
  return financialError(error, requestId);
}

export async function handleBankImportsGet(request: Request) {
  const requestId = crypto.randomUUID();
  try {
    const context = await requestContext(request);
    if ("error" in context) {
      if (context.error === "AUTHENTICATION_REQUIRED") {
        return problem(401, context.error, "Entre para acessar o Mourão Clareza.", requestId);
      }
      if (context.error === "COMPANY_CONTEXT_REQUIRED") {
        return problem(409, context.error, "Selecione uma empresa autorizada.", requestId);
      }
      return problem(
        403,
        "MEMBERSHIP_REQUIRED",
        "Sua identidade não possui acesso a este contexto.",
        requestId,
      );
    }
    const url = new URL(request.url);
    const result = await listBankImports({
      prisma: context.prisma,
      session: context.session,
      filters: {
        financialAccountId:
          url.searchParams.get("financialAccountId") ?? undefined,
        cursor: url.searchParams.get("cursor") ?? undefined,
        limit: url.searchParams.get("limit") ?? undefined,
      },
    });
    return Response.json({ ...result, meta: { requestId } });
  } catch (error) {
    return bankError(error, requestId);
  }
}

export async function handleBankImportsPost(request: Request) {
  const requestId = crypto.randomUUID();
  try {
    const context = await requestContext(request);
    if ("error" in context) {
      if (context.error === "AUTHENTICATION_REQUIRED") {
        return problem(401, context.error, "Entre para acessar o Mourão Clareza.", requestId);
      }
      if (context.error === "COMPANY_CONTEXT_REQUIRED") {
        return problem(409, context.error, "Selecione uma empresa autorizada.", requestId);
      }
      return problem(
        403,
        "MEMBERSHIP_REQUIRED",
        "Sua identidade não possui acesso a este contexto.",
        requestId,
      );
    }
    const parsed = await parseJsonInput(
      request,
      requestId,
      MAX_BANK_IMPORT_BODY_BYTES,
    );
    if ("response" in parsed) return parsed.response;

    const result = await createBankImport({
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
    return bankError(error, requestId);
  }
}

export async function handleBankTransactionsGet(request: Request) {
  const requestId = crypto.randomUUID();
  try {
    const context = await requestContext(request);
    if ("error" in context) {
      if (context.error === "AUTHENTICATION_REQUIRED") {
        return problem(401, context.error, "Entre para acessar o Mourão Clareza.", requestId);
      }
      if (context.error === "COMPANY_CONTEXT_REQUIRED") {
        return problem(409, context.error, "Selecione uma empresa autorizada.", requestId);
      }
      return problem(
        403,
        "MEMBERSHIP_REQUIRED",
        "Sua identidade não possui acesso a este contexto.",
        requestId,
      );
    }
    const url = new URL(request.url);
    const result = await listBankTransactions({
      prisma: context.prisma,
      session: context.session,
      filters: {
        financialAccountId:
          url.searchParams.get("financialAccountId") ?? undefined,
        bankImportId: url.searchParams.get("bankImportId") ?? undefined,
        cursor: url.searchParams.get("cursor") ?? undefined,
        limit: url.searchParams.get("limit") ?? undefined,
      },
    });
    return Response.json({ ...result, meta: { requestId } });
  } catch (error) {
    return bankError(error, requestId);
  }
}
