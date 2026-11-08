"use client";

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useReducer,
  useState,
} from "react";

import {
  contextRequestHeaders,
  createLatestRequestGate,
  defaultNavigationForCompany,
  deriveInitialNavigation,
  discardInvalidPreferences,
  fetchIdentity,
  INITIAL_SESSION_STATUS,
  IdentityClientError,
  type IdentitySummary,
  type NavigationContext,
  NavigationContextError,
  persistNavigationContextIfCurrent,
  readNavigationPreferences,
  recoverAuthorizedNavigation,
  resolveAuthorizedNavigation,
  safeNavigationStorage,
  type SessionStatus,
} from "./identity-client";

type ApplicationState = Readonly<{
  status: SessionStatus;
  identity: IdentitySummary | null;
  navigation: NavigationContext | null;
  switching: boolean;
  revision: number;
  message: string | null;
  code: string | null;
  requestId: string | null;
}>;

type InternalState = ApplicationState &
  Readonly<{ pendingRequestId: string | null }>;

type ApplicationContextValue = ApplicationState &
  Readonly<{
    retry: () => Promise<void>;
    selectCompany: (companyId: string) => Promise<void>;
    selectBranch: (branchId: string | null) => Promise<void>;
    fetchWithContext: (
      input: RequestInfo | URL,
      init?: RequestInit,
    ) => Promise<Response>;
  }>;

type Action =
  | Readonly<{ type: "load-start"; requestId: string }>
  | Readonly<{
      type: "ready";
      requestId: string;
      identity: IdentitySummary;
      navigation: NavigationContext;
      message?: string | null;
      code?: string | null;
    }>
  | Readonly<{
      type: "empty";
      requestId: string;
      identity: IdentitySummary | null;
      message: string;
      code: string;
    }>
  | Readonly<{
      type: "failure";
      requestId: string;
      state: ApplicationState;
    }>
  | Readonly<{ type: "switch-start"; requestId: string }>;

const INITIAL_STATE: InternalState = Object.freeze({
  status: INITIAL_SESSION_STATUS,
  identity: null,
  navigation: null,
  switching: false,
  revision: 0,
  message: null,
  code: null,
  requestId: null,
  pendingRequestId: null,
});

const ApplicationContext = createContext<ApplicationContextValue | null>(null);

function reducer(state: InternalState, action: Action): InternalState {
  if (action.type === "load-start") {
    return Object.freeze({
      ...INITIAL_STATE,
      revision: state.revision,
      pendingRequestId: action.requestId,
    });
  }

  if (action.type === "switch-start") {
    return Object.freeze({
      ...state,
      switching: true,
      message: null,
      code: null,
      requestId: null,
      pendingRequestId: action.requestId,
    });
  }

  if (state.pendingRequestId !== action.requestId) {
    return state;
  }

  if (action.type === "ready") {
    return Object.freeze({
      status: "ready",
      identity: action.identity,
      navigation: action.navigation,
      switching: false,
      revision: state.revision + 1,
      message: action.message ?? null,
      code: action.code ?? null,
      requestId: null,
      pendingRequestId: null,
    });
  }

  if (action.type === "empty") {
    return Object.freeze({
      status: "empty",
      identity: action.identity,
      navigation: null,
      switching: false,
      revision: state.revision,
      message: action.message,
      code: action.code,
      requestId: null,
      pendingRequestId: null,
    });
  }

  return Object.freeze({
    ...action.state,
    pendingRequestId: null,
  });
}

function stateForError(error: unknown, revision: number): ApplicationState {
  if (error instanceof IdentityClientError) {
    return Object.freeze({
      status: error.sessionStatus,
      identity: null,
      navigation: null,
      switching: false,
      revision,
      message: error.message,
      code: error.code,
      requestId: error.requestId,
    });
  }

  return Object.freeze({
    status: "error",
    identity: null,
    navigation: null,
    switching: false,
    revision,
    message:
      error instanceof Error
        ? error.message
        : "Não foi possível preparar o contexto do Mourão Clareza.",
    code: "APPLICATION_CONTEXT_ERROR",
    requestId: null,
  });
}

export function ApplicationContextProvider({
  children,
}: Readonly<{ children: React.ReactNode }>) {
  const [state, dispatch] = useReducer(reducer, INITIAL_STATE);
  const [requestGate] = useState(() => createLatestRequestGate());

  const load = useCallback(async () => {
    const requestId = requestGate.begin();
    dispatch({ type: "load-start", requestId });

    try {
      const identity = await fetchIdentity();
      const storage = safeNavigationStorage(
        typeof window === "undefined" ? null : window,
      );
      const result = deriveInitialNavigation(
        identity,
        readNavigationPreferences(storage),
      );

      if (!result.context) {
        if (!requestGate.isCurrent(requestId)) return;
        discardInvalidPreferences(storage, result);
        dispatch({
          type: "empty",
          requestId,
          identity,
          message:
            "Sua identidade é válida, mas não possui empresas disponíveis.",
          code: "NO_COMPANIES_AVAILABLE",
        });
        return;
      }

      if (!requestGate.isCurrent(requestId)) return;
      discardInvalidPreferences(storage, result);
      if (
        !persistNavigationContextIfCurrent(
          requestGate,
          requestId,
          storage,
          result.context,
        )
      ) {
        return;
      }
      dispatch({
        type: "ready",
        requestId,
        identity,
        navigation: result.context,
      });
    } catch (error) {
      if (!requestGate.isCurrent(requestId)) return;
      if (error instanceof NavigationContextError) {
        dispatch({
          type: "empty",
          requestId,
          identity: null,
          message: error.message,
          code: error.code,
        });
        return;
      }
      dispatch({
        type: "failure",
        requestId,
        state: stateForError(error, 0),
      });
    }
  }, [requestGate]);

  useEffect(() => {
    void Promise.resolve().then(load);
  }, [load]);

  const applySelection = useCallback(
    async (resolve: (identity: IdentitySummary) => NavigationContext) => {
      const previousNavigation = state.navigation;
      const requestId = requestGate.begin();
      dispatch({ type: "switch-start", requestId });
      let refreshedIdentity: IdentitySummary | null = null;

      try {
        refreshedIdentity = await fetchIdentity();
        let navigation: NavigationContext;
        let selectionMessage: string | null = null;
        let selectionCode: string | null = null;

        try {
          navigation = resolve(refreshedIdentity);
        } catch (error) {
          if (!(error instanceof NavigationContextError)) throw error;
          navigation =
            recoverAuthorizedNavigation(
              refreshedIdentity,
              previousNavigation,
            ) ??
            (() => {
              throw new NavigationContextError(
                "COMPANY_NOT_AUTHORIZED",
                "Sua identidade não possui mais um contexto operacional disponível.",
              );
            })();
          selectionMessage =
            navigation.fingerprint === previousNavigation?.fingerprint
              ? error.message
              : "Seu acesso mudou. O Mourão Clareza selecionou novamente um contexto autorizado.";
          selectionCode =
            navigation.fingerprint === previousNavigation?.fingerprint
              ? error.code
              : "CONTEXT_REAUTHORIZED";
        }

        const storage = safeNavigationStorage(
          typeof window === "undefined" ? null : window,
        );
        if (
          !persistNavigationContextIfCurrent(
            requestGate,
            requestId,
            storage,
            navigation,
          )
        ) {
          return;
        }
        dispatch({
          type: "ready",
          requestId,
          identity: refreshedIdentity,
          navigation,
          message: selectionMessage,
          code: selectionCode,
        });
      } catch (error) {
        if (!requestGate.isCurrent(requestId)) return;
        if (error instanceof NavigationContextError) {
          dispatch({
            type: "empty",
            requestId,
            identity: refreshedIdentity,
            message: error.message,
            code: error.code,
          });
          return;
        }
        dispatch({
          type: "failure",
          requestId,
          state: stateForError(error, state.revision),
        });
      }
    },
    [requestGate, state.navigation, state.revision],
  );

  const selectCompany = useCallback(
    async (companyId: string) => {
      await applySelection((identity) =>
        defaultNavigationForCompany(identity, companyId),
      );
    },
    [applySelection],
  );

  const selectBranch = useCallback(
    async (branchId: string | null) => {
      const companyId = state.navigation?.company.id;
      if (!companyId) return;
      await applySelection((identity) =>
        resolveAuthorizedNavigation(identity, companyId, branchId),
      );
    },
    [applySelection, state.navigation?.company.id],
  );

  const fetchWithContext = useCallback(
    (input: RequestInfo | URL, init: RequestInit = {}) => {
      if (state.status !== "ready" || !state.navigation || state.switching) {
        throw new NavigationContextError(
          "COMPANY_NOT_AUTHORIZED",
          "O contexto ainda não está pronto para consultar dados.",
        );
      }
      return fetch(input, {
        ...init,
        headers: contextRequestHeaders(state.navigation, init.headers),
      });
    },
    [state.navigation, state.status, state.switching],
  );

  const value: ApplicationContextValue = Object.freeze({
    status: state.status,
    identity: state.identity,
    navigation: state.navigation,
    switching: state.switching,
    revision: state.revision,
    message: state.message,
    code: state.code,
    requestId: state.requestId,
    retry: load,
    selectCompany,
    selectBranch,
    fetchWithContext,
  });

  return (
    <ApplicationContext.Provider value={value}>
      {children}
    </ApplicationContext.Provider>
  );
}

export function useApplicationContext() {
  const context = useContext(ApplicationContext);
  if (!context) {
    throw new Error(
      "useApplicationContext exige ApplicationContextProvider.",
    );
  }
  return context;
}
