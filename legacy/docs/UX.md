# Estratégia de UX

## Norte do produto

**Menos complexidade. Mais clareza.** O Clareza não deve apenas mostrar o número: deve ajudar a entender o que é, de onde veio, por que importa e qual investigação ou ação pode ser necessária.

O protótipo atual valida essa direção na Central de Comando. Ele não valida ainda a precisão dos cálculos, a utilidade com dados reais nem a eficiência de tarefas operacionais.

As superfícies atuais incluem KPIs, “O que mudou?”, saúde por área, caixa projetado em 60 dias, liquidez explicada, DRE explicada, pendências, busca e drawer do glossário. Busca e seletores demonstram interação local; não representam consulta autorizada a dados persistidos.

## Personas iniciais

| Persona | Necessidade principal | Profundidade esperada |
| --- | --- | --- |
| empresário/sócio | entender situação e prioridades sem jargão | essencial |
| gestor/diretor | comparar desempenho e agir sobre desvios | gerencial |
| financeiro | executar e conciliar com precisão | operacional/técnica |
| CFO/controller | investigar origem, margem, caixa e forecast | gerencial/técnica |
| contador/fiscal/DP | validar regras, saldos e obrigações | técnica |
| auditor/consultor | evidenciar problema, risco e tratamento | técnica e rastreável |
| somente leitura | acompanhar sem alterar | configurável |

Uma pessoa pode exercer mais de um papel; a interface deve responder à permissão e ao contexto, não apenas ao cargo.

## Perguntas que acompanham um dado

Quando relevante, toda apresentação deve responder progressivamente:

1. O que é e qual o valor/período?
2. O que significa em linguagem adequada ao usuário?
3. Como foi calculado e quais dados foram usados?
4. Como evoluiu e o que explica a mudança?
5. Existe divergência, risco ou incerteza?
6. Qual é a origem e até onde é possível investigar?
7. Qual ação é possível e quem a acompanha?

Nem toda resposta cabe no primeiro nível. Progressive disclosure evita transformar clareza em densidade.

## Arquitetura de informação

```text
Central de Comando
├─ visão executiva e perguntas-chave
├─ saúde por área
├─ o que mudou
├─ pendências e prioridades
└─ atalhos para análise

Domínios futuros
├─ financeiro
├─ contabilidade
├─ controladoria
├─ riscos/auditoria
├─ diagnósticos
└─ conhecimento/glossário
```

O contexto ativo de empresa, unidade, período e moeda deve permanecer visível. Trocar contexto requer feedback explícito e jamais amplia acesso.

## Padrões de investigação

### Drill-down

```text
resumo → indicador → explicação → composição → conta
→ lançamento → documento de origem
```

Cada nível mantém breadcrumbs, filtros e período. Se a origem não estiver disponível, a interface informa a limitação; não simula rastreabilidade.

### “O que mudou?”

- mostra base e período comparados;
- separa variação absoluta e percentual;
- destaca contribuintes, não causalidade não comprovada;
- oferece “Ver origem” quando há registros;
- trata base zero e dados incompletos sem percentuais enganosos.

### “Isso merece atenção?”

Usa critério visível, histórico e contexto. Evita “bom/ruim” universal. Cor, ícone, rótulo e explicação trabalham juntos.

### Explicação contextual

Tooltip oferece ajuda curta; drawer mantém o usuário no contexto e oferece definição, exemplo, fórmula, interpretação, riscos e termos relacionados. Modal só é usado quando a decisão exige foco.

## Conteúdo e confiança

- frases curtas e verbos de ação;
- rótulos que dizem o resultado da ação: “Ver composição”, não “Saiba mais”;
- números com unidade, moeda, período e atualização;
- estados vazios explicam como chegar ao primeiro resultado;
- erro informa o que ocorreu, o que foi preservado e como tentar novamente;
- conteúdo fictício do protótipo permanece identificável;
- recomendação não é ordem e explicação não é parecer profissional.

## Acessibilidade

Alvo: WCAG 2.2 nível AA.

- navegação e operação completas por teclado;
- foco visível e ordem coerente;
- landmarks, títulos e labels semânticos;
- nomes acessíveis para botões de ícone;
- painéis laterais/modais com foco contido e devolvido ao acionador;
- gráficos acompanhados de resumo e alternativa tabular;
- status nunca indicado apenas por cor;
- mensagens dinâmicas anunciadas com parcimônia;
- zoom e reflow sem perda funcional;
- animações respeitam `prefers-reduced-motion`.

## Responsividade

- desktop/notebook: densidade gerencial e comparação lado a lado;
- tablet: cards reordenados, navegação compacta e alvos de toque;
- celular: experiência razoável para consulta e ações prioritárias; tabelas complexas usam cartões, rolagem identificada ou visão resumida.

Nenhuma informação crítica desaparece no mobile; ela muda de ordem ou nível de detalhe.

## Pesquisa e métricas

Antes de expandir módulos, testar com representantes das personas:

- tempo para explicar “por que o lucro mudou”;
- taxa de conclusão ao localizar a origem de um indicador;
- compreensão da diferença entre lucro e caixa;
- erros ao trocar empresa/período;
- uso e utilidade do glossário;
- acessibilidade por teclado e leitor de tela;
- confiança calibrada: usuário reconhece limites e dados desatualizados.

Métrica de engajamento isolada não prova compreensão. Sucesso combina tarefa concluída, resposta correta, tempo, confiança e ausência de erro de escopo.
