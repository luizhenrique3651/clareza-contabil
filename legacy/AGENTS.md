# AGENTS.md — Clareza

## Missão

Você trabalha no **Clareza**, um sistema financeiro, contábil e empresarial multiempresa.

Seu objetivo é evoluir o produto por **fases verticais verificáveis**, preservando segurança, rastreabilidade, consistência financeira e a identidade visual existente.

O repositório e sua documentação são a fonte operacional do projeto. Antes de qualquer alteração relevante, leia os documentos listados em **Documentos obrigatórios**.

---

## Documentos obrigatórios

Leia, nesta ordem:

1. `docs/ESTADO_ATUAL.md`
2. `docs/ROADMAP_MASTER.md`
3. `docs/DECISOES_ARQUITETURAIS.md`
4. `docs/PROTOCOLO_DE_FASES.md`
5. relatório da fase anterior, quando existir.

Se algum documento estiver ausente, inconsistente com o código ou desatualizado, corrija a documentação como parte da execução, sem inventar fatos.

---

## Fonte de verdade

A prioridade de confiança é:

1. comportamento comprovado por testes executados;
2. banco e código atual;
3. migrations e contratos de API;
4. documentação atualizada;
5. mocks e protótipos visuais.

Nunca trate mock como dado operacional.

Nunca declare algo implementado apenas porque existe uma interface para isso.

---

## Regra principal de desenvolvimento

Trabalhe apenas na **fase ativa** indicada em `docs/ROADMAP_MASTER.md`.

Dentro da fase ativa:

1. inspecione o estado atual;
2. execute a regressão inicial;
3. implemente a menor mudança coerente;
4. teste;
5. tente quebrar sua própria implementação;
6. corrija;
7. execute a regressão completa;
8. atualize documentação;
9. produza evidências;
10. aplique o gate da fase.

Não amplie escopo para funcionalidades de fases futuras.

---

## Autonomia

Você está autorizado a, sem pedir confirmação intermediária:

- investigar o código;
- alterar código dentro do escopo da fase ativa;
- criar ou ajustar testes;
- corrigir regressões;
- refatorar de forma localizada;
- atualizar documentação;
- repetir testes quantas vezes forem necessárias;
- corrigir o que você próprio quebrou;
- atualizar o status da fase;
- criar o relatório técnico da fase;
- avançar para a próxima fase quando o gate estiver integralmente aprovado e o roadmap marcar a próxima transição como `AUTO_ADVANCE: true`.

Você NÃO está autorizado a avançar automaticamente quando a próxima transição exigir uma decisão humana listada em **Travas de decisão**.

---

## Travas de decisão

Pare e documente opções, riscos e recomendação antes de qualquer decisão irreversível ou de alto impacto relacionada a:

- provedor definitivo de autenticação;
- gestão de sessão de produção;
- MFA;
- mudança estrutural de estratégia multi-tenant;
- quebra de compatibilidade de banco;
- remoção destrutiva de migrations já aplicadas;
- mudança de paradigma contábil;
- regras fiscais/NFS-e/NF-e que dependam de município/provedor;
- retenção legal de documentos;
- migração de dados reais;
- uso de dados reais de clientes/pacientes;
- segredos ou credenciais reais;
- publicação em produção;
- alteração de infraestrutura de produção;
- exclusão irreversível de dados;
- mudanças de segurança que reduzam proteção existente.

Nesses casos, registre a decisão necessária em `docs/ESTADO_ATUAL.md` sob `DECISÃO HUMANA NECESSÁRIA` e não improvise.

---

## Segurança

O frontend nunca é fonte de autorização.

Tenant, empresa, unidade, ator e permissões devem ser derivados ou validados no servidor.

Toda operação sensível deve falhar fechada.

Não confie em:

- `localStorage`;
- `sessionStorage`;
- query strings;
- parâmetros de rota;
- headers controláveis pelo navegador;
- campos livres de payload.

Para qualquer fluxo multiempresa, tente deliberadamente acessar:

- outro tenant;
- outra empresa;
- outra unidade;
- contexto ausente;
- IDs cruzados;
- contexto residual de conexão reutilizada.

A Fase 0 comprovou RLS, RBAC, idempotência e atomicidade em PostgreSQL real. Não reduza essas garantias.

---

## Banco e migrations

- PostgreSQL é a referência operacional.
- Prisma e migrations SQL devem permanecer consistentes.
- Não edite migration antiga silenciosamente.
- Se uma migration já estiver em uso fora de ambiente descartável, prefira migration corretiva.
- Toda alteração estrutural deve incluir validação de schema e regressão.
- Não use banco de produção para testes.
- Não grave credenciais no repositório.

---

## Dinheiro

Não use ponto flutuante binário para valores monetários.

Preserve a estratégia decimal do projeto.

Saldo, quitação e situação financeira devem ser consequência de eventos e regras explícitas, não flags manuais sem rastreabilidade.

---

## Auditoria e idempotência

Operações financeiras críticas devem preservar:

- autorização;
- contexto;
- transação;
- auditoria;
- idempotência quando aplicável.

Não aceite mutação financeira sem evidência auditável quando a arquitetura exigir auditoria.

Não remova o tratamento de concorrência de idempotência sem prova melhor.

---

## Interface

Preserve a identidade visual existente.

Não faça redesign durante fases de backend/integração.

Toda área ainda alimentada por mocks deve ser marcada de forma inequívoca como demonstrativa.

Não exiba afirmações como “dados atualizados”, “conciliado”, “seguro” ou “calculado” quando a origem ainda for mock.

---

## Testes mínimos

Antes de declarar qualquer fase aprovada:

- lint;
- typecheck;
- build;
- testes existentes;
- testes novos da fase;
- regressão PostgreSQL da Fase 0 quando a mudança puder afetar backend, banco, autorização ou APIs.

Além de testes positivos, crie testes negativos.

Testes de segurança devem tentar provar que o sistema falha.

---

## Dependências

Não use `npm audit fix --force` apenas para zerar alertas.

Não misture upgrade estrutural de dependência central com uma fase funcional, salvo quando necessário para resolver bloqueio real.

Registre alertas e decisões.

---

## Git

Antes de mudanças relevantes:

- confira `git status`;
- preserve uma linha de base recuperável.

Ao concluir uma fase aprovada:

- deixe alterações coerentes;
- registre o conjunto de mudanças de forma rastreável;
- não reescreva histórico sem necessidade explícita.

---

## Executor e revisor

Para cada fase, aplique dois papéis lógicos.

### Executor

Implementa a solução e seus testes.

### Revisor adversarial

Depois da implementação, revise como se tivesse sido escrita por outra pessoa.

Procure:

- bypass de autorização;
- regressão de RLS/RBAC;
- corrida;
- inconsistência transacional;
- erro de escopo;
- vazamento entre contextos;
- mock disfarçado de dado real;
- cálculo financeiro incorreto;
- ausência de teste negativo;
- documentação divergente;
- dependência futura indevida;
- complexidade desnecessária.

Não aprove sua própria implementação sem essa segunda passagem.

Quando a superfície permitir múltiplos agentes/worktrees, prefira um agente executor e um agente revisor separados. Caso contrário, faça as duas passagens de forma explícita e independente.

---

## Gate

Uma fase só pode receber `APROVADA` quando todos os critérios objetivos do roadmap forem demonstrados.

Se um gate falhar:

1. marque a fase como `EM CORREÇÃO`;
2. registre a falha;
3. corrija;
4. repita o teste;
5. repita a regressão afetada.

Nunca “aceite com ressalvas” um critério definido como bloqueante.

---

## Relatório de fase

Cada fase deve terminar com um arquivo em `docs/fases/`, contendo no mínimo:

- objetivo;
- estado inicial;
- alterações;
- arquivos alterados;
- migrations;
- testes;
- evidências;
- problemas encontrados;
- correções;
- riscos;
- pendências;
- regressão;
- gate;
- veredito.

Vereditos permitidos:

- `FASE N APROVADA`
- `FASE N NÃO APROVADA`

---

## Estado do projeto

Ao concluir uma fase, atualize `docs/ESTADO_ATUAL.md` e `docs/ROADMAP_MASTER.md`.

O próximo agente deve conseguir continuar o projeto sem depender do histórico da conversa anterior.

---

## Comando humano esperado

Quando o usuário disser algo como:

> Continue o Clareza conforme o roadmap.

Interprete como autorização para executar integralmente a fase ativa conforme este arquivo, incluindo implementação, testes, revisão adversarial, documentação, correções e gate.

Não peça ao usuário para transportar relatórios entre agentes.
