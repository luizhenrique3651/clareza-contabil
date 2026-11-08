# Setup local

## Objetivo

Executar a experiência local e validar a primeira fundação operacional
PostgreSQL/Prisma. A página raiz ainda abre sem banco e exibe dados
demonstrativos; as APIs `/api/v1` exigem banco configurado.

## Pré-requisitos

- Node.js `>=22.13.0`;
- npm compatível com o `package-lock.json`;
- Docker Desktop/Engine e Docker Compose apenas para a fundação PostgreSQL;
- Git.

Nunca use dump, credencial ou documento de cliente no ambiente local.

## Frontend

```bash
npm install
npm run dev
```

Use o endereço exibido no terminal. Nenhum `.env` é necessário para a Central de Comando atual.

### Validação

```bash
npm run lint
npm run typecheck
npm test
```

`npm test` já executa o build e todos os arquivos `tests/*.test.mjs`, incluindo o teste local de isolamento quando presente.

Esse teste usa um repositório em memória para provar a derivação do tenant a partir do principal. Ele não substitui os futuros testes de integração contra PostgreSQL/RLS.

## PostgreSQL opcional

`docker-compose.yml`, `.env.example`, o schema, as migrations e o seed compõem a
fundação operacional. O Compose sobe **somente o PostgreSQL**; as migrations e o
seed são comandos explícitos.

```bash
docker compose config --quiet
docker compose up -d postgres
docker compose ps
```

Os valores padrão são exclusivamente locais. Copie `.env.example` para `.env` somente para personalizá-los ou fornecer URLs a uma ferramenta. Não versione `.env`.

O schema está em `prisma/schema.prisma`. As migrations incluem a fundação
inicial, o bootstrap seguro de identidade/RLS por unidade e a idempotência das
APIs financeiras. Em um banco local novo e descartável:

```bash
npm run db:validate
npm run db:generate
npm run db:migrate:deploy
npm run db:seed
```

`DIRECT_DATABASE_URL` é usada pelas migrations e pelo seed.
`DATABASE_URL` é usada pelo runtime com a role sem `BYPASSRLS`. Não execute o
runtime com superusuário em produção.

## Variáveis previstas

| Variável | Uso | Necessária agora? |
| --- | --- | --- |
| `DATABASE_URL` | conexão da aplicação via Prisma/adapter PostgreSQL | para APIs |
| `DIRECT_DATABASE_URL` | migrations e acesso administrativo controlado | para banco |

Segredos reais não pertencem ao `.env.example`.

## Portas e arquivos gerados

O endereço do frontend e a porta do PostgreSQL são informados pelas ferramentas/configuração. Diretórios como `.vinext/`, `.wrangler/`, `dist/` e `.next/` são artefatos locais e não devem ser editados.

## Solução de problemas

### `npm.ps1` bloqueado no PowerShell

Se a política do Windows impedir a execução de `npm.ps1`, use `npm.cmd` (`npm.cmd install`, `npm.cmd run dev`, `npm.cmd test`). Não reduza a política de segurança da máquina sem orientação da organização.

### Build desatualizado

Interrompa o servidor, remova apenas os artefatos de build do projeto e execute o build novamente. Não apague código, banco ou arquivos fora do workspace.

### Binding D1 ausente

O produto não usa D1 como banco-alvo. O binding do starter está desabilitado em `.openai/hosting.json`; a Central de Comando não deve chamar `getDb()`.

### Fonte ou asset externo indisponível

O build pode precisar de recursos resolvidos pela stack do frontend. Registre o erro completo e confirme rede, versão do Node e cache antes de alterar dependências.

### PostgreSQL não saudável

Use `docker compose ps` e os logs do serviço específico. Não recrie volumes com dados relevantes; o banco local deve conter apenas seed fictício e ser descartável.
