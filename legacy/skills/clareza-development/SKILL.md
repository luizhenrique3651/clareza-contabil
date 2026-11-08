# Clareza Development Workflow

## O que esta skill faz

Conduz a evolução do projeto Clareza usando o próprio repositório como fonte persistente de contexto, reduzindo dependência de prompts manuais entre fases.

## Entradas

Repositório Clareza contendo:

- `AGENTS.md`
- `docs/ESTADO_ATUAL.md`
- `docs/ROADMAP_MASTER.md`
- `docs/DECISOES_ARQUITETURAIS.md`
- `docs/PROTOCOLO_DE_FASES.md`

## Procedimento

1. Leia `AGENTS.md`.
2. Leia os quatro documentos de controle.
3. Identifique a fase ativa.
4. Execute baseline.
5. Implemente somente o escopo da fase.
6. Teste progressivamente.
7. Faça revisão adversarial.
8. Corrija falhas dentro do escopo.
9. Execute regressão.
10. Gere relatório da fase.
11. Atualize estado e roadmap.
12. Se o gate passar:
   - avance se `AUTO_ADVANCE: true`;
   - pare se `AUTO_ADVANCE: false`.
13. Nunca use dados reais sem autorização explícita e gate de produção.

## Saída obrigatória

Ao final de cada fase:

- relatório em `docs/fases/`;
- `ESTADO_ATUAL.md` atualizado;
- `ROADMAP_MASTER.md` atualizado;
- veredito objetivo;
- quantidade de testes e falhas;
- riscos remanescentes;
- próxima ação.

## Verificação final

Antes de concluir, confirme:

- nenhum critério bloqueante foi ignorado;
- nenhum mock foi apresentado como dado real;
- nenhuma proteção da Fase 0 regrediu;
- nenhuma decisão humana foi improvisada;
- documentação e código descrevem o mesmo estado.
