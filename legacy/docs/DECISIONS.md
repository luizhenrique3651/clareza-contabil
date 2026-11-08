# Registro de decisões

## Convenções

- **Aceita:** orienta o incremento e a evolução.
- **Proposta:** direção preferida que ainda precisa de validação prática.
- **Aberta:** decisão não tomada.

As decisões são registradas em 20/07/2026, salvo indicação futura. “Aceita” não significa implementação completa.

## ADR-001 — Monólito modular

**Status:** aceita.

**Decisão:** iniciar o backend como uma unidade de implantação com fronteiras de domínio explícitas.

**Razão:** reduz complexidade operacional e permite transações fortes no estágio inicial.

**Consequência:** módulos não acessam livremente estruturas internas uns dos outros. Extração para serviço só ocorrerá por necessidade mensurável.

## ADR-002 — Sites/vinext para o frontend

**Status:** aceita.

**Decisão:** manter a experiência em React/TypeScript, App Router e vinext, preparada para Sites/Cloudflare.

**Razão:** o starter já oferece preview e runtime executável adequados ao protótipo.

**Consequência:** recursos de Next.js devem ser verificados quanto à compatibilidade vinext; a API de domínio não fica acoplada ao frontend.

## ADR-003 — PostgreSQL e Prisma como persistência-alvo

**Status:** aceita.

**Decisão:** usar PostgreSQL por integridade relacional/transacional e Prisma como ORM principal da aplicação.

**Razão:** o domínio exige constraints, relações, precisão decimal, migrações revisáveis e isolamento em profundidade.

**Consequência:** o scaffold D1/Drizzle do starter não é a persistência do produto. A estratégia de conexão entre runtime e PostgreSQL precisa ser validada.

## ADR-004 — Multi-tenancy com defesa em profundidade

**Status:** aceita.

**Decisão:** derivar o tenant do principal autenticado e aplicar membership/RBAC, filtros de repositório, constraints compostas e RLS forçada.

**Razão:** isolamento entre empresas é requisito absoluto.

**Consequência:** `tenantId` do cliente não é confiável; cada transação de banco recebe `app.tenant_id`, `app.user_id`, `app.company_ids` e `app.tenant_wide_access`, todos derivados da autorização. Testes de bypass são gates.

## ADR-005 — Protótipo com dados exclusivamente fictícios

**Status:** aceita.

**Decisão:** demonstrar a Central de Comando com fixtures locais enquanto API e persistência não estão integradas.

**Razão:** permite validar linguagem, hierarquia e interação sem antecipar uma cadeia operacional insegura.

**Consequência:** nenhuma tela pode ser apresentada como cálculo real, conciliação ou diagnóstico automático.

## ADR-006 — Explicação e origem como parte do dado

**Status:** aceita.

**Decisão:** indicadores relevantes carregam período, fórmula/regra, atualização e caminho para origem.

**Razão:** o diferencial do produto é compreensão verificável.

**Consequência:** APIs e modelos de leitura devem reservar metadados de explicação; uma narrativa sem evidência não atende ao contrato.

## ADR-007 — Sem pontuação ou causalidade “mágica”

**Status:** aceita.

**Decisão:** saúde, criticidade e recomendações usam critérios visíveis, contextuais e versionados.

**Razão:** valores universais podem induzir decisões incorretas em setores, portes e sazonalidades diferentes.

**Consequência:** a interface distingue correlação, contribuição e causa comprovada.

## ADR-008 — API REST versionada em `/api/v1`

**Status:** proposta.

**Decisão:** adotar REST/JSON, OpenAPI, erros consistentes, cursor e idempotência onde aplicável.

**Razão:** contratos simples e auditáveis atendem ao MVP e integrações futuras.

**Consequência:** detalhes permanecem provisórios até o primeiro fluxo vertical e testes de consumidor.

## ADR-009 — RLS e equilíbrio contábil na migration de fundação

**Status:** aceita como fundação; integração pendente.

**Decisão:** a migration inicial força RLS por tenant e adiciona proteção no banco para lançamentos contábeis balanceados.

**Razão:** invariantes críticas merecem barreira além do código da aplicação.

**Consequência:** o mecanismo precisa de testes em PostgreSQL real, revisão de concorrência e uma estratégia explícita para rascunhos versus postagem.

## ADR-010 — Hosting da API e conectividade PostgreSQL

**Status:** aberta.

**Questão:** executar API junto ao runtime edge, usar runtime Node/container separado ou adotar conector/proxy compatível?

**Critérios:** segurança, pooling, transações/RLS, região dos dados, latência, custo, observabilidade e suporte do Prisma.

## ADR-011 — Provedor de identidade

**Status:** aberta.

**Questão:** qual provedor e modelo de sessão atenderão clientes externos, MFA, revogação, suporte e segregação de tenants?

**Restrição:** identidade fornecida pelo hosting não prova membership da aplicação.

## ADR-012 — Nome do produto

**Status:** aberta.

**Decisão temporária:** usar “Clareza” como codinome e “Menos complexidade. Mais clareza.” como tagline de trabalho.

**Consequência:** domínio, marca, pacote e conteúdo legal não devem assumir nome definitivo.

## ADR-013 — Administração global da plataforma

**Status:** aberta.

**Questão:** “Super Admin” será papel de tenant, identidade operacional da plataforma ou fluxo temporário de suporte?

**Restrição:** o `User` atual pertence a um tenant. Nenhum administrador global está implementado. A solução futura precisa de *break-glass*, prazo, justificativa, segregação de funções e auditoria reforçada, sem impersonação silenciosa.

## ADR-014 — Escopo de unidade na RLS

**Status:** aberta e bloqueante para autorização por unidade.

**Questão:** como representar unidades autorizadas, herança de acesso e registros com `branch_id` nulo no contexto transacional?

**Restrição:** a RLS de fundação restringe tenant/empresa; `MembershipScope.BRANCH` ainda não cria isolamento por unidade. Produção exige política e testes específicos.

## Decisões pendentes adicionais

- modelo comercial e papéis LGPD;
- jurisdição/residência dos dados;
- armazenamento e antivírus de documentos;
- RPO, RTO e retenções;
- estratégia de contas contábeis e períodos;
- fontes e vigência de regras fiscais/trabalhistas;
- motor determinístico de indicadores;
- política de IA, fornecedores e revisão humana.
