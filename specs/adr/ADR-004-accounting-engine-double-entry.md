# ADR-004: Modelo Contábil de Partidas Dobradas e DRE Derivada (EPIC-10)

- **Status:** Proposto (Sujeito a validação na Fase 4 / Sprint 9)
- **Data:** 2026-11-08
- **Tasks Relacionadas:** `CLZ-020`, `CLZ-021`

## Contexto

As tabelas `journal_entries` e `journal_entry_lines` já existem desde a `V1__init.sql` com constraint de equilíbrio ($\sum \text{debit} = \sum \text{credit}$), mas faltam o Plano de Contas estruturado (`chart_of_accounts`), o controle de Períodos Contábeis (`accounting_periods`) e os relatórios calculados (Diário, Razão, Balancete e DRE).

## Decisão

1. Nenhuma linha da DRE será editada manualmente ou alimentada por constantes: toda rubrica da DRE será agregada em tempo real a partir de `journal_entry_lines` pertencentes a `journal_entries` com `status = 'POSTED'`.
2. Fechamento de período (`CLOSED`) bloqueia novas postagens ou estornos com `entry_date` dentro do mês fechado, exigindo reabertura formal auditada (`REOPENED`).
3. Estornos contábeis criam um novo `JournalEntry` inverso referenciando `reverses_entry_id`, preservando a imutabilidade dos lançamentos postados.
