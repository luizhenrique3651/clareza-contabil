import React from "react";
import { ApplicationProvider, useApplicationContext } from "@/features/context-selector/ApplicationContext";

function CommandCenterShell() {
  const { viewState, identity, errorMessage, selectContext } = useApplicationContext();

  if (viewState === "loading") {
    return (
      <main style={{ padding: "3rem", maxWidth: "960px", margin: "0 auto" }}>
        <h1 className="serif-heading">Mourão Clareza Contábil e Financeira</h1>
        <p>Carregando seu acesso — consultando sua identidade, empresas, unidades e permissões...</p>
      </main>
    );
  }

  if (viewState !== "authenticated" || !identity) {
    return (
      <main style={{ padding: "3rem", maxWidth: "960px", margin: "0 auto" }}>
        <h1 className="serif-heading">Mourão Clareza Contábil e Financeira</h1>
        <section
          role="alert"
          style={{
            background: "#fff",
            border: "1px solid var(--color-ivory-200)",
            borderRadius: "12px",
            padding: "1.5rem",
          }}
        >
          <h2>Acesso restrito ({viewState})</h2>
          <p>{errorMessage ?? "Autentique-se para acessar o ambiente operacional multiempresa."}</p>
        </section>
      </main>
    );
  }

  const { activeContext, companies, branches, user } = identity;
  const companyBranches = branches.filter((b) => b.companyId === activeContext.companyId);

  return (
    <div style={{ minHeight: "100vh", display: "flex", flexDirection: "column" }}>
      <header
        style={{
          background: "var(--color-navy-900)",
          color: "var(--color-ivory-50)",
          padding: "1rem 2rem",
          display: "flex",
          justifyContent: "space-between",
          alignItems: "center",
          borderBottom: "2px solid var(--color-gold-500)",
        }}
      >
        <div>
          <strong style={{ fontSize: "1.125rem", fontFamily: "var(--font-serif)" }}>
            Mourão Clareza Contábil e Financeira
          </strong>
          <span style={{ marginLeft: "1rem", fontSize: "0.85rem", opacity: 0.85 }}>
            {activeContext.tenantName} • Papel: {activeContext.roleCode}
          </span>
        </div>

        <nav aria-label="Seletor de contexto operacional" style={{ display: "flex", gap: "0.75rem" }}>
          <label>
            <span style={{ fontSize: "0.75rem", display: "block" }}>Empresa ativa</span>
            <select
              value={activeContext.companyId}
              onChange={(e) =>
                void selectContext({
                  tenantId: activeContext.tenantId,
                  companyId: e.target.value,
                  branchId: null,
                })
              }
            >
              {companies.map((company) => (
                <option key={company.id} value={company.id}>
                  {company.tradeName} ({company.taxId})
                </option>
              ))}
            </select>
          </label>

          <label>
            <span style={{ fontSize: "0.75rem", display: "block" }}>Unidade</span>
            <select
              value={activeContext.branchId ?? ""}
              onChange={(e) =>
                void selectContext({
                  tenantId: activeContext.tenantId,
                  companyId: activeContext.companyId,
                  branchId: e.target.value || null,
                })
              }
            >
              <option value="">Todas as unidades autorizadas</option>
              {companyBranches.map((branch) => (
                <option key={branch.id} value={branch.id}>
                  {branch.code} — {branch.name}
                </option>
              ))}
            </select>
          </label>
        </nav>
      </header>

      <main style={{ padding: "2rem", maxWidth: "1280px", margin: "0 auto", width: "100%" }}>
        <h1 className="serif-heading">Central de Comando Operacional</h1>
        <p>
          Operador autenticado: <strong>{user.name}</strong> ({user.email}) — Contexto ativo:{" "}
          <strong>{activeContext.companyTradeName}</strong>
          {activeContext.branchName ? ` / ${activeContext.branchName}` : ""}
        </p>
      </main>
    </div>
  );
}

export function App() {
  return (
    <ApplicationProvider>
      <CommandCenterShell />
    </ApplicationProvider>
  );
}
export default App;
