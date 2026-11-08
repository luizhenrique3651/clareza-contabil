# Segurança

## Postura do incremento

O protótipo é local e usa dados fictícios. Ele **não possui uma cadeia de segurança pronta para produção**.

As primeiras APIs usam a identidade do ambiente Sites, resolvem membership e
permissões internos e executam operações financeiras em contexto RLS. Isso ainda
não equivale a uma cadeia pronta para produção: não há sessão própria, MFA,
monitoramento nem teste de integração PostgreSQL. Nenhum dado real deve ser
carregado.

## Objetivos

1. impedir acesso entre tenants e empresas;
2. aplicar menor privilégio por usuário, função e escopo;
3. preservar confidencialidade e integridade de documentos e valores;
4. manter origem e autoria verificáveis;
5. falhar de forma segura e observável;
6. reduzir dados sensíveis em logs, cópias e ambientes de desenvolvimento.

## Modelo de confiança

```text
navegador não confiável
→ borda/hosting
→ autenticação
→ membership + RBAC
→ caso de uso
→ transação com tenant confiável
→ RLS/constraints no PostgreSQL
```

Headers injetados pela plataforma só podem ser confiados após validação de origem e remoção de headers equivalentes enviados pelo cliente. Identidade do provedor não substitui a autorização interna.

## Controles-alvo

### Autenticação e sessão

- provedor de identidade configurado com MFA para perfis privilegiados;
- cookies `Secure`, `HttpOnly` e `SameSite` adequados, quando houver sessão por cookie;
- rotação e revogação de sessão;
- reautenticação para ações críticas;
- proteção contra enumeração de contas;
- credenciais locais, se adotadas, com hash de senha resistente e parâmetros revisáveis.

### Autorização

- negação por padrão;
- permissões granulares, por exemplo `financial.view`, `payables.manage` e `audit.view`;
- escopo por tenant, empresa e, quando aplicável, unidade;
- autorização no servidor em leitura, escrita, exportação e download;
- administração de papéis e acesso sempre auditada;
- contas de suporte sem acesso permanente e irrestrito.

Papéis iniciais de referência são: administrador da empresa, sócio, diretor, gestor, CFO, controller, financeiro, RH, departamento pessoal, contábil, fiscal, consultor, auditor e somente leitura. Eles são arquétipos para compor permissões e escopos, não enums imutáveis. A semântica de “Super Admin” permanece aberta: o modelo atual de `User` é vinculado a um tenant e não implementa um administrador global seguro. Qualquer acesso de plataforma/suporte exigirá identidade separada, *break-glass* com prazo e justificativa, menor privilégio e auditoria reforçada.

### Banco e isolamento

A fundação PostgreSQL define RLS forçada e contexto transacional. Nas APIs
iniciais, o servidor:

1. deriva do principal e de memberships válidas `app.tenant_id`, `app.user_id`,
   `app.company_ids`, `app.branch_ids` e `app.tenant_wide_access`;
2. inicia uma transação;
3. aplica os cinco valores como contexto local com `set_config(..., true)`;
4. executa somente dentro dessa transação;
5. finaliza o contexto antes de devolver a conexão ao pool.

`app.company_ids` e `app.branch_ids` contêm somente escopos autorizados, e
`app.tenant_wide_access` só é verdadeiro para um escopo tenant-wide efetivo. RLS
é segunda barreira; não substitui validação de membership nem filtros corretos.
A role da aplicação não possui privilégio de bypass.

**Limitação conhecida:** o escopo por unidade foi implementado na migration 0002,
mas ainda não foi validado contra PostgreSQL real. Até existirem testes negativos
entre unidades da mesma empresa, essa fronteira não pode ser tratada como pronta
para produção.

### Aplicação e API

- schemas de entrada com limites de tamanho e listas de valores permitidos;
- queries parametrizadas via ORM; SQL bruto somente revisado e parametrizado;
- encoding de saída e política CSP contra XSS;
- CSRF para mutações autenticadas por cookie;
- CORS restrito a origens necessárias;
- rate limit por principal, tenant, IP e operação conforme risco;
- upload com tipo/tamanho permitidos, varredura e armazenamento fora da raiz pública;
- SSRF bloqueado por allowlist e resolução segura em integrações;
- idempotência em importações e eventos financeiros.

### Segredos e criptografia

- segredos apenas em cofre/variáveis do ambiente, nunca em código, seed ou log;
- TLS em trânsito;
- criptografia em repouso para banco, backups e documentos;
- rotação de chaves e credenciais;
- separação de ambientes e contas de serviço;
- dados especialmente sensíveis com proteção por campo quando a análise de risco exigir.

## Ameaças prioritárias

| Ameaça | Controle principal | Verificação |
| --- | --- | --- |
| acesso cruzado entre tenants | contexto autenticado + autorização + RLS + FKs compostas | testes negativos de integração/E2E |
| elevação de privilégio | RBAC no servidor e negação por padrão | matriz de permissões automatizada |
| adulteração contábil | constraints, transações e trilha de auditoria | testes de invariantes e reconciliação |
| exposição em logs | mascaramento e lista de campos proibidos | testes de log e revisão de observabilidade |
| arquivo malicioso | quarentena, validação e varredura | conjunto de uploads adversariais |
| injeção | validação e queries parametrizadas | SAST/DAST e testes específicos |
| resposta de IA sem fundamento | recuperação restrita e referências obrigatórias | avaliações de autorização e atribuição |

## Dados proibidos em logs

- senhas, hashes e códigos de recuperação;
- tokens, cookies, chaves de API e strings de conexão;
- documentos completos, dados bancários completos e identificadores pessoais desnecessários;
- conteúdo integral de evidências ou uploads;
- payloads antes/depois sem filtragem.

## Testes e gates

Antes de produção são obrigatórios:

- teste “Empresa A não lê, altera, vincula ou infere dados da Empresa B”;
- teste de bypass de IDs, filtros, exportações e URLs assinadas;
- revisão de dependências e artefatos de build;
- secret scanning, SAST e testes de segurança da API;
- threat modeling por fluxo crítico;
- plano de correção com severidade e prazo;
- pentest independente antes de dados reais de maior risco.

## Vulnerabilidades e incidentes

O canal de reporte, a classificação de severidade, os responsáveis e o processo de resposta ainda precisam ser formalizados. Até lá, o repositório não deve ser apresentado como ambiente operacional.
