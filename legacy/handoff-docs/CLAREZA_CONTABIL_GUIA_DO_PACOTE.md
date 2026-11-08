# Clareza Contábil — guia do pacote de transferência

**Data da entrega:** 10/10/2026  
**Finalidade:** permitir que uma nova equipe receba, valide, versione e continue o desenvolvimento sem depender do ambiente original.

## 1. Conteúdo do ZIP

O pacote `CLAREZA_CONTABIL_CODIGO_FONTE_HANDOFF.zip` contém:

- todos os arquivos rastreados pelo Git;
- todos os arquivos locais não rastreados e não ignorados;
- código frontend, APIs, serviços de domínio e worker;
- schema Prisma, migrations SQL, seed e artefatos Drizzle;
- testes locais e de integração;
- documentação arquitetural, relatórios de fase e instruções operacionais;
- ativos visuais e rebranding presentes no working tree;
- configurações de exemplo, incluindo `.env.example`;
- um bundle Git portátil com todo o histórico commitado;
- um patch binário das alterações rastreadas ainda não commitadas;
- status, HEAD, log recente e relação de arquivos não rastreados;
- dossiê técnico, inventário, relatório de validação e hashes SHA-256.

O diretório `.git` não foi copiado. O histórico foi preservado em `git-history/clareza-repository.bundle`, formato portátil e verificável do próprio Git.

## 2. Ordem de leitura recomendada

1. `handoff-docs/CLAREZA_CONTABIL_STATUS_DE_VALIDACAO.md`
2. `handoff-docs/CLAREZA_CONTABIL_DOSSIE_TECNICO.md`
3. `AGENTS.md`
4. `docs/ESTADO_ATUAL.md`
5. `docs/ROADMAP_MASTER.md`
6. `docs/DECISOES_ARQUITETURAIS.md`
7. `docs/PROTOCOLO_DE_FASES.md`
8. `docs/fases/FASE_3_CONCILIACAO.md`
9. `handoff-docs/CLAREZA_CONTABIL_ARVORE_E_INVENTARIO.md`

## 3. Como restaurar o repositório com histórico

No PowerShell, a partir de uma pasta de trabalho vazia:

```powershell
git clone .\git-history\clareza-repository.bundle clareza-contabil
```

O clone restaura o estado commitado em `1a49d1f`. O diretório principal do ZIP já contém a versão corrente dos arquivos, incluindo o working tree não commitado. A abordagem recomendada é:

1. clonar o bundle em outro diretório;
2. revisar `git-history/WORKING_TREE.patch`;
3. copiar ou aplicar conscientemente as alterações atuais;
4. conferir os ativos não rastreados listados em `git-history/UNTRACKED.txt`;
5. executar os gates;
6. criar um commit de retomada próprio.

O patch contém somente mudanças em arquivos já rastreados. Os arquivos não rastreados estão incorporados diretamente no código-fonte do pacote.

## 4. Preparação do ambiente

Pré-requisitos registrados pelo projeto:

- Node.js `>=22.18`;
- npm compatível com o `package-lock.json`;
- PostgreSQL para gates de integração;
- variáveis locais criadas a partir de `.env.example`.

Instalação reprodutível:

```powershell
npm ci
npm run db:generate
npm run db:validate
npm run typecheck
npm run lint
npm test
```

Não promova o sistema a produção apenas porque os gates locais executam. A Fase 3, a homologação, a observabilidade, o deploy e a validação de recuperação ainda precisam ser concluídos.

## 5. Situação que a equipe receberá

- branch base: `master`;
- commit base: `1a49d1fcc8fde4bded0c2588842519d2b19687b5`;
- working tree: oito arquivos modificados e ativos/documentação não rastreados;
- rebranding Mourão: presente no pacote, ainda sem commit;
- Fase 3: em andamento;
- testes locais em 10/10/2026: 61 de 63 aprovados;
- duas falhas: a mesma asserção de título antigo em cenários do teste de HTML renderizado;
- integração PostgreSQL: não reexecutada nesta embalagem.

## 6. Exclusões deliberadas

O pacote não contém:

- `node_modules`;
- saídas geradas de build e caches; scripts-fonte versionados em `build/` permanecem incluídos;
- `.git` bruto;
- arquivos `.env` reais;
- bancos, dumps ou dados de produção;
- chaves privadas e credenciais;
- saídas temporárias do Codex e da ferramenta de deploy.

Esses itens não são necessários para reconstruir o projeto e poderiam aumentar muito o arquivo ou expor dados sensíveis. Dependências devem ser reinstaladas com `npm ci`.

## 7. Primeiras decisões da nova equipe

1. Revisar e versionar o working tree recebido.
2. Atualizar a expectativa de título em `tests/rendered-html.test.mjs` após confirmar o nome oficial.
3. Executar os testes de integração com PostgreSQL limpo.
4. Fechar os critérios de aceite da Fase 3.
5. Separar de forma inequívoca dados demonstrativos e dados persistidos.
6. Definir CI/CD, observabilidade, backup, recuperação e ambientes.
7. Realizar threat modeling e revisão LGPD antes de qualquer dado real.

## 8. Integridade

Valide primeiro o hash externo em `CLAREZA_CONTABIL_MANIFESTO_SHA256.md`. Depois de extrair o ZIP, use `SHA256SUMS.md` para conferir individualmente os arquivos do pacote.
