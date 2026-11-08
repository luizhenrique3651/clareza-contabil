# DECISOES_ARQUITETURAIS — Clareza

Este documento registra decisões que devem permanecer estáveis até que exista evidência técnica suficiente para mudá-las.

## 1. Arquitetura

Direção atual:

- aplicação web em arquitetura de monólito modular;
- API versionada;
- PostgreSQL;
- Prisma;
- migrations SQL para proteções avançadas;
- evolução por fatias verticais.

Não reescrever o sistema apenas por preferência tecnológica.

---

## 2. Multi-tenancy

Isolamento em camadas:

- autorização no servidor;
- RBAC;
- contexto transacional;
- RLS PostgreSQL;
- constraints compostas quando aplicável.

O frontend não é fonte de autorização.

---

## 3. Contexto

O navegador pode solicitar empresa/unidade ativa para navegação.

O servidor valida a seleção contra o conjunto autorizado.

Contexto PostgreSQL sensível deve permanecer limitado à transação/requisição, evitando vazamento em conexões reutilizadas.

---

## 4. Segurança fail-closed

Na ausência de:

- identidade;
- membership;
- empresa autorizada;
- unidade autorizada;
- permissão;
- contexto de banco exigido;

a operação deve ser negada.

Nunca conceder acesso amplo como fallback.

---

## 5. Dados financeiros

Valores monetários devem usar representação decimal apropriada.

Pagamentos e recebimentos devem evoluir para eventos próprios.

Saldo e status devem ser derivados de eventos e regras de negócio, evitando booleanos manuais como única fonte de verdade.

---

## 6. Auditoria

Operações financeiras críticas devem ter rastreabilidade.

A auditoria deve ser append-only quando possível.

Não registrar segredos, tokens ou dados sensíveis desnecessários.

O hash atual da Fase 0 é evidência de integridade básica; mecanismos autenticados/encadeados podem ser avaliados antes de produção conforme requisitos.

---

## 7. Idempotência

Comandos financeiros suscetíveis a retry devem ser idempotentes.

Requisições concorrentes com mesma chave e mesmo conteúdo devem convergir para uma única operação persistida.

Mesma chave com conteúdo diferente deve gerar conflito.

---

## 8. Migrations

Prisma schema e SQL devem permanecer consistentes.

Proteções que não são expressáveis adequadamente no Prisma podem permanecer em SQL, desde que:

- documentadas;
- testadas;
- preservadas em regressão.

---

## 9. Interface e mocks

A identidade visual do Clareza deve ser preservada.

Mocks podem coexistir temporariamente com dados reais apenas quando claramente identificados.

Não atribuir status operacional a dados demonstrativos.

---

## 10. Contabilidade

DRE deve ser consequência do motor contábil, não um relatório isolado com números independentes.

A modelagem contábil definitiva exige decisão humana antes da Fase 4.

---

## 11. Fiscal

O módulo fiscal deve ser domínio próprio do Clareza, desacoplado de dependência obrigatória do Conta Azul.

Integrações municipais devem ser adaptáveis.

A Fase 6 exige definição humana dos provedores/municípios alvo.

---

## 12. IA

IA será adicionada depois que:

- dados;
- autorização;
- auditoria;
- cálculos;
- origem dos indicadores;

forem confiáveis.

A IA deve explicar com fontes e não contornar controles.

---

## 13. Produção

Não declarar produção pronta enquanto não houver, no mínimo:

- autenticação/sessão definidas;
- segredos externos ao código;
- TLS;
- backup e restauração testados;
- observabilidade;
- CI/CD e regressão;
- proteção de borda;
- revisão de dependências;
- testes E2E;
- procedimento de incidente;
- revisão de segurança/pentest proporcional ao risco.
