# Estado atual do Clareza

> Auditoria técnica inicial realizada em 24/07/2026 sobre o repositório
> recuperado. Este documento descreve o que existe no código, e não apenas o
> que aparece na interface ou está proposto na documentação.

## Resumo executivo

O repositório é uma fundação legítima e executável, não um projeto vazio. A
Central de Comando possui uma identidade visual consistente, é responsiva e tem
boas preocupações iniciais de acessibilidade. A fundação de banco também é mais
avançada que a interface: há um schema Prisma conceitual, uma migration SQL com
constraints, RLS e proteção de lançamentos contábeis, além de um seed sintético
com dois tenants.

O produto ainda é um protótipo visual, mas a primeira fatia de backend já foi
implementada. A única página não usa API nem banco; seus valores, empresas,
usuário, indicadores, DRE, caixa, riscos, pendências, busca e glossário continuam
definidos em constantes dentro do componente React. Em paralelo, o repositório
agora possui Prisma Client, APIs `/api/v1`, resolução de identidade e membership,
RBAC aplicado, contexto transacional de tenant/empresa/unidade, auditoria e
idempotência para a criação de contas a pagar e receber.

O caminho correto é preservar a experiência atual e substituir os mocks por
fatias verticais reais, começando pela fundação de identidade/tenant e por
contas a pagar e receber. Não há justificativa para recriar o frontend ou trocar
o monólito modular. Não existe nem deve ser criada dependência do Conta Azul.

## Evidências da linha de base

Em 24/07/2026, antes de mudanças funcionais:

- `npm run typecheck`: aprovado;
- `npm run lint`: aprovado;
- `npm test`: aprovado, incluindo build;
- build vinext/Vite: aprovado;
- testes existentes: 8 aprovados, 0 falhas;
- Docker Compose: configuração válida;
- PostgreSQL: não validado nesta máquina porque o Docker Engine não estava em
  execução;
- Prisma CLI e `@prisma/client`: ausentes do manifesto e do lockfile.

Após o primeiro bloco de continuação:

- `prisma validate` e `prisma generate`: aprovados com Prisma 7.8.0;
- `npm run typecheck`: aprovado;
- `npm run lint`: aprovado;
- `npm test`: 21 testes aprovados, 0 falhas, incluindo build;
- `npm run build`: aprovado, com `/`, `/api/v1/me`, `/api/v1/payables` e
  `/api/v1/receivables`;
- PostgreSQL/RLS real: ainda não validado porque o Docker Engine local não ficou
  disponível.

## Stack encontrada

| Camada | Tecnologia encontrada | Estado |
| --- | --- | --- |
| Interface | React 19.2.8, TypeScript 5.9.3 | Executável |
| Roteamento/renderização | Next.js 16.2.11 via vinext 0.0.50 | Executável, com compatibilidade a observar |
| Build | Vite 8.1.5 | Executável |
| Runtime web | Cloudflare Worker/Sites | Build preparado |
| Ícones | lucide-react | Em uso |
| Estilos | CSS próprio e import do Tailwind CSS | Em uso |
| Banco-alvo | PostgreSQL 16 | Definido, não conectado |
| ORM-alvo | Prisma 7.8.0 + adapter PostgreSQL | Runtime integrado; banco não validado |
| Scaffold legado | D1, Drizzle ORM e Drizzle Kit | Não usado pelo produto |
| Testes | `node:test` | Cobertura inicial e sem banco real |

## Arquitetura real

### Em execução hoje

```text
Navegador
  → app/page.tsx
  → app/command-center.tsx
  → constantes e estado local do React
  → HTML/CSS gerado por vinext/Vite
```

Existe uma única superfície visual em `/`, ainda demonstrativa. Há rotas de API
e casos de uso financeiros no servidor, porém a página ainda não os consome.

### Arquitetura pretendida e coerente com o repositório

```text
rota/página
  → API /api/v1
  → identidade verificada + membership + RBAC
  → caso de uso
  → transação com contexto de tenant
  → Prisma
  → PostgreSQL com constraints e RLS
```

O monólito modular documentado continua adequado. As fronteiras de apresentação,
aplicação, domínio e infraestrutura ainda são majoritariamente uma intenção; o
código atual concentra a apresentação em um componente grande e possui apenas
um helper isolado de contexto de tenant.

## Estrutura do projeto

| Caminho | Conteúdo real |
| --- | --- |
| `app/` | página raiz, Central de Comando, identidade do Sites, APIs e CSS |
| `server/` | Prisma, identidade/membership, tenant/RBAC e casos de uso financeiros |
| `prisma/` | schema, três migrations SQL e seed sintético |
| `db/` | scaffold D1/Drizzle vazio; não é a persistência do Clareza |
| `worker/` | entrada Cloudflare/vinext e otimização de imagens |
| `tests/` | renderização, APIs, identidade, RBAC, idempotência e isolamento |
| `docs/` | arquitetura, segurança, banco, LGPD, UX e roadmap |
| `examples/` | exemplo D1 de notas, fora do produto |
| `build/` | plugin de empacotamento do Sites |

Diretórios `dist/`, `.vinext/`, `.wrangler/` e `node_modules/` são artefatos
gerados e não constituem código-fonte.

## Banco de dados

PostgreSQL foi escolhido corretamente para integridade relacional, precisão
decimal, transações e RLS. O Compose declara PostgreSQL 16 com credenciais
exclusivamente locais. O Prisma Client e o adapter `pg` estão integrados no
servidor; sem `DATABASE_URL`, as APIs falham de forma explícita e segura. A
interface ainda não abre conexão nem consome as APIs.

O scaffold D1/Drizzle está desativado em `.openai/hosting.json` e o schema D1 é
vazio. `examples/d1` é apenas exemplo legado. Os scripts oficiais de banco agora
apontam para Prisma; os artefatos legados permanecem no repositório recuperado
para evitar remoção destrutiva sem histórico de versão.

### Schema atual

Modelos presentes:

- acesso e organização: `Tenant`, `Company`, `Branch`, `User`, `Role`,
  `Permission`, `RolePermission`, `Membership`;
- financeiro: `Payable`, `Receivable`;
- contabilidade: `JournalEntry`, `JournalEntryLine`;
- conhecimento e gestão: `GlossaryTerm`, `PendingItem`, `Risk`, `ActionPlan`;
- identidade e rastreabilidade: `AuthIdentity`, `IdempotencyRecord` e
  `AuditLog`.

Enums presentes cobrem status de tenant/empresa, escopo de membership, status
financeiro, status de lançamento, categorias do glossário, prioridades,
trabalho, risco e plano de ação.

Valores monetários usam `Decimal(19,4)`, o que é adequado como fundação. A
idempotência de comandos financeiros já possui armazenamento próprio. Faltam
entidades de pagamentos, recebimentos, contraparte, categoria, centro de custo,
conta financeira e eventos financeiros.

### Migrations existentes

Existem três migrations manuais:

- `prisma/migrations/0001_init/migration.sql`.
- `prisma/migrations/0002_identity_bootstrap_and_branch_rls/migration.sql`;
- `prisma/migrations/0003_financial_api_foundation/migration.sql`.

A primeira cria extensão `pgcrypto`, role `clarity_app`, schema auxiliar `app`,
enums, tabelas, índices, constraints, triggers, funções de contexto, RLS forçada
e proteções de imutabilidade/equilíbrio contábil. A segunda adiciona o bootstrap
restrito de identidades e o escopo RLS por unidade. A terceira adiciona
idempotência persistente para comandos financeiros.

Pontos fortes:

- FKs compostas reduzem vínculos cruzados entre tenants;
- RLS falha fechada quando o contexto não existe;
- role de runtime não possui `BYPASSRLS`;
- lançamentos postados e logs de auditoria possuem proteção no banco;
- lançamentos postados precisam equilibrar débitos e créditos;
- seed contém dois tenants sintéticos, útil para testes negativos.

Lacunas e riscos:

- a migration ainda não foi aplicada e testada por esta continuação em
  PostgreSQL real;
- as migrations SQL ainda não foram aplicadas/testadas em PostgreSQL real;
- o owner ainda é usado na URL local de runtime antes de assumir a role;
- `User.passwordHash` é obrigatório embora não exista autenticação por senha;
- não existe sessão própria do produto nem mecanismo próprio de revogação;
- pagar/receber modelam a baixa como campos agregados, sem eventos próprios;
- o equilíbrio contábil foi desenhado, mas não há plano de contas ou períodos.

## Autenticação

`app/chatgpt-auth.ts` lê a identidade autenticada encaminhada pelo Sites. As APIs
usam esse e-mail para chamar a função SQL restrita
`app.resolve_identity_access($1)`, que resolve usuário, membership, empresas,
unidades e permissões. A seleção explícita de empresa/unidade é validada contra
esse contexto antes de qualquer acesso empresarial.

Não existem:

- login de produto conectado ao banco;
- sessão persistida, rotação ou revogação;
- MFA ou provedor de identidade decidido;
- auditoria de login, logout e negação.

A decisão do provedor para clientes externos permanece aberta. Até ela ser
tomada, a identidade encaminhada pelo hosting pode ser usada como adaptador de
autenticação em ambiente Sites, sempre seguida de membership e RBAC internos.

## Multiempresa e multitenancy

Existe uma boa fundação conceitual:

- `tenantId` é obrigatório nos dados empresariais;
- empresas e unidades possuem FKs compostas;
- `server/tenant-context.mjs` deriva o escopo de uma sessão já verificada;
- `AsyncLocalStorage` isola solicitações concorrentes;
- filtros e dados com tenant/empresa conflitantes são rejeitados;
- a transação configura `app.tenant_id`, `app.user_id`, `app.company_ids` e
  `app.tenant_wide_access`, além de `app.branch_ids`.

Ainda é parcial porque depende da identidade encaminhada pelo Sites e não há
sessão própria do produto. A resolução no banco e a RLS por unidade foram
implementadas, mas ainda precisam ser comprovadas contra PostgreSQL real.

## Usuários, RBAC e permissões

Modelos, constraints, seed e códigos de permissão existem. O servidor resolve as
permissões do escopo selecionado e as APIs financeiras chamam
`requirePermission`. Ainda não há interface de administração, CRUD de usuários,
convites, alteração de papéis ou sessão própria.

Estado: backend inicial funcional e testado em memória; administração e teste de
banco pendentes.

## RLS

RLS está implementada nas migrations para tenant, empresa e unidade. Ela usa
contexto local à transação e força políticas inclusive para o owner comum. O
contexto é enviado por parâmetros vinculados e o bootstrap de identidade não
concede `BYPASSRLS`. Não há teste de integração contra PostgreSQL nem prova
operacional do comportamento do pool após a transação.

Estado: implementação SQL promissora, validação operacional pendente.

## Auditoria

`AuditLog` possui contexto, ator, sessão, antes/depois, metadados, campos
redigidos e hash de integridade. A migration impede update/delete e restringe
inserção/leitura por RLS. A criação de contas a pagar/receber grava o evento de
auditoria na mesma transação do registro e da chave idempotente.

Ainda faltam auditoria de autenticação/negação, cobertura de todos os casos de
uso, consulta administrativa e teste de integração que detecte mutação sem
evento. A UI não registra ações.

## Módulos e páginas existentes

Página existente:

- `/`: Central de Comando.

Experiências presentes, todas demonstrativas:

- navegação, seleção de empresa e período;
- KPIs de receita, resultado, caixa, pagar e receber;
- “O que mudou?”;
- saúde empresarial;
- projeção de caixa;
- liquidez;
- DRE explicada;
- pendências;
- glossário;
- busca e notificações.

Não existem páginas operacionais de cadastros, pagar/receber, banco,
conciliação, contabilidade, fiscal, relatórios ou administração.

## APIs existentes

Existem:

- `GET /api/v1/me`: identidade, memberships e contextos autorizados;
- `GET|POST /api/v1/payables`: listagem e criação de contas a pagar;
- `GET|POST /api/v1/receivables`: listagem e criação de contas a receber.

As APIs exigem identidade do Sites, seleção explícita de empresa, permissionamento
interno e transação com contexto RLS. Escritas exigem `Idempotency-Key`, validam
o payload com Zod e derivam tenant, empresa, unidade e ator somente da sessão.
`examples/d1/app/api/notes/route.ts` continua sendo exemplo isolado do starter.

## Dados reais versus mocks

| Origem | Natureza | Uso atual |
| --- | --- | --- |
| constantes em `command-center.tsx` | mock local | toda a página |
| estado React | efêmero | empresa, período, drawers e popovers |
| `prisma/seed.sql` | dados sintéticos | preparado para banco local |
| PostgreSQL | runtime preparado, sem instância validada | APIs quando configurado |
| API | backend real inicial | ainda não consumido pela página |

Os rótulos “dados atualizados”, “96% conciliados”, “ambiente protegido” e
origens de indicadores são apenas texto visual; não comprovam atualização,
conciliação, autenticação, proteção ou rastreabilidade.

## Contas a pagar

Há modelo, tabela, constraints, índices, RLS e dois registros sintéticos. A API
inicial lista/cria registros com validação, RBAC, escopo autenticado, auditoria,
paginação e idempotência. Faltam contraparte estruturada, categoria, centro de
custo, conta financeira, parcelas, recorrência, pagamento parcial/total como
evento, estorno, anexos, histórico completo e UI operacional.

Estado: parcial.

## Contas a receber

Há modelo, tabela, constraints, índices, RLS e registros sintéticos. A API
inicial tem as mesmas garantias da API de contas a pagar. As lacunas de domínio
e UI são equivalentes, incluindo a ausência de `Receipt`.

Estado: parcial.

## Contabilidade

`JournalEntry` e `JournalEntryLine` possuem FKs compostas e proteções SQL para
imutabilidade, reversão e partidas dobradas. Faltam plano de contas, períodos,
fechamento, regras de origem, integração financeira, diário, razão, balancete,
DRE e balanço calculados.

Estado: fundação parcial.

## Dashboard, KPIs e DRE

O dashboard e a DRE possuem uma experiência visual forte, mas todos os valores
e explicações são estáticos. Não existe serviço de métricas, fórmula executada,
período consultado, origem persistida nem drill-down real.

Estado: protótipo visual; não operacional.

## Relatórios e exportações

Não existem relatórios calculados, filtros persistentes, exportações ou
permissões de exportação. A DRE visual não é relatório contábil operacional.

## Integrações

Não existem integrações bancárias, fiscais, contábeis ou com outros ERPs. Isso
é coerente com a fase atual. O Clareza não integra e não deve integrar o Conta
Azul. Futuras integrações fiscais devem usar infraestrutura oficial ou adapters
autorizados, preservando o domínio nativo do Clareza.

## Fiscal

Não existe módulo fiscal. O roadmap obrigatório deve incluir NFS-e nativa com
domínio próprio, credenciais somente no servidor, idempotência, adapters por
provedor/jurisdição, homologação/produção, eventos, XML/PDF, auditoria e vínculo
financeiro. NF-e permanece expansão posterior da mesma arquitetura.

## Testes

Existem:

- teste de renderização do HTML;
- verificações estáticas de acessibilidade e remoção do starter;
- teste em memória de contexto de tenant;
- teste de concorrência do `AsyncLocalStorage`;
- teste simulado do SQL que configura contexto RLS;
- testes das rotas de identidade e finanças;
- testes da resolução de identidade, memberships, empresas, unidades e permissões;
- testes de criação/listagem financeira, rejeição de escopo injetado, paginação,
  auditoria e idempotência.

Não existem testes:

- de banco PostgreSQL/RLS real;
- de sessão própria do produto;
- de cálculos financeiros;
- de pagar/receber contra PostgreSQL real;
- de pagamentos, estornos, transferências ou conciliação;
- contábeis ou fiscais;
- E2E de interface.

## Problemas técnicos e código incompleto

1. As migrations SQL não foram aplicadas/testadas em PostgreSQL real porque o
   Docker Engine local não ficou disponível.
2. D1/Drizzle e exemplos do starter aumentam ambiguidade, embora não sejam usados.
3. Não há sessão própria, rotação, revogação ou MFA do produto.
4. A única UI está concentrada em um componente cliente grande.
5. A Central de Comando ainda não consome as APIs reais.
6. O tipo do Worker declara bindings D1/Images que não correspondem integralmente
   à configuração de hosting atual.
7. Assets SVG remanescentes do starter não são usados; o favicon não segue a
   identidade do Clareza.
8. O build classifica APIs como dinâmicas/desconhecidas por uso de `headers()`;
    isso é esperado, mas deve ser acompanhado na compatibilidade vinext.
9. O repositório recuperado não possui commit inicial; todos os arquivos aparecem
    como não rastreados, elevando o risco de perda e dificultando comparação.
10. A auditoria de dependências ainda aponta alertas moderados na cadeia da CLI
    Prisma e alertas adicionais em ferramentas legadas/de desenvolvimento.

## Dependências ausentes

Prisma Client, Prisma CLI, adapter PostgreSQL, `pg`, tipos do driver e Zod foram
instalados. A dependência de autenticação própria permanece deliberadamente
adiada até a decisão do provedor/modelo de sessão. Não instalar bibliotecas para
módulos futuros antes de um caso de uso real.

O conjunto Prisma 7.8.0 foi mantido porque a atualização disponível reintroduzia
um alerta de severidade maior em dependência transitiva. Os alertas residuais
devem ser acompanhados e corrigidos sem `npm audit fix --force`.

## Riscos de regressão

- alterar o componente monolítico pode quebrar várias superfícies visuais de uma
  vez;
- testes de renderização dependem de textos e usuário fictício específicos;
- migrar o dashboard inteiro de uma vez criaria um corte amplo e difícil de
  validar;
- usar o owner do banco em runtime pode invalidar a defesa em profundidade;
- schema Prisma e SQL manual podem divergir;
- RLS sem teste real pode dar falsa sensação de isolamento;
- autenticação do hosting sem membership interna pode permitir identidade válida
  no tenant errado;
- substituir mocks sem estados de carregamento/erro/vazio pode degradar a UX;
- publicar o protótipo como operacional antes dos gates expõe risco de confiança.

## Matriz de funcionalidades

Legenda de prioridade: **P0** fundação bloqueante; **P1** primeiro fluxo
operacional; **P2** evolução financeira/contábil; **P3** posterior.

| Funcionalidade | Existe | Parcial | Não existe | Precisa corrigir | Prioridade |
| --- | :---: | :---: | :---: | :---: | --- |
| autenticação |  | X |  | X | P0 |
| sessões |  |  | X | X | P0 |
| empresas |  | X |  | X | P0 |
| unidades |  | X |  | X | P0 |
| usuários |  | X |  | X | P0 |
| permissões |  | X |  | X | P0 |
| auditoria |  | X |  | X | P0 |
| RLS por tenant |  | X |  | X | P0 |
| RLS por empresa |  | X |  | X | P0 |
| RLS por unidade |  | X |  | X | P0 |
| clientes |  |  | X | X | P1 |
| fornecedores |  |  | X | X | P1 |
| contrapartes unificadas |  |  | X | X | P1 |
| categorias financeiras |  |  | X | X | P1 |
| subcategorias |  |  | X | X | P1 |
| centros de custo |  |  | X | X | P1 |
| rateios |  |  | X | X | P2 |
| contas financeiras |  |  | X | X | P1 |
| contas bancárias |  |  | X | X | P1 |
| contas a pagar |  | X |  | X | P1 |
| contas a receber |  | X |  | X | P1 |
| pagamentos |  |  | X | X | P1 |
| recebimentos |  |  | X | X | P1 |
| pagamentos parciais |  |  | X | X | P1 |
| recebimentos parciais |  |  | X | X | P1 |
| parcelamento |  |  | X | X | P1 |
| recorrência |  |  | X | X | P2 |
| estornos |  |  | X | X | P1 |
| transferências |  |  | X | X | P2 |
| movimentações |  |  | X | X | P1 |
| importação OFX |  |  | X | X | P2 |
| importação CSV |  |  | X | X | P2 |
| detecção de duplicidade |  |  | X | X | P1 |
| conciliação bancária |  |  | X | X | P2 |
| conciliação 1:1, 1:N e N:1 |  |  | X | X | P2 |
| fluxo de caixa |  | X |  | X | P2 |
| aging/inadimplência |  |  | X | X | P2 |
| DRE |  | X |  | X | P2 |
| plano de contas |  |  | X | X | P2 |
| lançamentos contábeis |  | X |  | X | P2 |
| períodos contábeis |  |  | X | X | P2 |
| balancete |  |  | X | X | P2 |
| razão |  |  | X | X | P2 |
| diário |  |  | X | X | P2 |
| balanço patrimonial |  |  | X | X | P2 |
| dashboard |  | X |  | X | P2 |
| KPIs |  | X |  | X | P2 |
| drill-down |  | X |  | X | P2 |
| relatórios |  |  | X | X | P2 |
| exportações |  |  | X | X | P2 |
| anexos/documentos |  |  | X | X | P2 |
| NFS-e |  |  | X | X | P2 |
| NF-e |  |  | X | X | P3 |
| emissão fiscal |  |  | X | X | P2 |
| cancelamento fiscal |  |  | X | X | P2 |
| substituição fiscal |  |  | X | X | P2 |
| XML fiscal |  |  | X | X | P2 |
| PDF/DANFSe |  |  | X | X | P2 |
| impostos |  |  | X | X | P2 |
| retenções |  |  | X | X | P2 |
| orçamento |  |  | X | X | P3 |
| forecast |  |  | X | X | P3 |
| diagnóstico financeiro |  | X |  | X | P3 |
| recomendações |  | X |  | X | P3 |
| plano de ação |  | X |  | X | P3 |
| alertas |  | X |  | X | P3 |
| notificações |  | X |  | X | P3 |
| automações |  |  | X | X | P3 |
| IA |  |  | X | X | P3 |

## Próximo bloco por dependência

1. aplicar e validar as três migrations e o seed em PostgreSQL descartável;
2. adicionar testes de integração negativos para tenant, empresa e unidade;
3. criar `/api/v1/companies` e seleção de contexto baseada nos acessos de `/me`;
4. conectar uma primeira lista de pagar/receber à interface, com estados de
   carregamento, vazio, erro e permissão;
5. modelar contrapartes, categorias, centros de custo e contas financeiras;
6. modelar eventos de pagamento/recebimento, parcial, total e estorno;
7. substituir gradualmente os demais mocks do dashboard por dados calculados.

## Registro de progresso

### 24/07/2026 — auditoria inicial

- repositório, documentação, configuração, interface, banco, migration, seed,
  servidor, worker, exemplos e testes auditados;
- linha de base validada;
- nenhuma integração com Conta Azul encontrada ou planejada;
- próximo bloco: Fase 0, Prisma operacional e validação da fundação.

### 24/07/2026 — primeira fatia operacional

- Prisma 7.8.0, adapter PostgreSQL, geração, validação e scripts de banco
  integrados;
- identidade do Sites vinculada a usuário/membership internos por função SQL
  restrita;
- contexto autenticado de tenant, empresa e unidade aplicado em transação;
- RLS por unidade e idempotência adicionadas nas migrations 0002 e 0003;
- `/api/v1/me`, `/api/v1/payables` e `/api/v1/receivables` implementadas;
- escritas financeiras com validação, RBAC, auditoria atômica e idempotência;
- typecheck, lint, build e 21 testes aprovados;
- bloqueio remanescente: migrations/RLS ainda não comprovadas em PostgreSQL real
  porque o Docker Engine local não ficou disponível;
- próximo bloco: validação PostgreSQL, testes de isolamento reais e primeira
  conexão da UI às APIs.
