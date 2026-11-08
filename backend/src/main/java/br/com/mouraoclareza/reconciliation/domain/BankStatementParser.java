package br.com.mouraoclareza.reconciliation.domain;

import br.com.mouraoclareza.shared.domain.Money;
import br.com.mouraoclareza.shared.exception.DomainValidationException;
import br.com.mouraoclareza.shared.exception.ValidationErrorItem;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser determinístico para extratos bancários OFX (1.x SGML / 2.x XML) e CSV (; ou ,).
 * Preserva 100% das invariantes de validação e normalização de legacy/server/bank-imports.mjs.
 */
public final class BankStatementParser {

    private static final Pattern OFX_STMTTRN_BLOCK = Pattern.compile(
            "<STMTTRN>(.*?)(?:</STMTTRN>|(?=<STMTTRN>)|(?=</BANKTRANLIST>))",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final DateTimeFormatter BASIC_ISO_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter BR_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private BankStatementParser() {
    }

    public static ParsedStatement parse(
            UUID tenantId,
            UUID companyId,
            UUID bankAccountId,
            String rawFormat,
            String rawContent
    ) {
        if (rawContent == null || rawContent.isBlank()) {
            throw new DomainValidationException(List.of(
                    new ValidationErrorItem("content", "EMPTY_FILE", "O conteúdo do extrato bancário não pode estar vazio.")
            ));
        }

        String format = normalizeFormat(rawFormat, rawContent);
        String sourceChecksum = BankMovementHashCalculator.computeSourceChecksum(rawContent);

        List<ParsedMovementLine> movements = switch (format) {
            case "OFX" -> parseOfx(tenantId, companyId, bankAccountId, rawContent);
            case "CSV" -> parseCsv(tenantId, companyId, bankAccountId, rawContent);
            default -> throw new DomainValidationException(List.of(
                    new ValidationErrorItem("format", "UNSUPPORTED_FORMAT", "Formato de extrato deve ser OFX ou CSV.")
            ));
        };

        if (movements.isEmpty()) {
            throw new DomainValidationException(List.of(
                    new ValidationErrorItem(
                            "content",
                            "EMPTY_STATEMENT",
                            "Nenhuma movimentação válida foi encontrada no extrato informado."
                    )
            ));
        }

        return new ParsedStatement(format, sourceChecksum, movements);
    }

    private static String normalizeFormat(String rawFormat, String rawContent) {
        if (rawFormat != null && !rawFormat.isBlank()) {
            return rawFormat.trim().toUpperCase(Locale.ROOT);
        }
        String trimmed = rawContent.trim().toUpperCase(Locale.ROOT);
        if (trimmed.contains("<OFX") || trimmed.contains("<STMTTRN>")) {
            return "OFX";
        }
        return "CSV";
    }

    private static List<ParsedMovementLine> parseOfx(
            UUID tenantId,
            UUID companyId,
            UUID bankAccountId,
            String content
    ) {
        List<ParsedMovementLine> lines = new ArrayList<>();
        Matcher matcher = OFX_STMTTRN_BLOCK.matcher(content);
        int lineIndex = 0;

        while (matcher.find()) {
            lineIndex++;
            String block = matcher.group(1);
            String rawDate = extractOfxTag(block, "DTPOSTED");
            String rawAmount = extractOfxTag(block, "TRNAMT");
            String fitId = extractOfxTag(block, "FITID");
            String memo = extractOfxTag(block, "MEMO");
            String name = extractOfxTag(block, "NAME");

            LocalDate postedDate = parseOfxDate(rawDate, lineIndex);
            Money signedAmount = Money.parseNonZeroSigned(rawAmount, "lines[" + lineIndex + "].amount");
            String direction = signedAmount.isNegative() ? "DEBIT" : "CREDIT";
            Money absAmount = signedAmount.isNegative() ? Money.ZERO.subtract(signedAmount) : signedAmount;

            String description = buildOfxDescription(name, memo);
            if (description.isBlank()) {
                throw new DomainValidationException(List.of(
                        new ValidationErrorItem(
                                "lines[" + lineIndex + "].description",
                                "MISSING_DESCRIPTION",
                                "Movimentação OFX sem MEMO ou NAME na transação #" + lineIndex
                        )
                ));
            }

            String externalRef = fitId != null && !fitId.isBlank() ? fitId.trim() : null;
            String movementHash = BankMovementHashCalculator.computeMovementHash(
                    tenantId, companyId, bankAccountId, postedDate, direction, absAmount, description, externalRef
            );

            lines.add(new ParsedMovementLine(
                    lineIndex,
                    postedDate,
                    direction,
                    absAmount,
                    description,
                    externalRef,
                    movementHash
            ));
        }
        return lines;
    }

    private static List<ParsedMovementLine> parseCsv(
            UUID tenantId,
            UUID companyId,
            UUID bankAccountId,
            String content
    ) {
        String[] rawRows = content.replace("\r\n", "\n").replace("\r", "\n").split("\n");
        List<String> nonEmptyRows = new ArrayList<>();
        for (String r : rawRows) {
            if (!r.isBlank()) {
                nonEmptyRows.add(r.trim());
            }
        }
        if (nonEmptyRows.size() < 2) {
            throw new DomainValidationException(List.of(
                    new ValidationErrorItem("content", "EMPTY_STATEMENT", "O arquivo CSV deve conter cabeçalho e ao menos 1 linha.")
            ));
        }

        String headerLine = nonEmptyRows.getFirst();
        char delimiter = headerLine.contains(";") ? ';' : ',';
        String[] rawHeaders = headerLine.split(Pattern.quote(String.valueOf(delimiter)), -1);
        Map<String, Integer> headerMap = new HashMap<>();
        for (int i = 0; i < rawHeaders.length; i++) {
            String key = rawHeaders[i].trim().toLowerCase(Locale.ROOT)
                    .replace("\"", "")
                    .replace("_", "");
            headerMap.put(key, i);
        }

        Integer dateIdx = findColumn(headerMap, "date", "posteddate", "data");
        Integer amountIdx = findColumn(headerMap, "amount", "valor", "value");
        Integer descIdx = findColumn(headerMap, "description", "descricao", "historico", "memo");
        Integer refIdx = findColumn(headerMap, "externalreference", "reference", "documento", "fitid", "id");

        if (dateIdx == null || amountIdx == null || descIdx == null) {
            throw new DomainValidationException(List.of(
                    new ValidationErrorItem(
                            "content",
                            "INVALID_CSV_HEADERS",
                            "O CSV deve possuir colunas de data (date/data), valor (amount/valor) e descrição (description/descricao)."
                    )
            ));
        }

        List<ParsedMovementLine> result = new ArrayList<>();
        for (int rowNum = 1; rowNum < nonEmptyRows.size(); rowNum++) {
            String[] cols = nonEmptyRows.get(rowNum).split(Pattern.quote(String.valueOf(delimiter)), -1);
            String rawDate = getCell(cols, dateIdx);
            String rawAmount = getCell(cols, amountIdx);
            String rawDesc = getCell(cols, descIdx);
            String rawRef = refIdx != null ? getCell(cols, refIdx) : null;

            LocalDate postedDate = parseFlexibleDate(rawDate, rowNum);
            Money signedAmount = Money.parseNonZeroSigned(rawAmount, "lines[" + rowNum + "].amount");
            String direction = signedAmount.isNegative() ? "DEBIT" : "CREDIT";
            Money absAmount = signedAmount.isNegative() ? Money.ZERO.subtract(signedAmount) : signedAmount;

            if (rawDesc == null || rawDesc.isBlank()) {
                throw new DomainValidationException(List.of(
                        new ValidationErrorItem(
                                "lines[" + rowNum + "].description",
                                "MISSING_DESCRIPTION",
                                "Descrição ausente na linha CSV #" + rowNum
                        )
                ));
            }

            String cleanDesc = rawDesc.trim();
            String cleanRef = (rawRef != null && !rawRef.isBlank()) ? rawRef.trim() : null;
            String movementHash = BankMovementHashCalculator.computeMovementHash(
                    tenantId, companyId, bankAccountId, postedDate, direction, absAmount, cleanDesc, cleanRef
            );

            result.add(new ParsedMovementLine(
                    rowNum,
                    postedDate,
                    direction,
                    absAmount,
                    cleanDesc,
                    cleanRef,
                    movementHash
            ));
        }
        return result;
    }

    private static String extractOfxTag(String block, String tag) {
        Pattern p = Pattern.compile("<" + tag + ">\\s*([^<\\r\\n]+)", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(block);
        return m.find() ? m.group(1).trim() : null;
    }

    private static LocalDate parseOfxDate(String rawDate, int lineIndex) {
        if (rawDate == null || rawDate.length() < 8) {
            throw new DomainValidationException(List.of(
                    new ValidationErrorItem(
                            "lines[" + lineIndex + "].postedDate",
                            "INVALID_DATE",
                            "Data OFX inválida na transação #" + lineIndex
                    )
            ));
        }
        String digits = rawDate.substring(0, 8);
        try {
            return LocalDate.parse(digits, BASIC_ISO_DATE);
        } catch (DateTimeParseException e) {
            throw new DomainValidationException(List.of(
                    new ValidationErrorItem(
                            "lines[" + lineIndex + "].postedDate",
                            "INVALID_DATE",
                            "Data OFX inválida na transação #" + lineIndex
                    )
            ));
        }
    }

    private static LocalDate parseFlexibleDate(String rawDate, int lineIndex) {
        if (rawDate == null || rawDate.isBlank()) {
            throw new DomainValidationException(List.of(
                    new ValidationErrorItem("lines[" + lineIndex + "].postedDate", "INVALID_DATE", "Data ausente na linha #" + lineIndex)
            ));
        }
        String clean = rawDate.trim().replace("\"", "");
        try {
            if (clean.contains("/")) {
                return LocalDate.parse(clean, BR_DATE);
            }
            return LocalDate.parse(clean);
        } catch (DateTimeParseException e) {
            throw new DomainValidationException(List.of(
                    new ValidationErrorItem("lines[" + lineIndex + "].postedDate", "INVALID_DATE", "Formato de data inválido na linha #" + lineIndex)
            ));
        }
    }

    private static String buildOfxDescription(String name, String memo) {
        String n = name == null ? "" : name.trim();
        String m = memo == null ? "" : memo.trim();
        if (!n.isEmpty() && !m.isEmpty() && !m.equalsIgnoreCase(n)) {
            return n + " - " + m;
        }
        return !m.isEmpty() ? m : n;
    }

    private static Integer findColumn(Map<String, Integer> map, String... candidates) {
        for (String c : candidates) {
            if (map.containsKey(c)) {
                return map.get(c);
            }
        }
        return null;
    }

    private static String getCell(String[] cols, int index) {
        if (index < 0 || index >= cols.length) {
            return null;
        }
        return cols[index].trim().replace("\"", "");
    }

    public record ParsedStatement(
            String format,
            String sourceChecksum,
            List<ParsedMovementLine> movements
    ) {
    }

    public record ParsedMovementLine(
            int lineNumber,
            LocalDate postedDate,
            String direction,
            Money amount,
            String description,
            String externalReference,
            String movementHash
    ) {
    }
}
