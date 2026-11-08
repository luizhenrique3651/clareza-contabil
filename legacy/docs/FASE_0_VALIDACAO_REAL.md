# Fase 0 — validação real da fundação técnica

Data da execução: 2026-07-24  
Escopo: PostgreSQL, migrations, Prisma, identidade, multi-tenancy, RBAC, RLS, auditoria, idempotência e APIs financeiras já existentes.

## Estado inicial

O relatório `RELATORIO_COMPLETO_CLAREZA_2026-07-24.md` foi lido integralmente antes das alterações. Também foram inspecionados a árvore do projeto, `package.json`, schema e migrations Prisma, seed, servidor, APIs, testes e documentação.

O projeto recuperado já continha:

- aplicação React 19/Next 16 executada por Vinext/Vite;
- Prisma 7.8 com PostgreSQL e adapter `pg`;
- migrations `0001_init`, `0002_identity_bootstrap_and_branch_rls` e `0003_financial_api_foundation`;
- estruturas de tenant, empresa, unidade, identidade, membership, roles, permissões, RLS, auditoria e idempotência;
- APIs `/api/v1/me`, `/api/v1/payables` e `/api/v1/receivables`;
- 21 testes automatizados, majoritariamente unitários ou com doubles do banco.

Não existia histórico Git confiável no projeto recuperado. Antes das alterações foi criada uma cópia de trabalho exclusiva e um commit de baseline:

`6806650 chore: preserve recovered Clareza baseline`

O projeto recuperado original foi mantido intacto. O frontend, a identidade visual e o escopo de produto não foram alterados.

## Ambiente utilizado

| Componente | Evidência |
|---|---|
| Sistema | Windows, PowerShell, fuso America/Manaus |
| Node.js | 24.18.0 |
| npm | 11.16.0 |
| Prisma CLI/Client | 7.8.0 |
| PostgreSQL | 16.14, 64-bit, UTF-8 |
| Banco | `clarity_mvp`, exclusivo para desenvolvimento, em `127.0.0.1:55432` |
| Runtime | role `clarity_app`, sem login próprio e assumida pela conexão da aplicação |
| Migrações | conexão direta do owner, separada logicamente da role de runtime |

O engine do Docker estava instalado, mas indisponível e sem permissão para iniciar o serviço. Para não bloquear a validação foi utilizado PostgreSQL 16 portátil, executado localmente e com diretório de dados exclusivo. Nenhum banco de produção foi acessado. Todos os tenants, empresas, usuários e lançamentos do seed são fictícios e usam o domínio reservado `.invalid`.

As URLs foram injetadas somente no ambiente dos comandos. Não foi criado `.env` com credenciais e a varredura por padrões de segredo não encontrou chave, senha, token ou chave privada no projeto.

## Prisma

| Verificação | Resultado | Evidência |
|---|---|---|
| Validação do schema | Aprovada | `npm run db:validate` → `The schema at prisma\schema.prisma is valid` |
| Geração do client | Aprovada | `npm run db:generate` → Prisma Client 7.8.0 gerado |
| Consistência schema/banco | Aprovada | `prisma migrate diff --from-schema ... --to-config-datasource --exit-code` → `No difference detected` |

Foram alinhados no schema os defaults de UUID providos por `gen_random_uuid()`, os defaults de `updated_at`, os nomes reais das foreign keys e a ação `ON UPDATE NO ACTION`. RLS, policies, triggers, checks, índices parciais e funções continuam corretamente expressos nas migrations SQL e são comprovados por consulta ao catálogo no teste de integração.

## Migrations

### Resultado final em banco vazio

| Migration | Resultado | Estruturas confirmadas |
|---|---|---|
| `0001_init` | Aplicada | modelos centrais, enums, constraints compostas, índices, schema `app`, funções de contexto, role de runtime, RLS e auditoria |
| `0002_identity_bootstrap_and_branch_rls` | Aplicada | identidade externa, bootstrap de identidade, escopo de unidade e policies associadas |
| `0003_financial_api_foundation` | Aplicada | idempotência e fundação transacional das APIs financeiras |

O comando final em banco recriado do zero encontrou três migrations, aplicou as três na ordem e terminou com `All migrations have been successfully applied`. Em seguida, `prisma migrate status` retornou `Database schema is up to date`.

O teste de catálogo confirmou:

- as três migrations na tabela `_prisma_migrations`;
- 19 tabelas de domínio esperadas;
- todos os modelos sujeitos a tenant com RLS habilitada e forçada;
- policies nas 18 tabelas protegidas por RLS;
- `auth_identities` sem `SELECT` concedido à role da aplicação;
- role `clarity_app` com `rolsuper=false`, `rolcreaterole=false`, `rolcreatedb=false`, `rolcanlogin=false` e `rolbypassrls=false`.

### Correção explícita em migration existente

A primeira execução de `0001_init` falhou no PostgreSQL 16 com SQLSTATE `42601`: `GRANT USAGE ON ALL TYPES IN SCHEMA` não é sintaxe aceita. Como não havia implantação anterior confiável, o banco era descartável e o baseline Git preservava a versão recebida, a migration foi corrigida explicitamente para conceder `USAGE` aos enums nomeados. A alteração não foi escondida: está registrada no diff em relação ao baseline e foi provada novamente em banco vazio.

A mesma migration foi corrigida para `app.current_tenant_id()` retornar `NULL` quando `app.user_id` estiver ausente. Antes, definir apenas tenant/empresa permitia uma leitura indevida; agora o contexto incompleto falha fechado.

## Seed

O seed final foi executado duas vezes consecutivas com sucesso, comprovando repetibilidade. A matriz fictícia contém:

- Tenant A: Empresa A1 e Empresa A2, duas unidades em cada empresa;
- Tenant B: Empresa B1, duas unidades;
- usuário com acesso ao tenant inteiro;
- usuário com escrita somente em uma empresa;
- usuário somente leitura em uma empresa;
- usuário com escrita somente em uma unidade;
- usuário sem membership;
- membership inativa;
- usuário com membership, mas sem permissões.

O seed original não era idempotente para linhas de lançamentos já postados: na segunda execução, o trigger de imutabilidade era disparado antes de `ON CONFLICT`. Os inserts foram condicionados à existência do lançamento em estado `DRAFT`; depois da correção, os dois arquivos de seed passaram duas vezes em sequência.

## Testes

### Resumo

| Teste | Resultado | Evidência |
|---|---|---|
| Lint | Aprovado | `npm run lint`, saída sem erros |
| TypeScript | Aprovado | `npm run typecheck`, saída sem erros |
| Build | Aprovado | Vinext concluiu as cinco etapas e gerou as quatro rotas existentes |
| Testes existentes | Aprovados | 21 testes, 21 aprovados, 0 falhas |
| Integração PostgreSQL | Aprovada | 19 testes, 19 aprovados, 0 falhas, PostgreSQL 16 real |
| Total automatizado final | Aprovado | 40 testes, 40 aprovados, 0 falhas |
| Migrações em banco vazio | Aprovadas | 0001 → 0002 → 0003 |
| Seed repetido | Aprovado | duas execuções consecutivas, quatro scripts SQL bem-sucedidos |
| Diff Prisma/banco | Aprovado | nenhuma diferença detectada |

### Matriz de integração real

| Teste | Resultado | Evidência observada |
|---|---|---|
| Catálogo, migrations, RLS e role | Aprovado | estruturas esperadas e `BYPASSRLS=false` |
| Seed multi-tenant e perfis | Aprovado | 2 tenants, empresas/unidades e todos os perfis exigidos |
| Contexto ausente/incompleto | Aprovado | leitura retorna zero linhas; escrita é negada |
| Tenant A lê Tenant B | Bloqueado | zero linhas visíveis |
| Tenant A cria para Tenant B | Bloqueado | RLS rejeita a inserção |
| Tenant A altera/exclui Tenant B | Bloqueado | zero linhas afetadas |
| Empresa A1 lê/escreve A2 | Bloqueado | zero linhas ou rejeição por RLS |
| Unidade A lê/escreve unidade irmã | Bloqueado | zero linhas ou rejeição por RLS |
| IDs cruzados | Bloqueado | constraints compostas/RLS rejeitam tenant, empresa, unidade e usuário incompatíveis |
| Pool reutilizado | Aprovado | mesmo backend PostgreSQL, transações sucessivas, nenhum contexto residual |
| Identidade completa | Aprovado | External identity → AuthIdentity → User → Membership → Role → Permission → escopo |
| RBAC | Aprovado | leitura/escrita respeitam role e escopo |
| Empresa/unidade inválida na API | Bloqueado | servidor rejeita o contexto não autorizado |
| GET payables | Aprovado | filtros, paginação e escopo contra banco real |
| GET receivables | Aprovado | filtros e escopo contra banco real |
| POST payables/receivables | Aprovado | criação, validação, escopo derivado e auditoria |
| Idempotência concorrente | Aprovada | duas requisições simultâneas, uma operação persistida |
| Falha forçada de auditoria | Aprovado | rollback de financeiro e idempotência |
| Falha forçada de idempotência | Aprovado | rollback de financeiro e auditoria |
| Auditoria append-only | Aprovado | update/delete negados inclusive ao owner |

Uma execução intermediária dos testes existentes foi feita com variáveis de banco presentes; o teste que deliberadamente simula “banco não configurado” recebeu 403, não o 503 esperado. Isso era uma montagem incorreta do comando, não falha do produto. A execução final separou os ambientes: 21/21 testes existentes sem URLs de banco e 19/19 integrações com PostgreSQL configurado.

## RLS

### Tenant

Com contexto de Tenant A, a leitura de registros do Tenant B retornou zero linhas. `UPDATE` e `DELETE` afetaram zero linhas. A tentativa de `INSERT` usando o Tenant B foi rejeitada pela policy. Não houve uso do owner para simular o runtime: as ações foram realizadas sob a role `clarity_app`.

### Empresa

No mesmo tenant, o usuário limitado à Empresa A1 não viu registros da Empresa A2 e não conseguiu inserir nela. Empresa e ator são derivados do contexto autenticado no servidor, e não de campos livres do payload.

### Unidade

O usuário limitado à unidade norte da Empresa A1 não viu nem gravou na unidade sul ou no escopo sem unidade. A seleção de unidade inválida pela API também foi rejeitada.

### Contexto ausente

Foram testadas combinações sem `app.tenant_id`, `app.user_id`, `app.company_ids` e `app.branch_ids`. Todas retornaram zero dados ou erro de autorização. Em especial, a função de tenant exige a presença do usuário para evitar contexto parcial privilegiado.

### IDs cruzados

As constraints compostas rejeitaram:

- tenant válido com empresa de outro tenant;
- empresa válida com unidade de outra empresa;
- usuário pertencente a outro tenant;
- unidade de outro tenant.

### Pool

Um pool com `max=1` reutilizou o mesmo PID de backend para requisições de Tenant A e Tenant B. O contexto é aplicado com `SET LOCAL` dentro da transação e desaparece após `COMMIT`/`ROLLBACK`; a segunda requisição não herdou tenant, empresa, unidade ou usuário da primeira.

## Identidade e RBAC

Foram validados:

- identidade externa inexistente;
- identidade apontando para usuário inexistente;
- usuário sem membership;
- membership inativa;
- empresa não autorizada;
- unidade não autorizada;
- role sem permissão;
- role somente leitura;
- acesso em escopo de tenant, empresa e unidade.

Todos os casos inválidos falharam fechado. O leitor não criou registros, o usuário sem permissão não listou, e usuários autorizados só receberam dados do escopo concedido. A proteção foi exercitada no servidor e no PostgreSQL, não no frontend.

## APIs financeiras

As quatro operações exigidas foram executadas no worker compilado, contra PostgreSQL real:

- `GET /api/v1/payables`;
- `POST /api/v1/payables`;
- `GET /api/v1/receivables`;
- `POST /api/v1/receivables`.

Foram cobertos criação válida, payload inválido, falta de autorização, empresa/unidade inválidas, filtros, paginação e contexto incorreto. O schema Zod é estrito: tentativas de enviar `tenantId`, `companyId`, `branchId` ou `createdById` no corpo foram rejeitadas com 422. Tenant, ator, empresa e unidade autorizada vêm da sessão verificada.

## Idempotência

O teste usa `Promise.all` para enviar duas requisições simultâneas com a mesma chave e o mesmo conteúdo. O servidor serializa somente o escopo concorrente por um advisory lock transacional calculado sobre tenant, usuário, operação e chave.

Resultado:

- ambas as respostas: HTTP 201;
- corpos: idênticos;
- uma resposta com `Idempotency-Replayed: true`;
- financeiros persistidos: 1;
- eventos de auditoria persistidos: 1;
- registros de idempotência persistidos: 1.

Uma nova tentativa com a mesma chave e conteúdo diferente retornou HTTP 409 e código `IDEMPOTENCY_CONFLICT`.

## Transações e auditoria

Financeiro, auditoria e idempotência são gravados na mesma transação:

- um trigger temporário forçou falha no insert de auditoria: permaneceram 0 financeiros, 0 auditorias e 0 idempotências;
- outro trigger forçou falha no insert de idempotência: permaneceram 0 financeiros, 0 auditorias e 0 idempotências.

Para criação, o evento registra tenant, empresa, unidade, ator, sessão, ação, tipo e ID da entidade, `before_data=NULL`, estado posterior mínimo, metadata e campos redigidos. A chave de idempotência é guardada no evento apenas como SHA-256 e o nome da contraparte não é copiado para a auditoria.

O campo `integrity_hash` contém um SHA-256 de representação canônica do evento e o banco impede update/delete de auditoria inclusive para o owner. Isso oferece evidência de integridade para a Fase 0, mas não equivale a HMAC, encadeamento criptográfico ou armazenamento externo WORM.

## Segurança de dependências

Não foi executado `npm audit fix --force`, downgrade ou troca silenciosa de dependência central.

### Recorte de runtime

`npm audit --omit=dev` registrou 6 pacotes afetados, todos moderados, 0 altos e 0 críticos. As causas concretas são transitivas:

| Vulnerabilidade | Dependência/cadeia | Severidade | Uso/exploração no Clareza | Decisão |
|---|---|---|---|---|
| GHSA-92pp-h63x-v22m, bypass por barras repetidas em `serveStatic` | `@prisma/client` → `prisma` → `@prisma/dev` → `@hono/node-server` | Moderada | `serveStatic` do Hono não é importado pelas áreas executáveis inspecionadas | Registrar e atualizar a família Prisma com teste de compatibilidade |
| GHSA-frvp-7c67-39w9, path traversal por `%5C` no Windows | mesma cadeia Hono | Moderada | rota vulnerável não é usada pelo Clareza; deploy alvo não foi declarado Windows | Mesma decisão, sem minimizar a existência no grafo |
| GHSA-5qjj-4xww-7phc, falha de `flatten()` | mesma cadeia via `valibot` | Moderada | Clareza usa Zod e não importa Valibot/`flatten()` | Atualizar a cadeia Prisma após análise |

O `npm ls --omit=dev` confirmou que a cadeia é instalada por `@prisma/client@7.8.0`, apesar de conter ferramentas Prisma. `npm outdated` mostrou Prisma/Client/adapter 7.9.0 disponíveis. A atualização não foi misturada à Fase 0 porque muda dependências centrais e exige uma rodada própria de compatibilidade de migrations, geração e runtime.

### Grafo completo

`npm audit` registrou 27 pacotes afetados: 10 moderados, 17 altos e 0 críticos. Os altos propagam principalmente de `brace-expansion`/`minimatch` pela cadeia de ESLint; os moderados adicionais incluem `esbuild` antigo na cadeia de `drizzle-kit`. São ferramentas de desenvolvimento e não código servido pelas APIs, mas devem ser saneadas antes de endurecer a cadeia de CI e release.

## Problemas encontrados

1. ausência de baseline Git confiável;
2. Docker instalado, mas engine indisponível;
3. sintaxe inválida de grant em `0001_init`;
4. contexto de tenant incompleto não exigia usuário;
5. seed falhava na segunda execução para lançamentos já postados;
6. divergências de metadata entre schema Prisma e SQL;
7. janela de corrida na idempotência concorrente;
8. evento de auditoria sem `integrity_hash` preenchido pela API;
9. primeira versão do advisory lock retornava `void`, que o Prisma não desserializou, causando 2 falhas entre 19 integrações;
10. senha local padrão repetida em arquivos de configuração versionados;
11. alertas de dependências transitivas de runtime e desenvolvimento;
12. uma execução de teste montada com ambiente incompatível com o caso “sem banco”.

## Correções realizadas

1. criado baseline antes das mudanças;
2. disponibilizado PostgreSQL 16 portátil, local e descartável;
3. substituído o grant inválido por concessão explícita nos enums;
4. tornado o contexto de tenant dependente do usuário;
5. tornado o seed repetível e ampliada a matriz fictícia;
6. alinhado o schema Prisma às migrations até o diff ficar vazio;
7. incluído advisory lock transacional por chave de idempotência;
8. incluídos metadata redigidos e hash de integridade na auditoria;
9. alterado o retorno do lock para texto compatível com o Prisma;
10. removido o valor de senha padrão do Compose/config e mantidos somente placeholders;
11. criada suíte de 19 integrações contra PostgreSQL;
12. separados corretamente os ambientes das suítes existentes e de integração.

Após cada correção relevante, o banco foi novamente criado do zero e os testes relacionados foram repetidos. A execução final tem zero falhas.

## Arquivos alterados

- `package.json`;
- `.env.example`;
- `docker-compose.yml`;
- `prisma.config.ts`;
- `prisma/schema.prisma`;
- `prisma/migrations/0001_init/migration.sql`;
- `prisma/seed.sql`;
- `prisma/phase-zero-seed.sql`;
- `server/financial-records.mjs`;
- `tests/financial-records.test.mjs`;
- `tests/integration/postgresql-phase-zero.test.mjs`;
- `docs/FASE_0_VALIDACAO_REAL.md`.

## Pendências

Estas pendências não invalidam o gate técnico específico da Fase 0, mas impedem declarar o sistema pronto para produção:

- atualizar Prisma e a cadeia de lint/build em mudanças separadas, repetindo toda a matriz;
- definir topologia de produção para PostgreSQL com TLS, backup, restauração testada, observabilidade, alta disponibilidade e gestão externa de segredos;
- configurar provedor de identidade confiável, proteção do header de identidade pelo proxy, gestão de sessão, MFA e ciclo de vida de acesso;
- evoluir o hash de auditoria para mecanismo autenticado/encadeado e, se exigido, armazenamento imutável externo;
- executar a matriz também no CI e no ambiente de implantação escolhido;
- revisar a cadeia Drizzle, que não participa do fluxo Prisma validado, antes da Fase 1.

Não foram implementados DRE, dashboard, conciliação, documentos fiscais, IA, automações, redesign ou qualquer nova funcionalidade de produto.

## Veredito

**FASE 0 APROVADA**

Os 15 itens do gate foram demonstrados em PostgreSQL 16 real: migrations e seed em banco vazio, Prisma consistente, isolamento de tenant/empresa/unidade, fail-closed, pool sem vazamento, constraints cruzadas, identidade/RBAC, quatro operações financeiras, idempotência concorrente, atomicidade de auditoria e regressão completa. Resultado final: 40/40 testes automatizados aprovados e 0 falhas.

Este veredito aprova apenas a fundação técnica delimitada pela Fase 0. Não declara o Clareza pronto para produção.
