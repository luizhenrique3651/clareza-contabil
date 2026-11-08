# Clareza Contábil — árvore e inventário do pacote

**Gerado em:** 10/10/2026  
**Origem auditada:** `C:\Users\leona\Documents\Codex\2026-07-24\files-mentioned-by-the-user-voc\outputs\Clareza-fase-0`  
**Arquivos do projeto:** 115  
**Volume dos arquivos do projeto:** 5616001 bytes  
**Critério:** arquivos rastreados + não rastreados e não ignorados pelo Git.

## 1. Escopo

O inventário representa o código-fonte útil e reproduzível no working tree atual. O pacote acrescenta documentação de handoff, evidências Git portáteis e hashes. Dependências instaladas, builds, caches, ambientes reais e dados operacionais foram deliberadamente excluídos.

## 2. Contagem por área

| Área superior | Arquivos |
| --- | ---: |
| `docs` | 25 |
| `app` | 24 |
| `(raiz)` | 17 |
| `tests` | 13 |
| `prisma` | 10 |
| `public` | 9 |
| `server` | 8 |
| `db` | 2 |
| `examples` | 2 |
| `.openai` | 1 |
| `build` | 1 |
| `drizzle` | 1 |
| `skills` | 1 |
| `worker` | 1 |

## 3. Contagem por extensão

| Extensão | Arquivos |
| --- | ---: |
| `.md` | 30 |
| `.ts` | 29 |
| `.mjs` | 21 |
| `.sql` | 10 |
| `.tsx` | 6 |
| `.json` | 5 |
| `.svg` | 4 |
| `.jpeg` | 3 |
| `.png` | 2 |
| `.css` | 1 |
| `.example` | 1 |
| `.gitignore` | 1 |
| `.prisma` | 1 |
| `.yml` | 1 |

## 4. Maiores arquivos do projeto

| Arquivo | Bytes |
| --- | ---: |
| `public/brand/mourao-ui-texture.png` | 2712581 |
| `public/og.png` | 1028352 |
| `package-lock.json` | 423211 |
| `public/brand/mourao-original.jpeg` | 240976 |
| `public/brand/mourao-crest-navy.jpeg` | 228614 |
| `prisma/schema.prisma` | 67362 |
| `public/brand/mourao-crest-light.jpeg` | 65272 |
| `app/globals.css` | 53792 |
| `prisma/migrations/0001_init/migration.sql` | 41912 |
| `app/financial-workspace.tsx` | 41219 |
| `tests/integration/postgresql-phase-zero.test.mjs` | 38428 |
| `app/command-center.tsx` | 36584 |
| `tests/integration/zz-postgresql-phase-two.test.mjs` | 36131 |
| `prisma/migrations/0007_bank_reconciliation/migration.sql` | 33234 |
| `prisma/seed.sql` | 31801 |

## 5. Estrutura funcional

- `app/`: interface web, contexto, rotas e APIs versionadas.
- `server/`: infraestrutura e serviços executados no servidor.
- `worker/`: entrada e adaptação para execução em worker.
- `prisma/`: schema, migrations e seed.
- `db/` e `drizzle/`: contratos, políticas e artefatos SQL complementares.
- `tests/`: testes locais e integrações PostgreSQL.
- `docs/`: arquitetura, decisões, roadmap, fases e retomadas.
- `public/`: ativos públicos e identidade visual.
- `examples/`: exemplos versionados do projeto.
- `skills/`, `.agents/`, `.codex/` e `.openai/`: instruções auxiliares versionadas do ambiente de desenvolvimento.
- `git-history/`: bundle, patch e evidências Git adicionados pela embalagem.
- `handoff-docs/`: documentação executiva e operacional adicionada pela embalagem.

## 6. Inventário completo dos arquivos do projeto

```text
.env.example
.gitignore
.openai/hosting.json
AGENTS.md
app/api/v1/_shared/bank-collection.ts
app/api/v1/_shared/financial-collection.ts
app/api/v1/bank/imports/route.ts
app/api/v1/bank/transactions/route.ts
app/api/v1/financial/catalogs/route.ts
app/api/v1/me/route.ts
app/api/v1/payables/history/route.ts
app/api/v1/payables/reversals/route.ts
app/api/v1/payables/route.ts
app/api/v1/payables/settlements/route.ts
app/api/v1/receivables/history/route.ts
app/api/v1/receivables/reversals/route.ts
app/api/v1/receivables/route.ts
app/api/v1/receivables/settlements/route.ts
app/application-context.tsx
app/application.tsx
app/chatgpt-auth.ts
app/command-center.tsx
app/financial-request-guards.ts
app/financial-workspace.tsx
app/globals.css
app/identity-client.ts
app/layout.tsx
app/page.tsx
build/sites-vite-plugin.ts
cloudflare-env.d.ts
db/index.ts
db/schema.ts
docker-compose.yml
docs/API.md
docs/ARCHITECTURE.md
docs/AUDIT.md
docs/DATABASE.md
docs/DECISIONS.md
docs/DECISOES_ARQUITETURAIS.md
docs/DEPLOYMENT.md
docs/DESIGN_SYSTEM.md
docs/ESTADO_ATUAL_CLAREZA.md
docs/ESTADO_ATUAL.md
docs/FASE_0_VALIDACAO_REAL.md
docs/FASE_1_IDENTIDADE_CONTEXTO.md
docs/fases/FASE_1_IDENTIDADE_CONTEXTO.md
docs/fases/FASE_2_NUCLEO_FINANCEIRO.md
docs/fases/FASE_3_CONCILIACAO.md
docs/GLOSSARY.md
docs/LGPD.md
docs/MODULES.md
docs/PROTOCOLO_DE_FASES.md
docs/RETOMADA_MOURAO_CLAREZA_2026-08-04.md
docs/ROADMAP_MASTER.md
docs/ROADMAP.md
docs/SECURITY.md
docs/SETUP.md
docs/UX.md
drizzle.config.ts
drizzle/meta/_journal.json
eslint.config.mjs
examples/d1/app/api/notes/route.ts
examples/d1/db/schema.ts
next.config.ts
package-lock.json
package.json
postcss.config.mjs
prisma.config.ts
prisma/migrations/0001_init/migration.sql
prisma/migrations/0002_identity_bootstrap_and_branch_rls/migration.sql
prisma/migrations/0003_financial_api_foundation/migration.sql
prisma/migrations/0004_identity_branch_directory/migration.sql
prisma/migrations/0005_financial_operations/migration.sql
prisma/migrations/0006_phase_two_hardening/migration.sql
prisma/migrations/0007_bank_reconciliation/migration.sql
prisma/phase-zero-seed.sql
prisma/schema.prisma
prisma/seed.sql
PROMPT_UNICO_CODEX.md
public/brand/mourao-crest-light.jpeg
public/brand/mourao-crest-navy.jpeg
public/brand/mourao-original.jpeg
public/brand/mourao-ui-texture.png
public/favicon.svg
public/file.svg
public/globe.svg
public/og.png
public/window.svg
README_AUTOMACAO.md
README.md
server/auth-context.mjs
server/bank-imports.mjs
server/bank-service.mjs
server/financial-catalogs.mjs
server/financial-events.mjs
server/financial-records.mjs
server/prisma.ts
server/tenant-context.mjs
skills/clareza-development/SKILL.md
tests/api-auth.test.mjs
tests/auth-context.test.mjs
tests/bank-imports.test.mjs
tests/bank-service.test.mjs
tests/financial-events.test.mjs
tests/financial-records.test.mjs
tests/financial-request-guards.test.mjs
tests/identity-interface.test.mjs
tests/integration/postgresql-phase-zero.test.mjs
tests/integration/zz-postgresql-phase-two.test.mjs
tests/rendered-html.test.mjs
tests/sql/phase3-bank-reconciliation-gate.sql
tests/tenant-isolation.test.mjs
tsconfig.json
vite.config.ts
worker/index.ts
```

## 7. Exclusões deliberadas

- `.git/` bruto — substituído por bundle Git portátil;
- `node_modules/`;
- saídas geradas em `dist/`, `.next/`, `.vinext/` e caches; o script-fonte rastreado em `build/` permanece incluído;
- `.wrangler/`, `work/` e saídas temporárias;
- `.env` real, chaves privadas e credenciais;
- bancos, dumps, backups e dados de produção.

A ausência desses itens é intencional: eles não pertencem ao código-fonte transferível ou representam risco de segurança e privacidade.
