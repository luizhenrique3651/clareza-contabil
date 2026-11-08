# API

## Estado atual

Existe uma fatia financeira operacional:

- `GET /api/v1/me`;
- `GET|POST /api/v1/financial/catalogs`;
- `GET|POST /api/v1/payables`;
- `GET|POST /api/v1/receivables`;
- `GET /api/v1/payables/history`;
- `POST /api/v1/payables/settlements`;
- `POST /api/v1/payables/reversals`;
- `GET /api/v1/receivables/history`;
- `POST /api/v1/receivables/settlements`;
- `POST /api/v1/receivables/reversals`.

Essas rotas usam identidade autenticada encaminhada pelo Sites, resolvem
membership e permissões no servidor, exigem seleção autorizada de empresa e
executam o acesso empresarial dentro de uma transação Prisma com contexto RLS.
Mutações financeiras exigem `Idempotency-Key` e gravam auditoria na mesma
transação. Baixas e estornos são eventos append-only; saldo, status e data de
liquidação são projeções derivadas.

A Central de Comando consome as rotas operacionais de títulos, catálogos,
baixas, estornos e histórico. KPIs, DRE, caixa, diagnósticos e pendências ainda
são demonstrativos. O contrato OpenAPI continua planejado.

## Autenticação e seleção de contexto da fatia atual

O hosting encaminha `oai-authenticated-user-email`. Esse valor identifica a
pessoa, mas não concede acesso empresarial. A API consulta o diretório interno
de identidades e memberships.

Nas rotas financeiras:

- `x-clareza-company-id` é obrigatório;
- `x-clareza-branch-id` é opcional para acesso de empresa e obrigatório quando
  a membership estiver limitada a uma unidade;
- ambos são apenas seleções e são comparados com os acessos resolvidos no
  servidor;
- `tenantId`, `companyId`, `branchId` e `createdById` enviados no JSON são
  rejeitados.

Exemplo de criação:

```http
POST /api/v1/payables
Idempotency-Key: payable-2026-0001
x-clareza-company-id: 20000000-0000-4000-8000-000000000001
Content-Type: application/json
```

```json
{
  "counterpartyName": "Fornecedor Exemplo",
  "description": "Serviço mensal",
  "documentNumber": "DOC-2026-001",
  "issueDate": "2026-07-20",
  "dueDate": "2026-08-20",
  "amount": "1500.2500",
  "currencyCode": "BRL"
}
```

Dinheiro entra como string decimal. O servidor deriva tenant, empresa, unidade,
ator, saldo em aberto e status inicial.

## Convenções-alvo

- prefixo: `/api/v1`;
- JSON em UTF-8 e nomes de campos em `camelCase`;
- timestamps ISO 8601 em UTC;
- valores monetários como string decimal e moeda explícita;
- IDs opacos; o cliente não depende do formato;
- paginação por cursor para coleções mutáveis;
- filtros e ordenações em lista permitida por recurso;
- `Idempotency-Key` para importações e mutações financeiras adequadas;
- OpenAPI como contrato publicado e validado no CI.

## Identidade e contexto

A API autentica o principal e resolve no servidor suas memberships, tenant, empresas, unidades e permissões. Campos como `tenantId`, `companyId` ou `branchId` enviados pelo cliente são seleção de escopo, nunca prova de autorização.

Fluxo obrigatório:

```text
credencial/sessão
→ principal autenticado
→ membership ativa
→ tenant e empresa autorizados
→ permissão
→ transação com contexto do banco
→ caso de uso
```

Um recurso existente em outro tenant deve responder de forma que não revele sua existência. A escolha entre `403` e `404` será padronizada por tipo de operação e documentada no OpenAPI.

## Recursos iniciais

```text
GET    /api/v1/me                                      # implementado
GET    /api/v1/companies                               # planejado
GET    /api/v1/companies/{companyId}/branches

GET    /api/v1/payables                                # implementado
POST   /api/v1/payables                                # implementado
GET    /api/v1/payables/history                        # implementado
POST   /api/v1/payables/settlements                    # implementado
POST   /api/v1/payables/reversals                      # implementado
GET    /api/v1/receivables                             # implementado
POST   /api/v1/receivables                             # implementado
GET    /api/v1/receivables/history                     # implementado
POST   /api/v1/receivables/settlements                 # implementado
POST   /api/v1/receivables/reversals                   # implementado

GET    /api/v1/financial/catalogs                      # implementado
POST   /api/v1/financial/catalogs                      # implementado

GET    /api/v1/accounting/journal-entries
POST   /api/v1/accounting/journal-entries
GET    /api/v1/reports/income-statement

GET    /api/v1/glossary/terms
GET    /api/v1/pending-items
GET    /api/v1/risks
GET    /api/v1/action-plans
```

Itens sem indicação de implementação continuam como direção de design.

## Coleções

Exemplo proposto:

```http
GET /api/v1/payables?status=open&limit=50&cursor=opaque
x-clareza-company-id: 20000000-0000-4000-8000-000000000001
```

```json
{
  "data": [],
  "page": {
    "nextCursor": null,
    "hasMore": false
  },
  "meta": {
    "requestId": "req_..."
  }
}
```

- `limit` possui teto definido pelo servidor;
- cursor é opaco e vinculado aos filtros;
- busca textual deve ter tamanho mínimo/máximo;
- filtros inválidos retornam erro, em vez de serem silenciosamente ignorados.

## Erros

O alvo é um formato consistente de *problem details*:

```json
{
  "type": "https://clareza.example/problems/validation",
  "title": "Dados inválidos",
  "status": 422,
  "code": "VALIDATION_ERROR",
  "requestId": "req_...",
  "errors": [
    { "field": "dueDate", "code": "INVALID_DATE", "message": "Informe uma data válida." }
  ]
}
```

Mensagens públicas não contêm stack trace, SQL, segredo, regra interna de detecção ou dado de outro tenant.

| Status | Uso esperado |
| --- | --- |
| 400 | sintaxe ou parâmetro malformado |
| 401 | autenticação ausente ou inválida |
| 403 | principal conhecido sem permissão |
| 404 | recurso não encontrado no escopo autorizado |
| 409 | conflito de estado ou idempotência |
| 422 | campos válidos sintaticamente, mas rejeitados pela regra |
| 429 | limite excedido |

## Concorrência e idempotência

Recursos editáveis devem usar versão ou `ETag`/`If-Match` quando uma gravação perdida for relevante. Operações financeiras e importações não podem ser duplicadas por retry; a chave de idempotência é armazenada com principal, tenant, operação e resultado.

## Cálculos e explicações

Respostas de indicadores devem poder incluir:

```json
{
  "value": "125000.00",
  "currency": "BRL",
  "period": { "from": "2026-01-01", "to": "2026-06-30" },
  "calculation": { "rule": "net-income", "version": "1" },
  "source": { "updatedAt": "2026-07-20T12:00:00Z", "drillDownAvailable": true }
}
```

Explicações automáticas não podem omitir a origem nem apresentar inferência como fato.

## Compatibilidade

Mudança incompatível exige nova versão ou período formal de depreciação. Campos podem ser adicionados de forma compatível; clientes devem ignorar campos desconhecidos. O contrato OpenAPI e testes de consumidor definirão o que é compatível na prática.
