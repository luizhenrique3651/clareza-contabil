# Retomada — Mourão Clareza Contábil e Financeira

**Data do snapshot:** 4 de agosto de 2026  
**Raiz do software:** `C:\Users\leona\Documents\Codex\2026-07-24\files-mentioned-by-the-user-voc\outputs\Clareza-fase-0`  
**Branch:** `master`  
**Último commit seguro:** `1a49d1f` — `feat: add audited bank import APIs`  
**Produção:** não implantada  
**Dados reais:** não utilizados e não autorizados

## Resumo executivo

As Fases 0, 1 e 2 estão aprovadas. A Fase 3 está em andamento. O último
checkpoint versionado acrescentou as APIs auditadas de importação e listagem
bancária. Depois desse checkpoint começou a mudança pública de nome e identidade
visual para **Mourão Clareza Contábil e Financeira**.

O rebranding está parcialmente implementado, mas ainda não foi versionado. Ele
deve ser concluído, validado e salvo em um commit separado antes da continuidade
das APIs de conciliação e reversão.

## O que está concluído e versionado

### Fases aprovadas

- Fase 0 — fundação e protótipo local: aprovada.
- Fase 1 — identidade e contexto: aprovada.
- Fase 2 — núcleo financeiro: aprovada.
- Fase 3 — banco, movimentações e conciliação: ativa e parcial.

### Checkpoint `881713f`

- parser bancário CSV/OFX com validações estritas;
- schema Prisma e migration `0007`;
- modelos de importação, transação e conciliação;
- conciliação prevista para 1:1, 1:N e N:1;
- reversão append-only;
- reservas exclusivas de participantes;
- bloqueio pessimista da conta;
- migration aplicada em PostgreSQL 16 local: 61/61 instruções;
- gate SQL direto de invariantes;
- revisão adversarial sem P0/P1.

### Checkpoint `1a49d1f`

- serviço transacional de importação CSV/OFX;
- `POST /api/v1/bank/imports`;
- `GET /api/v1/bank/imports`;
- `GET /api/v1/bank/transactions`;
- idempotência com replay de resposta persistida;
- auditoria na mesma transação;
- deduplicação por conteúdo, identificador externo e fingerprint;
- isolamento obrigatório por tenant, empresa e unidade ativa;
- bloqueio por conta antes das verificações de duplicidade concorrente;
- cursor vinculado ao contexto, recurso, filtros e ordenação;
- 63/63 testes no checkpoint;
- build, lint, typecheck, Prisma validate e integridade do diff aprovados;
- revisão adversarial final do bloco bancário: nenhum P0, P1 ou P2.

## O que foi feito no rebranding, mas ainda não foi versionado

### Nome e linguagem pública

- nome institucional adotado: **Mourão Clareza Contábil e Financeira**;
- nome curto da interface: **Mourão Clareza**;
- título e descrição de metadata atualizados;
- mensagens públicas da tela de sessão, contexto e APIs atualizadas;
- identificadores técnicos `clareza-*` preservados por compatibilidade.

### Identidade visual

- última imagem anexada adotada como logo original e referência oficial;
- paleta principal alterada de verde/lima para azul-marinho, dourado e marfim;
- logo completa aplicada na tela de sessão;
- brasão escuro aplicado no menu e no rodapé;
- sidebar ampliada de 244 px para 280 px;
- textura de apoio azul-marinho/dourada criada sem redesenhar a logo;
- parte da tipografia e dos contrastes da navegação foi ampliada e ajustada.

### Ativos atuais não rastreados

- `public/brand/mourao-original.jpeg`
- `public/brand/mourao-crest-navy.jpeg`
- `public/brand/mourao-crest-light.jpeg`
- `public/brand/mourao-ui-texture.png`

### Arquivos atualmente modificados

- `app/api/v1/_shared/bank-collection.ts`
- `app/api/v1/_shared/financial-collection.ts`
- `app/api/v1/me/route.ts`
- `app/application-context.tsx`
- `app/application.tsx`
- `app/command-center.tsx`
- `app/globals.css`
- `app/layout.tsx`

Não há arquivos staged. Essas alterações pertencem ao trabalho em andamento e
não devem ser descartadas, revertidas ou misturadas com mudanças de conciliação.

## Situação das validações no ponto da parada

| Validação | Situação atual |
|---|---|
| Testes locais | 62/63 |
| Typecheck | aprovado |
| Lint | aprovado |
| Prisma validate | aprovado |
| `git diff --check` | aprovado |
| Build do rebranding atual | ainda não comprovado |
| Prévia visual desktop | ainda não comprovada |
| Prévia visual mobile | ainda não comprovada |
| Revisão adversarial do rebranding | ainda não executada |

### Única falha conhecida

`tests/rendered-html.test.mjs` ainda espera o título antigo:

```text
Clareza | Central de comando empresarial
```

O código já usa:

```text
Mourão Clareza Contábil e Financeira | Central de comando
```

O teste deve ser atualizado para refletir a decisão de marca. A mudança precisa
ser tratada como atualização de contrato visual, não como regressão funcional.

## Onde o trabalho parou

O trabalho parou durante a preparação da prévia local do rebranding. A tentativa
de iniciar o servidor em segundo plano no Windows falhou antes de abrir a página
por um conflito de ambiente entre as chaves `Path` e `PATH`. Nenhuma captura de
tela foi validada e nenhum servidor de prévia ficou comprovadamente ativo.

O ponto seguro de retomada é:

1. preservar integralmente o working tree atual;
2. atualizar o teste do título;
3. terminar os ajustes visuais e documentais da marca;
4. atualizar `public/og.png` para a nova marca;
5. executar build e regressão completa em sequência, sem dois builds paralelos;
6. abrir uma prévia local por um processo persistente compatível com o ambiente;
7. validar desktop e mobile;
8. submeter o rebranding a auditoria adversarial;
9. criar um commit separado do rebranding;
10. somente então retomar a implementação funcional da Fase 3.

## O que ainda falta no rebranding

- alinhar o teste de HTML renderizado ao novo título;
- revisar toda a interface para garantir que o antigo nome público não aparece;
- manter deliberadamente os identificadores técnicos `clareza-*`;
- atualizar README, estado atual, roadmap e documentos visuais;
- listar os três endpoints bancários na seção de APIs operacionais;
- substituir a imagem social antiga `public/og.png`;
- confirmar favicon, metadata e textos alternativos;
- revisar fontes menores que 12 px nas áreas operacionais;
- validar entrada, sidebar, dashboard e área financeira em desktop e celular;
- validar contraste, foco, rolagem e ausência de cortes;
- rodar 63/63 testes, build, lint, typecheck, Prisma e integridade do diff;
- obter revisão independente sem P0/P1;
- versionar o rebranding em commit próprio.

## O que ainda falta na Fase 3

- serviço e APIs transacionais de conciliação;
- serviço e APIs de reversão append-only;
- testes positivos e negativos de conciliação 1:1, 1:N e N:1;
- provas de soma, moeda, conta, tenant, empresa e unidade;
- prova convencional de concorrência por driver;
- interface operacional de importação e conciliação;
- suíte PostgreSQL convencional da migration 0007;
- revisão adversarial final e gate integral da fase;
- documentação final da API.

A execução da migration 0007 em provedor PostgreSQL temporário exige autorização
explícita, pois envia o schema para um serviço externo. A evidência atual é local,
em PostgreSQL 16 single-user. A Fase 4 não deve começar antes do encerramento da
Fase 3 e de uma decisão humana sobre o modelo contábil.

## Regras de segurança e continuidade

- não usar dados reais;
- não declarar o produto pronto para produção;
- não publicar ou implantar sem autorização explícita;
- não desfazer alterações existentes do usuário;
- não renomear cabeçalhos, storage keys, URLs de problema ou contratos técnicos
  `clareza-*` apenas por branding;
- executar builds que compartilham o mesmo diretório em sequência;
- criar checkpoints pequenos e recuperáveis;
- atualizar o usuário em Markdown no início, a cada marco e sempre antes de uma
  etapa potencialmente demorada;
- não deixar mais de 60 segundos sem uma atualização durante trabalho ativo.

## Prompt único para continuar no Codex

```text
Você é o engenheiro principal responsável pela continuidade do sistema “Mourão
Clareza Contábil e Financeira”. Trabalhe diretamente no repositório existente:

C:\Users\leona\Documents\Codex\2026-07-24\files-mentioned-by-the-user-voc\outputs\Clareza-fase-0

OBJETIVO
Retomar exatamente do estado atual, concluir e versionar o rebranding com
validação visual desktop/mobile e, somente depois, continuar a Fase 3 pelas APIs
de conciliação e reversão. Preserve tudo que já foi realizado.

COMUNICAÇÃO OBRIGATÓRIA
1. Responda sempre em português.
2. Comece informando em Markdown: situação atual, o que encontrou, o que fará
   agora e qual é o próximo checkpoint.
3. Durante trabalho ativo, envie uma atualização curta a cada marco e nunca
   fique mais de 60 segundos sem informar progresso, resultado parcial ou
   bloqueio.
4. Mostre prévias do sistema sempre que possível, no mínimo desktop e celular.
5. No final de cada etapa, entregue Markdown com: feito, funcionando, validado,
   pendente, riscos, arquivos alterados, commit e próximo passo.

AGENTES E AUDITORIA
1. Mantenha um agente principal implementando e crie ou reutilize pelo menos um
   agente auditor independente, somente leitura.
2. O auditor deve conferir estado inicial, diff, isolamento de tenant/empresa/
   unidade, idempotência, concorrência, testes, documentação e riscos.
3. O auditor não deve editar os mesmos arquivos do agente principal.
4. Antes de cada commit, peça ao auditor um veredito com P0/P1/P2 e
   APROVADO/VETO.
5. Se houver P0 ou P1, não faça commit do checkpoint até corrigir e obter nova
   revisão.
6. Use um agente de direção visual para desktop/mobile e um revisor adversarial
   para alterações financeiras sensíveis, quando houver capacidade.
7. Organize as tarefas em checkpoints pequenos; evite agentes duplicando o mesmo
   trabalho.

LEITURA INICIAL OBRIGATÓRIA
1. Leia integralmente AGENTS.md e as instruções do repositório.
2. Leia docs/ESTADO_ATUAL.md, docs/ROADMAP_MASTER.md,
   docs/DECISOES_ARQUITETURAIS.md, docs/PROTOCOLO_DE_FASES.md,
   docs/fases/FASE_2_NUCLEO_FINANCEIRO.md,
   docs/fases/FASE_3_CONCILIACAO.md e
   docs/RETOMADA_MOURAO_CLAREZA_2026-08-04.md.
3. Confirme branch, HEAD, git status e diff antes de editar.
4. Não descarte nem reverta o working tree existente.

ESTADO DE PARTIDA
- Branch master.
- HEAD seguro: 1a49d1f — APIs auditadas de importação bancária.
- Fases 0–2 aprovadas; Fase 3 ativa.
- Rebranding ainda não commitado: 8 arquivos modificados e 4 ativos em
  public/brand.
- Validação atual: 62/63 testes; lint, typecheck, Prisma e diff aprovados.
- Única falha conhecida: tests/rendered-html.test.mjs ainda espera o título
  antigo.
- Build e prévia visual do rebranding atual ainda não foram comprovados.

IDENTIDADE VISUAL AUTORIZADA
- Nome institucional: “Mourão Clareza Contábil e Financeira”.
- Nome curto da interface: “Mourão Clareza”.
- A última logo anexada é a original/canônica.
- Preserve a arte “MOURÃO — INTELIGÊNCIA FINANCEIRA”; não redesenhe nem cubra a
  logo.
- Use azul-marinho como base, dourado como acento e marfim/branco nas superfícies.
- Preserve cores semânticas de sucesso, alerta e erro.
- Não use dourado como texto pequeno sobre branco.
- Identificadores internos como x-clareza-*, clareza.navigation.*,
  ClarezaApplication, package name e problem URLs devem permanecer estáveis por
  compatibilidade, salvo decisão explícita e migração planejada.

ORDEM OBRIGATÓRIA DA RETOMADA
CHECKPOINT A — fechar o rebranding
1. Inspecione o diff atual e os quatro ativos de public/brand.
2. Atualize o teste do título para a nova marca.
3. Conclua textos públicos, layout, tipografia mínima, responsividade, favicon,
   metadata, texto alternativo e public/og.png.
4. Atualize README e documentação, inclusive a lista de APIs bancárias.
5. Não implemente conciliação enquanto o rebranding estiver sujo.

CHECKPOINT B — validar e mostrar prévias
1. Rode em sequência: testes completos, build, lint, typecheck, Prisma validate e
   git diff --check. Não execute dois builds simultaneamente no mesmo diretório.
2. Inicie a prévia local por processo persistente compatível com Windows. A
   tentativa anterior de Start-Process falhou por conflito Path/PATH; não presuma
   que há servidor ativo.
3. Valide em navegador real pelo menos 1440 px e 390 px.
4. Capture e mostre as prévias da tela de entrada, sidebar/dashboard e área
   financeira sempre que o estado autenticado puder ser aberto com segurança.
5. Verifique cortes, rolagem, foco, contraste, fontes menores que 12 px e console.

CHECKPOINT C — auditoria e commit do rebranding
1. Entregue o diff e evidências ao agente auditor.
2. Corrija todo P0/P1 e repita o gate afetado.
3. Faça commit separado e recuperável do rebranding somente com gate verde.
4. Registre commit, validações, prévias e pendências nos documentos.

CHECKPOINT D — continuar a Fase 3
1. Implemente serviços/APIs de conciliação e reversão com transação, idempotência,
   auditoria, RLS e escopo derivado da sessão.
2. Prove 1:1, 1:N, N:1, conflitos de soma/moeda/conta/escopo, reversão append-only
   e concorrência.
3. Implemente a UI operacional sem misturar dados demonstrativos com dados reais.
4. Submeta cada fatia financeira a revisão adversarial antes do commit.
5. Não inicie a Fase 4.

LIMITES
- Não usar dados reais.
- Não declarar produção pronta.
- Não publicar/deployar sem autorização explícita.
- A prova da migration 0007 em provedor externo exige autorização explícita.
- Não executar ações destrutivas nem apagar o trabalho atual.
- Se houver bloqueio, esgote verificações seguras, informe a causa concreta e
  apresente o próximo caminho seguro.

COMECE AGORA
Faça a leitura inicial, mostre o snapshot em Markdown, ative o agente auditor e
retome pelo CHECKPOINT A. Não pare apenas no diagnóstico: avance até o próximo
checkpoint seguro, mantendo atualizações frequentes e claras.
```

