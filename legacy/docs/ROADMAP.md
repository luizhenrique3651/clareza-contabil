# Roadmap

## Critério

O roadmap é orientado por gates de risco e aprendizado, não por quantidade de telas. Datas e compromissos comerciais ainda não foram definidos.

## Agora — fundação executável

Entregas deste repositório:

- Central de Comando responsiva com dados fictícios;
- seleção local de empresa/período, KPIs, “O que mudou?” e saúde por área;
- caixa projetado em 60 dias, liquidez, DRE explicada e pendências;
- busca demonstrativa;
- glossário contextual demonstrável;
- codinome e princípios de experiência;
- artefatos de fundação para Prisma/PostgreSQL: schema, migration/RLS, seed e contrato inicial de contexto de tenant, ainda sem integração à UI ou validação em PostgreSQL real;
- teste local em memória do princípio de isolamento, sem substituir o futuro teste PostgreSQL;
- documentação de arquitetura, segurança, LGPD e evolução.

Gate de saída: build/testes disponíveis passam e cada superfície fictícia é identificável. Isso valida a fundação/proposta visual, não produção.

## Próximo — plataforma confiável

### 1. Identidade e multiempresa ponta a ponta

- autenticação e sessão;
- tenants, empresas, unidades e memberships;
- RBAC granular;
- contexto transacional Prisma/PostgreSQL com RLS;
- isolamento e herança de acesso por unidade, hoje não cobertos pela política de fundação;
- API `/api/v1` e OpenAPI;
- trilha de auditoria para acesso e administração.

Gate: Empresa A não lê, grava, vincula, exporta ou infere Empresa B; unidade autorizada não acessa outra unidade da mesma empresa, salvo herança explícita. Ambos os cenários passam em testes de banco, API e E2E.

### 2. Primeiro fluxo financeiro vertical

- contas a pagar e receber;
- pagamentos/recebimentos e status derivados;
- fluxo de caixa realizado e projetado;
- filtros, paginação, exportação controlada e auditoria;
- dashboard alimentado por API, com origem rastreável.

Gate: invariantes, idempotência, conciliação básica e explicação dos KPIs validadas com usuários.

### 3. Núcleo contábil e demonstrativos

- plano de contas e períodos;
- lançamentos balanceados e reversões;
- balancete;
- DRE e balanço com composição e drill-down;
- comparação entre períodos e dados incompletos explícitos.

Gate: reconciliação dos demonstrativos com os lançamentos e revisão profissional dos cálculos.

### 4. Emissor fiscal nativo

- NFS-e como primeiro documento fiscal;
- configuração fiscal por empresa e ambiente;
- adapters para padrões nacionais/municipais autorizados;
- impostos e retenções configuráveis, sem regra tributária mutável hardcoded;
- emissão, consulta, rejeição, retry idempotente, cancelamento e substituição;
- XML, PDF/DANFSe, protocolo e código de verificação;
- credenciais/certificados somente no servidor;
- auditoria reforçada e vínculo com conta a receber/recebimento;
- arquitetura extensível para NF-e.

Gate: homologação, idempotência, rejeição, cancelamento, armazenamento seguro e
vínculo financeiro testados. O módulo é próprio do Clareza e não depende do
Conta Azul ou de qualquer outro ERP.

## Depois — compreensão e ação

### 5. Glossário gerenciável e explicações

- níveis essencial, gerencial e técnico;
- fluxo editorial, fontes e vigência;
- termos contextuais em relatórios;
- explicações determinísticas com fórmula/origem.

### 6. Importações e documentos

- upload, preview, mapeamento, validação e confirmação;
- jobs idempotentes, erros por linha e rollback seguro;
- documentos com classificação, versões, retenção e acesso auditado.

### 7. Pendências, riscos e planos de ação

- regras explicáveis;
- matriz configurável e critérios versionados;
- responsável, prazo, evidência e monitoramento;
- saúde empresarial derivada sem “nota mágica”.

### 8. Auditoria e diagnósticos

- programas, testes, amostras, evidências e achados;
- fluxo de consultoria e recomendações;
- relatórios revisáveis e acompanhamento de resultados.

### 9. Controladoria e planejamento

- centros de custo;
- orçamento, forecast e realizado;
- margens, rentabilidade e análise de desvios;
- comparações entre unidades com critérios consistentes.

## Futuro — ecossistema e inteligência

- integrações bancárias, contábeis, fiscais e ERPs;
- processamento assíncrono e filas onde houver volume;
- API externa com consentimento e escopos;
- “Pergunte aos números” com fontes, autorização e avaliações;
- SaaS multi-região e aplicativo mobile somente após validação de uso.

## Itens deliberadamente adiados

- microsserviços;
- pontuação universal de saúde;
- recomendações por IA sem trilha de origem;
- módulos regulatórios sem especialista e vigência;
- aplicativo mobile nativo;
- customização ilimitada antes de casos de uso estáveis.

## Métricas de progresso

- isolamento e autorização comprovados;
- reconciliação e integridade dos cálculos;
- tempo até encontrar a origem de um número;
- compreensão correta em testes com personas;
- acessibilidade de tarefas críticas;
- incidentes, erros e retrabalho;
- ações concluídas a partir de pendências explicadas.
