import assert from "node:assert/strict";
import test from "node:test";

import { isCurrentHistoryResponse } from "../app/financial-request-guards.ts";

test("histórico A atrasado não pode sobrescrever a seleção B", () => {
  const currentRevision = 2;

  assert.equal(
    isCurrentHistoryResponse({
      requestRevision: 1,
      currentRevision,
      requestedRecordId: "titulo-a",
      responseRecordId: "titulo-a",
    }),
    false,
  );
  assert.equal(
    isCurrentHistoryResponse({
      requestRevision: 2,
      currentRevision,
      requestedRecordId: "titulo-b",
      responseRecordId: "titulo-b",
    }),
    true,
  );
});

test("histórico rejeita resposta cujo título difere do solicitado", () => {
  assert.equal(
    isCurrentHistoryResponse({
      requestRevision: 3,
      currentRevision: 3,
      requestedRecordId: "titulo-b",
      responseRecordId: "titulo-a",
    }),
    false,
  );
});
