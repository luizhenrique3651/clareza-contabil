# CLAUDE.md — Guia de Execução para Claude Code / Antigravity

Consulte [AGENTS.md](./AGENTS.md) para o fluxo operacional completo.

## Leitura Progressiva de Contexto (*Progressive Disclosure*)

1. Leia [`docs/ESTADO_ATUAL.md`](./docs/ESTADO_ATUAL.md) e [`docs/ROADMAP_MASTER.md`](./docs/ROADMAP_MASTER.md) para identificar a Task `CLZ-XXX` ativa.
2. Consulte a tabela roteadora em [`docs/DECISOES_ARQUITETURAIS.md`](./docs/DECISOES_ARQUITETURAIS.md) e abra em [`specs/adr/`](./specs/adr) **somente** os ADRs (`ADR-001` a `ADR-007`) mapeados para a Task ativa.
3. Consulte a especificação detalhada da Task em [`docs/PLANO_REFATORACAO_JAVA_REACT_SDD.md`](./docs/PLANO_REFATORACAO_JAVA_REACT_SDD.md) e o contrato OpenAPI em [`specs/openapi/clareza-api-v1.yaml`](./specs/openapi/clareza-api-v1.yaml).
4. Use `./legacy/` (ou `./_legacy_reference/`) apenas como oráculo de leitura para paridade de regras de negócio das Fases 0 a 3.

## Ambiente Local Detectado (macOS Apple Silicon)

- **Java / OpenJDK:** `/opt/homebrew/opt/openjdk/bin/java` (`JAVA_HOME=/opt/homebrew/opt/openjdk`)
- **Maven:** `/opt/homebrew/bin/mvn`
- **Node.js / npm:** `/usr/local/bin/node` e `/usr/local/bin/npm`
- **Docker:** `/usr/local/bin/docker`

## Comandos de Verificação

```bash
# Testes Unitários e de Domínio do Backend Java
JAVA_HOME=/opt/homebrew/opt/openjdk /opt/homebrew/bin/mvn -f backend/pom.xml test

# Typecheck e Build do Frontend React 19
npm --prefix frontend run typecheck
npm --prefix frontend run build
```
