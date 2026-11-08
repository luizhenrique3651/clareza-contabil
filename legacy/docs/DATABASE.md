# Banco de dados

## Estado atual

A página executável usa identidade, empresas, unidades e permissões resolvidas
em PostgreSQL. A área financeira operacional da Central de Comando lê e grava
títulos, catálogos, baixas, estornos e histórico. Os indicadores financeiros e
gerenciais permanecem demonstrativos.

O repositório nasceu de um starter com suporte opcional a Cloudflare D1/Drizzle. Esse suporte não define a persistência do produto: a arquitetura-alvo do Clareza é **PostgreSQL com Prisma**. Artefatos de schema criados na fundação servem para validar nomes, relações e constraints; somente uma migração aplicada e testada em banco torna uma estrutura operacional.

O `prisma/schema.prisma` atual define como fundação: `Tenant`, `Company`,
`Branch`, `User`, `AuthIdentity`, `Membership`, `Role`, `Permission`,
`RolePermission`, `Payable`, `Receivable`, `JournalEntry`,
`JournalEntryLine`, `GlossaryTerm`, `PendingItem`, `Risk`, `ActionPlan`,
`IdempotencyRecord`, `AuditLog`, catálogos financeiros, `Payment`,
`PaymentReversal`, `Receipt` e `ReceiptReversal`. Seis migrations SQL
acrescentam constraints, índices, políticas RLS, triggers, bootstrap restrito de
identidade, idempotência e projeções financeiras que o schema Prisma não
expressa integralmente. A migration 0004 fornece o diretório de unidades; a
0005 introduz as operações financeiras; e a 0006 endurece catálogos, projeções,
eventos temporais e permissões.

As migrations 0001–0005 e a versão final do upgrade 0005 → 0006 foram validadas
em PostgreSQL 16 descartável. A 0006 final aplicou 26 instruções sem erro; seu
preflight e as quatro guardas temporais também foram comprovados diretamente.

Em 29 de julho de 2026, um PostgreSQL Neon 17 descartável e acessível ao driver
Node recebeu, em banco vazio, as seis migrations e os dois seeds. A suíte
convencional por Prisma/`pg` aprovou 30/30 testes, sem falha ou cancelamento.
Essa execução complementa — e não substitui — a prova específica já realizada
no PostgreSQL 16. Nenhum dado real ou banco de produção foi usado.

## Princípios do modelo-alvo

- chaves primárias estáveis, preferencialmente UUID/UUIDv7 ou equivalente definido em ADR;
- `tenant_id` obrigatório em todo dado pertencente a cliente;
- chaves estrangeiras e constraints no banco, não apenas na aplicação;
- índices guiados por consultas reais;
- `created_at` e `updated_at` em UTC;
- `created_by` e `updated_by` quando houver autoria relevante;
- exclusão lógica apenas quando obrigação de retenção ou recuperação justificar;
- valores monetários em decimal de precisão definida e código ISO da moeda;
- datas contábeis separadas de timestamps técnicos;
- versionamento de regras, classificações e fórmulas que afetem resultados.

## Mapa conceitual

| Contexto | Entidades candidatas | Estado |
| --- | --- | --- |
| Acesso | Tenant, User, Role, Permission, Membership | fundação/alvo |
| Organização | Company, Branch, Department, CostCenter | alvo |
| Contabilidade | ChartOfAccount, AccountingPeriod, JournalEntry, JournalEntryLine | alvo |
| Demonstrativos | TrialBalanceSnapshot, FinancialStatement, StatementLine | alvo |
| Financeiro | BankAccount, BankTransaction, Payable, Receivable, Payment, Receipt, Reconciliation | alvo |
| Planejamento | Budget, BudgetItem, Forecast | alvo |
| Compliance | TaxObligation, FiscalObligation, Certificate, Document | alvo |
| Auditoria | Audit, AuditTest, AuditEvidence, AuditFinding, AuditLog | alvo |
| Gestão | Risk, ActionPlan, ActionPlanTask, Diagnostic, DiagnosticFinding | alvo |
| Explicação | GlossaryTerm, GlossaryRelation, Insight, Alert | alvo |
| Ingestão | ImportJob, ImportError | alvo |

“Alvo” significa modelo a validar por casos de uso; não confirma tabela implantada.

O schema atual concentra os vínculos tenant/empresa/unidade em `Membership`. Tabelas separadas como `UserCompanyAccess` e `UserBranchAccess` são alternativas conceituais, não modelos implementados. A escolha deve ser reavaliada quando forem definidos herança, múltiplos papéis e exceções de escopo.

## Isolamento multi-tenant

O isolamento deve existir em profundidade:

1. contexto autenticado resolve tenants e empresas permitidos;
2. services aplicam autorização antes do acesso;
3. repositories exigem contexto confiável de tenant, usuário e empresas autorizadas;
4. constraints compostas evitam relações cruzadas entre tenants;
5. PostgreSQL Row-Level Security usa `app.tenant_id`, `app.user_id`,
   `app.company_ids`, `app.branch_ids` e `app.tenant_wide_access` como barreira
   complementar;
6. testes de integração tentam leitura, alteração, associação e inferência entre tenants.

Exemplo conceitual de integridade: uma linha de lançamento deve referenciar uma conta do mesmo `tenant_id` e da mesma empresa. Uma FK simples apenas por `account_id` não basta para provar essa invariável.

A RLS restringe por tenant, lista de empresas e lista de unidades. Registros com
unidade exigem `app.can_access_branch`; registros sem unidade exigem acesso à
empresa. `MembershipScope.BRANCH` e a leitura de catálogos compartilhados foram
comprovados em PostgreSQL descartável. A regressão convencional por Prisma/`pg`
também foi aprovada em 30/30 testes.

## Convenções importantes

### Contabilidade

- lançamentos devem equilibrar débitos e créditos antes da contabilização;
- períodos fechados impedem alterações comuns e exigem fluxo auditável de reabertura;
- snapshots não substituem os lançamentos de origem;
- moeda, competência, documento e lote de importação precisam ser rastreáveis.

### Financeiro

- pagamento e recebimento são eventos, não apenas status da obrigação;
- conciliação relaciona transações sem apagar a origem;
- alterações de valor, vencimento e baixa geram eventos de auditoria.

### Documentos e dados pessoais

O banco mantém apenas metadados e referências quando o arquivo puder residir em armazenamento próprio. Conteúdo sensível deve ter classificação, controle de acesso, retenção e criptografia adequados.

## Migrações e seed

Quando a persistência for habilitada:

```text
alterar schema Prisma
→ revisar migration SQL
→ testar em banco descartável
→ aplicar seed exclusivamente fictício
→ executar testes de integridade e isolamento
→ promover por ambiente
```

Migrações nunca devem depender de edição manual em produção. O seed de desenvolvimento não pode conter dados copiados de clientes.

## Backup e recuperação

Antes de produção devem ser definidos RPO, RTO, retenção, criptografia de backup, restauração pontual e teste periódico de recuperação. “Backup configurado” só é aceito após restauração comprovada.
