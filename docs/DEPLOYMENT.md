# Deployment

## Estado

O repositório gera um build vinext/Cloudflare, mas o Clareza **não está aprovado para produção**. Não há API de negócio integrada, autenticação/RBAC completo, persistência operacional, observabilidade ou processo de recuperação validado.

## Topologia pretendida

```text
Usuário
  │
  ▼
Sites / CDN / frontend vinext
  │ HTTPS
  ▼
API do monólito modular
  │
  ├─ PostgreSQL gerenciado
  ├─ armazenamento de documentos
  └─ observabilidade/filas quando necessárias
```

O hosting da API, a região, o pool/conector PostgreSQL e a estratégia de armazenamento são decisões abertas. A latência para usuários, residência de dados, LGPD, backups e compatibilidade do Prisma devem orientar a escolha.

## Ambientes

| Ambiente | Dados | Finalidade |
| --- | --- | --- |
| local | sintéticos | desenvolvimento |
| preview | sintéticos e isolados | revisão por mudança |
| staging | sintéticos representativos | integração, segurança e migração |
| produção | reais autorizados | operação após gates |

Nenhum ambiente não produtivo recebe cópia bruta de produção.

## Build do frontend

```bash
npm ci
npm run lint
npm run typecheck
npm test
```

O deploy deve usar artefato imutável produzido pelo CI. `.openai/hosting.json` atualmente não declara binding D1 ou R2; qualquer binding futuro exige revisão de finalidade e ambiente.

## Pipeline-alvo

```text
commit
→ lint/typecheck/testes
→ análise de dependências e segredos
→ build reproduzível
→ preview
→ testes E2E/acessibilidade/segurança
→ aprovação
→ migration compatível
→ deploy gradual
→ smoke test e observação
```

Frontend e API podem ser promovidos separadamente somente com contratos compatíveis.

## Banco de dados

- migrations são revisadas e aplicadas por identidade própria, não pela aplicação web;
- alterações seguem expandir → migrar/backfill → trocar leitura → remover;
- backup e restauração são testados antes de migrations destrutivas;
- RLS e privilégios são verificados após cada mudança;
- a role de runtime não é owner e não possui `BYPASSRLS`;
- contexto `app.tenant_id`, `app.user_id`, `app.company_ids` e `app.tenant_wide_access` é derivado da autorização, local à transação e não vaza pelo pool;
- seed é proibido em produção.

## Configuração e segredos

- variáveis por ambiente em cofre gerenciado;
- menor privilégio para CI e runtimes;
- rotação sem rebuild quando possível;
- logs do deploy ocultam valores;
- domínios, CORS, cookies e callbacks usam listas de permissões específicas por ambiente.

## Gates de produção

Antes do primeiro dado real:

- autenticação, revogação e RBAC implementados;
- testes E2E de isolamento entre tenants, incluindo exportações e documentos;
- API e schemas de validação versionados;
- RLS/constraints verificadas em PostgreSQL real;
- revisão de segurança e pentest conforme risco;
- inventário LGPD, contratos, retenção e canal de titulares;
- observabilidade, alertas, runbooks e plantão/responsável;
- backups com restauração comprovada e RPO/RTO aprovados;
- acessibilidade e responsividade validadas;
- conteúdo regulatório revisado por especialista;
- plano de rollback testado.

## Rollback

Código deve permitir rollback para a versão anterior compatível. Migration destrutiva não é revertida automaticamente: requer plano de recuperação de dados, feature flag ou estratégia de compatibilidade. Após rollback, executar smoke tests de autenticação, escopo, leitura e integridade contábil.

## Pós-deploy

Monitorar taxa de erro, latência, saturação, falhas de autenticação, negações anormais, jobs, pool, consultas lentas e eventos de auditoria ausentes. Métricas usam IDs e dimensões seguras, nunca payload financeiro ou documento completo.
