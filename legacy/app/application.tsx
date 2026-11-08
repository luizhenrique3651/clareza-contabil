"use client";

import {
  AlertTriangle,
  Building2,
  LoaderCircle,
  LockKeyhole,
  RefreshCw,
  ServerCrash,
  UserX,
} from "lucide-react";

import {
  ApplicationContextProvider,
  useApplicationContext,
} from "./application-context";
import { CommandCenter } from "./command-center";
import type { SessionStatus } from "./identity-client";

const stateContent: Record<
  Exclude<SessionStatus, "ready">,
  Readonly<{
    eyebrow: string;
    title: string;
    description: string;
    icon: typeof LoaderCircle;
  }>
> = {
  loading: {
    eyebrow: "Identidade",
    title: "Carregando seu acesso",
    description:
      "Estamos consultando sua identidade, empresas, unidades e permissões.",
    icon: LoaderCircle,
  },
  unauthenticated: {
    eyebrow: "Não autenticado",
    title: "Entre para acessar o Mourão Clareza",
    description:
      "Este ambiente não recebeu uma identidade autenticada. Nenhum usuário fictício será utilizado como fallback.",
    icon: UserX,
  },
  forbidden: {
    eyebrow: "Sem acesso",
    title: "Sua identidade não possui acesso ao Mourão Clareza",
    description:
      "Solicite a um administrador uma membership ativa em uma empresa ou unidade.",
    icon: LockKeyhole,
  },
  unavailable: {
    eyebrow: "Serviço indisponível",
    title: "Não foi possível consultar seu acesso",
    description:
      "O serviço ou o banco do Mourão Clareza está temporariamente indisponível.",
    icon: ServerCrash,
  },
  error: {
    eyebrow: "Erro inesperado",
    title: "O contexto não pôde ser preparado",
    description:
      "A resposta de identidade foi inesperada. O acesso permanece bloqueado.",
    icon: AlertTriangle,
  },
  empty: {
    eyebrow: "Sem empresas",
    title: "Nenhuma empresa está disponível",
    description:
      "Sua identidade é válida, mas não possui um contexto operacional disponível.",
    icon: Building2,
  },
};

function SessionStateScreen({
  status,
  switching = false,
}: {
  status: Exclude<SessionStatus, "ready">;
  switching?: boolean;
}) {
  const { retry, message, requestId } = useApplicationContext();
  const content = switching
    ? {
        eyebrow: "Contexto",
        title: "Trocando empresa ou unidade",
        description:
          "Os dados anteriores foram ocultados enquanto o servidor valida o novo contexto.",
        icon: LoaderCircle,
      }
    : stateContent[status];
  const Icon = content.icon;
  const canRetry = status === "unavailable" || status === "error";

  return (
    <main
      className="session-state-page"
      data-session-state={switching ? "switching" : status}
    >
      <section className="session-state-card" aria-live="polite">
        <span
          className="session-brand-lockup"
          role="img"
          aria-label="Mourão Inteligência Financeira"
        />
        <p className="session-product-name">
          Mourão Clareza <span>Contábil e Financeira</span>
        </p>
        <div
          className={`session-state-icon ${
            status === "loading" || switching ? "is-loading" : ""
          }`}
        >
          <Icon size={26} aria-hidden="true" />
        </div>
        <p className="eyebrow">{content.eyebrow}</p>
        <h1>{content.title}</h1>
        <p>{message ?? content.description}</p>
        {requestId ? <small>Referência: {requestId}</small> : null}
        {canRetry ? (
          <button type="button" onClick={() => void retry()}>
            <RefreshCw size={16} aria-hidden="true" />
            Tentar novamente
          </button>
        ) : null}
      </section>
    </main>
  );
}

function ApplicationBoundary() {
  const context = useApplicationContext();

  if (context.switching) {
    return <SessionStateScreen status="loading" switching />;
  }

  if (
    context.status !== "ready" ||
    !context.identity ||
    !context.navigation
  ) {
    return (
      <SessionStateScreen
        status={context.status === "ready" ? "error" : context.status}
      />
    );
  }

  return (
    <CommandCenter
      key={`${context.navigation.fingerprint}:${context.revision}`}
    />
  );
}

export function ClarezaApplication() {
  return (
    <ApplicationContextProvider>
      <ApplicationBoundary />
    </ApplicationContextProvider>
  );
}
