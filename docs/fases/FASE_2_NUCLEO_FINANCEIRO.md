# FASE 2 — Núcleo financeiro operacional

**Atualizado em:** 29 de julho de 2026

**Estado da implementação:** aprovada na revisão adversarial, sem P0/P1

**Estado do gate da fase:** aprovado

**Produção:** não implantada

**Dados reais:** não utilizados

## Resumo executivo

A Fase 2 está implementada no worktree e cobre o fluxo operacional completo:

```text
cadastro financeiro
→ criação do título
→ baixa parcial
→ nova baixa
→ quitação
→ estorno
→ recomposição do saldo
→ histórico cronológico e auditoria
```

O código, a interface, os testes locais e a suíte PostgreSQL convencional foram
aprovados por revisão adversarial. Não existe P0, P1 ou bloqueador residual.

A evidência final combina PostgreSQL 16 real para a semântica específica da
migration 0006 com PostgreSQL 17 temporário para o caminho convencional por
Prisma/`pg`. A suíte final passou em 30/30 testes, sem falhas ou cancelamentos.
Como `AUTO_ADVANCE: true`, a Fase 3 foi ativada.

## Raiz e estado do Git

Raiz do software:

```text
C:\Users\leona\Documents\Codex\2026-07-24\files-mentioned-by-the-user-voc\outputs\Clareza-fase-0
```

Branch:

```text
master
```

Último commit aprovado:

```text
898f71c feat: complete Clareza phase one identity context
```

O conjunto da Fase 2 foi mantido sem commit até a aprovação integral do gate.
Este relatório registra a aprovação e integra o conjunto coerente preparado
para o commit de encerramento.

## Entregas implementadas

### Modelo e migrations

Foram criadas:

```text
prisma/migrations/0005_financial_operations/migration.sql
prisma/migrations/0006_phase_two_hardening/migration.sql
```

A migration 0005 introduz:

- contrapartes;
- categorias financeiras;
- centros de custo;
- contas financeiras;
- pagamentos;
- estornos de pagamentos;
- recebimentos;
- estornos de recebimentos;
- constraints compostas por tenant e empresa;
- RLS;
- eventos financeiros append-only;
- gatilhos de escopo;
- permissões da aplicação.

A migration 0006 endurece:

- visibilidade de catálogos compartilhados para membership de unidade;
- separação entre policy de leitura e policies de escrita de catálogos;
- compatibilidade de filial, contraparte, tipo e direção do catálogo;
- projeções automáticas de saldo, status e data de liquidação;
- bloqueio de alteração direta das colunas projetadas;
- bloqueio de exclusão direta de títulos pela role da aplicação;
- rejeição de baixa em título cancelado;
- rejeição de estorno anterior à baixa;
- rejeição de eventos financeiros futuros;
- rejeição de timestamps financeiros não finitos;
- preflight que aborta o upgrade se a base 0005 contiver evento futuro ou não
  finito que precise de correção.

### Serviços de domínio

Arquivos principais:

```text
server/financial-records.mjs
server/financial-catalogs.mjs
server/financial-events.mjs
```

O domínio implementa:

- criação e listagem de contas a pagar e receber;
- `OVERDUE` derivado no momento da leitura pelo dia financeiro de Manaus;
- criação e consulta de catálogos;
- baixa parcial e total;
- juros, multa e desconto explícitos;
- estorno integral de uma baixa;
- timeline unificada de `SETTLEMENT` e `REVERSAL`;
- recálculo transacional e verificação das projeções;
- idempotência com replay e conflito;
- proteção contra estouro monetário;
- rejeição de eventos futuros antes da abertura da transação;
- auditoria na mesma transação da operação;
- isolamento por tenant, empresa e unidade.

### APIs

Rotas operacionais disponíveis:

```text
GET  /api/v1/me

GET  /api/v1/financial/catalogs
POST /api/v1/financial/catalogs

GET  /api/v1/payables
POST /api/v1/payables
GET  /api/v1/payables/history
POST /api/v1/payables/settlements
POST /api/v1/payables/reversals

GET  /api/v1/receivables
POST /api/v1/receivables
GET  /api/v1/receivables/history
POST /api/v1/receivables/settlements
POST /api/v1/receivables/reversals
```

As coleções financeiras:

- exigem identidade e seleção autorizada de contexto;
- não aceitam tenant, empresa, unidade ou ator como autoridade vinda do JSON;
- exigem idempotência nas mutações;
- limitam o corpo a 64 KiB durante a leitura do stream, inclusive sem
  `Content-Length`;
- retornam erro 4xx para contexto malformado;
- usam strings decimais para valores monetários.

### Interface

Arquivo principal:

```text
app/financial-workspace.tsx
```

A Central de Comando possui uma área operacional separada dos indicadores
demonstrativos, com:

- alternância entre contas a pagar e contas a receber;
- listagem por contexto ativo;
- criação dos quatro tipos de cadastro financeiro;
- criação de título;
- baixa parcial ou total;
- juros, multa e desconto;
- saldo e status derivados;
- histórico cronológico;
- estorno;
- modo somente leitura;
- moeda correta em todos os itens da timeline;
- proteção contra resposta atrasada na troca de lista e de histórico;
- leitura de títulos independente da permissão ou disponibilidade do catálogo;
- diálogos com semântica acessível;
- tabela larga com rolagem local em tela pequena.

## Correções dos vetos adversariais

| Veto | Correção |
| --- | --- |
| `OVERDUE` persistido e obsoleto | Derivado em leitura usando o dia financeiro de Manaus |
| Estorno aninhado e fora da ordem real | Timeline contém baixa e estorno como eventos separados |
| Catálogo incompatível protegido apenas na API | Gatilhos validam filial, kind e direction no banco |
| Catálogo compartilhado invisível para BRANCH | Policy de leitura específica permite compartilhado sem ampliar escrita |
| Projeção alterável diretamente | Banco recalcula e guarda saldo, status e data de liquidação |
| Evento futuro altera o saldo atual | API e quatro gatilhos SQL rejeitam timestamp futuro |

## Validação executada

### Gate local atual

Resultado mais recente:

```text
npm run lint       PASSOU
npm run typecheck  PASSOU
npm test           PASSOU
npm run build      PASSOU
npm run db:validate PASSOU
git diff --check   PASSOU
node --check       PASSOU
```

Testes locais:

```text
47/47 aprovados
```

Os 47 testes incluem testes locais próprios para:

- rejeição de evento futuro;
- aceitação de evento passado;
- geração segura do instante atual quando `occurredAt` é omitido;
- descarte do histórico A atrasado depois da seleção B;
- rejeição de histórico cujo título diverge do solicitado.

### Evidência PostgreSQL já obtida

Um PostgreSQL 16 descartável em modo single-user comprovou:

- versão final da 0006 aplicada sobre uma base 0001–0005;
- 26 instruções da 0006 aplicadas sem erro;
- preflight recusando base 0005 com evento futuro;
- rollback atômico do upgrade recusado;
- futuro e `-infinity` bloqueados diretamente nas quatro superfícies de evento;
- seed com 58 instruções sem erro;
- catálogo compartilhado visível para membership `BRANCH`;
- escrita de catálogo compartilhado bloqueada para membership `BRANCH`;
- 16 policies de catálogo;
- `DELETE` de payable/receivable revogado da role runtime;
- alteração direta de projeção bloqueada;
- pagamento recalculando saldo e status;
- estorno restaurando saldo e status;
- referência cross-branch rejeitada;
- tipo de catálogo incompatível rejeitado;
- estorno anterior à baixa rejeitado;
- baixa em título cancelado rejeitada.

O fluxo anterior da Fase 2 também havia aprovado 28/28 testes PostgreSQL para a
base 0001–0005.

Em 29 de julho de 2026, um PostgreSQL Neon 17 temporário recebeu as migrations
0001–0006 e os dois seeds em banco vazio. Após associar o login temporário à
role `clarity_app`, o comando oficial aprovou:

```text
npm run test:integration
30 testes
30 aprovados
0 falhas
0 cancelamentos
```

A prova PostgreSQL 16 e a prova convencional PostgreSQL 17 são complementares:
a primeira valida a versão-alvo e o SQL final; a segunda valida Prisma, driver
`pg`, APIs, pool, RLS, concorrência, rollback e auditoria.

### Validação visual

A interface foi inspecionada em navegador local:

- desktop `1280 × 900`: área e modal centralizados, sem overflow horizontal;
- mobile `390 × 844`: documento com largura correta de 390 px;
- tabela com rolagem apenas no contêiner local;
- modal móvel sem ultrapassar a viewport;
- semântica `role="dialog"` e `aria-modal="true"` confirmada;
- rota temporária de preview removida;
- servidor temporário encerrado;
- nenhuma porta de preview deixada aberta.

### Revisão adversarial

O primeiro passe encontrou o P1 de eventos futuros. Depois da correção, o
segundo passe concluiu:

```text
VEREDITO: APROVADO
P0 aberto: 0
P1 aberto: 0
```

A revisão final também avaliou os ajustes de portabilidade do gate e concluiu:

```text
VEREDITO: APROVADO
P0 aberto: 0
P1 aberto: 0
bloqueador residual: 0
```

## Problemas encontrados durante o gate final

1. O teste da Fase 0 exigia literalmente o nome local `clarity_mvp`.
2. O login temporário ainda não era membro de `clarity_app`.
3. Um teste antigo esperava executar `DELETE`, embora a 0006 revogue o
   privilégio da role runtime.
4. O limite fixo de 20 segundos era insuficiente para um banco remoto com
   latência por operação.

## Correções realizadas

- o banco esperado passou a ser derivado de `DIRECT_DATABASE_URL`;
- o login descartável recebeu somente a associação necessária a `clarity_app`;
- o teste de exclusão passou a esperar `42501`;
- `INTEGRATION_TEST_TIMEOUT_MS` tornou o limite configurável, preservando o piso
  local de 20 segundos;
- a suíte completa foi repetida até obter 30/30, sem cancelamento.

## Pendências

Não há P0, P1 ou P2 conhecido na Fase 2.

Continuam fora do escopo desta fase:

- contas bancárias e movimentações;
- OFX/CSV;
- conciliação;
- motor contábil;
- indicadores reais;
- fiscal;
- forecast;
- automações e IA;
- hardening de produção.

## Limitação de infraestrutura registrada

O PostgreSQL portátil local continuou impedido de abrir TCP no host Windows, e
o driver Node não conseguiu usar o socket de arquivo. Essa limitação foi
contornada com um banco Neon descartável, sem alterar infraestrutura de
produção e sem usar dados reais.

O serviço temporário era PostgreSQL 17. Por isso, ele foi usado para provar o
caminho convencional Prisma/`pg`, enquanto o PostgreSQL 16 local permaneceu
como prova da semântica específica da versão-alvo.

## Como continuar

A Fase 2 deve ser versionada como um conjunto coerente. Em seguida, conforme
`AUTO_ADVANCE: true`, a Fase 3 inicia com:

```text
contas bancárias
→ movimentações importadas
→ deduplicação
→ conciliação reversível
→ trilha auditável
```

Não usar dados reais e não iniciar o motor contábil da Fase 4 antes da decisão
humana exigida pelo roadmap.

## Veredito

> **FASE 2 APROVADA.**
>
> Gate local 47/47, gate PostgreSQL convencional 30/30, PostgreSQL 16 direto
> aprovado e revisão adversarial sem P0/P1. A Fase 3 está autorizada a iniciar.
