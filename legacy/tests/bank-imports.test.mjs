import assert from "node:assert/strict";
import test from "node:test";

import {
  BankImportValidationError,
  normalizeBankAmount,
  parseBankImport,
} from "../server/bank-imports.mjs";

const ACCOUNT = "85000000-0000-4000-8000-000000000001";

test("CSV aceita vírgula decimal, aspas e produz hash determinístico", () => {
  const content = [
    "data;valor;descricao;id;memo",
    '2026-07-20;-123,4500;"Tarifa; bancária";TX-1;"Mensal"',
    '2026-07-21;500.00;"Recebimento ""Cliente""";TX-2;',
  ].join("\r\n");
  const first = parseBankImport({
    format: "csv",
    content,
    financialAccountId: ACCOUNT,
  });
  const second = parseBankImport({
    format: "CSV",
    content: content.replace(/\r\n/g, "\n"),
    financialAccountId: ACCOUNT,
  });
  assert.equal(first.contentHash, second.contentHash);
  assert.equal(first.entries.length, 2);
  assert.deepEqual(first.entries[0], {
    sourceLine: 2,
    bookedAt: "2026-07-20T00:00:00.000Z",
    amount: "-123.4500",
    description: "Tarifa; bancária",
    externalId: "TX-1",
    memo: "Mensal",
    fingerprint: first.entries[0].fingerprint,
  });
  assert.match(first.entries[0].fingerprint, /^[0-9a-f]{64}$/);
  assert.equal(first.entries[1].description, 'Recebimento "Cliente"');
});

test("OFX SGML aceita datas com fuso e rejeita entidades externas", () => {
  const parsed = parseBankImport({
    format: "OFX",
    financialAccountId: ACCOUNT,
    content: `
      OFXHEADER:100
      <OFX><BANKMSGSRSV1><STMTTRNRS><STMTRS><BANKTRANLIST>
      <STMTTRN>
        <TRNTYPE>DEBIT
        <DTPOSTED>20260720103000[-4:AMT]
        <TRNAMT>-42.50
        <FITID>ofx-1
        <NAME>Tarifa &amp; serviço
        <MEMO>Conta demo
      </STMTTRN>
      </BANKTRANLIST></STMTRS></STMTTRNRS></BANKMSGSRSV1></OFX>`,
  });
  assert.equal(parsed.entries[0].bookedAt, "2026-07-20T14:30:00.000Z");
  assert.equal(parsed.entries[0].amount, "-42.5000");
  assert.equal(parsed.entries[0].description, "Tarifa & serviço");

  assert.throws(
    () =>
      parseBankImport({
        format: "OFX",
        financialAccountId: ACCOUNT,
        content:
          '<!DOCTYPE x [<!ENTITY secret SYSTEM "file:///etc/passwd">]><OFX></OFX>',
      }),
    (error) =>
      error instanceof BankImportValidationError &&
      error.errors[0].code === "UNSAFE_XML",
  );
});

test("OFX rejeita calendário impossível, hora inválida e sufixo ignorado", () => {
  for (const date of [
    "20260231000000",
    "20261301000000",
    "20260720250000",
    "20260720103000[-4:AMT]lixo",
    "2026-02-31T00:00:00Z",
    "20260720113000[99:BAD]",
  ]) {
    assert.throws(
      () =>
        parseBankImport({
          format: "OFX",
          financialAccountId: ACCOUNT,
          content: `<OFX><STMTTRN><DTPOSTED>${date}<TRNAMT>-1.00<FITID>${date}<NAME>Inválida</STMTTRN></OFX>`,
        }),
      (error) =>
        error instanceof BankImportValidationError &&
        error.errors.some((item) => item.code === "INVALID_DATE"),
      date,
    );
  }
});

test("CSV rejeita calendário ISO impossível e fuso fora do padrão", () => {
  const valid = parseBankImport({
    format: "CSV",
    financialAccountId: ACCOUNT,
    content:
      "date,amount,description\n2026-07-20T10:30:00-04:00,-1.00,Válida",
  });
  assert.equal(valid.entries[0].bookedAt, "2026-07-20T14:30:00.000Z");

  for (const date of [
    "2026-02-31T00:00:00Z",
    "2026-07-20T25:00:00Z",
    "2026-07-20T10:30:00+15:00",
    "2026-07-20T10:30:00Zlixo",
  ]) {
    assert.throws(
      () =>
        parseBankImport({
          format: "CSV",
          financialAccountId: ACCOUNT,
          content: `date,amount,description\n${date},-1.00,Inválida`,
        }),
      (error) =>
        error instanceof BankImportValidationError &&
        error.errors.some((item) => item.code === "INVALID_DATE"),
      date,
    );
  }
});

test("movimentações idênticas sem FITID continuam distintas por linha", () => {
  const parsed = parseBankImport({
    format: "CSV",
    financialAccountId: ACCOUNT,
    content: [
      "date,amount,description",
      "2026-07-20,-10.00,Tarifa",
      "2026-07-20,-10.00,Tarifa",
    ].join("\n"),
  });
  assert.equal(parsed.entries.length, 2);
  assert.equal(parsed.entries[0].fingerprint, parsed.entries[1].fingerprint);
  assert.notEqual(parsed.entries[0].sourceLine, parsed.entries[1].sourceLine);
});

test("decimal bancário rejeita zero, milhar ambíguo e precisão excessiva", () => {
  assert.equal(normalizeBankAmount("+0012,3"), "12.3000");
  for (const value of ["0", "1.234,56", "10.00000", "NaN", "Infinity"]) {
    assert.throws(
      () => normalizeBankAmount(value),
      BankImportValidationError,
      value,
    );
  }
});

test("CSV rejeita cabeçalho ausente, aspas abertas e arquivo excessivo", () => {
  const cases = [
    "date,description\n2026-07-20,Sem valor",
    'date,amount,description\n2026-07-20,10.00,"Aberto',
    `date,amount,description\n2026-07-20,10.00,${"x".repeat(1024 * 1024)}`,
  ];
  for (const content of cases) {
    assert.throws(
      () =>
        parseBankImport({
          format: "CSV",
          financialAccountId: ACCOUNT,
          content,
        }),
      BankImportValidationError,
    );
  }
});
