# ADR-003: Autenticação Spring Security 6 (OAuth2/JWT + Sessões Persistidas em `user_sessions`)

- **Status:** Aprovado
- **Data:** 2026-11-08
- **Tasks Relacionadas:** `CLZ-007`, `CLZ-008`

## Contexto

O código legado confiava em cabeçalhos HTTP planos (`oai-authenticated-user-email`) e gerava um `sessionId` aleatório descartável em cada requisição HTTP sem persistência ou possibilidade de revogação.

## Decisão

1. Introduzir a tabela `user_sessions` na migration `V8__user_sessions_and_auth_hardening.sql` com RLS por `tenant_id` e `user_id`, permitindo rastrear e revogar sessões ativas (`revoked_at`).
2. Configurar **Spring Security 6** para suportar tokens JWT assinados / OAuth2 Resource Server em produção e manter um extrator de identidade compatível no profile `test` para execução de toda a matriz de testes de contrato existentes.
3. Validar obrigatoriamente `x-clareza-company-id` e `x-clareza-branch-id` contra as memberships ativas no `ClarezaContextFilter`, ignorando quaisquer cabeçalhos de tenant ou usuário enviados pelo cliente.
