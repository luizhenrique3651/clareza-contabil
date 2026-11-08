import assert from "node:assert/strict";
import test from "node:test";

async function requestApi(headers = {}) {
  const workerUrl = new URL("../dist/server/index.js", import.meta.url);
  workerUrl.searchParams.set("api-test", `${process.pid}-${Date.now()}`);
  const { default: worker } = await import(workerUrl.href);

  return worker.fetch(
    new Request("http://localhost/api/v1/me", { headers }),
    {
      ASSETS: {
        fetch: async () => new Response("Not found", { status: 404 }),
      },
    },
    {
      waitUntil() {},
      passThroughOnException() {},
    },
  );
}

async function requestFinancial(path, headers = {}) {
  const workerUrl = new URL("../dist/server/index.js", import.meta.url);
  workerUrl.searchParams.set("financial-api-test", `${process.pid}-${Date.now()}`);
  const { default: worker } = await import(workerUrl.href);

  return worker.fetch(
    new Request(`http://localhost${path}`, { headers }),
    {
      ASSETS: {
        fetch: async () => new Response("Not found", { status: 404 }),
      },
    },
    {
      waitUntil() {},
      passThroughOnException() {},
    },
  );
}

test("GET /api/v1/me exige identidade autenticada", async () => {
  const response = await requestApi();
  assert.equal(response.status, 401);
  const body = await response.json();
  assert.equal(body.code, "AUTHENTICATION_REQUIRED");
  assert.ok(body.requestId);
});

test("GET /api/v1/me falha de forma explícita sem banco configurado", async () => {
  const response = await requestApi({
    "oai-authenticated-user-email": "ana@example.com",
  });
  assert.equal(response.status, 503);
  const body = await response.json();
  assert.equal(body.code, "DATABASE_UNAVAILABLE");
});

test("API financeira exige identidade antes de consultar dados", async () => {
  const response = await requestFinancial("/api/v1/payables");
  assert.equal(response.status, 401);
  assert.equal((await response.json()).code, "AUTHENTICATION_REQUIRED");
});

test("API financeira exige seleção explícita de empresa", async () => {
  const response = await requestFinancial("/api/v1/receivables", {
    "oai-authenticated-user-email": "ana@example.com",
  });
  assert.equal(response.status, 409);
  assert.equal((await response.json()).code, "COMPANY_CONTEXT_REQUIRED");
});

test("API bancária exige identidade e contexto de empresa", async () => {
  const anonymous = await requestFinancial("/api/v1/bank/imports");
  assert.equal(anonymous.status, 401);
  assert.equal((await anonymous.json()).code, "AUTHENTICATION_REQUIRED");

  const withoutCompany = await requestFinancial("/api/v1/bank/transactions", {
    "oai-authenticated-user-email": "ana@example.com",
  });
  assert.equal(withoutCompany.status, 409);
  assert.equal(
    (await withoutCompany.json()).code,
    "COMPANY_CONTEXT_REQUIRED",
  );
});
