# LGPD e privacidade

## Escopo

Privacidade deve ser incorporada antes da ingestão de dados reais. O protótipo usa conteúdo fictício e não implementa um programa de conformidade completo.

Os papéis legais de controlador, operador, suboperador e encarregado dependem do modelo comercial e contratual final. Eles não são presumidos neste documento e devem ser validados com assessoria jurídica.

## Princípios de produto

- finalidade explícita e compatível;
- adequação e necessidade;
- minimização por campo e por perfil;
- transparência em coleta, cálculo e compartilhamento;
- qualidade e possibilidade de correção;
- segurança e prevenção;
- não discriminação;
- responsabilização demonstrável.

## Inventário inicial

| Categoria | Exemplos futuros | Risco | Diretriz |
| --- | --- | --- | --- |
| identidade profissional | nome, e-mail, cargo | pessoal | coletar apenas para acesso e colaboração |
| RH/trabalhista | vínculo, remuneração, afastamentos | alto | acesso estrito, finalidade e retenção específicas |
| financeiro | pagamentos, contas e contrapartes | alto | mascaramento, segregação e trilha de acesso |
| societário | sócios, procurações e documentos | alto | classificação documental e acesso por necessidade |
| auditoria | evidências, achados e autoria | variável/alto | minimizar cópias e restringir exportação |
| telemetria | IP, user agent, eventos técnicos | pessoal | retenção curta e pseudonimização quando possível |

Dados pessoais sensíveis exigem avaliação adicional e não devem ser incluídos apenas porque estão disponíveis na fonte.

## Registro de tratamento

Antes de cada módulo operar com dados reais, registrar:

- finalidade e caso de uso;
- categorias de titulares e dados;
- hipótese legal validada;
- origem, destinatários e operadores;
- local de armazenamento e eventual transferência internacional;
- prazo e critério de retenção;
- controles de acesso e segurança;
- procedimento de correção, exportação, bloqueio e eliminação;
- risco residual e necessidade de RIPD.

Consentimento não deve ser usado como resposta genérica quando outra hipótese legal for a aplicável.

## Direitos dos titulares

O fluxo-alvo deve receber, autenticar, registrar e responder solicitações de:

- confirmação e acesso;
- correção;
- anonimização, bloqueio ou eliminação quando cabível;
- portabilidade conforme regulamentação aplicável;
- informação sobre compartilhamentos;
- revisão de decisões automatizadas, quando aplicável;
- revogação de consentimento, quando essa for a base.

A resposta deve respeitar dados de terceiros, segredo comercial e obrigações legais de conservação. Prazos e responsáveis serão definidos no procedimento operacional.

## Privacy by design

- campos opcionais permanecem vazios por padrão;
- ambientes de desenvolvimento usam seeds sintéticos;
- exportações exigem permissão específica e são auditadas;
- telas mascaram dados quando o valor completo não é necessário;
- relatórios evitam granularidade que permita reidentificação;
- logs técnicos usam IDs internos e mascaramento;
- documentos têm classificação, expiração de acesso e retenção;
- exclusão de conta não implica apagar registros sujeitos a obrigação legal: o fluxo decide eliminação, anonimização ou bloqueio.

## Retenção

Não há tabela única de retenção definida. Cada categoria deverá ter dono, fundamento, início de contagem, prazo, exceção de litígio e descarte verificável. Backups devem acompanhar uma política de expiração e restauração que não reative dados eliminados sem tratamento.

## IA e explicações

Antes de usar modelos externos:

- realizar avaliação de impacto e de fornecedor;
- impedir treino com dados do cliente sem base e contrato explícitos;
- reduzir e pseudonimizar contexto;
- aplicar autorização também à recuperação de fontes;
- registrar modelo/versão, fontes e intervenção humana pertinente;
- permitir contestação de decisões que produzam efeitos relevantes;
- não enviar dados sensíveis quando uma explicação determinística resolver.

## Incidentes de privacidade

O plano de resposta deverá identificar titulares e dados afetados, preservar evidências, conter a exposição, avaliar risco/dano relevante e suportar comunicações legalmente exigidas. Critérios e prazos precisam de validação jurídica e do encarregado antes da produção.

## Gate para dados reais

Dados reais só entram após inventário aprovado, contratos e papéis definidos, controles testados, política de retenção, canal de titulares, plano de incidentes e autorização formal do responsável pelo tratamento.
