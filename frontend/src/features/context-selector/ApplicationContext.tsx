import React, { createContext, useCallback, useContext, useEffect, useRef, useState } from "react";
import {
  fetchIdentitySummary,
  IdentitySummary,
  safeNavigationStorage,
} from "@/features/auth/identity-client";
import { ClarezaApiError, NavigationContextSelection } from "@/shared/api/http-client";

export type SessionViewState =
  | "loading"
  | "authenticated"
  | "unauthenticated"
  | "forbidden"
  | "unavailable"
  | "error";

interface ApplicationContextValue {
  viewState: SessionViewState;
  identity: IdentitySummary | null;
  errorMessage: string | null;
  selectContext: (next: NavigationContextSelection) => Promise<void>;
  refreshIdentity: () => Promise<void>;
}

const ApplicationContext = createContext<ApplicationContextValue | undefined>(undefined);

export function ApplicationProvider({ children }: { children: React.ReactNode }) {
  const [viewState, setViewState] = useState<SessionViewState>("loading");
  const [identity, setIdentity] = useState<IdentitySummary | null>(null);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  const requestSeqRef = useRef(0);
  const abortControllerRef = useRef<AbortController | null>(null);

  const loadIdentity = useCallback(async (requestedSelection?: NavigationContextSelection) => {
    abortControllerRef.current?.abort();
    const controller = new AbortController();
    abortControllerRef.current = controller;
    const currentSeq = ++requestSeqRef.current;

    setViewState("loading");
    setErrorMessage(null);

    try {
      const summary = await fetchIdentitySummary(requestedSelection, controller.signal);
      if (currentSeq !== requestSeqRef.current) return;

      safeNavigationStorage.write({
        tenantId: summary.activeContext.tenantId,
        companyId: summary.activeContext.companyId,
        branchId: summary.activeContext.branchId,
      });

      setIdentity(summary);
      setViewState("authenticated");
    } catch (err) {
      if (controller.signal.aborted || currentSeq !== requestSeqRef.current) return;

      if (err instanceof ClarezaApiError) {
        const status = err.problem.status;
        if (status === 400 && err.problem.code === "INVALID_CONTEXT_SELECTION") {
          // Auto-recuperação caso a preferência salva não seja mais válida
          safeNavigationStorage.clear();
          await loadIdentity(undefined);
          return;
        }
        if (status === 401) {
          setViewState("unauthenticated");
          setErrorMessage(err.problem.detail);
          return;
        }
        if (status === 403) {
          setViewState("forbidden");
          setErrorMessage(err.problem.detail);
          return;
        }
        if (status === 503) {
          setViewState("unavailable");
          setErrorMessage(err.problem.detail);
          return;
        }
      }
      setViewState("error");
      setErrorMessage("Não foi possível carregar o contexto operacional.");
    }
  }, []);

  useEffect(() => {
    const saved = safeNavigationStorage.read();
    void loadIdentity(saved ?? undefined);
    return () => {
      abortControllerRef.current?.abort();
    };
  }, [loadIdentity]);

  const selectContext = useCallback(
    async (next: NavigationContextSelection) => {
      await loadIdentity(next);
    },
    [loadIdentity],
  );

  const refreshIdentity = useCallback(async () => {
    const current = identity?.activeContext
      ? {
          tenantId: identity.activeContext.tenantId,
          companyId: identity.activeContext.companyId,
          branchId: identity.activeContext.branchId,
        }
      : safeNavigationStorage.read() ?? undefined;
    await loadIdentity(current);
  }, [identity, loadIdentity]);

  return (
    <ApplicationContext.Provider
      value={{
        viewState,
        identity,
        errorMessage,
        selectContext,
        refreshIdentity,
      }}
    >
      {children}
    </ApplicationContext.Provider>
  );
}

export function useApplicationContext(): ApplicationContextValue {
  const context = useContext(ApplicationContext);
  if (!context) {
    throw new Error("useApplicationContext deve ser utilizado dentro de ApplicationProvider.");
  }
  return context;
}
