"use client";

import {
  AlertTriangle,
  ArrowDownRight,
  ArrowRight,
  ArrowUpRight,
  Bell,
  BookOpen,
  Building2,
  CalendarDays,
  Check,
  CheckCircle2,
  ChevronDown,
  CircleHelp,
  ClipboardList,
  Clock3,
  FileText,
  FolderKanban,
  Landmark,
  LayoutDashboard,
  Lightbulb,
  Menu,
  Receipt,
  Scale,
  Search,
  Settings,
  ShieldCheck,
  Sparkles,
  Target,
  TrendingDown,
  TrendingUp,
  Upload,
  Users,
  Wallet,
  X,
} from "lucide-react";
import { useEffect, useMemo, useRef, useState } from "react";

import { useApplicationContext } from "./application-context";
import { FinancialWorkspace } from "./financial-workspace";
import { permissionPresentation } from "./identity-client";

type GlossaryKey = "receita" | "resultado" | "caixa" | "liquidez" | "dre";

type GlossaryEntry = {
  title: string;
  short: string;
  management: string;
  formula: string;
  why: string;
  source: string;
  related: string[];
};

const glossary: Record<GlossaryKey, GlossaryEntry> = {
  receita: {
    title: "Receita líquida",
    short:
      "É o valor que permanece das vendas depois de impostos, devoluções e descontos.",
    management:
      "Mostra quanto da venda está efetivamente disponível para cobrir custos, despesas e gerar resultado.",
    formula: "Receita bruta − impostos − devoluções − descontos",
    why: "É a base correta para acompanhar margens e comparar o desempenho operacional.",
    source: "Dados demonstrativos • DRE • contas 3.01 a 3.04 • junho/2026",
    related: ["Receita bruta", "Deduções", "Margem líquida"],
  },
  resultado: {
    title: "Resultado líquido",
    short:
      "É o valor final que restou no período após custos, despesas, impostos e efeitos financeiros.",
    management:
      "Ajuda a entender se a operação criou valor, mas não equivale ao dinheiro disponível no banco.",
    formula: "Receitas − custos − despesas − tributos ± resultado financeiro",
    why: "É um dos principais sinais de sustentabilidade econômica da empresa.",
    source: "Dados demonstrativos • DRE gerencial • junho/2026",
    related: ["Margem líquida", "Fluxo de caixa", "EBITDA"],
  },
  caixa: {
    title: "Saldo de caixa",
    short:
      "É a soma do dinheiro disponível em contas bancárias e aplicações de liquidez imediata.",
    management:
      "Revela a capacidade de honrar compromissos agora. Pode cair mesmo quando existe lucro contábil.",
    formula: "Saldo inicial + entradas − saídas",
    why: "Mantém a operação funcionando e reduz a necessidade de crédito de emergência.",
    source: "Dados demonstrativos • exemplo de 4 contas bancárias",
    related: ["Capital de giro", "Conciliação", "Fluxo de caixa"],
  },
  liquidez: {
    title: "Liquidez corrente",
    short:
      "Compara os recursos de curto prazo com as obrigações que vencem no mesmo horizonte.",
    management:
      "Para cada R$ 1,00 de dívida de curto prazo, há R$ 1,43 registrado em ativos de curto prazo.",
    formula: "Ativo circulante ÷ passivo circulante",
    why: "Ajuda a enxergar folga financeira, sempre considerando setor, sazonalidade e qualidade dos recebíveis.",
    source: "Dados demonstrativos • balanço ilustrativo • junho/2026",
    related: ["Ativo circulante", "Passivo circulante", "Capital de giro"],
  },
  dre: {
    title: "DRE",
    short:
      "A Demonstração do Resultado organiza receitas, custos e despesas para mostrar como o resultado foi formado.",
    management:
      "É uma fotografia econômica do período. O modo explicado destaca as etapas que mais alteraram a margem.",
    formula: "Receitas − deduções − custos − despesas = resultado",
    why: "Permite descobrir onde a empresa ganhou ou perdeu eficiência.",
    source: "Dados demonstrativos • lançamentos contábeis ilustrativos",
    related: ["Resultado líquido", "Margem bruta", "Regime de competência"],
  },
};

const periods = {
  june: {
    label: "Junho de 2026",
    short: "Jun 2026",
    revenue: "R$ 1.248.900",
    result: "R$ 156.120",
    cash: "R$ 438.260",
    receive: "R$ 392.480",
    payable: "R$ 286.940",
  },
  may: {
    label: "Maio de 2026",
    short: "Mai 2026",
    revenue: "R$ 1.115.100",
    result: "R$ 190.390",
    cash: "R$ 464.800",
    receive: "R$ 351.220",
    payable: "R$ 241.830",
  },
};

const navGroups = [
  {
    label: "Decisão",
    items: [
      { label: "Visão geral", icon: LayoutDashboard, href: "#visao-geral" },
      { label: "Pendências", icon: ClipboardList, href: "#pendencias", badge: "Demo" },
      { label: "Riscos", icon: ShieldCheck, href: "#saude", badge: "Demo" },
    ],
  },
  {
    label: "Números",
    items: [
      { label: "Financeiro", icon: Wallet, href: "#financeiro-operacional" },
      { label: "Contabilidade", icon: Scale, href: "#dre-explicada" },
      { label: "Relatórios", icon: FileText, href: "#dre-explicada" },
    ],
  },
  {
    label: "Conhecimento",
    items: [
      { label: "Glossário", icon: BookOpen, href: "#dre-explicada" },
      { label: "Importações", icon: Upload, href: "#pendencias" },
      { label: "Planos de ação", icon: Target, href: "#pendencias" },
    ],
  },
];

const healthItems = [
  {
    label: "Financeiro",
    status: "Atenção",
    note: "Caixa projetado exige monitoramento",
    tone: "warning",
    icon: Wallet,
  },
  {
    label: "Contábil",
    status: "3 divergências",
    note: "2 conciliações e 1 saldo atípico",
    tone: "danger",
    icon: Scale,
  },
  {
    label: "Fiscal",
    status: "Em dia",
    note: "Próxima obrigação em 9 dias",
    tone: "success",
    icon: Receipt,
  },
  {
    label: "Planos de ação",
    status: "8 em curso",
    note: "2 vencem nesta semana",
    tone: "info",
    icon: Target,
  },
];

const pendingItems = [
  {
    severity: "Crítico",
    title: "Conciliação bancária incompleta",
    area: "Financeiro",
    due: "Vence hoje",
    owner: "Paulo",
    tone: "danger",
  },
  {
    severity: "Importante",
    title: "Certidão federal próxima do vencimento",
    area: "Fiscal",
    due: "Em 3 dias",
    owner: "Camila",
    tone: "warning",
  },
  {
    severity: "Atenção",
    title: "Despesa sem documento de origem",
    area: "Contábil",
    due: "Em 5 dias",
    owner: "Rafael",
    tone: "info",
  },
];

const dreLines = [
  { label: "Receita bruta", value: "R$ 1.419.200", width: 100, tone: "base" },
  { label: "Impostos e deduções", value: "− R$ 170.300", width: 12, tone: "negative" },
  { label: "Receita líquida", value: "R$ 1.248.900", width: 88, tone: "base" },
  { label: "Custos", value: "− R$ 592.400", width: 42, tone: "negative" },
  { label: "Lucro bruto", value: "R$ 656.500", width: 46, tone: "positive" },
  { label: "Despesas operacionais", value: "− R$ 452.800", width: 32, tone: "negative" },
  { label: "Resultado líquido", value: "R$ 156.120", width: 11, tone: "result" },
];

function HelpButton({
  term,
  onOpen,
}: {
  term: GlossaryKey;
  onOpen: (term: GlossaryKey) => void;
}) {
  return (
    <button
      className="help-button"
      type="button"
      aria-label={`Entender ${glossary[term].title}`}
      onClick={() => onOpen(term)}
    >
      <CircleHelp aria-hidden="true" size={14} />
    </button>
  );
}

function initials(displayName: string) {
  const parts = displayName
    .trim()
    .split(/\s+/)
    .map((part) => part.replace(/[^\p{L}\p{N}]/gu, ""))
    .filter(Boolean);
  return (parts.length > 1
    ? `${parts[0][0]}${parts.at(-1)?.[0] ?? ""}`
    : parts[0]?.slice(0, 2) ?? "CL"
  ).toUpperCase();
}

export function CommandCenter() {
  const application = useApplicationContext();
  const identity = application.identity!;
  const navigation = application.navigation!;
  const authorizedCompanies = identity.tenants.flatMap((tenant) =>
    tenant.companies.map((company) => ({ tenant, company })),
  );
  const permissionLevel = permissionPresentation(navigation.permissions);
  const permissionLabel = {
    manage: "Acesso de gestão",
    read: "Somente leitura",
    none: "Sem permissão operacional",
  }[permissionLevel];
  const canViewFinancial = navigation.permissions.some(
    (permission) =>
      permission === "payables.view" ||
      permission === "payables.manage" ||
      permission === "receivables.view" ||
      permission === "receivables.manage",
  );
  const firstName = identity.displayName.trim().split(/\s+/)[0] || "usuário";
  const [period, setPeriod] = useState<keyof typeof periods>("june");
  const [sidebarOpen, setSidebarOpen] = useState(false);
  const [driversOpen, setDriversOpen] = useState(false);
  const [glossaryTerm, setGlossaryTerm] = useState<GlossaryKey | null>(null);
  const [searchOpen, setSearchOpen] = useState(false);
  const [noticeOpen, setNoticeOpen] = useState(false);
  const closeDrawerRef = useRef<HTMLButtonElement>(null);

  const current = useMemo(() => periods[period], [period]);

  useEffect(() => {
    if (!glossaryTerm && !searchOpen) return;
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        setGlossaryTerm(null);
        setSearchOpen(false);
      }
    };
    document.addEventListener("keydown", onKeyDown);
    return () => document.removeEventListener("keydown", onKeyDown);
  }, [glossaryTerm, searchOpen]);

  useEffect(() => {
    if (glossaryTerm) closeDrawerRef.current?.focus();
  }, [glossaryTerm]);

  return (
    <div className="app-shell">
      <a className="skip-link" href="#conteudo-principal">
        Pular para o conteúdo
      </a>

      <aside className={`sidebar ${sidebarOpen ? "sidebar-open" : ""}`}>
        <div className="brand-row">
          <a
            className="brand"
            href="#visao-geral"
            aria-label="Mourão Clareza Contábil e Financeira, início"
          >
            <span className="brand-emblem" aria-hidden="true" />
            <span>
              <strong>Mourão Clareza</strong>
              <small>Contábil e Financeira</small>
            </span>
          </a>
          <button
            className="icon-button sidebar-close"
            type="button"
            aria-label="Fechar menu"
            onClick={() => setSidebarOpen(false)}
          >
            <X size={20} aria-hidden="true" />
          </button>
        </div>

        <div className="company-selector-wrap">
          <label htmlFor="company-select">Empresa em análise</label>
          <div className="company-select-shell">
            <Building2 size={17} aria-hidden="true" />
            <select
              id="company-select"
              value={navigation.company.id}
              onChange={(event) =>
                void application.selectCompany(event.target.value)
              }
            >
              {authorizedCompanies.map(({ tenant, company }) => (
                <option key={`${tenant.id}:${company.id}`} value={company.id}>
                  {company.name}
                </option>
              ))}
            </select>
            <ChevronDown size={15} aria-hidden="true" />
          </div>
          <p>
            {navigation.tenantName} •{" "}
            {navigation.accessMode === "company"
              ? "acesso à empresa inteira"
              : "acesso limitado por unidade"}
          </p>
          <label htmlFor="branch-select">Unidade em análise</label>
          <div className="company-select-shell">
            <Landmark size={17} aria-hidden="true" />
            <select
              id="branch-select"
              value={navigation.branch?.id ?? ""}
              disabled={
                navigation.accessMode === "company" &&
                navigation.company.branches.length === 0
              }
              onChange={(event) =>
                void application.selectBranch(event.target.value || null)
              }
            >
              {navigation.accessMode === "company" ? (
                <option value="">Toda a empresa</option>
              ) : null}
              {navigation.company.branches.map((branch) => (
                <option key={branch.id} value={branch.id}>
                  {branch.name}
                </option>
              ))}
            </select>
            <ChevronDown size={15} aria-hidden="true" />
          </div>
          <span
            className={`access-summary access-${permissionLevel}`}
            data-permission-level={permissionLevel}
          >
            {permissionLabel}
          </span>
          {application.message ? (
            <small className="context-message" role="alert">
              {application.message}
            </small>
          ) : null}
        </div>

        <nav className="side-nav" aria-label="Navegação principal">
          {navGroups.map((group) => (
            <div className="nav-group" key={group.label}>
              <p>{group.label}</p>
              {group.items.map((item, index) => {
                const Icon = item.icon;
                const disabled =
                  item.label === "Financeiro" && !canViewFinancial;
                return (
                  <a
                    key={item.label}
                    className={`${group.label === "Decisão" && index === 0 ? "active" : ""} ${disabled ? "disabled" : ""}`}
                    href={item.href}
                    aria-disabled={disabled || undefined}
                    tabIndex={disabled ? -1 : undefined}
                    onClick={(event) => {
                      if (disabled) {
                        event.preventDefault();
                        return;
                      }
                      setSidebarOpen(false);
                    }}
                  >
                    <Icon size={18} aria-hidden="true" />
                    <span>{item.label}</span>
                    {item.badge ? <small>{item.badge}</small> : null}
                  </a>
                );
              })}
            </div>
          ))}
        </nav>

        <div className="sidebar-footer">
          <div className="closing-progress">
            <div className="closing-head">
              <span>Exemplo de fechamento</span>
              <strong>82%</strong>
            </div>
            <div
              className="progress-track"
              aria-label="Progresso demonstrativo de 82%"
            >
              <span style={{ width: "82%" }} />
            </div>
            <p>Dados demonstrativos</p>
          </div>
          <button
            className="profile-row"
            type="button"
            aria-label={`Abrir perfil de ${identity.displayName}`}
          >
            <span className="avatar">{initials(identity.displayName)}</span>
            <span>
              <strong>{identity.displayName}</strong>
              <small>{permissionLabel}</small>
            </span>
            <Settings size={17} aria-hidden="true" />
          </button>
        </div>
      </aside>

      {sidebarOpen ? (
        <button
          className="sidebar-scrim"
          type="button"
          aria-label="Fechar menu"
          onClick={() => setSidebarOpen(false)}
        />
      ) : null}

      <div className="main-column">
        <header className="topbar">
          <button
            className="icon-button mobile-menu"
            type="button"
            aria-label="Abrir menu"
            onClick={() => setSidebarOpen(true)}
          >
            <Menu size={21} aria-hidden="true" />
          </button>
          <button
            className="search-trigger"
            type="button"
            aria-label="Abrir busca global"
            onClick={() => setSearchOpen(true)}
          >
            <Search size={18} aria-hidden="true" />
            <span>Buscar indicador, relatório ou termo...</span>
            <kbd>⌘ K</kbd>
          </button>
          <div className="topbar-actions">
            <div className="period-select-shell">
              <CalendarDays size={17} aria-hidden="true" />
              <select
                aria-label="Período demonstrativo"
                value={period}
                onChange={(event) => setPeriod(event.target.value as keyof typeof periods)}
              >
                {Object.entries(periods).map(([value, item]) => (
                  <option key={value} value={value}>
                    {item.short} • Demo
                  </option>
                ))}
              </select>
              <ChevronDown size={15} aria-hidden="true" />
            </div>
            <div className="notification-wrap">
              <button
                className="icon-button notification-button"
                type="button"
                aria-label="Abrir notificações demonstrativas"
                aria-expanded={noticeOpen}
                onClick={() => setNoticeOpen((value) => !value)}
              >
                <Bell size={20} aria-hidden="true" />
                <span />
              </button>
              {noticeOpen ? (
                <div className="notification-popover" role="status">
                  <strong>Notificações demonstrativas</strong>
                  <p>Este exemplo ainda não consulta notificações reais.</p>
                  <a href="#pendencias" onClick={() => setNoticeOpen(false)}>
                    Ver exemplos <ArrowRight size={14} aria-hidden="true" />
                  </a>
                </div>
              ) : null}
            </div>
          </div>
        </header>

        <main id="conteudo-principal" tabIndex={-1}>
          <section className="page-intro" id="visao-geral" aria-labelledby="page-title">
            <div>
              <p className="eyebrow"><span /> Central de comando</p>
              <h1 id="page-title">Bom dia, {firstName}.</h1>
              <p>
                Contexto ativo: <strong>{navigation.company.name}</strong>
                {navigation.branch ? ` • ${navigation.branch.name}` : ""}.
              </p>
            </div>
            <div className="data-status">
              <span className="status-icon"><Check size={15} aria-hidden="true" /></span>
              <span>
                <strong>Identidade resolvida</strong>
                <small>Empresa e unidade obtidas do backend</small>
              </span>
            </div>
          </section>

          <FinancialWorkspace />

          <aside className="demo-banner" role="note">
            <Sparkles size={17} aria-hidden="true" />
            <span>
              <strong>Dados demonstrativos</strong>
              Os indicadores e análises abaixo ainda são exemplos visuais e não
              representam dados da empresa ativa.
            </span>
          </aside>

          <section className="metric-grid" aria-label={`Indicadores de ${current.label}`}>
            <article className="metric-card metric-primary">
              <div className="metric-label">
                <span>Receita líquida</span>
                <HelpButton term="receita" onOpen={setGlossaryTerm} />
              </div>
              <strong>{current.revenue}</strong>
              <div className="metric-meta positive">
                <ArrowUpRight size={16} aria-hidden="true" />
                <b>12,0%</b> sobre maio
              </div>
              <div className="mini-bars" aria-hidden="true">
                {[42, 48, 44, 61, 59, 76, 70, 88].map((height, index) => (
                  <span key={index} style={{ height: `${height}%` }} />
                ))}
              </div>
            </article>

            <article className="metric-card">
              <div className="metric-label">
                <span>Resultado líquido</span>
                <HelpButton term="resultado" onOpen={setGlossaryTerm} />
              </div>
              <strong>{current.result}</strong>
              <div className="metric-meta negative">
                <ArrowDownRight size={16} aria-hidden="true" />
                <b>18,0%</b> abaixo de maio
              </div>
              <p className="metric-foot">12,5% da receita permaneceu</p>
            </article>

            <article className="metric-card">
              <div className="metric-label">
                <span>Saldo em caixa</span>
                <HelpButton term="caixa" onOpen={setGlossaryTerm} />
              </div>
              <strong>{current.cash}</strong>
              <div className="metric-meta neutral">
                <Landmark size={15} aria-hidden="true" />
                Exemplo demonstrativo
              </div>
              <p className="metric-foot warning-text">Menor ponto projetado: R$ 192 mil</p>
            </article>

            <article className="metric-card metric-split">
              <div className="split-metric">
                <div className="metric-label"><span>A receber</span></div>
                <strong>{current.receive}</strong>
                <small className="positive-text">82% no prazo</small>
              </div>
              <div className="split-divider" />
              <div className="split-metric">
                <div className="metric-label"><span>A pagar</span></div>
                <strong>{current.payable}</strong>
                <small className="danger-text">R$ 74 mil em 7 dias</small>
              </div>
            </article>
          </section>

          <section className="decision-grid" aria-label="Análises prioritárias">
            <article className="insight-panel">
              <div className="insight-kicker">
                <span><Sparkles size={15} aria-hidden="true" /> O que mudou?</span>
                <small>Maio → Junho</small>
              </div>
              <div className="insight-content">
                <div>
                  <h2>O lucro caiu <em>18%</em>, mesmo com a receita em alta.</h2>
                  <p>
                    As despesas cresceram mais rápido que as vendas. Três grupos explicam 78% da variação.
                  </p>
                </div>
                <div className="change-score" aria-label="Resultado diminuiu de 190 para 156 mil reais">
                  <div className="change-value previous">
                    <span>Mai</span><strong>190 mil</strong>
                  </div>
                  <ArrowRight size={19} aria-hidden="true" />
                  <div className="change-value current">
                    <span>Jun</span><strong>156 mil</strong>
                  </div>
                </div>
              </div>

              <button
                className="text-button light-button"
                type="button"
                aria-expanded={driversOpen}
                onClick={() => setDriversOpen((value) => !value)}
              >
                {driversOpen ? "Ocultar causas" : "Entender por que mudou"}
                <ArrowRight size={16} aria-hidden="true" />
              </button>

              {driversOpen ? (
                <div
                  className="driver-breakdown"
                  aria-label="Causas demonstrativas"
                >
                  <div>
                    <span>Folha e encargos</span>
                    <div><i style={{ width: "86%" }} /></div>
                    <strong>+ R$ 32 mil</strong>
                  </div>
                  <div>
                    <span>Serviços terceirizados</span>
                    <div><i style={{ width: "61%" }} /></div>
                    <strong>+ R$ 18,5 mil</strong>
                  </div>
                  <div>
                    <span>Marketing</span>
                    <div><i style={{ width: "39%" }} /></div>
                    <strong>+ R$ 9,2 mil</strong>
                  </div>
                  <a href="#dre-explicada">Abrir composição na DRE <ArrowRight size={14} /></a>
                </div>
              ) : null}
            </article>

            <article className="health-panel" id="saude">
              <div className="section-heading compact-heading">
                <div>
                  <p className="eyebrow">Leitura consolidada</p>
                  <h2>Saúde da empresa</h2>
                </div>
                <a href="#pendencias">Ver detalhes</a>
              </div>
              <div className="health-list">
                {healthItems.map((item) => {
                  const Icon = item.icon;
                  return (
                    <a href="#pendencias" className="health-item" key={item.label}>
                      <span className={`health-icon ${item.tone}`}><Icon size={18} aria-hidden="true" /></span>
                      <span className="health-copy">
                        <strong>{item.label}</strong>
                        <small>{item.note}</small>
                      </span>
                      <span className={`status-pill ${item.tone}`}>{item.status}</span>
                      <ArrowRight size={16} aria-hidden="true" />
                    </a>
                  );
                })}
              </div>
              <p className="health-note">
                <CircleHelp size={14} aria-hidden="true" /> Classificações
                demonstrativas; ainda não representam diagnóstico operacional.
              </p>
            </article>
          </section>

          <section className="story-grid" aria-label="Fluxo de caixa e liquidez">
            <article className="cashflow-panel" id="fluxo-caixa">
              <div className="section-heading">
                <div>
                  <p className="eyebrow">Próximos 60 dias</p>
                  <h2>Teremos caixa para pagar?</h2>
                </div>
                <button className="secondary-button" type="button">
                  Ver fluxo completo <ArrowRight size={15} aria-hidden="true" />
                </button>
              </div>
              <div className="cashflow-summary">
                <div>
                  <span>Saldo projetado em 31/08</span>
                  <strong>R$ 318.400</strong>
                </div>
                <div className="coverage-badge">
                  <CheckCircle2 size={17} aria-hidden="true" />
                  <span><strong>1,34×</strong> cobertura</span>
                </div>
              </div>
              <div className="cash-chart" role="img" aria-label="Saldo projetado cai em julho e volta a crescer em agosto">
                <div className="chart-grid-lines"><span /><span /><span /><span /></div>
                <div className="chart-columns">
                  {[72, 66, 52, 39, 31, 44, 57, 63].map((height, index) => (
                    <div className="chart-column" key={index}>
                      <i style={{ height: `${height}%` }} className={index === 4 ? "low" : ""} />
                    </div>
                  ))}
                </div>
                <div className="chart-axis"><span>Hoje</span><span>Julho</span><span>Agosto</span></div>
              </div>
              <div className="chart-callout">
                <AlertTriangle size={16} aria-hidden="true" />
                <span><strong>18 de julho:</strong> menor saldo previsto, R$ 192.300.</span>
                <button type="button">Ver compromissos</button>
              </div>
            </article>

            <article className="liquidity-panel">
              <div className="section-heading compact-heading">
                <div>
                  <p className="eyebrow">Indicador explicado</p>
                  <h2>Liquidez corrente <HelpButton term="liquidez" onOpen={setGlossaryTerm} /></h2>
                </div>
              </div>
              <div className="liquidity-gauge">
                <div className="gauge-ring"><strong>1,43</strong><span>atual</span></div>
                <div>
                  <p>Para cada <strong>R$ 1,00</strong> a pagar no curto prazo, existem <strong>R$ 1,43</strong> registrados em recursos de curto prazo.</p>
                  <span className="context-tag"><Lightbulb size={14} /> Estável frente ao histórico</span>
                </div>
              </div>
              <div className="formula-card">
                <div><span>Ativo circulante</span><strong>R$ 1,86 mi</strong></div>
                <b>÷</b>
                <div><span>Passivo circulante</span><strong>R$ 1,30 mi</strong></div>
                <b>=</b>
                <div><span>Liquidez</span><strong>1,43</strong></div>
              </div>
              <button className="text-button" type="button" onClick={() => setGlossaryTerm("liquidez")}>
                Entender este indicador <ArrowRight size={15} aria-hidden="true" />
              </button>
            </article>
          </section>

          <section className="dre-section" id="dre-explicada" aria-labelledby="dre-title">
            <div className="section-heading dre-heading">
              <div>
                <p className="eyebrow">Relatório que ensina</p>
                <h2 id="dre-title">DRE explicada <HelpButton term="dre" onOpen={setGlossaryTerm} /></h2>
                <p>Veja como cada R$ 100 faturados se transforma em resultado.</p>
              </div>
              <div className="view-switch" aria-label="Modo de visualização">
                <button className="active" type="button">Explicado</button>
                <button type="button">Técnico</button>
              </div>
            </div>
            <div className="dre-layout">
              <div className="dre-waterfall">
                {dreLines.map((line) => (
                  <button className={`dre-line ${line.tone}`} type="button" key={line.label}>
                    <span className="dre-line-label">{line.label}</span>
                    <span className="dre-bar-track"><i style={{ width: `${line.width}%` }} /></span>
                    <strong>{line.value}</strong>
                    <ArrowRight size={15} aria-hidden="true" />
                  </button>
                ))}
              </div>
              <aside className="hundred-story">
                <span className="hundred-number">100</span>
                <p>De cada <strong>R$ 100</strong> faturados...</p>
                <div className="hundred-result">
                  <TrendingUp size={24} aria-hidden="true" />
                  <strong>R$ 12,50</strong>
                  <span>permaneceram como resultado líquido</span>
                </div>
                <div className="hundred-compare">
                  <TrendingDown size={16} aria-hidden="true" />
                  <span>Em maio eram <strong>R$ 17,10</strong></span>
                </div>
                <button type="button" onClick={() => setDriversOpen(true)}>
                  Por que diminuiu? <ArrowRight size={15} />
                </button>
              </aside>
            </div>
          </section>

          <section className="pending-section" id="pendencias" aria-labelledby="pending-title">
            <div className="section-heading">
              <div>
                <p className="eyebrow">Próximas ações</p>
                <h2 id="pending-title">O que precisa da sua atenção</h2>
              </div>
              <button className="secondary-button" type="button">Ver central de pendências</button>
            </div>
            <div className="pending-grid">
              {pendingItems.map((item) => (
                <article className="pending-card" key={item.title}>
                  <div className="pending-top">
                    <span className={`status-pill ${item.tone}`}>
                      {item.tone === "danger" ? <AlertTriangle size={13} /> : <Clock3 size={13} />}
                      {item.severity}
                    </span>
                    <small>{item.area}</small>
                  </div>
                  <h3>{item.title}</h3>
                  <div className="pending-meta">
                    <span><CalendarDays size={14} /> {item.due}</span>
                    <span><Users size={14} /> {item.owner}</span>
                  </div>
                  <button type="button">Ver origem e agir <ArrowRight size={15} /></button>
                </article>
              ))}
            </div>
          </section>

          <footer className="product-footer">
            <span><span className="brand-emblem small" aria-hidden="true" /> Mourão Clareza</span>
            <p>Dados demonstrativos • Nenhuma informação real é utilizada neste ambiente.</p>
            <span><ShieldCheck size={15} aria-hidden="true" /> Contexto obtido do backend</span>
          </footer>
        </main>
      </div>

      {glossaryTerm ? (
        <div className="drawer-layer" role="presentation" onMouseDown={() => setGlossaryTerm(null)}>
          <aside
            className="explanation-drawer"
            role="dialog"
            aria-modal="true"
            aria-labelledby="drawer-title"
            onMouseDown={(event) => event.stopPropagation()}
          >
            <div className="drawer-header">
              <span className="drawer-icon"><BookOpen size={20} aria-hidden="true" /></span>
              <div><small>Dicionário inteligente</small><span>Nível gerencial</span></div>
              <button
                ref={closeDrawerRef}
                className="icon-button"
                type="button"
                aria-label="Fechar explicação"
                onClick={() => setGlossaryTerm(null)}
              >
                <X size={20} aria-hidden="true" />
              </button>
            </div>
            <div className="drawer-body">
              <p className="eyebrow">Entenda em contexto</p>
              <h2 id="drawer-title">{glossary[glossaryTerm].title}</h2>
              <p className="drawer-lead">{glossary[glossaryTerm].short}</p>
              <div className="explanation-block highlighted">
                <span><Lightbulb size={16} /> Leitura gerencial</span>
                <p>{glossary[glossaryTerm].management}</p>
              </div>
              <div className="explanation-block">
                <span>Como foi calculado</span>
                <code>{glossary[glossaryTerm].formula}</code>
              </div>
              <div className="explanation-block">
                <span>Por que importa</span>
                <p>{glossary[glossaryTerm].why}</p>
              </div>
              <div className="explanation-block source-block">
                <span><FolderKanban size={15} /> Origem deste número</span>
                <p>{glossary[glossaryTerm].source}</p>
                <button type="button">Ver dados utilizados <ArrowRight size={14} /></button>
              </div>
              <div className="related-terms">
                <span>Termos relacionados</span>
                <div>{glossary[glossaryTerm].related.map((term) => <button type="button" key={term}>{term}</button>)}</div>
              </div>
            </div>
            <div className="drawer-footer"><CheckCircle2 size={15} /> Conteúdo demonstrativo</div>
          </aside>
        </div>
      ) : null}

      {searchOpen ? (
        <div className="search-layer" role="presentation" onMouseDown={() => setSearchOpen(false)}>
          <div className="search-dialog" role="dialog" aria-modal="true" aria-label="Busca global" onMouseDown={(event) => event.stopPropagation()}>
            <div className="search-input-row">
              <Search size={20} aria-hidden="true" />
              <input autoFocus aria-label="Buscar" placeholder="Busque por receita, DRE, pendência..." />
              <button type="button" onClick={() => setSearchOpen(false)} aria-label="Fechar busca"><X size={18} /></button>
            </div>
            <p>Atalhos demonstrativos</p>
            <div className="search-results">
              <a href="#dre-explicada" onClick={() => setSearchOpen(false)}><Scale size={18} /><span><strong>DRE explicada</strong><small>Relatório demonstrativo • Junho de 2026</small></span><ArrowRight size={16} /></a>
              <button type="button" onClick={() => { setSearchOpen(false); setGlossaryTerm("liquidez"); }}><BookOpen size={18} /><span><strong>Liquidez corrente</strong><small>Termo do glossário</small></span><ArrowRight size={16} /></button>
              <a href="#pendencias" onClick={() => setSearchOpen(false)}><AlertTriangle size={18} /><span><strong>Conciliação incompleta</strong><small>Pendência demonstrativa</small></span><ArrowRight size={16} /></a>
            </div>
          </div>
        </div>
      ) : null}
    </div>
  );
}
