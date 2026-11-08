import assert from "node:assert/strict";
import test from "node:test";

import {
  contextRequestHeaders,
  createLatestRequestGate,
  defaultNavigationForCompany,
  deriveInitialNavigation,
  discardInvalidPreferences,
  fetchIdentity,
  IDENTITY_ENDPOINT,
  IdentityClientError,
  INITIAL_SESSION_STATUS,
  NAVIGATION_STORAGE_KEYS,
  NavigationContextError,
  permissionPresentation,
  persistNavigationContext,
  persistNavigationContextIfCurrent,
  readNavigationPreferences,
  recoverAuthorizedNavigation,
  resolveAuthorizedNavigation,
  safeNavigationStorage,
} from "../app/identity-client.ts";

const TENANT = "10000000-0000-4000-8000-000000000001";
const COMPANY_A = "20000000-0000-4000-8000-000000000001";
const COMPANY_B = "20000000-0000-4000-8000-000000000002";
const BRANCH_A1 = "30000000-0000-4000-8000-000000000001";
const BRANCH_A2 = "30000000-0000-4000-8000-000000000002";
const BRANCH_B1 = "30000000-0000-4000-8000-000000000003";
const BRANCH_B2 = "30000000-0000-4000-8000-000000000004";

function identityEnvelope() {
  return {
    data: {
      email: "ana@example.invalid",
      displayName: "Ana Souza",
      tenants: [
        {
          id: TENANT,
          name: "Grupo Horizonte",
          companies: [
            {
              id: COMPANY_A,
              name: "Horizonte Matriz",
              scopes: ["COMPANY"],
              permissions: ["payables.manage", "payables.view"],
              branches: [
                {
                  id: BRANCH_A1,
                  name: "Unidade Norte",
                  permissions: ["payables.manage", "payables.view"],
                },
                {
                  id: BRANCH_A2,
                  name: "Unidade Sul",
                  permissions: ["payables.view"],
                },
              ],
            },
            {
              id: COMPANY_B,
              name: "Horizonte Serviços",
              scopes: ["BRANCH"],
              permissions: [],
              branches: [
                {
                  id: BRANCH_B1,
                  name: "Serviços Centro",
                  permissions: ["receivables.view"],
                },
                {
                  id: BRANCH_B2,
                  name: "Serviços Leste",
                  permissions: [],
                },
              ],
            },
          ],
        },
      ],
    },
    meta: { requestId: "request-valid" },
  };
}

function jsonResponse(payload, status = 200) {
  return new Response(JSON.stringify(payload), {
    status,
    headers: { "content-type": "application/json" },
  });
}

function storage(initial = {}) {
  const values = new Map(Object.entries(initial));
  const reads = [];
  return {
    reads,
    values,
    getItem(key) {
      reads.push(key);
      return values.get(key) ?? null;
    },
    setItem(key, value) {
      values.set(key, String(value));
    },
    removeItem(key) {
      values.delete(key);
    },
  };
}

test("cliente central inicia em loading e consome somente GET /api/v1/me", async () => {
  assert.equal(INITIAL_SESSION_STATUS, "loading");
  const calls = [];
  const identity = await fetchIdentity({
    fetcher: async (input, init) => {
      calls.push({ input, init });
      return jsonResponse(identityEnvelope());
    },
  });

  assert.equal(calls.length, 1);
  assert.equal(calls[0].input, IDENTITY_ENDPOINT);
  assert.equal(calls[0].init.method, "GET");
  assert.equal(calls[0].init.credentials, "same-origin");
  assert.equal(calls[0].init.cache, "no-store");
  assert.equal(identity.displayName, "Ana Souza");
  assert.equal(identity.tenants[0].companies.length, 2);
});

for (const scenario of [
  {
    status: 401,
    sessionStatus: "unauthenticated",
    code: "AUTHENTICATION_REQUIRED",
  },
  { status: 403, sessionStatus: "forbidden", code: "MEMBERSHIP_REQUIRED" },
  { status: 503, sessionStatus: "unavailable", code: "DATABASE_UNAVAILABLE" },
]) {
  test(`identidade trata ${scenario.status} com estado próprio`, async () => {
    await assert.rejects(
      fetchIdentity({
        fetcher: async () =>
          jsonResponse(
            {
              title: `Erro ${scenario.status}`,
              code: scenario.code,
              requestId: `request-${scenario.status}`,
            },
            scenario.status,
          ),
      }),
      (error) => {
        assert.ok(error instanceof IdentityClientError);
        assert.equal(error.sessionStatus, scenario.sessionStatus);
        assert.equal(error.code, scenario.code);
        assert.equal(error.requestId, `request-${scenario.status}`);
        return true;
      },
    );
  });
}

test("503 sem JSON continua classificado como indisponibilidade", async () => {
  await assert.rejects(
    fetchIdentity({
      fetcher: async () =>
        new Response("<html>temporariamente indisponível</html>", {
          status: 503,
          headers: { "content-type": "text/html" },
        }),
    }),
    (error) =>
      error instanceof IdentityClientError &&
      error.status === 503 &&
      error.sessionStatus === "unavailable",
  );
});

test("falha de rede permanece em estado indisponível e sem fallback", async () => {
  await assert.rejects(
    fetchIdentity({
      fetcher: async () => {
        throw new TypeError("connection refused");
      },
    }),
    (error) =>
      error instanceof IdentityClientError &&
      error.sessionStatus === "unavailable" &&
      error.code === "IDENTITY_NETWORK_ERROR",
  );
});

test("erro inesperado e contrato inválido não criam identidade fictícia", async () => {
  await assert.rejects(
    fetchIdentity({
      fetcher: async () =>
        jsonResponse({ title: "Falha inesperada", code: "INTERNAL" }, 500),
    }),
    (error) =>
      error instanceof IdentityClientError &&
      error.sessionStatus === "error",
  );

  await assert.rejects(
    fetchIdentity({
      fetcher: async () => jsonResponse({ data: { displayName: "Fallback" } }),
    }),
    (error) =>
      error instanceof IdentityClientError &&
      error.code === "IDENTITY_INVALID_CONTRACT",
  );
});

test("uma única empresa é selecionada automaticamente", async () => {
  const payload = identityEnvelope();
  payload.data.tenants[0].companies = [payload.data.tenants[0].companies[0]];
  const identity = await fetchIdentity({
    fetcher: async () => jsonResponse(payload),
  });
  const result = deriveInitialNavigation(identity, {
    companyId: null,
    branchId: null,
  });

  assert.equal(result.context.company.id, COMPANY_A);
  assert.equal(result.context.branch, null);
  assert.equal(result.context.accessMode, "company");
});

test("várias empresas respeitam somente preferência ainda autorizada", async () => {
  const identity = await fetchIdentity({
    fetcher: async () => jsonResponse(identityEnvelope()),
  });
  const result = deriveInitialNavigation(identity, {
    companyId: COMPANY_B,
    branchId: BRANCH_B2,
  });

  assert.equal(result.context.company.id, COMPANY_B);
  assert.equal(result.context.branch.id, BRANCH_B2);
  assert.equal(result.discardedCompanyPreference, false);
  assert.equal(result.discardedBranchPreference, false);
});

test("empresa antiga ou storage adulterado é descartado sem ampliar acesso", async () => {
  const identity = await fetchIdentity({
    fetcher: async () => jsonResponse(identityEnvelope()),
  });
  const tamperedStorage = storage({
    [NAVIGATION_STORAGE_KEYS.companyId]:
      "90000000-0000-4000-8000-000000000099",
    [NAVIGATION_STORAGE_KEYS.branchId]:
      "90000000-0000-4000-8000-000000000098",
    "clareza.tenant.admin": "true",
  });
  const result = deriveInitialNavigation(
    identity,
    readNavigationPreferences(tamperedStorage),
  );
  discardInvalidPreferences(tamperedStorage, result);

  assert.equal(result.context.company.id, COMPANY_A);
  assert.equal(result.discardedCompanyPreference, true);
  assert.equal(tamperedStorage.values.has(NAVIGATION_STORAGE_KEYS.companyId), false);
  assert.equal(tamperedStorage.values.has(NAVIGATION_STORAGE_KEYS.branchId), false);
  assert.equal(tamperedStorage.values.get("clareza.tenant.admin"), "true");
  assert.deepEqual(tamperedStorage.reads, [
    NAVIGATION_STORAGE_KEYS.companyId,
    NAVIGATION_STORAGE_KEYS.branchId,
  ]);
});

test("seleção manual de empresa não autorizada falha fechada", async () => {
  const identity = await fetchIdentity({
    fetcher: async () => jsonResponse(identityEnvelope()),
  });
  assert.throws(
    () =>
      defaultNavigationForCompany(
        identity,
        "90000000-0000-4000-8000-000000000099",
      ),
    (error) =>
      error instanceof NavigationContextError &&
      error.code === "COMPANY_NOT_AUTHORIZED",
  );
});

test("acesso à empresa inteira funciona sem unidade e aceita unidade autorizada", async () => {
  const identity = await fetchIdentity({
    fetcher: async () => jsonResponse(identityEnvelope()),
  });
  const wholeCompany = resolveAuthorizedNavigation(identity, COMPANY_A);
  const branch = resolveAuthorizedNavigation(identity, COMPANY_A, BRANCH_A2);

  assert.equal(wholeCompany.accessMode, "company");
  assert.equal(wholeCompany.branch, null);
  assert.deepEqual(wholeCompany.permissions, [
    "payables.manage",
    "payables.view",
  ]);
  assert.equal(branch.branch.id, BRANCH_A2);
  assert.deepEqual(branch.permissions, ["payables.view"]);
});

test("acesso limitado exige unidade e suporta múltiplas unidades autorizadas", async () => {
  const identity = await fetchIdentity({
    fetcher: async () => jsonResponse(identityEnvelope()),
  });
  const initial = defaultNavigationForCompany(identity, COMPANY_B);
  const second = resolveAuthorizedNavigation(identity, COMPANY_B, BRANCH_B2);

  assert.equal(initial.accessMode, "branch");
  assert.equal(initial.branch.id, BRANCH_B1);
  assert.equal(second.branch.id, BRANCH_B2);
  assert.deepEqual(second.permissions, []);
  assert.throws(
    () => resolveAuthorizedNavigation(identity, COMPANY_B),
    (error) =>
      error instanceof NavigationContextError &&
      error.code === "BRANCH_NOT_AUTHORIZED",
  );
});

test("unidade de outra empresa ou unidade adulterada é rejeitada", async () => {
  const identity = await fetchIdentity({
    fetcher: async () => jsonResponse(identityEnvelope()),
  });
  for (const branchId of [
    BRANCH_A1,
    "90000000-0000-4000-8000-000000000099",
  ]) {
    assert.throws(
      () => resolveAuthorizedNavigation(identity, COMPANY_B, branchId),
      (error) =>
        error instanceof NavigationContextError &&
        error.code === "BRANCH_NOT_AUTHORIZED",
    );
  }
});

test("permissões distinguem escritor, leitor e usuário sem permissão", () => {
  assert.equal(
    permissionPresentation(["payables.view", "payables.manage"]),
    "manage",
  );
  assert.equal(permissionPresentation(["payables.view"]), "read");
  assert.equal(permissionPresentation([]), "none");
});

test("troca A → B → A produz contextos distintos sem manter estado antigo", async () => {
  const identity = await fetchIdentity({
    fetcher: async () => jsonResponse(identityEnvelope()),
  });
  const contextA1 = defaultNavigationForCompany(identity, COMPANY_A);
  const contextB = defaultNavigationForCompany(identity, COMPANY_B);
  const contextA2 = defaultNavigationForCompany(identity, COMPANY_A);

  assert.notEqual(contextA1.fingerprint, contextB.fingerprint);
  assert.equal(contextA1.fingerprint, contextA2.fingerprint);
  assert.equal(contextB.branch.id, BRANCH_B1);

  const headersA = contextRequestHeaders(contextA1);
  const headersB = contextRequestHeaders(contextB);
  assert.equal(headersA.get("x-clareza-company-id"), COMPANY_A);
  assert.equal(headersA.has("x-clareza-branch-id"), false);
  assert.equal(headersB.get("x-clareza-company-id"), COMPANY_B);
  assert.equal(headersB.get("x-clareza-branch-id"), BRANCH_B1);
});

test("resposta atrasada não persiste contexto depois de uma troca mais nova", async () => {
  const identity = await fetchIdentity({
    fetcher: async () => jsonResponse(identityEnvelope()),
  });
  const contextA = defaultNavigationForCompany(identity, COMPANY_A);
  const contextB = defaultNavigationForCompany(identity, COMPANY_B);
  const ids = ["request-a", "request-b"];
  const gate = createLatestRequestGate(() => ids.shift());
  const requestA = gate.begin();
  const requestB = gate.begin();
  const safeStorage = storage();

  assert.equal(
    persistNavigationContextIfCurrent(
      gate,
      requestA,
      safeStorage,
      contextA,
    ),
    false,
  );
  assert.equal(safeStorage.values.size, 0);
  assert.equal(
    persistNavigationContextIfCurrent(
      gate,
      requestB,
      safeStorage,
      contextB,
    ),
    true,
  );
  assert.equal(
    safeStorage.values.get(NAVIGATION_STORAGE_KEYS.companyId),
    COMPANY_B,
  );
  assert.equal(
    safeStorage.values.get(NAVIGATION_STORAGE_KEYS.branchId),
    BRANCH_B1,
  );
});

test("contexto anterior é revalidado ou substituído após revogação", async () => {
  const original = await fetchIdentity({
    fetcher: async () => jsonResponse(identityEnvelope()),
  });
  const previous = defaultNavigationForCompany(original, COMPANY_A);

  const reducedPayload = identityEnvelope();
  reducedPayload.data.tenants[0].companies[0].permissions = ["payables.view"];
  const reduced = await fetchIdentity({
    fetcher: async () => jsonResponse(reducedPayload),
  });
  const revalidated = recoverAuthorizedNavigation(reduced, previous);
  assert.equal(revalidated.company.id, COMPANY_A);
  assert.deepEqual(revalidated.permissions, ["payables.view"]);

  const revokedPayload = identityEnvelope();
  revokedPayload.data.tenants[0].companies =
    revokedPayload.data.tenants[0].companies.filter(
      (company) => company.id !== COMPANY_A,
    );
  const revoked = await fetchIdentity({
    fetcher: async () => jsonResponse(revokedPayload),
  });
  const replacement = recoverAuthorizedNavigation(revoked, previous);
  assert.equal(replacement.company.id, COMPANY_B);
  assert.equal(replacement.branch.id, BRANCH_B1);

  revokedPayload.data.tenants[0].companies = [];
  const empty = await fetchIdentity({
    fetcher: async () => jsonResponse(revokedPayload),
  });
  assert.equal(recoverAuthorizedNavigation(empty, previous), null);
});

test("headers e preferências controláveis são sobrescritos pelo contexto autorizado", async () => {
  const identity = await fetchIdentity({
    fetcher: async () => jsonResponse(identityEnvelope()),
  });
  const context = defaultNavigationForCompany(identity, COMPANY_A);
  const headers = contextRequestHeaders(context, {
    "x-clareza-company-id": COMPANY_B,
    "x-clareza-branch-id": BRANCH_B1,
    "x-clareza-tenant-id":
      "90000000-0000-4000-8000-000000000099",
    "x-clareza-user-id":
      "90000000-0000-4000-8000-000000000098",
    "x-clareza-actor-id":
      "90000000-0000-4000-8000-000000000097",
  });
  const safeStorage = storage();
  persistNavigationContext(safeStorage, context);

  assert.equal(headers.get("x-clareza-company-id"), COMPANY_A);
  assert.equal(headers.has("x-clareza-branch-id"), false);
  assert.equal(
    safeStorage.values.get(NAVIGATION_STORAGE_KEYS.companyId),
    COMPANY_A,
  );
  assert.equal(safeStorage.values.has(NAVIGATION_STORAGE_KEYS.branchId), false);
  assert.equal(headers.has("x-clareza-tenant-id"), false);
  assert.equal(headers.has("x-clareza-user-id"), false);
  assert.equal(headers.has("x-clareza-actor-id"), false);
});

test("storage bloqueado é tratado como preferência opcional", () => {
  const blockedOwner = {};
  Object.defineProperty(blockedOwner, "localStorage", {
    get() {
      throw new DOMException("blocked", "SecurityError");
    },
  });

  assert.equal(safeNavigationStorage(blockedOwner), null);
  assert.equal(safeNavigationStorage(null), null);
  const available = storage();
  assert.equal(safeNavigationStorage({ localStorage: available }), available);
});

test("identidade válida sem empresas resulta em estado vazio", async () => {
  const payload = identityEnvelope();
  payload.data.tenants[0].companies = [];
  const identity = await fetchIdentity({
    fetcher: async () => jsonResponse(payload),
  });
  const result = deriveInitialNavigation(identity, {
    companyId: null,
    branchId: null,
  });
  assert.equal(result.context, null);
});
