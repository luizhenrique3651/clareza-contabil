# Glossário e dicionário contextual

## Visão

O glossário é uma capacidade de produto, não apenas esta página. Um termo técnico deve ser explicado no contexto em que aparece, sem obrigar o usuário a abandonar a tarefa.

No incremento atual, o drawer contextual demonstra a interação com conteúdo estático. A busca global oferece um atalho fixo para um termo; ainda não pesquisa uma base persistida. Gestão editorial, busca real, relações, preferências de nível e conteúdo persistido são etapas futuras. A fundação de dados inclui `GlossaryTerm`, mas não está conectada à UI.

## Níveis de explicação

- **Essencial:** linguagem direta para quem não tem formação técnica.
- **Gerencial:** relação com decisão, tendência e desempenho.
- **Técnico:** definição formal, fórmula, critérios e limitações.

O valor e a regra não mudam entre níveis; muda a forma de explicação. Simplificar nunca autoriza alterar o sentido.

## Estrutura editorial-alvo

Cada termo pode conter:

- nome, sigla, aliases e categoria;
- definição essencial, gerencial e técnica;
- exemplo prático e exemplo numérico;
- fórmula, unidade e premissas;
- por que importa e onde aparece;
- como interpretar e limitações;
- erros comuns e riscos relacionados;
- termos relacionados;
- fontes, jurisdição e vigência quando aplicável;
- autor, revisor, status e data da última revisão.

Conteúdo fiscal, tributário e trabalhista deve indicar escopo legal e revisão profissional; uma definição sem vigência não deve orientar decisão operacional.

## Interação

```text
termo no contexto
→ dica curta
→ drawer explicativo
→ termos/fórmula relacionados
→ origem do valor ou relatório
```

- hover nunca é o único meio de acesso;
- o acionador funciona por teclado e toque;
- a definição curta não interrompe a leitura;
- o drawer preserva contexto e devolve foco ao acionador;
- links relacionados não criam ciclos de navegação confusos;
- o usuário pode reportar conteúdo desatualizado.

## Governança

Fluxo proposto:

```text
rascunho → revisão de domínio → revisão de linguagem/acessibilidade
→ publicado → revisão periódica → arquivado/substituído
```

Mudanças relevantes mantêm histórico. Termos usados em explicações já emitidas precisam de versão rastreável.

## Glossário do produto

| Termo | Definição usada no Clareza |
| --- | --- |
| Tenant | fronteira principal de isolamento de um cliente/organização na plataforma |
| Empresa | entidade gerenciada dentro de um tenant; pode possuir unidades |
| Unidade | filial, estabelecimento ou recorte operacional autorizado de uma empresa |
| Membership | vínculo de um usuário com tenant e escopos permitidos |
| RBAC | autorização baseada em papéis compostos por permissões granulares |
| Contexto ativo | combinação autorizada de empresa, unidade, período e preferências da análise |
| Competência | período econômico ao qual receita, custo ou despesa pertence |
| Caixa | disponibilidade e movimentação financeira; não é sinônimo de resultado |
| DRE | demonstração que organiza receitas, custos e despesas até o resultado do período |
| Balanço patrimonial | posição de ativos, passivos e patrimônio líquido em uma data |
| Balancete | relação de contas e saldos usada para conferência e análise contábil |
| Drill-down | navegação de um resumo até os registros e documentos que o compõem |
| Pendência | item que requer informação, validação ou ação, com origem e responsável |
| Risco | possibilidade de evento afetar objetivos, avaliada por critérios explícitos |
| Achado | condição identificada e suportada por critério e evidência |
| Insight | interpretação derivada de dados e regra identificáveis; não é fato sem suporte |
| RLS | política do banco que restringe linhas pelo contexto de acesso |
| Seed | conjunto sintético usado para desenvolvimento e teste, nunca cópia de cliente |

## Exemplo editorial resumido

### Ativo circulante

**Essencial:** recursos que a empresa possui ou espera transformar em dinheiro no curto prazo.

**Por que importa:** ajuda a entender a capacidade de sustentar a operação e cumprir obrigações próximas, mas não deve ser analisado isoladamente.

**Onde aparece:** balanço patrimonial.

**Relacionados:** passivo circulante, liquidez corrente e capital de giro.

Uma publicação real ainda precisará definir o critério de “curto prazo”, exemplos, fontes e revisão técnica aplicáveis.
