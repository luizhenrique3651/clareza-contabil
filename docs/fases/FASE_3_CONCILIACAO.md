# Fase 3 — Banco, movimentações e conciliação

**Iniciada em:** 29 de julho de 2026

**Estado:** em execução

**Produção:** não implantada

**Dados reais:** não utilizados

## Objetivo

Adicionar movimentações bancárias importadas e conciliação reversível,
auditável e reproduzível com os eventos financeiros já existentes.

## Estado inicial

A Fase 3 inicia sobre o commit aprovado:

```text
2827859 feat: complete Clareza phase two financial core
```

Baseline herdado:

```text
testes locais                47/47
testes PostgreSQL Prisma/pg  30/30
lint                         aprovado
typecheck                    aprovado
build                        aprovado
Prisma validate              aprovado
revisão adversarial          sem P0/P1
```

Já existem:

- `FinancialAccount` com tipos `CASH`, `CHECKING`, `SAVINGS`, `PAYMENT` e
  `OTHER`;
- pagamentos e recebimentos append-only;
- estornos append-only;
- RLS por tenant, empresa e unidade;
- RBAC;
- idempotência;
- auditoria;
- contexto transacional;
- proteção contra concorrência.

Não existem ainda:

- lote de importação bancária;
- transação bancária;
- parser OFX;
- parser CSV;
- deduplicação bancária;
- conciliação;
- desfazer conciliação;
- trilha de conciliação.

## Decisões de desenho adotadas no checkpoint

1. `FinancialAccount` continuará sendo a conta financeira e bancária; não será
   criado um segundo cadastro concorrente.
2. Importações guardarão metadados, hash do conteúdo e contagens, sem persistir
   o arquivo bruto.
3. Transações bancárias usarão valor decimal assinado. Identificador externo,
   quando presente, é identidade forte por conta; fingerprint é apenas sinal
   informativo de possível duplicidade.
4. Um grupo de conciliação poderá conter várias transações bancárias e vários
   pagamentos/recebimentos, cobrindo 1:1, 1:N e N:1.
5. A soma assinada do extrato deverá coincidir com recebimentos menos
   pagamentos.
6. Grupos e itens serão append-only.
7. Desfazer conciliação criará um evento de reversão, sem apagar a conciliação.
8. Transação ou evento financeiro não poderá participar simultaneamente de
   dois grupos ativos.
9. RLS, escopo de conta, moeda, tenant, empresa e unidade serão validados no
   banco e no serviço.

10. Uma projeção interna de reservas, sem acesso direto pela role runtime,
    possui chave única por participante ativo.
11. A reversão remove somente a reserva projetada; grupo, itens e evento de
    reversão permanecem append-only.
12. Estorno de pagamento ou recebimento conciliado exige primeiro desfazer a
    conciliação.
13. Filial, moeda e tipo da conta tornam-se imutáveis depois de qualquer evento
    financeiro ou bancário.
14. `fingerprint` sem identificador externo é apenas sinal de possível
    duplicidade. A identidade forte é conteúdo+linha; operações legítimas
    idênticas não são descartadas.

## Implementação do checkpoint

### Parser bancário

Criado:

```text
server/bank-imports.mjs
tests/bank-imports.test.mjs
```

Implementado:

- CSV com vírgula ou ponto decimal, aspas e delimitador vírgula/ponto e vírgula;
- OFX SGML/XML sem `DOCTYPE` ou entidades externas;
- datas ISO e datas OFX com fuso;
- decimal exato com quatro casas;
- limites de 1 MiB, 2.000 entradas, campos e complexidade;
- hash canônico do conteúdo;
- fingerprint informativo;
- validação estrita do calendário OFX, sem normalizar datas ou horas
  impossíveis e sem ignorar sufixos;
- rejeição de zero, não finito, precisão excessiva e separador de milhar
  ambíguo;
- duas movimentações legitimamente iguais preservadas por linhas distintas.

### Modelo e migration

Criado:

```text
prisma/migrations/0007_bank_reconciliation/migration.sql
```

Entidades:

- `BankImport`;
- `BankTransaction`;
- `BankReconciliationGroup`;
- `BankReconciliationItem`;
- `BankReconciliationReversal`;
- `BankReconciliationReservation` como projeção interna.

Proteções:

- FKs compostas por tenant e empresa;
- RLS estrita, sem regra ampla de catálogo;
- conta compartilhada invisível para membership apenas de unidade;
- valores assinados não nulos e não zero;
- crédito positivo e débito negativo;
- quantidade real de transações obrigatoriamente igual à quantidade declarada
  pela importação no fechamento da transação SQL;
- bloqueio pessimista da conta financeira antes de pagamentos, recebimentos,
  importações, transações bancárias e grupos de conciliação;
- grupos 1:1, 1:N e N:1;
- validação diferida de soma;
- reserva exclusiva concorrente;
- grupo e itens obrigatórios mesmo quando revertidos na mesma transação;
- eventos financeiros estornados não conciliáveis;
- estorno financeiro bloqueado enquanto houver reserva ativa;
- grupo, itens, transações, importação e reversão append-only;
- reversão única, temporal e posterior à conciliação;
- reutilização dos participantes após desfazer.

### Gate SQL direto

Criado:

```text
tests/sql/phase3-bank-reconciliation-gate.sql
```

O gate comprova:

- conciliação 1:1 balanceada;
- importação sem todas as linhas recusada;
- linha excedente à quantidade declarada recusada;
- presença dos cinco bloqueios de conta financeira comprovada;
- dupla reserva recusada;
- estorno financeiro conciliado recusado;
- filial/moeda/tipo da conta protegidos;
- grupo append-only;
- grupo vazio recusado;
- desfazer liberando reservas;
- nova conciliação dos mesmos participantes.

## Problemas encontrados e corrigidos

1. A reserva era criada em `BEFORE INSERT` e violava a FK para o item ainda não
   persistido. Corrigido para `AFTER INSERT`.
2. A função diferida tentava acessar `group_id` em um registro do próprio grupo.
   Corrigido usando `TG_TABLE_NAME`.
3. Uma tentativa de validação usou o nome incorreto de banco em um clone local e
   foi descartada sem aplicar a migration.
4. Uma tentativa de enviar a 0007 ao PostgreSQL externo foi bloqueada pela
   política de segurança antes do envio. Nenhum schema da Fase 3 foi transmitido.
5. O parser OFX aceitava calendário impossível por normalização automática do
   JavaScript e ignorava sufixos. A expressão foi ancorada e os componentes
   agora são validados por round-trip.
6. As contagens da importação não eram comparadas ao número real de transações.
   Dois constraint triggers diferidos agora exigem igualdade exata no commit.
7. Havia uma janela de corrida entre alterar o escopo da conta e inserir um
   evento. Todos os cinco caminhos de criação agora obtêm `FOR UPDATE` na conta
   antes das guardas de escopo.
8. O fallback ISO ainda aceitava calendário impossível por normalização do
   JavaScript, e o OFX aceitava offsets absurdos. Todos os formatos ISO aceitos
   agora passam por parser estrito e round-trip; offsets ISO e OFX possuem
   limites semânticos.

## Validação do checkpoint

```text
parser bancário                 7/7
testes locais totais           54/54
build                           aprovado
lint                            aprovado
typecheck                       aprovado
Prisma validate                 aprovado
Prisma schema diff              aprovado
git diff --check                aprovado
0007 em PostgreSQL 16 limpo     61/61 instruções
gate SQL de invariantes         aprovado
revisão adversarial corrigida   aprovada sem P0/P1
```

O ensaio dinâmico de duas conexões locais não pôde abrir a porta do PostgreSQL
no sandbox. A autorização automática necessária também ficou indisponível por
limite de uso da conta. Não houve tentativa de contorno. A migration, os
triggers, a ordem de execução e o gate single-user foram validados; a prova
dinâmica concorrente continua explicitamente pendente.

## Fatia vertical inicial

```text
conta financeira bancária
→ importação CSV/OFX sintética
→ deduplicação
→ transação bancária
→ conciliação com pagamento/recebimento
→ desfazer conciliação
→ auditoria
```

### Checkpoint de importação e listagem — 4 de agosto de 2026

A primeira parte da fatia vertical está implementada:

- serviço transacional de importação CSV/OFX;
- `POST/GET /api/v1/bank/imports`;
- `GET /api/v1/bank/transactions`;
- idempotência com replay da resposta persistida;
- deduplicação por conteúdo, identificador externo e fingerprint;
- auditoria na mesma transação;
- bloqueio por conta antes das consultas de duplicidade;
- isolamento obrigatório pela unidade ativa;
- cursores vinculados ao tenant, empresa, unidade, recurso, filtros e ordem.

Validação deste checkpoint:

```text
testes locais totais            63/63
testes dirigidos do bloco       15/15
build                           aprovado
lint                            aprovado
typecheck                       aprovado
Prisma validate                 aprovado
git diff --check                aprovado
revisão adversarial             aprovada sem P0/P1/P2
```

## Gate planejado

- banco vazio recebe todas as migrations;
- upgrade incremental preserva a Fase 2;
- importação repetida não duplica transações;
- conteúdo diferente com mesma chave gera conflito;
- CSV e OFX inválidos falham fechados;
- arquivo e payload têm limites;
- transação de outro tenant, empresa, unidade ou conta é invisível;
- 1:1, 1:N e N:1 são comprovados;
- soma divergente é rejeitada;
- moeda e conta divergentes são rejeitadas;
- conciliação concorrente do mesmo item converge ou falha sem duplicar;
- grupos e itens não podem ser alterados ou excluídos;
- desfazer é append-only e idempotente;
- item revertido pode ser conciliado novamente;
- auditoria e idempotência falhando revertem toda a operação;
- regressões das Fases 0–2 permanecem verdes;
- lint, typecheck, build e Prisma validate passam;
- revisão adversarial termina sem P0/P1.

## Pendências

Ainda faltam para concluir a Fase 3:

- APIs de conciliação e reversão;
- provas automatizadas de 1:N e N:1;
- concorrência convencional por driver;
- UI operacional de importação e conciliação;
- suíte PostgreSQL convencional da 0007;
- revisão adversarial final da fase;
- documentação de API e encerramento do gate.

A execução externa da 0007 exige autorização explícita porque envia o novo
schema a um provedor temporário. Até essa autorização, a evidência disponível é
o PostgreSQL 16 local em modo single-user.

## Veredito atual

> **FASE 3 EM EXECUÇÃO — CHECKPOINT DE FUNDAÇÃO APROVADO SEM P0/P1.**
>
> Nenhum recurso de conciliação deve ser tratado como operacional antes do gate.
