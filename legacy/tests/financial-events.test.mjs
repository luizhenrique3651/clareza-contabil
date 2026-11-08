import assert from "node:assert/strict";
import test from "node:test";

import { financialOccurredAt } from "../server/financial-events.mjs";

test("evento financeiro rejeita instante futuro antes de abrir transação", () => {
  const future = new Date(Date.now() + 60_000).toISOString();

  assert.throws(
    () => financialOccurredAt(future),
    (error) =>
      error?.code === "VALIDATION_ERROR" &&
      error.errors?.some(
        (item) =>
          item.field === "occurredAt" &&
          item.code === "FUTURE_FINANCIAL_EVENT",
      ),
  );
});

test("evento financeiro aceita instante passado e gera o instante atual quando omitido", () => {
  const past = new Date(Date.now() - 60_000).toISOString();
  assert.equal(financialOccurredAt(past).toISOString(), past);

  const before = Date.now();
  const generated = financialOccurredAt();
  const after = Date.now();
  assert.ok(generated.valueOf() >= before);
  assert.ok(generated.valueOf() <= after);
});
