# ADR-005: Precisão Monetária Decimal (`NUMERIC(19,4)`) e Fuso Horário Operacional (`America/Manaus`)

- **Status:** ACCEPTED
- **Data:** 2026-11-08
- **Aplica-se a:** `shared`, `financial`, `reconciliation`, `accounting`, `frontend`
- **Tasks Relacionadas:** `CLZ-006`, `CLZ-012`, `CLZ-013`, `CLZ-015`, `CLZ-017`, `CLZ-020`

---

## Contexto

Sistemas financeiros e contábeis sofrem erros graves de arredondamento e divergência de centavos quando valores monetários são representados em ponto flutuante binário (`float`, `double`, `Number` IEEE 754 em operações aritméticas). Além disso, títulos com status `OPEN` tornam-se vencidos (`OVERDUE`) com base na data civil do domicílio operacional da organização (`America/Manaus`), não em UTC puro.

---

## Decisão

1. **Proibição Absoluta de Ponto Flutuante Binário:**
   - É proibido utilizar `float` ou `double` em entidades, DTOs, serviços ou cálculos financeiros no backend Java.
   - Todo valor monetário deve ser encapsulado pelo Value Object imutável [`Money`](../../backend/src/main/java/br/com/mouraoclareza/shared/domain/Money.java).
2. **Escala e Limites Canônicos (`NUMERIC(19,4)`):**
   - `Money` armazena `BigDecimal` com escala fixa `4` (`RoundingMode.UNNECESSARY`), aceitando no máximo 15 dígitos inteiros e 4 casas decimais (`-999999999999999.9999` a `999999999999999.9999`).
   - Qualquer entrada com mais de 4 casas decimais deve falhar com `422 VALIDATION_ERROR` (`INVALID_DECIMAL`), sem arredondamento silencioso.
   - Na serialização JSON (`@JsonValue`), valores monetários são trafegados como `String` canônica com 4 casas decimais (ex.: `"1500.2500"`).
3. **Derivação de `OVERDUE` em `America/Manaus`:**
   - O status `OVERDUE` nunca é gravado manualmente sem lastro; ele é derivado em tempo de leitura (`FinancialRecordDomainPolicy.deriveEffectiveStatus`) quando `status == 'OPEN'` e `dueDate < LocalDate.now(ZoneId.of("America/Manaus"))`.

---

## Consequências

- **Positivas:** Paridade matemática de 100% entre Java (`BigDecimal(19,4)`) e PostgreSQL (`NUMERIC(19,4)`), eliminando diferenças de centavos em baixas parciais, conciliações e partidas dobradas.
- **Negativas / Trade-offs:** Exige parsing explícito via `Money.parse(...)` nas bordas de entrada REST e arquivos OFX/CSV.
