"use client";

import {
  ArrowDownCircle,
  ArrowUpCircle,
  CheckCircle2,
  Clock3,
  History,
  LoaderCircle,
  Plus,
  RotateCcw,
  ShieldCheck,
  WalletCards,
  X,
} from "lucide-react";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";

import { useApplicationContext } from "./application-context";
import { isCurrentHistoryResponse } from "./financial-request-guards";

type FinancialKind = "payable" | "receivable";
type CatalogResource =
  | "counterparty"
  | "category"
  | "costCenter"
  | "financialAccount";
type FinancialRecord = Readonly<{
  id: string;
  branchId: string | null;
  counterpartyId: string | null;
  categoryId: string | null;
  costCenterId: string | null;
  counterpartyName: string;
  description: string;
  documentNumber: string | null;
  issueDate: string | null;
  dueDate: string;
  amount: string;
  outstandingAmount: string;
  currencyCode: string;
  status: "OPEN" | "PARTIAL" | "SETTLED" | "OVERDUE" | "CANCELLED";
  settledAt: string | null;
}>;
type CatalogItem = Readonly<{
  id: string;
  resource: string;
  branchId: string | null;
  code: string;
  name: string;
  kind?: "SUPPLIER" | "CUSTOMER" | "BOTH";
  direction?: "PAYABLE" | "RECEIVABLE" | "BOTH";
  type?: string;
  currencyCode?: string;
}>;
type Catalogs = Readonly<{
  counterparties: readonly CatalogItem[];
  categories: readonly CatalogItem[];
  costCenters: readonly CatalogItem[];
  financialAccounts: readonly CatalogItem[];
}>;
type HistoryEvent = Readonly<{
  id: string;
  type: "SETTLEMENT" | "REVERSAL";
  reversesEventId?: string;
  principalAmount: string;
  interestAmount: string;
  fineAmount: string;
  discountAmount: string;
  cashAmount: string;
  occurredAt: string;
  note: string | null;
  financialAccount: Readonly<{ id: string; name: string; type: string }>;
  actor: Readonly<{ id: string; displayName: string }>;
  originalActor?: Readonly<{ id: string; displayName: string }>;
  reason?: string;
  reversed: boolean;
  reversalId: string | null;
}>;
type FinancialHistory = Readonly<{
  record: FinancialRecord;
  events: readonly HistoryEvent[];
  audit: Readonly<{
    appendOnly: boolean;
    eventCount: number;
    settlementCount: number;
    reversalCount: number;
    projectionVerified: boolean;
  }>;
}>;

const EMPTY_CATALOGS: Catalogs = Object.freeze({
  counterparties: [],
  categories: [],
  costCenters: [],
  financialAccounts: [],
});
const STATUS_LABELS: Record<FinancialRecord["status"], string> = {
  OPEN: "Em aberto",
  PARTIAL: "Parcial",
  SETTLED: "Liquidado",
  OVERDUE: "Vencido",
  CANCELLED: "Cancelado",
};

function money(value: string, currencyCode = "BRL") {
  const parsed = /^(-?)(\d+)(?:\.(\d{1,4}))?$/.exec(String(value));
  if (!parsed) return `${currencyCode} ${value}`;
  const negative = parsed[1] === "-";
  const units =
    BigInt(parsed[2]) * 10_000n +
    BigInt((parsed[3] ?? "").padEnd(4, "0"));
  const cents = (units + 50n) / 100n;
  const integer = String(cents / 100n).replace(
    /\B(?=(\d{3})+(?!\d))/g,
    ".",
  );
  const fraction = String(cents % 100n).padStart(2, "0");
  const symbol = currencyCode === "BRL" ? "R$" : currencyCode;
  return `${negative ? "-" : ""}${symbol} ${integer},${fraction}`;
}

function dateLabel(value: string) {
  return new Intl.DateTimeFormat("pt-BR", {
    day: "2-digit",
    month: "short",
    year: "numeric",
  }).format(new Date(`${value}T12:00:00.000Z`));
}

function dateTimeLabel(value: string) {
  return new Intl.DateTimeFormat("pt-BR", {
    dateStyle: "short",
    timeStyle: "short",
  }).format(new Date(value));
}

function decimalInput(value: FormDataEntryValue | null, fallback = "0") {
  const normalized = String(value ?? "").trim().replace(",", ".");
  return normalized || fallback;
}

async function responseBody<T>(response: Response): Promise<T> {
  const body = (await response.json().catch(() => null)) as
    | (T & { title?: string })
    | null;
  if (!response.ok) {
    throw new Error(body?.title ?? "Não foi possível concluir a operação.");
  }
  if (!body) throw new Error("O servidor retornou uma resposta inválida.");
  return body;
}

export function FinancialWorkspace() {
  const application = useApplicationContext();
  const permissions = application.navigation!.permissions;
  const canViewPayables = permissions.includes("payables.view");
  const canViewReceivables = permissions.includes("receivables.view");
  const [kind, setKind] = useState<FinancialKind>(() =>
    canViewPayables ? "payable" : "receivable",
  );
  const [records, setRecords] = useState<readonly FinancialRecord[]>([]);
  const [catalogs, setCatalogs] = useState<Catalogs>(EMPTY_CATALOGS);
  const [loading, setLoading] = useState(true);
  const [working, setWorking] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [createOpen, setCreateOpen] = useState(false);
  const [catalogOpen, setCatalogOpen] = useState(false);
  const [catalogResource, setCatalogResource] =
    useState<CatalogResource>("counterparty");
  const [settlementRecord, setSettlementRecord] =
    useState<FinancialRecord | null>(null);
  const [historyRecord, setHistoryRecord] = useState<FinancialRecord | null>(
    null,
  );
  const [history, setHistory] = useState<FinancialHistory | null>(null);
  const [reversalEvent, setReversalEvent] = useState<HistoryEvent | null>(null);
  const loadRevision = useRef(0);
  const historyRevision = useRef(0);

  const canView = permissions.includes(`${kind}s.view`);
  const canManage = permissions.includes(`${kind}s.manage`);
  const canViewCatalogs = permissions.includes("financial.view");
  const canManageCatalogs = permissions.includes("financial.manage");
  const expectedCounterpartyKind =
    kind === "payable" ? "SUPPLIER" : "CUSTOMER";
  const expectedDirection = kind === "payable" ? "PAYABLE" : "RECEIVABLE";
  const activeBranchId = application.navigation!.branch?.id ?? null;
  const availableInActiveScope = useCallback(
    (item: CatalogItem) =>
      item.branchId === null || item.branchId === activeBranchId,
    [activeBranchId],
  );
  const availableCounterparties = useMemo(
    () =>
      catalogs.counterparties.filter(
        (item) =>
          availableInActiveScope(item) &&
          (item.kind === expectedCounterpartyKind || item.kind === "BOTH"),
      ),
    [
      availableInActiveScope,
      catalogs.counterparties,
      expectedCounterpartyKind,
    ],
  );
  const availableCategories = useMemo(
    () =>
      catalogs.categories.filter(
        (item) =>
          availableInActiveScope(item) &&
          (item.direction === expectedDirection || item.direction === "BOTH"),
      ),
    [availableInActiveScope, catalogs.categories, expectedDirection],
  );

  const load = useCallback(async () => {
    const revision = ++loadRevision.current;
    setLoading(true);
    setError(null);
    try {
      const [recordsResponse, catalogsResponse] = await Promise.all([
        application.fetchWithContext(`/api/v1/${kind}s?limit=100`),
        canViewCatalogs
          ? application.fetchWithContext("/api/v1/financial/catalogs")
          : Promise.resolve(null),
      ]);
      const recordsBody = await responseBody<{
        data: readonly FinancialRecord[];
      }>(recordsResponse);
      let nextCatalogs = EMPTY_CATALOGS;
      if (catalogsResponse) {
        try {
          const catalogsBody = await responseBody<{ data: Catalogs }>(
            catalogsResponse,
          );
          nextCatalogs = catalogsBody.data;
        } catch (catalogError) {
          if (revision !== loadRevision.current) return;
          setError(
            catalogError instanceof Error
              ? `Títulos carregados. ${catalogError.message}`
              : "Títulos carregados, mas os cadastros financeiros estão indisponíveis.",
          );
        }
      }
      if (revision !== loadRevision.current) return;
      setRecords(recordsBody.data);
      setCatalogs(nextCatalogs);
    } catch (loadError) {
      if (revision !== loadRevision.current) return;
      setError(
        loadError instanceof Error
          ? loadError.message
          : "Não foi possível carregar os dados financeiros.",
      );
      setRecords([]);
    } finally {
      if (revision === loadRevision.current) setLoading(false);
    }
  }, [application, canViewCatalogs, kind]);

  useEffect(() => {
    if (canView) void Promise.resolve().then(load);
    return () => {
      loadRevision.current += 1;
    };
  }, [canView, load]);

  async function post<T>(path: string, input: unknown) {
    const response = await application.fetchWithContext(path, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "Idempotency-Key": `clareza-ui:${crypto.randomUUID()}`,
      },
      body: JSON.stringify(input),
    });
    return responseBody<T>(response);
  }

  async function createRecord(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const data = new FormData(form);
    setWorking(true);
    setError(null);
    try {
      const counterpartyId = String(data.get("counterpartyId") ?? "");
      const selectedCounterparty = availableCounterparties.find(
        (item) => item.id === counterpartyId,
      );
      await post(`/api/v1/${kind}s`, {
        ...(counterpartyId ? { counterpartyId } : {}),
        ...(data.get("categoryId")
          ? { categoryId: String(data.get("categoryId")) }
          : {}),
        ...(data.get("costCenterId")
          ? { costCenterId: String(data.get("costCenterId")) }
          : {}),
        counterpartyName:
          selectedCounterparty?.name ||
          String(data.get("counterpartyName") ?? "").trim(),
        description: String(data.get("description") ?? "").trim(),
        ...(data.get("documentNumber")
          ? { documentNumber: String(data.get("documentNumber")).trim() }
          : {}),
        dueDate: String(data.get("dueDate")),
        amount: decimalInput(data.get("amount")),
        currencyCode: "BRL",
      });
      form.reset();
      setCreateOpen(false);
      setNotice(
        kind === "payable"
          ? "Conta a pagar criada com sucesso."
          : "Conta a receber criada com sucesso.",
      );
      await load();
    } catch (mutationError) {
      setError(
        mutationError instanceof Error
          ? mutationError.message
          : "Não foi possível criar o título.",
      );
    } finally {
      setWorking(false);
    }
  }

  async function createCatalog(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const data = new FormData(form);
    const input: Record<string, string> = {
      resource: catalogResource,
      code: String(data.get("code") ?? "").trim(),
      name: String(data.get("name") ?? "").trim(),
    };
    if (catalogResource === "counterparty") {
      input.kind = String(data.get("kind") ?? "BOTH");
      const taxId = String(data.get("taxId") ?? "").trim();
      if (taxId) input.taxId = taxId;
    } else if (catalogResource === "category") {
      input.direction = String(data.get("direction") ?? "BOTH");
    } else if (catalogResource === "financialAccount") {
      input.type = String(data.get("type") ?? "CHECKING");
      input.currencyCode = "BRL";
    }

    setWorking(true);
    setError(null);
    try {
      await post("/api/v1/financial/catalogs", input);
      form.reset();
      setCatalogOpen(false);
      setNotice("Cadastro financeiro criado no contexto ativo.");
      await load();
    } catch (mutationError) {
      setError(
        mutationError instanceof Error
          ? mutationError.message
          : "Não foi possível criar o cadastro financeiro.",
      );
    } finally {
      setWorking(false);
    }
  }

  async function settle(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!settlementRecord) return;
    const data = new FormData(event.currentTarget);
    setWorking(true);
    setError(null);
    try {
      await post(`/api/v1/${kind}s/settlements`, {
        recordId: settlementRecord.id,
        financialAccountId: String(data.get("financialAccountId")),
        amount: decimalInput(data.get("amount")),
        interestAmount: decimalInput(data.get("interestAmount")),
        fineAmount: decimalInput(data.get("fineAmount")),
        discountAmount: decimalInput(data.get("discountAmount")),
        ...(data.get("note")
          ? { note: String(data.get("note")).trim() }
          : {}),
      });
      setSettlementRecord(null);
      setNotice(
        kind === "payable"
          ? "Pagamento registrado e saldo recalculado."
          : "Recebimento registrado e saldo recalculado.",
      );
      await load();
    } catch (mutationError) {
      setError(
        mutationError instanceof Error
          ? mutationError.message
          : "Não foi possível registrar a baixa.",
      );
    } finally {
      setWorking(false);
    }
  }

  async function openHistory(record: FinancialRecord) {
    const revision = ++historyRevision.current;
    setHistoryRecord(record);
    setHistory(null);
    setReversalEvent(null);
    setError(null);
    try {
      const response = await application.fetchWithContext(
        `/api/v1/${kind}s/history?recordId=${encodeURIComponent(record.id)}`,
      );
      const body = await responseBody<{ data: FinancialHistory }>(response);
      if (
        !isCurrentHistoryResponse({
          requestRevision: revision,
          currentRevision: historyRevision.current,
          requestedRecordId: record.id,
          responseRecordId: body.data.record.id,
        })
      ) {
        return;
      }
      setHistory(body.data);
    } catch (historyError) {
      if (revision !== historyRevision.current) return;
      setError(
        historyError instanceof Error
          ? historyError.message
          : "Não foi possível carregar o histórico.",
      );
    }
  }

  async function reverse(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!historyRecord || !reversalEvent) return;
    const data = new FormData(event.currentTarget);
    setWorking(true);
    setError(null);
    try {
      await post(`/api/v1/${kind}s/reversals`, {
        recordId: historyRecord.id,
        eventId: reversalEvent.id,
        reason: String(data.get("reason") ?? "").trim(),
      });
      setReversalEvent(null);
      setNotice("Estorno registrado como novo evento auditável.");
      await load();
      await openHistory(historyRecord);
    } catch (mutationError) {
      setError(
        mutationError instanceof Error
          ? mutationError.message
          : "Não foi possível registrar o estorno.",
      );
    } finally {
      setWorking(false);
    }
  }

  if (!canView) return null;

  return (
    <section
      className="financial-workspace"
      id="financeiro-operacional"
      aria-labelledby="financial-workspace-title"
    >
      <div className="financial-workspace-heading">
        <div>
          <p className="eyebrow">
            <span /> Dados operacionais
          </p>
          <h2 id="financial-workspace-title">Contas a pagar e a receber</h2>
          <p>
            Títulos, baixas, estornos e saldos consultados no contexto ativo.
          </p>
        </div>
        <div className="financial-heading-actions">
          <span className="operational-badge">
            <ShieldCheck size={15} aria-hidden="true" />
            Isolamento ativo
          </span>
          {canManageCatalogs ? (
            <button
              className="secondary-button"
              type="button"
              onClick={() => {
                setError(null);
                setCatalogOpen(true);
              }}
            >
              <Plus size={16} aria-hidden="true" />
              Novo cadastro
            </button>
          ) : null}
          {canManage ? (
            <button
              className="primary-button"
              type="button"
              onClick={() => {
                setError(null);
                setCreateOpen(true);
              }}
            >
              <Plus size={16} aria-hidden="true" />
              Novo título
            </button>
          ) : (
            <span className="read-only-badge">Somente leitura</span>
          )}
        </div>
      </div>

      <div className="financial-tabs" role="tablist" aria-label="Tipo de título">
        {canViewPayables ? (
          <button
            role="tab"
            aria-selected={kind === "payable"}
            className={kind === "payable" ? "active" : ""}
            type="button"
            onClick={() => {
              loadRevision.current += 1;
              setKind("payable");
              setNotice(null);
            }}
          >
            <ArrowUpCircle size={17} aria-hidden="true" />
            Contas a pagar
          </button>
        ) : null}
        {canViewReceivables ? (
          <button
            role="tab"
            aria-selected={kind === "receivable"}
            className={kind === "receivable" ? "active" : ""}
            type="button"
            onClick={() => {
              loadRevision.current += 1;
              setKind("receivable");
              setNotice(null);
            }}
          >
            <ArrowDownCircle size={17} aria-hidden="true" />
            Contas a receber
          </button>
        ) : null}
      </div>

      {error ? (
        <div className="financial-message error" role="alert">
          {error}
          <button type="button" onClick={() => setError(null)}>
            Fechar
          </button>
        </div>
      ) : null}
      {notice ? (
        <div className="financial-message success" role="status">
          <CheckCircle2 size={16} aria-hidden="true" />
          {notice}
        </div>
      ) : null}

      {loading ? (
        <div className="financial-empty" aria-live="polite">
          <LoaderCircle className="spin" size={22} aria-hidden="true" />
          Carregando dados do contexto ativo...
        </div>
      ) : records.length === 0 ? (
        <div className="financial-empty">
          <WalletCards size={28} aria-hidden="true" />
          <strong>Nenhum título neste contexto</strong>
          <span>
            {canManage
              ? "Crie o primeiro título operacional."
              : "Não existem registros disponíveis para leitura."}
          </span>
        </div>
      ) : (
        <div className="financial-table-wrap">
          <table className="financial-table">
            <thead>
              <tr>
                <th>Contraparte / descrição</th>
                <th>Vencimento</th>
                <th>Valor</th>
                <th>Saldo</th>
                <th>Status</th>
                <th aria-label="Ações" />
              </tr>
            </thead>
            <tbody>
              {records.map((record) => (
                <tr key={record.id}>
                  <td>
                    <strong>{record.counterpartyName}</strong>
                    <span>{record.description}</span>
                  </td>
                  <td>{dateLabel(record.dueDate)}</td>
                  <td>{money(record.amount, record.currencyCode)}</td>
                  <td>
                    <strong>
                      {money(record.outstandingAmount, record.currencyCode)}
                    </strong>
                  </td>
                  <td>
                    <span
                      className={`financial-status status-${record.status.toLowerCase()}`}
                    >
                      {STATUS_LABELS[record.status]}
                    </span>
                  </td>
                  <td>
                    <div className="financial-row-actions">
                      {canManage &&
                      !["SETTLED", "CANCELLED"].includes(record.status) ? (
                        <button
                          type="button"
                          onClick={() => {
                            setError(null);
                            setSettlementRecord(record);
                          }}
                        >
                          Registrar baixa
                        </button>
                      ) : null}
                      <button type="button" onClick={() => void openHistory(record)}>
                        <History size={14} aria-hidden="true" />
                        Histórico
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <p className="financial-footnote">
        <Clock3 size={14} aria-hidden="true" />
        O status e o saldo são projeções dos eventos de pagamento e recebimento.
        Não há marcação manual de “pago”.
      </p>

      {catalogOpen ? (
        <div
          className="financial-modal-layer"
          role="presentation"
          onMouseDown={() => !working && setCatalogOpen(false)}
        >
          <form
            className="financial-modal"
            role="dialog"
            aria-modal="true"
            aria-labelledby="financial-catalog-title"
            onSubmit={createCatalog}
            onMouseDown={(event) => event.stopPropagation()}
          >
            <div className="financial-modal-head">
              <div>
                <p className="eyebrow">Cadastro financeiro</p>
                <h3 id="financial-catalog-title">Novo item de apoio</h3>
              </div>
              <button
                type="button"
                aria-label="Fechar"
                disabled={working}
                onClick={() => setCatalogOpen(false)}
              >
                <X size={19} />
              </button>
            </div>
            <div className="financial-form-grid">
              <label className="wide">
                Tipo de cadastro
                <select
                  value={catalogResource}
                  onChange={(event) =>
                    setCatalogResource(event.target.value as CatalogResource)
                  }
                >
                  <option value="counterparty">Contraparte</option>
                  <option value="category">Categoria</option>
                  <option value="costCenter">Centro de custo</option>
                  <option value="financialAccount">Conta financeira</option>
                </select>
              </label>
              <label>
                Código
                <input
                  name="code"
                  pattern="[A-Za-z0-9._-]{1,40}"
                  maxLength={40}
                  required
                />
              </label>
              <label>
                Nome
                <input name="name" minLength={2} maxLength={180} required />
              </label>
              {catalogResource === "counterparty" ? (
                <>
                  <label>
                    Papel
                    <select name="kind" defaultValue="BOTH">
                      <option value="SUPPLIER">Fornecedor</option>
                      <option value="CUSTOMER">Cliente</option>
                      <option value="BOTH">Fornecedor e cliente</option>
                    </select>
                  </label>
                  <label>
                    CPF/CNPJ
                    <input name="taxId" minLength={5} maxLength={24} />
                  </label>
                </>
              ) : null}
              {catalogResource === "category" ? (
                <label className="wide">
                  Direção
                  <select name="direction" defaultValue="BOTH">
                    <option value="PAYABLE">Contas a pagar</option>
                    <option value="RECEIVABLE">Contas a receber</option>
                    <option value="BOTH">Ambas</option>
                  </select>
                </label>
              ) : null}
              {catalogResource === "financialAccount" ? (
                <label className="wide">
                  Tipo de conta
                  <select name="type" defaultValue="CHECKING">
                    <option value="CASH">Caixa</option>
                    <option value="CHECKING">Conta corrente</option>
                    <option value="SAVINGS">Poupança</option>
                    <option value="PAYMENT">Conta de pagamento</option>
                    <option value="OTHER">Outra</option>
                  </select>
                </label>
              ) : null}
            </div>
            <p className="form-hint">
              O item será criado para a unidade ativa. Sem unidade selecionada,
              ficará disponível para toda a empresa.
            </p>
            <div className="financial-modal-actions">
              <button
                className="secondary-button"
                type="button"
                disabled={working}
                onClick={() => setCatalogOpen(false)}
              >
                Cancelar
              </button>
              <button className="primary-button" type="submit" disabled={working}>
                {working ? <LoaderCircle className="spin" size={16} /> : null}
                Criar cadastro
              </button>
            </div>
          </form>
        </div>
      ) : null}

      {createOpen ? (
        <div
          className="financial-modal-layer"
          role="presentation"
          onMouseDown={() => !working && setCreateOpen(false)}
        >
          <form
            className="financial-modal"
            role="dialog"
            aria-modal="true"
            aria-labelledby="financial-create-title"
            onSubmit={createRecord}
            onMouseDown={(event) => event.stopPropagation()}
          >
            <div className="financial-modal-head">
              <div>
                <p className="eyebrow">Novo título</p>
                <h3 id="financial-create-title">
                  {kind === "payable" ? "Conta a pagar" : "Conta a receber"}
                </h3>
              </div>
              <button
                type="button"
                aria-label="Fechar"
                disabled={working}
                onClick={() => setCreateOpen(false)}
              >
                <X size={19} />
              </button>
            </div>
            <div className="financial-form-grid">
              <label className="wide">
                Contraparte cadastrada
                <select name="counterpartyId" defaultValue="">
                  <option value="">Informar nome manualmente</option>
                  {availableCounterparties.map((item) => (
                    <option key={item.id} value={item.id}>
                      {item.code} · {item.name}
                    </option>
                  ))}
                </select>
              </label>
              <label className="wide">
                Nome da contraparte
                <input
                  name="counterpartyName"
                  minLength={2}
                  maxLength={180}
                  placeholder="Obrigatório se não houver cadastro"
                  required={availableCounterparties.length === 0}
                />
              </label>
              <label className="wide">
                Descrição
                <input name="description" minLength={2} maxLength={500} required />
              </label>
              <label>
                Documento
                <input name="documentNumber" maxLength={80} />
              </label>
              <label>
                Vencimento
                <input name="dueDate" type="date" required />
              </label>
              <label>
                Valor
                <input
                  name="amount"
                  inputMode="decimal"
                  placeholder="0,00"
                  required
                />
              </label>
              <label>
                Categoria
                <select name="categoryId" defaultValue="">
                  <option value="">Sem categoria</option>
                  {availableCategories.map((item) => (
                    <option key={item.id} value={item.id}>
                      {item.code} · {item.name}
                    </option>
                  ))}
                </select>
              </label>
              <label className="wide">
                Centro de custo
                <select name="costCenterId" defaultValue="">
                  <option value="">Sem centro de custo</option>
                  {catalogs.costCenters
                    .filter(availableInActiveScope)
                    .map((item) => (
                    <option key={item.id} value={item.id}>
                      {item.code} · {item.name}
                    </option>
                    ))}
                </select>
              </label>
            </div>
            {!canManageCatalogs ? (
              <p className="form-hint">
                Cadastros financeiros são disponibilizados apenas para seleção.
              </p>
            ) : null}
            <div className="financial-modal-actions">
              <button
                className="secondary-button"
                type="button"
                disabled={working}
                onClick={() => setCreateOpen(false)}
              >
                Cancelar
              </button>
              <button className="primary-button" type="submit" disabled={working}>
                {working ? <LoaderCircle className="spin" size={16} /> : null}
                Criar título
              </button>
            </div>
          </form>
        </div>
      ) : null}

      {settlementRecord ? (
        <div
          className="financial-modal-layer"
          role="presentation"
          onMouseDown={() => !working && setSettlementRecord(null)}
        >
          <form
            className="financial-modal"
            role="dialog"
            aria-modal="true"
            aria-labelledby="financial-settlement-title"
            onSubmit={settle}
            onMouseDown={(event) => event.stopPropagation()}
          >
            <div className="financial-modal-head">
              <div>
                <p className="eyebrow">Evento financeiro</p>
                <h3 id="financial-settlement-title">Registrar baixa</h3>
                <span>{settlementRecord.counterpartyName}</span>
              </div>
              <button
                type="button"
                aria-label="Fechar"
                disabled={working}
                onClick={() => setSettlementRecord(null)}
              >
                <X size={19} />
              </button>
            </div>
            <div className="settlement-summary">
              <span>Saldo atual</span>
              <strong>
                {money(
                  settlementRecord.outstandingAmount,
                  settlementRecord.currencyCode,
                )}
              </strong>
            </div>
            <div className="financial-form-grid">
              <label className="wide">
                Conta financeira
                <select name="financialAccountId" defaultValue="" required>
                  <option value="" disabled>
                    Selecione
                  </option>
                  {catalogs.financialAccounts
                    .filter(
                      (item) =>
                        item.currencyCode === settlementRecord.currencyCode &&
                        (item.branchId === null ||
                          item.branchId === settlementRecord.branchId),
                    )
                    .map((item) => (
                      <option key={item.id} value={item.id}>
                        {item.code} · {item.name}
                      </option>
                    ))}
                </select>
              </label>
              <label>
                Principal
                <input
                  name="amount"
                  inputMode="decimal"
                  defaultValue={settlementRecord.outstandingAmount}
                  required
                />
              </label>
              <label>
                Juros
                <input name="interestAmount" inputMode="decimal" defaultValue="0" />
              </label>
              <label>
                Multa
                <input name="fineAmount" inputMode="decimal" defaultValue="0" />
              </label>
              <label>
                Desconto
                <input name="discountAmount" inputMode="decimal" defaultValue="0" />
              </label>
              <label className="wide">
                Observação
                <input name="note" minLength={2} maxLength={500} />
              </label>
            </div>
            {catalogs.financialAccounts.length === 0 ? (
              <p className="form-hint warning">
                Nenhuma conta financeira está disponível. Selecione a unidade
                cadastrada ou crie o cadastro pela API.
              </p>
            ) : null}
            <div className="financial-modal-actions">
              <button
                className="secondary-button"
                type="button"
                disabled={working}
                onClick={() => setSettlementRecord(null)}
              >
                Cancelar
              </button>
              <button className="primary-button" type="submit" disabled={working}>
                {working ? <LoaderCircle className="spin" size={16} /> : null}
                Confirmar baixa
              </button>
            </div>
          </form>
        </div>
      ) : null}

      {historyRecord ? (
        <div
          className="financial-modal-layer history-layer"
          role="presentation"
          onMouseDown={() => !working && setHistoryRecord(null)}
        >
          <aside
            className="financial-history"
            role="dialog"
            aria-modal="true"
            aria-labelledby="financial-history-title"
            onMouseDown={(event) => event.stopPropagation()}
          >
            <div className="financial-modal-head">
              <div>
                <p className="eyebrow">Rastro visual</p>
                <h3 id="financial-history-title">Histórico do título</h3>
                <span>{historyRecord.counterpartyName}</span>
              </div>
              <button
                type="button"
                aria-label="Fechar"
                disabled={working}
                onClick={() => setHistoryRecord(null)}
              >
                <X size={19} />
              </button>
            </div>
            {!history ? (
              <div className="financial-empty">
                <LoaderCircle className="spin" size={22} />
                Carregando histórico...
              </div>
            ) : (
              <>
                <div className="history-audit">
                  <ShieldCheck size={18} aria-hidden="true" />
                  <span>
                    <strong>Projeção verificada</strong>
                    {history.audit.settlementCount} baixa(s) ·{" "}
                    {history.audit.reversalCount} estorno(s) · trilha somente
                    acréscimo
                  </span>
                </div>
                <div className="history-balance">
                  <span>Saldo derivado dos eventos</span>
                  <strong>
                    {money(
                      history.record.outstandingAmount,
                      history.record.currencyCode,
                    )}
                  </strong>
                  <span
                    className={`financial-status status-${history.record.status.toLowerCase()}`}
                  >
                    {STATUS_LABELS[history.record.status]}
                  </span>
                </div>
                {history.events.length === 0 ? (
                  <div className="financial-empty compact">
                    Ainda não há eventos de baixa.
                  </div>
                ) : (
                  <ol className="history-events">
                    {history.events.map((item) => (
                      <li
                        className={
                          item.type === "REVERSAL" || item.reversed
                            ? "reversed"
                            : ""
                        }
                        key={item.id}
                      >
                        <span className="history-dot" />
                        <div>
                          <div className="history-event-head">
                            <strong>
                              {item.type === "REVERSAL"
                                ? "Estorno registrado"
                                : item.reversed
                                  ? "Baixa posteriormente estornada"
                                  : "Baixa registrada"}
                            </strong>
                            <span>{dateTimeLabel(item.occurredAt)}</span>
                          </div>
                          <p>
                            Principal{" "}
                            {money(
                              item.principalAmount,
                              history.record.currencyCode,
                            )}{" "}
                            · Caixa{" "}
                            {money(item.cashAmount, history.record.currencyCode)}
                          </p>
                          <small>
                            Juros{" "}
                            {money(
                              item.interestAmount,
                              history.record.currencyCode,
                            )}{" "}
                            · Multa{" "}
                            {money(item.fineAmount, history.record.currencyCode)}{" "}
                            · Desconto{" "}
                            {money(
                              item.discountAmount,
                              history.record.currencyCode,
                            )}
                          </small>
                          <small>
                            {item.financialAccount.name} · {item.actor.displayName}
                          </small>
                          {item.note ? <blockquote>{item.note}</blockquote> : null}
                          {item.type === "REVERSAL" ? (
                            <div className="reversal-note">
                              <RotateCcw size={14} aria-hidden="true" />
                              <span>
                                {item.reason} · baixa original{" "}
                                {item.reversesEventId?.slice(0, 8)}
                              </span>
                            </div>
                          ) : item.reversed ? (
                            <div className="reversal-note">
                              <RotateCcw size={14} aria-hidden="true" />
                              <span>
                                Estorno {item.reversalId?.slice(0, 8)} aparece
                                separadamente nesta timeline.
                              </span>
                            </div>
                          ) : canManage ? (
                            <button
                              className="history-reverse-button"
                              type="button"
                              onClick={() => setReversalEvent(item)}
                            >
                              <RotateCcw size={14} aria-hidden="true" />
                              Estornar
                            </button>
                          ) : null}
                        </div>
                      </li>
                    ))}
                  </ol>
                )}
                {reversalEvent ? (
                  <form className="reversal-form" onSubmit={reverse}>
                    <label>
                      Motivo do estorno
                      <input
                        name="reason"
                        minLength={4}
                        maxLength={500}
                        autoFocus
                        required
                      />
                    </label>
                    <div>
                      <button
                        type="button"
                        disabled={working}
                        onClick={() => setReversalEvent(null)}
                      >
                        Cancelar
                      </button>
                      <button type="submit" disabled={working}>
                        Confirmar estorno
                      </button>
                    </div>
                  </form>
                ) : null}
              </>
            )}
          </aside>
        </div>
      ) : null}
    </section>
  );
}
