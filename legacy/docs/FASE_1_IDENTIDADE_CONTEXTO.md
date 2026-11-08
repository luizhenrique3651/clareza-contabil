# Fase 1 — Identidade e contexto reais na interface

Este caminho é mantido por compatibilidade com a solicitação original da fase.

O relatório integral e canônico está em:

[`docs/fases/FASE_1_IDENTIDADE_CONTEXTO.md`](./fases/FASE_1_IDENTIDADE_CONTEXTO.md)

## Situação atual

- **Fase 1 aprovada**;
- `/api/v1/me` integrado;
- usuário, empresas, unidades e permissões reais na área autenticada;
- troca segura, revalidação após revogação e proteção contra respostas atrasadas;
- migration `0004_identity_branch_directory`;
- lint, typecheck, build e Prisma aprovados;
- 42/42 testes locais;
- 20/20 testes PostgreSQL;
- total 62/62;
- Fase 2 em execução por `AUTO_ADVANCE: true`.

## O que temos

- backend como autoridade;
- estados explícitos para loading, 401, 403, 503, erro e vazio;
- preferências locais não confiáveis e seguras;
- permissões refletidas na UX;
- mocks remanescentes visualmente marcados;
- regressão integral da Fase 0.

## O que está funcionando

- contexto de empresa inteira e por unidade;
- uma ou múltiplas empresas/unidades;
- rejeição de empresa/unidade inválida;
- rejeição de adulteração de storage, URL, headers e payload;
- troca A → B → A sem mistura;
- unidade inexistente/cross-company rejeitada em PostgreSQL real.

## O que falta

- núcleo financeiro operacional da Fase 2;
- conciliação da Fase 3;
- decisões humanas antes das fases 4, 6, 8 e 9;
- hardening de produção.

## Veredito

**APROVADA, mas não pronta para produção nem para dados reais.**
