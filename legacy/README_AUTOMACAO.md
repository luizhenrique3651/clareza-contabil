# Kit de automação do desenvolvimento do Clareza

## O que é

Este pacote transforma o repositório do Clareza na principal fonte de contexto do Codex.

A ideia é evitar o ciclo manual de copiar relatório para outro chat, pedir um novo prompt e voltar ao Codex.

## Arquivos

- `AGENTS.md` — regras permanentes para agentes que trabalham no repositório.
- `docs/ROADMAP_MASTER.md` — fases, gates e regras de avanço.
- `docs/ESTADO_ATUAL.md` — snapshot atual do projeto.
- `docs/DECISOES_ARQUITETURAIS.md` — decisões que não devem ser rediscutidas sem evidência.
- `docs/PROTOCOLO_DE_FASES.md` — ciclo executor → revisor → correção → gate.
- `skills/clareza-development/SKILL.md` — versão reutilizável do fluxo para instalar/gerenciar como Skill, caso sua superfície do Codex use Skills.
- `PROMPT_UNICO_CODEX.md` — comando inicial recomendado.

## Instalação no repositório

1. Coloque `AGENTS.md` na raiz do repositório Clareza.
2. Mescle os arquivos da pasta `docs/` com a pasta `docs/` existente.
3. Preserve os relatórios técnicos já existentes, especialmente a validação da Fase 0.
4. Revise o `git diff`.
5. Faça commit desse “sistema operacional de desenvolvimento” antes da próxima fase.

## Como usar no dia a dia

Depois de instalado, a instrução normal pode ser apenas:

> Continue o Clareza conforme o AGENTS.md e o roadmap. Execute integralmente a fase ativa, faça revisão adversarial, corrija falhas, rode o gate, atualize a documentação e avance somente quando autorizado pelo roadmap.

## Skills

O arquivo `skills/clareza-development/SKILL.md` é um playbook reutilizável.

A forma exata de instalar/gerenciar Skills pode depender da superfície do Codex usada. O Codex App possui interface dedicada para criar e gerenciar Skills. O fluxo também funciona sem a Skill, porque as regras essenciais já estão no `AGENTS.md` e nos documentos do repositório.

## Autonomia segura

O roadmap foi configurado para permitir avanço automático em fases de implementação controlável, mas parar antes de decisões de alto impacto, como:

- contabilidade;
- fiscal;
- IA com poder de ação;
- produção;
- autenticação definitiva quando a decisão se tornar necessária.

Isso reduz trabalho manual sem permitir que o agente tome sozinho decisões irreversíveis.
