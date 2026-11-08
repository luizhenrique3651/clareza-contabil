# Clareza

> **Codinome provisório:** Clareza — *Menos complexidade. Mais clareza.*

Fundação executável de uma plataforma empresarial multiempresa para transformar dados contábeis, financeiros e gerenciais em informação compreensível, rastreável e acionável.

## Estado do incremento

Este repositório contém um **protótipo local de experiência**, não um sistema pronto para produção.

| Área | Estado atual |
| --- | --- |
| Central de Comando | Identidade, empresa, unidade e permissão reais; KPIs, diagnósticos, caixa, DRE e pendências ainda demonstrativos |
| Glossário contextual | Interação demonstrável por drawer, com conteúdo estático |
| Dados exibidos | Fictícios e locais, criados exclusivamente para demonstração |
| Contratos de domínio e dados | Identidade/contexto conectados; núcleo financeiro operacional implementado e aprovado em revisão |
| PostgreSQL e Prisma | Seis migrations; gate da Fase 2 aprovado com PostgreSQL 16 direto e suíte Prisma/`pg` 30/30 |
| API de negócio | Identidade, títulos, catálogos, baixas, estornos e histórico consumidos pela área operacional |
| Autenticação, RBAC e isolamento multi-tenant | Identidade da borda, membership, permissões e contexto empresa/unidade validados em PostgreSQL 16 real |
| IA, importações e integrações | Fora do incremento atual |

Não use o protótipo com dados reais, pessoais, financeiros ou confidenciais.

## Experiência demonstrada

- visão executiva orientada a perguntas, em vez de um dashboard decorativo;
- explicações em linguagem simples, sem substituir a informação técnica;
- comparação de períodos em “O que mudou?”;
- projeção ilustrativa de caixa em 60 dias e leitura contextual de liquidez;
- DRE visual com leitura progressiva;
- sinais de saúde e pendências com texto, ícone e cor;
- navegação contextual até a explicação de termos;
- busca e período demonstrativos, com seletores de empresa/unidade vindos do backend;
- área operacional de contas a pagar e receber, com baixa, estorno e histórico.

Os valores, alertas, percentuais, projeções e diagnósticos apresentados são exemplos. Eles não resultam de conciliação, cálculo contábil, motor de regras ou análise por IA. Busca, notificações e período são interações ilustrativas. Identidade, perfil, empresas, unidades e permissões são resolvidos pelo backend, mas isso não torna o sistema pronto para produção.

## Stack deste protótipo

- React 19 e TypeScript;
- App Router compatível com Next.js, executado por [vinext](https://github.com/cloudflare/vinext);
- Vite e adaptador Cloudflare Worker;
- Sites como superfície prevista para preview e hospedagem do frontend;
- CSS responsivo no próprio projeto.

A arquitetura de produto pretendida é um **monólito modular**, com API versionada, PostgreSQL e Prisma. A separação entre o que existe e o que é alvo está detalhada em [Arquitetura](docs/ARCHITECTURE.md).

## Executar localmente

Pré-requisito: Node.js `>=22.18.0`.

```bash
npm install
npm run dev
```

Abra o endereço informado pelo terminal. Para validar o incremento:

```bash
npm run lint
npm run typecheck
npm test
```

`npm test` já produz o build e executa os testes `node:test`. Consulte [Setup](docs/SETUP.md) para detalhes da fundação de dados.

A interface abre sem banco apenas em estado seguro de indisponibilidade/não
autenticado. A área autenticada e as APIs operacionais exigem `DATABASE_URL`,
migrations aplicadas e uma identidade da borda vinculada a uma membership.
Consulte [Setup](docs/SETUP.md).

## Estrutura relevante

```text
app/                 interface e rota executável
prisma/              schema, migrations e seeds PostgreSQL
server/              contratos de contexto de tenant no servidor
db/                  compatibilidade D1/Drizzle legada, fora da persistência do produto
worker/              entrada do runtime vinext/Cloudflare
tests/               verificações automatizadas disponíveis
docs/                documentação de produto e engenharia
.openai/hosting.json configuração declarativa do Sites
```

Pastas conceituais descritas no roadmap não devem ser criadas antes de existir código real que justifique sua presença.

## Princípios inegociáveis

1. O tenant e a empresa autorizada serão derivados do contexto autenticado no servidor, nunca aceitos como fonte de verdade do frontend.
2. Todo dado calculado deverá informar período, fórmula e origem.
3. Nenhuma classificação será chamada de “boa”, “ruim” ou “saudável” sem contexto e critério visível.
4. IA futura só poderá responder com dados autorizados e referências verificáveis.
5. Segurança, LGPD, acessibilidade e rastreabilidade são critérios de aceite, não complementos.

## Documentação

- [Arquitetura](docs/ARCHITECTURE.md) e [decisões](docs/DECISIONS.md)
- [Banco de dados](docs/DATABASE.md), [módulos](docs/MODULES.md) e [API](docs/API.md)
- [Segurança](docs/SECURITY.md), [LGPD](docs/LGPD.md) e [auditoria](docs/AUDIT.md)
- [UX](docs/UX.md), [design system](docs/DESIGN_SYSTEM.md) e [glossário](docs/GLOSSARY.md)
- [Setup](docs/SETUP.md), [deploy](docs/DEPLOYMENT.md) e [roadmap](docs/ROADMAP.md)

## Limites de responsabilidade

O Clareza apoia compreensão e decisão; não substitui escrituração oficial, aconselhamento contábil, fiscal, jurídico ou trabalhista. Regras legais e tributárias devem possuir vigência, jurisdição, fonte e revisão profissional antes de uso operacional.
