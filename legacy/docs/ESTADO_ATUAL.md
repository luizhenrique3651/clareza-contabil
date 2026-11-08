# ESTADO ATUAL — Clareza

**Atualizado em:** 4 de agosto de 2026

**Fase ativa:** Fase 3 — banco, movimentações e conciliação

**Estado:** Fase 2 aprovada; Fase 3 em execução

**Produção:** não implantada

**Dados reais:** não utilizados e não autorizados

## Resumo executivo

As Fases 0, 1 e 2 estão aprovadas. A Fase 2 entregou o núcleo financeiro
operacional completo, foi validada por duas matrizes PostgreSQL complementares
e recebeu aprovação adversarial final sem P0, P1 ou bloqueador residual.

O gate final da Fase 2 foi:

```text
lint                         PASSOU
typecheck                    PASSOU
build                        PASSOU
Prisma validate              PASSOU
git diff --check             PASSOU
testes locais                47/47
testes PostgreSQL Prisma/pg  30/30
revisão adversarial          APROVADA
P0 aberto                    0
P1 aberto                    0
```

Como o roadmap marca `AUTO_ADVANCE: true`, a Fase 3 foi ativada. Além do
checkpoint inicial com parser CSV/OFX, schema Prisma, migration 0007, reservas
exclusivas e gate SQL local, a primeira fatia operacional de importação e
listagem bancária já está implementada e auditada. A conciliação e a respectiva
interface ainda não estão declaradas como prontas.

O Clareza continua sendo um protótipo local e não está pronto para produção.

## Raiz do software

```text
C:\Users\leona\Documents\Codex\2026-07-24\files-mentioned-by-the-user-voc\outputs\Clareza-fase-0
```

## Fases

| Fase | Situação |
| --- | --- |
| Fase 0 — fundação técnica | Aprovada e versionada |
| Fase 1 — identidade e contexto | Aprovada e versionada |
| Fase 2 — núcleo financeiro | Aprovada e versionada no commit `2827859` |
| Fase 3 — banco, movimentações e conciliação | Em execução |
| Fases 4–9 | Pendentes conforme roadmap |

## O que existe e está funcionando

### Identidade, autorização e isolamento

- identidade resolvida pelo backend;
- empresas e unidades autorizadas vindas do PostgreSQL;
- seleção de contexto validada no servidor;
- RLS por tenant, empresa e unidade;
- RBAC;
- fail-closed;
- pool sem vazamento de contexto;
- troca de contexto sem reutilização de dados antigos;
- estados próprios para loading, 401, 403, 503, vazio e erro.

### Núcleo financeiro operacional

- contrapartes;
- categorias financeiras;
- centros de custo;
- contas financeiras;
- contas a pagar;
- contas a receber;
- pagamentos;
- recebimentos;
- baixa parcial e total;
- juros, multa e desconto;
- estornos append-only;
- histórico cronológico;
- auditoria e idempotência;
- saldo, status e data de liquidação derivados dos eventos;
- `OVERDUE` derivado em leitura pelo dia financeiro de Manaus;
- rejeição de eventos futuros e timestamps não finitos;
- proteção SQL contra catálogo incompatível;
- proteção SQL contra alteração direta das projeções;
- concorrência com chaves iguais e diferentes;
- rollback atômico quando auditoria ou idempotência falham.

### Interface

- área financeira operacional integrada à Central de Comando;
- criação de cadastros e títulos;
- registro de baixa e estorno;
- timeline com a moeda do título;
- modo somente leitura;
- proteção contra resposta atrasada;
- cobertura automatizada da corrida de históricos A → B;
- títulos carregados independentemente dos catálogos;
- comportamento desktop e mobile validado visualmente;
- KPIs, caixa, DRE, diagnósticos e pendências ainda demonstrativos e rotulados.

## Migrations atuais

```text
0001_init
0002_identity_bootstrap_and_branch_rls
0003_financial_api_foundation
0004_identity_branch_directory
0005_financial_operations
0006_phase_two_hardening
0007_bank_reconciliation
```

Nenhuma migration antiga foi reescrita nesta retomada.

## Evidência PostgreSQL da Fase 2

### PostgreSQL 16 real

Um clone descartável em modo single-user comprovou:

- aplicação da versão final da 0006 sobre 0001–0005;
- 26/26 instruções aplicadas sem erro;
- preflight recusando evento futuro;
- rollback atômico do upgrade recusado;
- quatro superfícies financeiras rejeitando futuro e valor não finito;
- policies, privilégios, catálogos e projeções;
- baixa, estorno e recomposição de saldo;
- referências cross-branch e tipos incompatíveis rejeitados.

### PostgreSQL 17 convencional por Prisma/`pg`

Um banco Neon temporário, sem dados reais, recebeu:

1. migrations 0001–0006 em banco vazio;
2. seed base;
3. seed de cenários;
4. associação do login temporário à role restrita `clarity_app`;
5. suíte oficial `npm run test:integration`.

Resultado:

```text
30 testes
30 aprovados
0 falhas
0 cancelamentos
```

A matriz é complementar: PostgreSQL 16 comprova a semântica específica da
versão-alvo; PostgreSQL 17 comprova o caminho operacional por Prisma, driver
`pg`, APIs, pool e concorrência.

## Ajustes finais de portabilidade

Durante o gate remoto foram corrigidas duas expectativas do teste:

1. o banco esperado agora é derivado de `DIRECT_DATABASE_URL`, em vez de exigir
   literalmente o nome local `clarity_mvp`;
2. a exclusão direta de `payables` agora espera `42501`, coerente com a
   revogação de `DELETE` introduzida pela migration 0006;
3. testes longos aceitam `INTEGRATION_TEST_TIMEOUT_MS`, preservando o piso local
   de 20 segundos e sem relaxar as asserções.

Esses ajustes foram aprovados pelo revisor adversarial.

## APIs operacionais

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

## Problemas encontrados e resolvidos na Fase 2

| Problema | Situação |
| --- | --- |
| `OVERDUE` persistido | Corrigido |
| Estorno fora da ordem cronológica | Corrigido |
| Catálogo incompatível protegido apenas na API | Corrigido no banco |
| Catálogo compartilhado invisível para BRANCH | Corrigido |
| Projeção alterável diretamente | Bloqueada no banco |
| Evento futuro alterando saldo presente | Rejeitado na API e no banco |
| Histórico A podendo sobrescrever B | Corrigido e testado |
| Timeline assumindo BRL | Corrigido |
| Leitura de título acoplada ao catálogo | Corrigida |
| Concorrência com chaves diferentes sem prova | Cobertura adicionada |
| Nome fixo de banco no teste | Tornado portátil |
| `DELETE` esperado após revogação | Teste alinhado a `42501` |
| Timeout local insuficiente para banco remoto | Tornado configurável |

## Pendências e riscos conhecidos

### Fase 2

Não há P0, P1 ou P2 conhecido. O encerramento técnico está aprovado.

### Fase 3

Já existe no checkpoint:

- parser CSV/OFX limitado e testado;
- modelo de importação e transação bancária;
- modelo de conciliação 1:1, 1:N e N:1;
- reversão append-only;
- reserva exclusiva de participantes;
- validação estrita de datas ISO/OFX e dos respectivos fusos;
- contagem real da importação validada no commit;
- bloqueio pessimista da conta nos cinco caminhos de evento;
- migration 0007 aplicada do zero sobre a Fase 2 em PostgreSQL 16 local,
  61/61 instruções;
- gate SQL direto de invariantes;
- serviço transacional de importação CSV/OFX;
- APIs de importação e listagem de movimentações bancárias;
- idempotência, auditoria e deduplicação forte;
- isolamento obrigatório pela unidade ativa;
- bloqueio por conta antes das verificações de duplicidade concorrente;
- paginação vinculada ao contexto e aos filtros;
- 63/63 testes locais;
- build, lint, typecheck, Prisma validate e `git diff --check` aprovados;
- duas revisões adversariais aprovadas sem P0/P1/P2 no bloco bancário.

Ainda falta implementar e provar:

- APIs de conciliação e reversão;
- provas automatizadas de conciliação 1:N e N:1;
- prova convencional de concorrência por driver;
- interface operacional de importação e conciliação;
- suíte PostgreSQL convencional da 0007;
- revisão adversarial final e gate integral da fase.

### Produção

Continuam fora do escopo atual:

- identidade e sessão definitivas;
- MFA;
- gestão de segredos;
- TLS e controles de borda;
- backup e restauração;
- observabilidade;
- CI/CD;
- revisão de dependências;
- testes E2E de produção;
- resposta a incidentes e pentest.

## Próximo passo

Preservar o checkpoint aprovado de importação/listagem e implementar o serviço
transacional e as APIs de conciliação e reversão que completem:

```text
conta bancária
→ transação importada
→ deduplicação
→ conciliação com evento financeiro
→ desfazer conciliação
→ trilha auditável
```

Não iniciar a Fase 4 antes do gate integral da Fase 3. A Fase 4 exige decisão
humana sobre o modelo contábil.

## Veredito atual

> **FASE 2 APROVADA. FASE 3 EM EXECUÇÃO.**
>
> O Clareza não está pronto para produção e não deve receber dados reais.
