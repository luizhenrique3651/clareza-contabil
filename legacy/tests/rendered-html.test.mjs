import assert from "node:assert/strict";
import { access, readFile } from "node:fs/promises";
import test from "node:test";

const projectRoot = new URL("../", import.meta.url);

async function render() {
  const workerUrl = new URL("../dist/server/index.js", import.meta.url);
  workerUrl.searchParams.set("test", `${process.pid}-${Date.now()}`);
  const { default: worker } = await import(workerUrl.href);

  return worker.fetch(
    new Request("http://localhost/", {
      headers: { accept: "text/html" },
    }),
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

test("server-renders o loading seguro sem identidade fictícia", async () => {
  const response = await render();
  assert.equal(response.status, 200);
  assert.match(response.headers.get("content-type") ?? "", /^text\/html\b/i);

  const html = await response.text();
  assert.match(html, /<html[^>]*lang="pt-BR"/i);
  assert.match(html, /<title>Mourão Clareza Contábil e Financeira \| Central de comando<\/title>/i);
  assert.match(html, /Carregando seu acesso/i);
  assert.match(html, /consultando sua identidade, empresas, unidades e permissões/i);
  assert.doesNotMatch(html, /Marina Costa|Aurora Alimentos|Administradora/i);
  assert.doesNotMatch(html, /codex-preview|react-loading-skeleton|Your site is taking shape/i);
});

test("keeps the product surface accessible and the starter disposable", async () => {
  const [
    page,
    layout,
    application,
    applicationContext,
    identityClient,
    commandCenter,
    css,
    packageJson,
  ] = await Promise.all([
    readFile(new URL("../app/page.tsx", import.meta.url), "utf8"),
    readFile(new URL("../app/layout.tsx", import.meta.url), "utf8"),
    readFile(new URL("../app/application.tsx", import.meta.url), "utf8"),
    readFile(new URL("../app/application-context.tsx", import.meta.url), "utf8"),
    readFile(new URL("../app/identity-client.ts", import.meta.url), "utf8"),
    readFile(new URL("../app/command-center.tsx", import.meta.url), "utf8"),
    readFile(new URL("../app/globals.css", import.meta.url), "utf8"),
    readFile(new URL("../package.json", import.meta.url), "utf8"),
  ]);

  assert.match(page, /<ClarezaApplication \/>/);
  assert.match(layout, /<html lang="pt-BR">/);
  assert.match(layout, /Mourão Clareza Contábil e Financeira \| Central de comando/);
  assert.match(identityClient, /IDENTITY_ENDPOINT = "\/api\/v1\/me"/);
  assert.match(applicationContext, /safeNavigationStorage/);
  assert.match(applicationContext, /persistNavigationContextIfCurrent/);
  assert.match(applicationContext, /recoverAuthorizedNavigation/);
  assert.doesNotMatch(applicationContext, /sessionStorage|location\.search/);
  for (const state of [
    "loading",
    "unauthenticated",
    "forbidden",
    "unavailable",
    "error",
    "empty",
  ]) {
    assert.match(application, new RegExp(`${state}:`));
  }
  assert.match(commandCenter, /aria-label="Navegação principal"/);
  assert.match(commandCenter, /role="dialog"/);
  assert.match(commandCenter, /aria-modal="true"/);
  assert.match(commandCenter, /event\.key === "Escape"/);
  assert.match(commandCenter, /Nenhuma informação real é utilizada/);
  assert.match(commandCenter, /Dados demonstrativos/);
  assert.match(commandCenter, /badge: "Demo"/);
  assert.match(commandCenter, /\{item\.short\} • Demo/);
  assert.match(commandCenter, /Atalhos demonstrativos/);
  assert.doesNotMatch(
    commandCenter,
    /Marina Costa|Aurora Alimentos|Aurora Logística|Grupo Aurora/,
  );
  assert.doesNotMatch(commandCenter, /Dados atualizados|96% conciliados|Ambiente protegido/);
  assert.match(css, /:focus-visible/);
  assert.match(css, /prefers-reduced-motion:\s*reduce/);
  assert.doesNotMatch(packageJson, /react-loading-skeleton|site-creator-vinext-starter/);
  assert.doesNotMatch(page + layout, /codex-preview|_sites-preview/);

  await assert.rejects(access(new URL("../app/_sites-preview", projectRoot)));
});
