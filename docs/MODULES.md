# Módulos

## Legenda

- **Protótipo:** existe uma experiência local demonstrável com dados fictícios.
- **Fundação:** há conceitos, contratos ou artefatos iniciais, sem fluxo operacional completo.
- **Planejado:** ainda depende de descoberta e implementação.

## Plataforma e experiência

| Módulo | Responsabilidade | Estado |
| --- | --- | --- |
| Central de Comando | KPIs, perguntas executivas, tendências, caixa projetado, liquidez e atalhos de análise | Protótipo |
| Saúde empresarial | Rótulos e contagens ilustrativos com atalho de investigação; critérios e motor ainda planejados | Protótipo visual |
| Explicação contextual | Drawer e conteúdo didático associado a termos/indicadores | Protótipo |
| Pendências | Prioridade, prazo, responsável, origem e ação sugerida | Protótipo visual |
| Navegação e busca | Shell, sidebar por âncoras, busca e seleção local de empresa/período | Protótipo visual |
| Identidade e sessão | Autenticação e revogação de sessão | Planejado |
| Tenants e empresas | Organização, unidades e contexto ativo | Fundação/planejado |
| Usuários, papéis e permissões | RBAC granular e escopo por empresa/unidade | Fundação/planejado |
| Notificações | Preferências e entrega de eventos acionáveis | Planejado |

## Domínios empresariais

| Módulo | Primeiro recorte de valor | Estado |
| --- | --- | --- |
| Financeiro | pagar, receber e fluxo de caixa | Planejado |
| Contabilidade | plano de contas, lançamentos e balancete | Planejado |
| DRE | composição, comparação e explicação rastreável | Protótipo visual; cálculo planejado |
| Balanço patrimonial | composição e drill-down | Planejado |
| Controladoria | centros de custo, orçamento, forecast e desvios | Planejado |
| Fiscal e tributário | obrigações, apurações e regularidade | Planejado, sujeito a especialistas |
| Trabalhista/DP | eventos e obrigações essenciais | Planejado, sujeito a especialistas |
| Prestadores e contratos | vínculo, documentos, retenções e riscos | Planejado |
| Societário | empresas, sócios, licenças e certidões | Planejado |

## Garantia, consultoria e conhecimento

| Módulo | Primeiro recorte de valor | Estado |
| --- | --- | --- |
| Auditoria | programa, testes, evidências, achados e recomendações | Planejado |
| Riscos | registro, matriz, criticidade e tratamento | Planejado |
| Planos de ação | responsável, prazo, tarefas e acompanhamento | Planejado |
| Diagnósticos | coleta, achados, recomendações e evolução | Planejado |
| Glossário | termos, níveis de explicação, relações e revisão editorial | Protótipo estático; gestão planejada |
| Insights | explicações determinísticas e rastreáveis | Planejado |
| “Pergunte aos números” | respostas fundamentadas nos dados autorizados | Futuro |

## Capacidades transversais

- documentos e evidências;
- importações validadas e idempotentes;
- pesquisa e command palette;
- relatórios e exportações;
- auditoria de ações;
- privacidade, retenção e atendimento a titulares;
- observabilidade e suporte operacional.

Essas capacidades não devem virar “módulos vazios”. Elas serão introduzidas quando um caso de uso vertical precisar delas.

## Regra de integração

Um módulo não consulta diretamente tabelas internas de outro módulo. Ele usa um caso de uso, uma visão de leitura ou um evento com contrato explícito. No monólito, essa disciplina preserva a possibilidade de evolução sem impor comunicação distribuída prematura.

## Ordem sugerida

1. identidade, tenant, empresa, unidade e permissões;
2. persistência e auditoria transversal;
3. pagar/receber e fluxo de caixa;
4. plano de contas, lançamentos e balancete;
5. DRE e balanço com drill-down;
6. glossário gerenciável, pendências e planos de ação;
7. importações, riscos, auditoria e diagnósticos;
8. controladoria, integrações e inteligência assistida.

Os critérios de passagem estão em [Roadmap](ROADMAP.md).

## Baseline de requisitos

### Funcionais prioritários

- autenticar, resolver membership e selecionar apenas contextos autorizados;
- administrar empresas, unidades, usuários, papéis e permissões;
- registrar pagar/receber e lançamentos com origem e histórico;
- produzir DRE, balanço, fluxo de caixa e comparações rastreáveis;
- explicar termos/indicadores e navegar até registros de origem;
- gerir pendências, riscos, achados e planos de ação;
- importar somente após preview, mapeamento e validação.

### Não funcionais obrigatórios

- isolamento multi-tenant e menor privilégio;
- integridade transacional e precisão decimal;
- segurança/LGPD por padrão;
- acessibilidade WCAG 2.2 AA como alvo;
- responsividade para desktop, notebook e tablet, com consulta móvel razoável;
- observabilidade sem exposição de dados e recuperação testável;
- desempenho medido por fluxos críticos, sem meta arbitrária nesta fase.

### Natureza das regras

| Natureza | Exemplos | Tratamento |
| --- | --- | --- |
| inegociável | tenant derivado da sessão; lançamento postado balanceado; origem do cálculo | invariantes e testes |
| configurável | papéis, limites, responsáveis, categorias, nível de explicação | configuração versionada e auditada |
| incerta | “Super Admin”, herança por unidade, critérios setoriais, regras tributárias e retenções | descoberta, ADR e validação profissional antes de implementar |
