# Design system

## Propósito

O design system do Clareza deve criar consistência, acessibilidade e velocidade sem esconder regras de negócio em componentes visuais. O protótipo contém padrões de interface reutilizados, mas ainda não constitui uma biblioteca publicada ou estável.

## Fundação visual atual

O protótipo usa Manrope para interface, Newsreader para ênfase editorial e variáveis CSS locais como `--canvas`, `--surface`, `--ink`, `--brand`, `--success`, `--warning` e `--danger`. Verde profundo, superfícies claras e acentos lima/coral formam a paleta de trabalho. Esses valores são implementados em `app/globals.css`, mas nome definitivo, tema escuro, testes sistemáticos de contraste e pacote de componentes permanecem pendentes.

## Princípios

1. clareza antes de decoração;
2. hierarquia por espaço, tipografia e contraste;
3. densidade progressiva;
4. significado nunca dependente apenas de cor;
5. estados completos: carregamento, vazio, erro, sucesso, desabilitado e sem permissão;
6. composição sobre variantes ilimitadas;
7. componentes de domínio exibem origem e contexto, não só valor.

## Tokens semânticos-alvo

Na evolução, tokens devem expressar papel, não cor literal:

```text
color.background
color.surface / surface-raised
color.text / text-muted / text-inverse
color.border / border-strong
color.brand
color.info / positive / attention / critical
space.1 … space.8
radius.small / medium / large
shadow.low / medium
type.body / label / title / display / numeric
motion.fast / normal
```

Temas futuros alteram valores, preservando significado e contraste. Tokens de risco não devem ser usados para decorar conteúdo neutro.

## Tipografia e números

- corpo confortável em telas densas e aumento de zoom;
- títulos seguem uma hierarquia sem pular níveis no HTML;
- valores tabulares usam alinhamento e numerais apropriados;
- `R$`, separadores e casas decimais seguem locale e tipo do dado;
- negativo usa sinal e texto/contexto, não apenas vermelho;
- percentuais distinguem variação percentual de ponto percentual.

## Catálogo-alvo

### Primitivos

`Button`, `IconButton`, `Input`, `Select`, `Combobox`, `DatePicker`, `DateRangePicker`, `Checkbox`, `Radio`, `Switch`, `Textarea`, `Link`.

### Estrutura e feedback

`Card`, `Tabs`, `Accordion`, `Tooltip`, `Popover`, `Dialog`, `Drawer`, `Breadcrumb`, `Alert`, `Badge`, `ProgressBar`, `Skeleton`, `EmptyState`, `ErrorState`, `Toast`, `ConfirmationDialog`.

### Dados

`Table`, `DataTable`, `MetricCard`, `ChartCard`, `Comparison`, `Timeline`, `FilterBar`, `Pagination`, `DataFreshness`.

### Domínio

`GlossaryTerm`, `HelpTooltip`, `ExplanationDrawer`, `DrillDownBreadcrumb`, `InsightCard`, `RiskCard`, `StatusBadge`, `PendingItem`, `Money`, `AccountingPeriod`, `SourceLink`.

O catálogo é backlog. Um nome nesta lista não significa componente implementado.

## Contratos de componentes de domínio

### MetricCard

Deve aceitar valor formatado, período, comparação, estado de atualização, descrição acessível e ação de drill-down. Não calcula o KPI internamente.

### StatusBadge

Combina texto, ícone e cor. O status vem de uma regra de domínio explícita; o componente não decide criticidade.

### GlossaryTerm

É acionável por teclado e toque, preserva semântica do texto e abre explicação associada por id estável. Tooltip não pode conter a única versão do conteúdo.

### ChartCard

Exige pergunta/título, período, legenda compreensível, resumo textual e alternativa acessível. Gráfico sem pergunta de negócio não entra no produto.

## Interação

- ação primária única por contexto sempre que possível;
- ações destrutivas informam objeto e consequência;
- confirmação é reservada a impacto relevante, não a toda ação;
- carregamento preserva layout e identifica progresso;
- atualização otimista só é usada quando reversão e conflito estão resolvidos;
- atalhos possuem equivalente visível;
- áreas clicáveis não escondem controles internos incompatíveis.

## Acessibilidade como API

Todo componente documenta:

- elemento semântico e nome acessível;
- ordem e comportamento de teclado;
- gerenciamento de foco;
- mensagens anunciadas;
- contraste em todos os estados;
- comportamento com zoom, reflow e redução de movimento.

Testes automatizados ajudam, mas não substituem teclado, leitor de tela e avaliação visual.

## Governança

1. provar recorrência antes de extrair componente;
2. documentar problema, anatomia, estados e exemplos;
3. testar acessibilidade e responsividade;
4. versionar mudanças incompatíveis;
5. medir adoção e remover duplicações;
6. manter regra contábil fora da camada visual.

Nome definitivo, paleta de marca e tipografia proprietária permanecem decisões pendentes. “Clareza” é codinome.
