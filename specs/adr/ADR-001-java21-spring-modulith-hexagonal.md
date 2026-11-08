# ADR-001: Adoção de Monólito Modular Hexagonal em Java 21 (Spring Boot 3.4) + React 19

- **Status:** Aprovado
- **Data:** 2026-11-08
- **Tasks Relacionadas:** `CLZ-001`, `CLZ-003`

## Contexto

O código legado foi prototipado sobre um template híbrido (`vinext` + Next.js + Vite + Cloudflare Workers + Drizzle D1 morto + Prisma 7) e implementava o domínio financeiro em arquivos JavaScript sem tipagem estática (`server/*.mjs`) e componentes React monolíticos (`app/command-center.tsx` e `app/financial-workspace.tsx`).

## Decisão

1. Adotar **Java 21+** com **Spring Boot 3.4** estruturado como um **Monólito Modular Hexagonal** (`shared`, `iam`, `finance`, `banking`, `accounting`, `governance`), verificado por **ArchUnit**.
2. Adotar **Maven (`pom.xml`)** como ferramenta de build determinística.
3. Separar o frontend em uma SPA dedicada em **React 19 + TypeScript 5.8 + Vite 6** (`frontend/`), consumindo os contratos de `specs/openapi/clareza-api-v1.yaml`.
4. Isolar o código legado integralmente em `legacy/` como oráculo de comportamento e regressão.

## Consequências

- **Positivas:** Tipagem estática forte em todo o domínio financeiro, eliminação de aritmética manual em strings/`BigInt` via `BigDecimal` (`Money`), remoção total de dependências órfãs de Cloudflare Workers/D1, e fronteiras arquiteturais verificadas automaticamente no build.
- **Negativas:** Exige portabilidade disciplinada dos casos de uso existentes mantendo paridade de contratos HTTP e SQL.
