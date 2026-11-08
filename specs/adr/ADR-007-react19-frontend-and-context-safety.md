# ADR-007: Arquitetura Frontend React 19, Prevenção de Condições de Corrida e Zero-Trust no Cliente

- **Status:** ACCEPTED
- **Data:** 2026-11-08
- **Aplica-se a:** `frontend`
- **Tasks Relacionadas:** `CLZ-009`, `CLZ-010`, `CLZ-018`, `CLZ-019`, `CLZ-022`

---

## Contexto

Em um sistema multiempresa e multiunidade, o operador pode alternar rapidamente entre empresas ou unidades no seletor de contexto. Se uma resposta HTTP mais antiga sobrescrever uma mais recente, a interface exibirá dados de uma empresa rotulados com o nome de outra. Além disso, o armazenamento local do navegador (`localStorage` / `sessionStorage`) não é confiável para decisões de autorização.

---

## Decisão

1. **Zero-Trust no Navegador (`safeNavigationStorage`):**
   - O `localStorage` armazena exclusivamente a preferência de navegação `{ tenantId, companyId, branchId }` na chave `clareza.navigation.context.v1`.
   - É proibido armazenar papéis (`roleCode`), permissões (`permissions[]`) ou dados financeiros sensíveis em `localStorage` ou `sessionStorage`.
   - Toda autorização visual deriva da resposta fresca de `GET /api/v1/me` e é revalidada pelo backend em cada chamada subsequente.
2. **Prevenção de Condição de Corrida (`ApplicationContext.tsx`):**
   - Toda troca de contexto cancela imediatamente a requisição anterior via `AbortController` e incrementa um contador monotônico (`requestSeqRef`).
   - Respostas cujo número de sequência seja anterior ao `requestSeqRef.current` são descartadas silenciosamente.
   - Caso o servidor retorne `400 INVALID_CONTEXT_SELECTION` (ex.: vínculo revogado), o cliente limpa `safeNavigationStorage` e recarrega automaticamente o contexto padrão autorizado.
3. **Identidade Visual e Honestidade Operacional:**
   - Preservar os tokens visuais Mourão Clareza (`--color-navy-900: #112239`, `--color-gold-500: #c5a059`, `--color-ivory-100: #f7f4ec`, tipografia `Manrope` e `Newsreader`).
   - Qualquer seção ainda demonstrativa deve exibir badge explícito `Demo` / `Dados demonstrativos`, nunca afirmando que dados simulados estão "conciliados" ou "auditados".
