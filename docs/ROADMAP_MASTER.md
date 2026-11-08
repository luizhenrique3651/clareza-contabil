# ROADMAP_MASTER — Clareza

Última consolidação: 2026-07-25

## Regras do roadmap

Estados possíveis:

- `PENDENTE`
- `EM EXECUÇÃO`
- `EM CORREÇÃO`
- `APROVADA`
- `BLOQUEADA`
- `DECISÃO HUMANA`

`AUTO_ADVANCE: true` significa que, após aprovação integral do gate, o Codex pode atualizar o estado e iniciar a fase seguinte sem aguardar um novo prompt.

`AUTO_ADVANCE: false` exige parada após o relatório e registro da decisão necessária.

---

# Fase 0 — Fundação técnica

**STATUS: APROVADA**

**AUTO_ADVANCE: true**

Objetivo concluído:

- PostgreSQL 16 real;
- migrations 0001, 0002 e 0003;
- Prisma consistente;
- seed repetível;
- RLS tenant/empresa/unidade;
- fail-closed;
- pool sem vazamento;
- constraints de IDs cruzados;
- identidade/RBAC;
- APIs financeiras iniciais;
- idempotência concorrente;
- atomicidade de auditoria;
- 40/40 testes automatizados aprovados na validação da fase.

Relatório:
`docs/FASE_0_VALIDACAO_REAL.md` ou cópia equivalente preservada no repositório.

---

# Fase 1 — Identidade e contexto reais na interface

**STATUS: APROVADA**

**AUTO_ADVANCE: true**

## Objetivo

Substituir identidade, empresas e unidades fictícias da área autenticada por dados reais do backend, usando `/api/v1/me`, sem transformar o frontend em fonte de autorização.

## Entregas

- camada central de consumo de `/api/v1/me`;
- estados explícitos de loading, 401, 403, 503 e erro inesperado;
- usuário real;
- seletor de empresa derivado da sessão;
- seletor de unidade derivado da sessão;
- contexto de navegação;
- invalidação segura na troca de contexto;
- permissões refletidas na UX;
- mocks restantes claramente marcados como demonstrativos.

## Gate

A fase só é aprovada quando:

1. `/api/v1/me` estiver integrado à interface;
2. usuário fictício não existir na área autenticada;
3. empresas vierem exclusivamente do backend;
4. unidades vierem exclusivamente do backend;
5. empresa inválida for rejeitada;
6. unidade inválida for rejeitada;
7. adulteração de storage/URL não conceder acesso;
8. permissões forem refletidas corretamente;
9. 401, 403, 503 e erros tiverem estados próprios;
10. troca de contexto não misturar dados;
11. mocks restantes estiverem identificados;
12. testes da fase passarem;
13. Fase 0 continuar sem regressão;
14. lint passar;
15. typecheck passar;
16. build passar.

Relatório esperado:
`docs/fases/FASE_1_IDENTIDADE_CONTEXTO.md`

Resultado:

- identidade/contexto integrados à interface;
- migration 0004 para diretório e validação de unidades;
- gate 16/16;
- 42/42 testes locais;
- 20/20 testes PostgreSQL;
- total 62/62;
- nenhuma regressão aberta.

---

# Fase 2 — Núcleo financeiro operacional

**STATUS: APROVADA**

**AUTO_ADVANCE: true**

**Gate aprovado em 2026-07-29:** a semântica final da migration 0006 foi
comprovada em PostgreSQL 16 real e a execução convencional por Prisma/`pg` foi
aprovada em PostgreSQL 17 descartável, com 30/30 testes. A revisão adversarial
final não encontrou P0, P1 ou bloqueador residual.

## Objetivo

Transformar as APIs financeiras iniciais em um fluxo operacional rastreável de ponta a ponta.

## Escopo

- contrapartes;
- categorias;
- centros de custo;
- contas financeiras;
- conclusão da UI de contas a pagar;
- conclusão da UI de contas a receber;
- `Payment`;
- `Receipt`;
- baixa parcial;
- baixa total;
- estorno;
- histórico;
- auditoria visual;
- juros, multa e desconto quando modelados de forma explícita.

## Princípio

Saldo e situação devem ser derivados de eventos financeiros. Evitar flags manuais como única verdade de pagamento.

## Gate resumido

Deve ser possível executar:

criação → parcial → nova baixa → quitação → estorno → histórico

com autorização, transação, auditoria e consistência.

Relatório esperado:
`docs/fases/FASE_2_NUCLEO_FINANCEIRO.md`

Resultado validado:

- migrations 0005 e 0006 implementadas;
- fluxo operacional completo na API e na interface;
- projeções financeiras derivadas dos eventos;
- RLS, catálogos, auditoria e append-only endurecidos;
- revisão adversarial aprovada sem P0/P1;
- validação visual desktop/mobile aprovada;
- lint, typecheck, build e Prisma validate aprovados;
- 47/47 testes locais aprovados;
- 0006 final aplicada em PostgreSQL single-user, 26/26 instruções;
- preflight e quatro guardas temporais comprovados diretamente no banco;
- banco vazio recebeu migrations 0001–0006 e os dois seeds;
- suíte PostgreSQL convencional por Prisma/`pg`: 30/30;
- revisão adversarial final aprovada sem P0/P1;
- nenhuma regressão aberta.

---

# Fase 3 — Banco, movimentações e conciliação

**STATUS: EM EXECUÇÃO**

**AUTO_ADVANCE: true**

## Escopo

- contas bancárias;
- movimentações;
- importação OFX;
- importação CSV;
- deduplicação;
- conciliação 1:1;
- conciliação 1:N;
- conciliação N:1;
- desfazer conciliação;
- trilha de conciliação.

## Gate resumido

Toda conciliação deve ser reversível, auditável e reproduzível a partir das transações importadas e eventos financeiros.

Relatório esperado:
`docs/fases/FASE_3_CONCILIACAO.md`

---

# Fase 4 — Motor contábil

**STATUS: PENDENTE**

**AUTO_ADVANCE: false**

## DECISÃO HUMANA ANTES DE INICIAR

Antes desta fase, apresentar:

- modelo contábil proposto;
- eventos que geram lançamentos;
- regras de competência;
- estratégia de fechamento/reabertura;
- relação financeiro → contabilidade;
- tratamento de estornos;
- plano de contas base;
- riscos de mudança futura.

## Escopo após aprovação humana

- plano de contas;
- períodos contábeis;
- lançamentos;
- diário;
- razão;
- balancete;
- DRE calculada;
- balanço patrimonial;
- fechamento/reabertura.

---

# Fase 5 — Dashboard e inteligência gerencial baseada em dados

**STATUS: PENDENTE**

**AUTO_ADVANCE: true**

## Escopo

Substituir progressivamente os mocks por indicadores calculados:

- receita;
- resultado;
- margem;
- caixa;
- contas a pagar;
- contas a receber;
- inadimplência;
- liquidez;
- projeções suportadas por dados.

Cada KPI relevante deve permitir rastrear origem/drill-down.

---

# Fase 6 — Fiscal

**STATUS: PENDENTE**

**AUTO_ADVANCE: false**

## DECISÃO HUMANA ANTES DE INICIAR

Antes de implementar, documentar:

- municípios/provedores alvo;
- NFS-e prioritária;
- requisitos de homologação;
- credenciais;
- retenções;
- armazenamento de XML/PDF;
- vínculo fiscal-financeiro;
- requisitos legais aplicáveis.

A arquitetura fiscal deve ser própria do Clareza e adaptável por município/provedor.

---

# Fase 7 — Orçamento e forecast

**STATUS: PENDENTE**

**AUTO_ADVANCE: true**

## Escopo

- orçamento;
- realizado x orçado;
- forecast;
- cenários;
- projeção de caixa;
- metas.

---

# Fase 8 — Alertas, automações e IA explicável

**STATUS: PENDENTE**

**AUTO_ADVANCE: false**

## DECISÃO HUMANA ANTES DE INICIAR

Definir quais decisões a IA poderá:

- apenas explicar;
- recomendar;
- preparar;
- executar mediante aprovação.

A IA nunca deve contornar autorização, auditoria ou regras financeiras.

## Princípio

IA deve operar sobre dados confiáveis e apontar as fontes utilizadas.

---

# Fase 9 — Hardening e produção

**STATUS: PENDENTE**

**AUTO_ADVANCE: false**

## DECISÃO HUMANA OBRIGATÓRIA

Inclui:

- provedor de identidade definitivo;
- sessão;
- MFA;
- gestão de segredos;
- TLS;
- backups;
- restauração testada;
- observabilidade;
- alta disponibilidade;
- CI/CD;
- SAST/secret scanning;
- rate limit;
- CSP/headers;
- incident response;
- pentest;
- revisão de dependências;
- ambiente de homologação;
- política de migração;
- checklist de produção.

Nenhum dado real deve ser carregado antes da aprovação desta etapa e das decisões de segurança correspondentes.
