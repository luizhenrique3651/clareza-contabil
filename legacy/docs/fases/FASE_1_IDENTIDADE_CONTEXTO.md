# Fase 1 — Identidade e contexto reais na interface

Data de conclusão: 2026-07-25

Veredito: **APROVADA**

Escopo: identidade autenticada, empresas, unidades, permissões e contexto de navegação da área autenticada.

## Objetivo

Substituir a identidade, as empresas e as unidades fictícias da área autenticada por dados resolvidos no backend por `GET /api/v1/me`, mantendo o servidor como autoridade e sem apresentar os demais dados demonstrativos como se fossem operacionais.

## Estado inicial

O início da fase foi precedido pela leitura integral dos documentos de controle, do relatório da Fase 0 e pela inspeção do código.

O baseline encontrado era:

- Fase 0 aprovada em PostgreSQL 16 real;
- 21 testes locais e 19 testes PostgreSQL, total de 40/40;
- lint, typecheck e build aprovados;
- Central de Comando visualmente preservada;
- usuário fictício `Marina Costa`;
- empresas fictícias do Grupo Aurora;
- ausência de seletor real de unidade;
- ausência de consumo de `/api/v1/me` pela interface;
- ausência de estados próprios para loading, 401, 403, 503 e erro de contrato;
- indicadores, diagnósticos, pendências, DRE, caixa e notificações estáticos, alguns sem identificação inequívoca de demonstração.

O sistema já não era considerado pronto para produção ou dados reais, e essa restrição foi preservada.

## Alterações implementadas

### Camada central de identidade

Foi criada uma camada única de consumo de `GET /api/v1/me` em `app/identity-client.ts`, responsável por:

- request `GET`, `same-origin`, `no-store`;
- parse estrito do contrato;
- estados próprios para 401, 403, 503, falha de rede, erro inesperado e contrato inválido;
- rejeição de respostas inválidas sem criar identidade fictícia;
- tipos de tenant, empresa, unidade, escopo, permissões e contexto;
- resolução somente de empresas e unidades presentes no contrato do backend.

### Provider de contexto

Foi criado `ApplicationContextProvider`, que:

- inicia em `loading`;
- consulta `/api/v1/me`;
- deriva o contexto autorizado;
- oferece seleção de empresa e unidade;
- atualiza novamente `/api/v1/me` a cada troca;
- oculta integralmente os dados anteriores durante a validação;
- remonta a Central de Comando quando o fingerprint do contexto muda;
- impede requests de dados antes de o contexto ficar pronto;
- injeta apenas empresa/unidade do contexto autorizado nos requests subsequentes;
- remove cabeçalhos de tenant, usuário e ator fornecidos pelo cliente;
- revalida o contexto anterior quando uma seleção é rejeitada;
- escolhe somente outro contexto retornado pelo backend quando o anterior foi revogado;
- entra em estado vazio quando nenhum contexto permanece autorizado.

### Preferência de navegação

Somente IDs de empresa e unidade são persistidos em `localStorage`, como preferência não confiável.

As preferências:

- não são sessão;
- não contêm permissões, tenant, usuário ou ator;
- são confrontadas novamente com `/api/v1/me`;
- são descartadas quando antigas, adulteradas ou incompatíveis;
- deixam de ser obrigatórias quando o getter de `localStorage` lança `SecurityError`.

Um gate de request impede que uma resposta atrasada sobrescreva o storage ou a interface depois de uma troca mais nova.

### Interface

A Central de Comando passou a exibir:

- nome e iniciais reais do usuário;
- tenant real;
- empresas autorizadas reais;
- unidades autorizadas reais;
- distinção entre empresa inteira e acesso limitado por unidade;
- nível de permissão visível: gestão, somente leitura ou sem permissão operacional;
- área Financeiro desabilitada quando não há permissão financeira de leitura/gestão.

Foram adicionadas telas próprias para:

- carregamento;
- não autenticado;
- sem membership;
- backend/banco indisponível;
- erro inesperado/contrato inválido;
- identidade válida sem empresas;
- troca segura de empresa/unidade.

### Identificação de dados demonstrativos

Os dados não abrangidos pela Fase 1 continuam fictícios e foram marcados visualmente:

- banner global de dados demonstrativos;
- período com sufixo `Demo`;
- badges laterais `Demo`;
- fechamento, notificações, busca e atalhos demonstrativos;
- origens de números e conteúdo do drawer demonstrativos;
- rodapé declarando que nenhuma informação real é usada;
- remoção de alegações operacionais como “Dados atualizados”, “96% conciliados” e “Ambiente protegido”.

### Diretório e autoridade de unidades

A revisão adversarial provou que uma membership `COMPANY`/`TENANT` conseguia enviar uma unidade arbitrária no header, porque o servidor aceitava o acesso amplo sem comprovar a existência da unidade.

A migration aditiva `0004_identity_branch_directory` corrigiu o problema:

- `/api/v1/me` passou a receber o diretório de unidades ativas para acessos amplos;
- tenants, empresas e unidades são ordenados deterministicamente;
- o servidor só aceita `x-clareza-branch-id` quando a unidade consta no diretório autorizado daquela empresa;
- unidade inexistente, de outra empresa ou de outro tenant resulta em 403;
- acesso amplo continua podendo operar no contexto de empresa inteira.

## Arquivos alterados

### Aplicação

- `app/page.tsx`
- `app/application.tsx`
- `app/application-context.tsx`
- `app/identity-client.ts`
- `app/command-center.tsx`
- `app/globals.css`

### Backend e banco

- `server/auth-context.mjs`
- `prisma/migrations/0004_identity_branch_directory/migration.sql`

### Testes e runtime

- `tests/auth-context.test.mjs`
- `tests/identity-interface.test.mjs`
- `tests/integration/postgresql-phase-zero.test.mjs`
- `tests/rendered-html.test.mjs`
- `package.json`
- `package-lock.json`

### Documentação

- `README.md`
- `docs/DATABASE.md`
- `docs/ESTADO_ATUAL.md`
- `docs/ROADMAP_MASTER.md`
- `docs/FASE_1_IDENTIDADE_CONTEXTO.md`
- `docs/fases/FASE_1_IDENTIDADE_CONTEXTO.md`

## Banco/migrations

### Upgrade incremental

O banco com as migrations `0001`, `0002` e `0003` recebeu `0004_identity_branch_directory` por `prisma migrate deploy`.

Resultado:

- quatro migrations encontradas;
- somente `0004` aplicada;
- `All migrations have been successfully applied`;
- seed executado novamente com sucesso.

### Banco vazio

O PostgreSQL local descartável foi recriado de forma controlada e recebeu:

1. `0001_init`;
2. `0002_identity_bootstrap_and_branch_rls`;
3. `0003_financial_api_foundation`;
4. `0004_identity_branch_directory`.

Depois:

- seed base aprovado;
- seed de cenários aprovado;
- `prisma migrate status`: `Database schema is up to date`;
- `prisma validate`: schema válido.

Nenhuma migration anterior foi reescrita nesta fase.

## Testes executados

### Baseline antes das mudanças

| Verificação | Resultado |
| --- | ---: |
| Testes locais | 21/21 |
| Testes PostgreSQL | 19/19 |
| Total | 40/40 |
| Lint | aprovado |
| Typecheck | aprovado |
| Build | aprovado |

### Gate final

| Verificação | Resultado |
| --- | ---: |
| `npm run lint` | aprovado |
| `npm run typecheck` | aprovado |
| `npm test` | 42/42 |
| `npm run test:integration` | 20/20 |
| Total automatizado | **62/62** |
| Builds de produção | aprovados |
| `prisma validate` | aprovado |
| Upgrade 0001–0003 → 0004 | aprovado |
| Banco vazio 0001 → 0004 | aprovado |
| Seed repetível | aprovado |
| `git diff --check` | aprovado |

A suíte local foi executada sem URLs de banco para preservar o teste explícito de 503 sem configuração. A suíte PostgreSQL foi executada separadamente com URLs do banco local descartável. Uma tentativa combinada contaminou esse cenário com variáveis de banco e foi descartada; a execução isolada final passou integralmente.

## Evidências

### Automatizadas

- cliente chama somente `GET /api/v1/me`;
- 401, 403, 503 JSON, 503 não JSON, rede indisponível e contrato inválido;
- uma e múltiplas empresas;
- empresa antiga/inválida;
- uma e múltiplas unidades;
- unidade de outra empresa e unidade adulterada;
- writer, reader e usuário sem permissão;
- storage adulterado e storage bloqueado;
- troca A → B → A;
- resposta A atrasada depois de B sem alterar o storage vencedor;
- revogação do contexto anterior com revalidação/fallback autorizado;
- remoção de headers de tenant/usuário/ator do cliente;
- diretório real de unidades para membership COMPANY e TENANT;
- unidade inexistente e cross-company rejeitada pelo backend real;
- 19 testes anteriores da Fase 0 PostgreSQL preservados;
- 21 testes locais anteriores preservados.

### Manuais

No harness local com dados exclusivamente sintéticos foram verificados:

- tela 401 sem fallback fictício;
- usuário real `Tiago Tenant (Demo)`;
- duas empresas vindas do backend;
- identidade e nível de acesso visíveis;
- troca A → B com tela intermediária ocultando os dados antigos;
- retorno B → A sem seleção antiga remanescente;
- parâmetros adulterados de tenant/empresa/unidade na URL ignorados;
- valor inválido injetado no seletor de empresa não aceito;
- iniciais do perfil corrigidas para nomes com sufixo `(Demo)`;
- fechamento e progresso marcados como demonstrativos.

Os cenários adicionais de reader, sem permissão e branch-only foram comprovados pela suíte automatizada e pela integração PostgreSQL real.

## Revisão adversarial

Uma revisão independente e somente leitura foi executada depois da implementação inicial.

Ela tentou quebrar:

- autoridade do servidor;
- unidade inexistente/cross-company;
- revogação durante troca;
- resposta fora de ordem;
- persistência adulterada;
- storage indisponível;
- ordenação do contexto;
- rotulagem dos mocks;
- compatibilidade do runtime declarado.

Após as correções, a revisão não encontrou bloqueante de código restante.

## Problemas encontrados

1. Unidade arbitrária aceita para membership ampla.
2. `/me` não fornecia unidades reais para memberships COMPANY/TENANT.
3. Seleção revogada podia restaurar identidade/permissões antigas na UX.
4. Resposta atrasada podia alterar o storage depois de o reducer rejeitá-la.
5. Ordem default dependia da ordem não garantida do SQL.
6. Getter de `localStorage` bloqueado levava a erro de aplicação.
7. 503 com corpo não JSON era classificado como erro inesperado.
8. Headers de tenant/usuário/ator passavam pelo helper do cliente.
9. Badges/período/busca possuíam resíduos de mock sem rótulo visual suficiente.
10. O teste TypeScript direto exigia Node mais novo que o piso declarado.
11. A primeira execução combinada do gate injetou banco na suíte que prova ausência de banco.

## Correções realizadas

- migration 0004 e prova server-side de unidade;
- diretório de unidades para acessos amplos;
- fallback reautorizado após revogação;
- gate de request para UI e storage;
- helpers testáveis de recovery, concorrência e storage;
- ordenação por ID;
- 503 não JSON e falha de rede classificados como indisponibilidade;
- remoção de headers de identidade não autorizados;
- rótulos visuais `Demo`;
- Node mínimo alinhado para `>=22.18.0` e lock sincronizado;
- isolamento correto das suítes sem banco e com PostgreSQL.

## Segurança

- `/api/v1/me` é a única origem de identidade/contexto do frontend;
- storage e URL nunca concedem autorização;
- empresa/unidade são revalidadas no servidor em requests financeiros;
- tenant, usuário, ator e permissões não são persistidos como autoridade;
- payload não substitui escopo;
- requests sem contexto pronto falham fechados;
- troca oculta dados antigos;
- respostas stale não alteram storage nem UI;
- unidade inexistente/cross-company retorna 403;
- RLS, constraints, RBAC, idempotência e auditoria da Fase 0 continuam aprovados.

Limitação não bloqueante: a identidade atual depende do header injetado pela borda/hosting. Sessão definitiva, proteção operacional contra spoofing na topologia final, MFA, segredos, TLS e demais controles de produção pertencem ao hardening posterior.

## Regressões

Nenhuma regressão funcional ou de segurança ficou aberta.

- 21/21 testes locais anteriores continuam verdes;
- 19/19 testes PostgreSQL anteriores continuam verdes;
- build final aprovado;
- Central de Comando visualmente preservada;
- nenhuma funcionalidade financeira da Fase 2 foi antecipada nesta entrega;
- nenhum dado real foi usado.

## Pendências

Não bloqueiam a Fase 1:

- fluxo financeiro operacional da Fase 2;
- sessão e provedor de identidade definitivos;
- MFA;
- CI da matriz PostgreSQL;
- topologia de produção, TLS, backup/restauração, observabilidade e HA;
- substituição dos indicadores demonstrativos por dados calculados em fase posterior;
- revisão futura da família Prisma e da cadeia vinext.

## Gate

| # | Critério | Estado | Evidência principal |
| ---: | --- | --- | --- |
| 1 | `/api/v1/me` integrado | aprovado | cliente/provider central + testes |
| 2 | usuário fictício removido | aprovado | UI real + busca negativa |
| 3 | empresas só do backend | aprovado | parse/resolução + PostgreSQL |
| 4 | unidades só do backend | aprovado | migration 0004 + PostgreSQL |
| 5 | empresa inválida rejeitada | aprovado | unitário + API |
| 6 | unidade inválida rejeitada | aprovado | unitário + API real |
| 7 | storage/URL não concedem acesso | aprovado | testes + validação manual |
| 8 | permissões refletidas | aprovado | gestão/leitura/nenhuma |
| 9 | estados 401/403/503/erro | aprovado | testes de cliente e UI |
| 10 | troca sem mistura | aprovado | remount, gate e A→B→A |
| 11 | mocks identificados | aprovado | banner e rótulos visuais |
| 12 | testes da fase | aprovado | 22 novos cenários líquidos |
| 13 | Fase 0 sem regressão | aprovado | 40/40 anteriores |
| 14 | lint | aprovado | zero erro |
| 15 | typecheck | aprovado | zero erro |
| 16 | build | aprovado | build de produção |

## Veredito

**FASE 1 APROVADA.**

O gate de 16 itens foi satisfeito. A interface agora usa identidade, empresas, unidades e permissões reais do backend, falha fechada, trata concorrência/revogação e identifica os dados ainda demonstrativos.

O Clareza **não está pronto para produção nem para dados reais**.

Como `AUTO_ADVANCE: true`, a Fase 2 — Núcleo financeiro operacional — passa a `EM EXECUÇÃO`.
