# Arquitetura

## Objetivo

O Clareza evoluirá como um **monólito modular**: uma unidade de implantação no início, com fronteiras de domínio explícitas e sem a sobrecarga prematura de microsserviços. Clareza, isolamento de dados, testabilidade e rastreabilidade têm prioridade sobre abstrações.

## Arquitetura do incremento atual

```text
Navegador
   │
   ▼
Frontend React / App Router
   │  dados fictícios e interações locais
   ▼
vinext + Vite
   │
   ▼
Cloudflare Worker / Sites
```

O protótipo possui uma única experiência integrada de Central de Comando. A camada visual não está conectada a uma API de negócio nem a PostgreSQL. Qualquer artefato de schema ou contrato presente neste incremento representa fundação, não persistência operacional.

## Arquitetura-alvo

```text
Sites/vinext frontend
        │ HTTPS /api/v1
        ▼
API do monólito modular
        │
        ├─ identidade, sessão, RBAC e contexto do tenant
        ├─ empresas, unidades e usuários
        ├─ financeiro e contabilidade
        ├─ glossário, pendências e riscos
        ├─ auditoria, diagnósticos e planos de ação
        └─ importações, relatórios e notificações
        │
        ├─ Prisma ── PostgreSQL
        ├─ armazenamento de documentos
        └─ fila/processamento assíncrono, somente quando necessário
```

O frontend Sites/vinext é a superfície de experiência. O runtime da API e a estratégia de conectividade segura com PostgreSQL ainda precisam ser decididos antes da implantação produtiva; não se presume que uma conexão longa e direta a banco seja adequada a um runtime edge.

## Fronteiras e dependências

Cada módulo de domínio deve expor casos de uso e contratos estáveis. Regras de negócio não devem depender de componentes React, transporte HTTP ou detalhes do Prisma.

Fluxo preferido:

```text
rota/controlador → caso de uso → domínio → porta de repositório
                                      ↑            │
                                      └─ adaptador Prisma
```

- **Apresentação:** páginas, componentes, acessibilidade e estado de interface.
- **Aplicação:** autorização, transações e orquestração de casos de uso.
- **Domínio:** invariantes, cálculos e linguagem de negócio.
- **Infraestrutura:** Prisma, PostgreSQL, arquivos, mensageria e serviços externos.

DDD, ports/adapters e repository pattern serão aplicados apenas onde preservarem uma fronteira ou facilitarem testes; não são metas de quantidade de pastas.

## Multi-tenancy

A hierarquia conceitual é `Plataforma → Tenant → Empresa → Unidade → Departamento`.

Regras obrigatórias da arquitetura-alvo:

1. autenticação estabelece a identidade;
2. o servidor resolve participações, tenant e escopo de empresa/unidade;
3. autorização valida permissão e escopo em cada operação;
4. repositórios sempre recebem contexto de acesso confiável;
5. toda consulta e mutação de dado empresarial aplica o filtro de tenant;
6. testes negativos provam que um usuário da Empresa A não acessa a Empresa B.

Um `tenantId` enviado por query, body ou header do navegador jamais concede acesso. Quando necessário para selecionar contexto, ele é apenas uma solicitação confrontada com a sessão autenticada.

Na fundação PostgreSQL, o contexto RLS inclui `app.tenant_id`, `app.user_id`, a lista autorizada `app.company_ids` e `app.tenant_wide_access`. A aplicação deverá derivar todos esses valores de memberships válidas e aplicá-los localmente dentro da transação.

## Rastreabilidade

Indicadores e explicações devem carregar, conforme o caso:

- tenant, empresa e unidade autorizados;
- período e moeda;
- fórmula ou regra utilizada e sua versão;
- registros de origem e data de atualização;
- nível de explicação exibido;
- autor e histórico de alterações manuais.

Isso permite o drill-down `resumo → indicador → categoria → conta → lançamento → documento` sem criar números opacos.

## Requisitos de qualidade

- TypeScript estrito nas fronteiras públicas;
- validação de entrada no servidor;
- transações para invariantes que abrangem múltiplos registros;
- contratos OpenAPI para a API;
- observabilidade sem dados sensíveis;
- testes unitários de cálculo, integração de repositório e E2E de autorização;
- acessibilidade WCAG 2.2 AA como alvo de produto;
- degradação segura: falhas não devem exibir conclusões parciais como definitivas.

## Fora do incremento atual

Autenticação de produto, autorização granular, persistência PostgreSQL, API de domínio, upload, processamento de documentos, motor de regras, integrações bancárias/contábeis e IA não estão implementados de ponta a ponta.

Consulte [Decisões](DECISIONS.md) para o registro de escolhas e [Roadmap](ROADMAP.md) para a ordem de evolução.
