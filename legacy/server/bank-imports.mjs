import { createHash } from "node:crypto";

export const BANK_IMPORT_LIMITS = Object.freeze({
  maxBytes: 1024 * 1024,
  maxEntries: 2_000,
  maxFieldsPerRow: 16,
  maxFieldLength: 500,
});

export class BankImportValidationError extends Error {
  constructor(message, errors = []) {
    super(message);
    this.name = "BankImportValidationError";
    this.code = "BANK_IMPORT_VALIDATION_ERROR";
    this.errors = Object.freeze(errors);
  }
}

function fail(field, code, message) {
  throw new BankImportValidationError("O arquivo bancário é inválido.", [
    Object.freeze({ field, code, message }),
  ]);
}

function normalizedContent(content) {
  if (typeof content !== "string") {
    fail("content", "INVALID_TYPE", "Envie o conteúdo bancário como texto.");
  }
  const byteLength = Buffer.byteLength(content, "utf8");
  if (byteLength === 0) {
    fail("content", "EMPTY_FILE", "O arquivo bancário está vazio.");
  }
  if (byteLength > BANK_IMPORT_LIMITS.maxBytes) {
    fail(
      "content",
      "FILE_TOO_LARGE",
      `O arquivo excede ${BANK_IMPORT_LIMITS.maxBytes} bytes.`,
    );
  }
  if (content.includes("\0")) {
    fail("content", "INVALID_CHARACTER", "O arquivo contém caractere nulo.");
  }
  return content.replace(/^\uFEFF/, "").replace(/\r\n?/g, "\n");
}

function textField(value, field, { required = false, max = 500 } = {}) {
  const normalized = String(value ?? "")
    .replace(/\s+/g, " ")
    .trim();
  if (required && !normalized) {
    fail(field, "REQUIRED", `O campo ${field} é obrigatório.`);
  }
  if (normalized.length > max) {
    fail(field, "TOO_LONG", `O campo ${field} excede ${max} caracteres.`);
  }
  return normalized || null;
}

export function normalizeBankAmount(value, field = "amount") {
  const raw = String(value ?? "").trim();
  const match = /^([+-]?)(\d{1,15})(?:([.,])(\d{1,4}))?$/.exec(raw);
  if (!match) {
    fail(
      field,
      "INVALID_DECIMAL",
      "Use um decimal com até 15 inteiros e 4 casas, sem separador de milhar.",
    );
  }
  const negative = match[1] === "-";
  const integer = match[2].replace(/^0+(?=\d)/, "");
  const fraction = (match[4] ?? "").padEnd(4, "0");
  const scaled = BigInt(integer) * 10_000n + BigInt(fraction || "0");
  if (scaled === 0n) {
    fail(field, "ZERO_AMOUNT", "A movimentação bancária não pode ter valor zero.");
  }
  return `${negative ? "-" : ""}${integer}.${fraction}`;
}

function isoDate(value, field = "date") {
  const raw = String(value ?? "").trim();
  const match =
    /^(\d{4})-(\d{2})-(\d{2})(?:[Tt](\d{2}):(\d{2})(?::(\d{2})(?:\.(\d{1,3}))?)?([Zz]|[+-]\d{2}:\d{2}))?$/.exec(
      raw,
    );
  if (!match) {
    fail(
      field,
      "INVALID_DATE",
      "Informe uma data bancária ISO 8601 completa e válida.",
    );
  }

  const year = Number(match[1]);
  const month = Number(match[2]) - 1;
  const day = Number(match[3]);
  const hour = Number(match[4] ?? 0);
  const minute = Number(match[5] ?? 0);
  const second = Number(match[6] ?? 0);
  const millisecond = Number((match[7] ?? "").padEnd(3, "0") || 0);
  const zone = match[8] ?? "Z";
  const localCalendar = new Date(
    Date.UTC(year, month, day, hour, minute, second, millisecond),
  );
  if (
    year < 1900 ||
    year > 2200 ||
    month < 0 ||
    month > 11 ||
    day < 1 ||
    day > 31 ||
    hour < 0 ||
    hour > 23 ||
    minute < 0 ||
    minute > 59 ||
    second < 0 ||
    second > 59 ||
    localCalendar.getUTCFullYear() !== year ||
    localCalendar.getUTCMonth() !== month ||
    localCalendar.getUTCDate() !== day ||
    localCalendar.getUTCHours() !== hour ||
    localCalendar.getUTCMinutes() !== minute ||
    localCalendar.getUTCSeconds() !== second
  ) {
    fail(field, "INVALID_DATE", "A data bancária contém um calendário inválido.");
  }

  let offsetMinutes = 0;
  if (!/^[Zz]$/.test(zone)) {
    const sign = zone[0] === "-" ? -1 : 1;
    const offsetHour = Number(zone.slice(1, 3));
    const offsetMinute = Number(zone.slice(4, 6));
    if (
      offsetHour > 14 ||
      offsetMinute > 59 ||
      (offsetHour === 14 && offsetMinute !== 0)
    ) {
      fail(field, "INVALID_DATE", "O fuso da data bancária é inválido.");
    }
    offsetMinutes = sign * (offsetHour * 60 + offsetMinute);
  }

  const parsed = new Date(localCalendar.getTime() - offsetMinutes * 60_000);
  if (
    !Number.isFinite(parsed.getTime()) ||
    parsed.getUTCFullYear() < 1900 ||
    parsed.getUTCFullYear() > 2200
  ) {
    fail(field, "INVALID_DATE", "A data bancária está fora do intervalo aceito.");
  }
  return parsed.toISOString();
}

function ofxDate(value, field = "DTPOSTED") {
  const raw = String(value ?? "").trim();
  const match =
    /^(\d{4})(\d{2})(\d{2})(?:(\d{2})(\d{2})(\d{2}))?(?:\.(\d{1,3}))?(?:\[([+-]?\d+(?:\.\d+)?):[^\]]+\])?$/.exec(
      raw,
    );
  if (!match) {
    return isoDate(raw, field);
  }
  const year = Number(match[1]);
  const month = Number(match[2]) - 1;
  const day = Number(match[3]);
  const hour = Number(match[4] ?? 0);
  const minute = Number(match[5] ?? 0);
  const second = Number(match[6] ?? 0);
  const millisecond = Number((match[7] ?? "").padEnd(3, "0") || 0);
  const offsetHours = Number(match[8] ?? 0);
  if (offsetHours < -12 || offsetHours > 14) {
    fail(field, "INVALID_DATE", "O fuso da data OFX está fora do intervalo aceito.");
  }
  const localCalendar = new Date(
    Date.UTC(year, month, day, hour, minute, second, millisecond),
  );
  if (
    month < 0 ||
    month > 11 ||
    day < 1 ||
    day > 31 ||
    hour < 0 ||
    hour > 23 ||
    minute < 0 ||
    minute > 59 ||
    second < 0 ||
    second > 59 ||
    localCalendar.getUTCFullYear() !== year ||
    localCalendar.getUTCMonth() !== month ||
    localCalendar.getUTCDate() !== day ||
    localCalendar.getUTCHours() !== hour ||
    localCalendar.getUTCMinutes() !== minute ||
    localCalendar.getUTCSeconds() !== second
  ) {
    fail(field, "INVALID_DATE", "A data OFX contém um calendário inválido.");
  }
  const timestamp =
    Date.UTC(year, month, day, hour, minute, second, millisecond) -
    offsetHours * 60 * 60 * 1_000;
  const parsed = new Date(timestamp);
  if (
    parsed.getUTCFullYear() < 1900 ||
    parsed.getUTCFullYear() > 2200 ||
    !Number.isFinite(parsed.getTime())
  ) {
    fail(field, "INVALID_DATE", "A data OFX está fora do intervalo aceito.");
  }
  return parsed.toISOString();
}

function sha256(value) {
  return createHash("sha256").update(value, "utf8").digest("hex");
}

function transactionFingerprint(accountId, entry) {
  return sha256(
    [
      accountId,
      entry.bookedAt,
      entry.amount,
      entry.externalId ?? "",
      entry.description.toLocaleLowerCase("pt-BR"),
      entry.memo?.toLocaleLowerCase("pt-BR") ?? "",
    ].join("\u001f"),
  );
}

function splitCsv(content, delimiter) {
  const rows = [];
  let row = [];
  let field = "";
  let quoted = false;

  for (let index = 0; index < content.length; index += 1) {
    const character = content[index];
    if (quoted) {
      if (character === '"' && content[index + 1] === '"') {
        field += '"';
        index += 1;
      } else if (character === '"') {
        quoted = false;
      } else {
        field += character;
      }
      continue;
    }
    if (character === '"') {
      if (field.length > 0) {
        fail("content", "INVALID_CSV", "Aspas devem iniciar um campo CSV.");
      }
      quoted = true;
    } else if (character === delimiter) {
      row.push(field);
      field = "";
    } else if (character === "\n") {
      row.push(field);
      rows.push(row);
      row = [];
      field = "";
    } else {
      field += character;
    }
  }
  if (quoted) {
    fail("content", "INVALID_CSV", "Existe um campo CSV com aspas não fechadas.");
  }
  row.push(field);
  rows.push(row);
  return rows.filter((candidate) =>
    candidate.some((value) => String(value).trim().length > 0),
  );
}

function canonicalHeader(value) {
  return String(value)
    .normalize("NFD")
    .replace(/\p{Diacritic}/gu, "")
    .toLowerCase()
    .replace(/[^a-z0-9]/g, "");
}

const CSV_HEADERS = Object.freeze({
  date: new Set(["date", "data", "bookedat", "dtposted"]),
  amount: new Set(["amount", "valor", "trnamt"]),
  description: new Set(["description", "descricao", "historico", "name"]),
  externalId: new Set(["externalid", "id", "fitid", "documento"]),
  memo: new Set(["memo", "observacao", "complemento"]),
});

function headerIndex(headers, name, required = false) {
  const aliases = CSV_HEADERS[name];
  const index = headers.findIndex((header) => aliases.has(header));
  if (required && index < 0) {
    fail("content", "MISSING_HEADER", `O CSV precisa da coluna ${name}.`);
  }
  return index;
}

function parseCsv(content) {
  const firstLine = content.split("\n", 1)[0];
  const delimiter =
    (firstLine.match(/;/g)?.length ?? 0) > (firstLine.match(/,/g)?.length ?? 0)
      ? ";"
      : ",";
  const rows = splitCsv(content, delimiter);
  if (rows.length < 2) {
    fail("content", "EMPTY_CSV", "O CSV não contém movimentações.");
  }
  if (rows.length - 1 > BANK_IMPORT_LIMITS.maxEntries) {
    fail(
      "content",
      "TOO_MANY_ENTRIES",
      `O CSV excede ${BANK_IMPORT_LIMITS.maxEntries} movimentações.`,
    );
  }
  const headers = rows[0].map(canonicalHeader);
  const indexes = {
    date: headerIndex(headers, "date", true),
    amount: headerIndex(headers, "amount", true),
    description: headerIndex(headers, "description", true),
    externalId: headerIndex(headers, "externalId"),
    memo: headerIndex(headers, "memo"),
  };

  return rows.slice(1).map((row, index) => {
    if (row.length > BANK_IMPORT_LIMITS.maxFieldsPerRow) {
      fail(
        `rows.${index + 1}`,
        "TOO_MANY_FIELDS",
        "A linha possui campos demais.",
      );
    }
    if (row.some((value) => value.length > BANK_IMPORT_LIMITS.maxFieldLength)) {
      fail(`rows.${index + 1}`, "FIELD_TOO_LONG", "A linha possui campo longo demais.");
    }
    return {
      sourceLine: index + 2,
      bookedAt: isoDate(row[indexes.date], `rows.${index + 1}.date`),
      amount: normalizeBankAmount(
        row[indexes.amount],
        `rows.${index + 1}.amount`,
      ),
      description: textField(
        row[indexes.description],
        `rows.${index + 1}.description`,
        { required: true },
      ),
      externalId:
        indexes.externalId < 0
          ? null
          : textField(
              row[indexes.externalId],
              `rows.${index + 1}.externalId`,
              { max: 160 },
            ),
      memo:
        indexes.memo < 0
          ? null
          : textField(row[indexes.memo], `rows.${index + 1}.memo`),
    };
  });
}

function decodeOfxText(value, field) {
  const raw = String(value ?? "");
  if (/&(?!(?:amp|lt|gt|quot|apos);)/i.test(raw)) {
    fail(field, "UNSUPPORTED_ENTITY", "O OFX contém uma entidade não permitida.");
  }
  return raw
    .replace(/&amp;/gi, "&")
    .replace(/&lt;/gi, "<")
    .replace(/&gt;/gi, ">")
    .replace(/&quot;/gi, '"')
    .replace(/&apos;/gi, "'");
}

function ofxTag(block, tag, required = false) {
  const match = new RegExp(`<${tag}>\\s*([^<\\n\\r]+)`, "i").exec(block);
  const value = match?.[1]?.trim() ?? "";
  if (required && !value) {
    fail("content", "MISSING_OFX_TAG", `O OFX precisa da tag ${tag}.`);
  }
  return value || null;
}

function parseOfx(content) {
  if (/<!DOCTYPE|<!ENTITY/i.test(content)) {
    fail(
      "content",
      "UNSAFE_XML",
      "DOCTYPE e entidades externas não são aceitos em OFX.",
    );
  }
  const blocks = [
    ...content.matchAll(/<STMTTRN>([\s\S]*?)(?:<\/STMTTRN>|(?=<STMTTRN>|<\/BANKTRANLIST>))/gi),
  ].map((match) => match[1]);
  if (blocks.length === 0) {
    fail("content", "EMPTY_OFX", "O OFX não contém blocos STMTTRN.");
  }
  if (blocks.length > BANK_IMPORT_LIMITS.maxEntries) {
    fail(
      "content",
      "TOO_MANY_ENTRIES",
      `O OFX excede ${BANK_IMPORT_LIMITS.maxEntries} movimentações.`,
    );
  }
  return blocks.map((block, index) => {
    const name = ofxTag(block, "NAME");
    const memo = ofxTag(block, "MEMO");
    const description = decodeOfxText(name ?? memo ?? "", `entries.${index}.description`);
    return {
      sourceLine: index + 1,
      bookedAt: ofxDate(ofxTag(block, "DTPOSTED", true)),
      amount: normalizeBankAmount(
        ofxTag(block, "TRNAMT", true),
        `entries.${index}.amount`,
      ),
      description: textField(description, `entries.${index}.description`, {
        required: true,
      }),
      externalId: textField(
        ofxTag(block, "FITID"),
        `entries.${index}.externalId`,
        { max: 160 },
      ),
      memo: textField(
        memo ? decodeOfxText(memo, `entries.${index}.memo`) : null,
        `entries.${index}.memo`,
      ),
    };
  });
}

export function parseBankImport({ format, content, financialAccountId }) {
  const normalizedFormat = String(format ?? "").trim().toUpperCase();
  if (!["CSV", "OFX"].includes(normalizedFormat)) {
    fail("format", "UNSUPPORTED_FORMAT", "Use o formato CSV ou OFX.");
  }
  const accountId = textField(financialAccountId, "financialAccountId", {
    required: true,
    max: 36,
  });
  const normalized = normalizedContent(content);
  const entries =
    normalizedFormat === "CSV" ? parseCsv(normalized) : parseOfx(normalized);
  return Object.freeze({
    format: normalizedFormat,
    contentHash: sha256(normalized),
    entries: Object.freeze(
      entries.map((entry) =>
        Object.freeze({
          ...entry,
          fingerprint: transactionFingerprint(accountId, entry),
        }),
      ),
    ),
  });
}
