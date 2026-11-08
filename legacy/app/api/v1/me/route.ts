import { chatGPTUserFromHeaders } from "../../../chatgpt-auth";
import {
  resolveIdentityAccess,
  summarizeIdentityAccess,
} from "../../../../server/auth-context.mjs";
import {
  DatabaseConfigurationError,
  getPrisma,
} from "../../../../server/prisma";

export const dynamic = "force-dynamic";

function problem(
  status: number,
  code: string,
  title: string,
  requestId: string,
) {
  return Response.json(
    {
      type: `https://clareza.example/problems/${code.toLowerCase()}`,
      title,
      status,
      code,
      requestId,
    },
    { status },
  );
}

export async function GET(request: Request) {
  const requestId = crypto.randomUUID();
  const identity = chatGPTUserFromHeaders(request.headers);
  if (!identity) {
    return problem(
      401,
      "AUTHENTICATION_REQUIRED",
      "Entre para acessar o Mourão Clareza.",
      requestId,
    );
  }

  try {
    const access = await resolveIdentityAccess(getPrisma(), identity.email);
    if (access.length === 0) {
      return problem(
        403,
        "MEMBERSHIP_REQUIRED",
        "Sua identidade ainda não possui acesso a uma empresa no Mourão Clareza.",
        requestId,
      );
    }

    return Response.json({
      data: summarizeIdentityAccess(access, identity),
      meta: { requestId },
    });
  } catch (error) {
    if (error instanceof DatabaseConfigurationError) {
      return problem(
        503,
        "DATABASE_UNAVAILABLE",
        "O banco do Mourão Clareza ainda não está configurado neste ambiente.",
        requestId,
      );
    }

    return problem(
      503,
      "SERVICE_UNAVAILABLE",
      "Não foi possível consultar seu acesso agora.",
      requestId,
    );
  }
}
