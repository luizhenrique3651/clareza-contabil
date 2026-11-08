package br.com.mouraoclareza.financial.domain;

import br.com.mouraoclareza.shared.exception.DomainValidationException;
import br.com.mouraoclareza.shared.exception.ValidationErrorItem;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Política de domínio pura para validação e normalização de Catálogos Financeiros
 * (Participantes/Counterparties, Categorias Financeiras, Centros de Custo e Contas Financeiras).
 * Alinhada com V5__financial_operations.sql, V6__phase_two_hardening.sql e legacy/server/financial-catalogs.mjs (FIX-04).
 */
public final class FinancialCatalogDomainPolicy {

    public static final Set<String> VALID_PARTY_ROLES = Set.of("CUSTOMER", "SUPPLIER", "BOTH", "OTHER");
    public static final Set<String> VALID_CATEGORY_KINDS = Set.of("REVENUE", "EXPENSE", "PAYABLE", "RECEIVABLE", "BOTH");
    public static final Set<String> VALID_ACCOUNT_TYPES = Set.of("CASH", "CHECKING", "SAVINGS", "PAYMENT", "OTHER");
    private static final Pattern CODE_PATTERN = Pattern.compile("^[A-Z0-9._-]{2,40}$");

    private FinancialCatalogDomainPolicy() {
    }

    public static ValidatedParty validatePartyInput(
            String legalName,
            String tradeName,
            String rawDocumentNumber,
            String rawRole,
            String email
    ) {
        return validatePartyInput(null, legalName, tradeName, rawDocumentNumber, rawRole, email);
    }

    public static ValidatedParty validatePartyInput(
            String rawCode,
            String legalName,
            String tradeName,
            String rawDocumentNumber,
            String rawRole,
            String email
    ) {
        List<ValidationErrorItem> errors = new ArrayList<>();

        String cleanLegalName = trimToNull(legalName);
        if (cleanLegalName == null || cleanLegalName.length() < 2 || cleanLegalName.length() > 180) {
            errors.add(new ValidationErrorItem(
                    "legalName",
                    "INVALID_LEGAL_NAME",
                    "A razão social / nome completo deve ter entre 2 e 180 caracteres."
            ));
        }

        String cleanTradeName = trimToNull(tradeName);
        if (cleanTradeName != null && cleanTradeName.length() > 180) {
            errors.add(new ValidationErrorItem(
                    "tradeName",
                    "INVALID_TRADE_NAME",
                    "O nome fantasia deve ter no máximo 180 caracteres."
            ));
        }

        String digits = rawDocumentNumber == null ? "" : rawDocumentNumber.replaceAll("\\D", "");
        String documentType = null;
        if (digits.length() == 11) {
            documentType = "CPF";
        } else if (digits.length() == 14) {
            documentType = "CNPJ";
        } else {
            errors.add(new ValidationErrorItem(
                    "documentNumber",
                    "INVALID_DOCUMENT_NUMBER",
                    "O documento deve conter 11 dígitos (CPF) ou 14 dígitos (CNPJ)."
            ));
        }

        String normalizedRole = rawRole == null ? "" : rawRole.trim().toUpperCase(Locale.ROOT);
        if (!VALID_PARTY_ROLES.contains(normalizedRole)) {
            errors.add(new ValidationErrorItem(
                    "partyRole",
                    "INVALID_PARTY_ROLE",
                    "O papel do participante deve ser CUSTOMER, SUPPLIER, BOTH ou OTHER."
            ));
        }

        String cleanEmail = trimToNull(email);
        if (cleanEmail != null) {
            cleanEmail = cleanEmail.toLowerCase(Locale.ROOT);
            if (!cleanEmail.contains("@") || cleanEmail.length() > 180) {
                errors.add(new ValidationErrorItem(
                        "email",
                        "INVALID_EMAIL",
                        "Informe um endereço de e-mail válido."
                ));
            }
        }

        String code = trimToNull(rawCode) != null
                ? normalizeCatalogCode(rawCode, "code", errors)
                : ("PTY-" + digits);

        if (!errors.isEmpty()) {
            throw new DomainValidationException(errors);
        }

        String dbKind = switch (normalizedRole) {
            case "CUSTOMER" -> "CUSTOMER";
            case "SUPPLIER" -> "SUPPLIER";
            default -> "BOTH";
        };

        return new ValidatedParty(
                code,
                cleanLegalName,
                cleanTradeName != null ? cleanTradeName : cleanLegalName,
                documentType,
                digits,
                normalizedRole,
                dbKind,
                cleanEmail
        );
    }

    public static ValidatedCategory validateCategoryInput(String rawCode, String rawName, String rawKind) {
        List<ValidationErrorItem> errors = new ArrayList<>();
        String code = normalizeCatalogCode(rawCode, "code", errors);
        String name = trimToNull(rawName);
        if (name == null || name.length() < 2 || name.length() > 160) {
            errors.add(new ValidationErrorItem(
                    "name",
                    "INVALID_CATEGORY_NAME",
                    "O nome da categoria deve ter entre 2 e 160 caracteres."
            ));
        }
        String kindInput = rawKind == null ? "" : rawKind.trim().toUpperCase(Locale.ROOT);
        if (!VALID_CATEGORY_KINDS.contains(kindInput)) {
            errors.add(new ValidationErrorItem(
                    "kind",
                    "INVALID_CATEGORY_KIND",
                    "A natureza da categoria deve ser REVENUE, EXPENSE, PAYABLE, RECEIVABLE ou BOTH."
            ));
        }

        if (!errors.isEmpty()) {
            throw new DomainValidationException(errors);
        }

        String apiKind = switch (kindInput) {
            case "PAYABLE", "EXPENSE" -> "EXPENSE";
            case "RECEIVABLE", "REVENUE" -> "REVENUE";
            default -> "BOTH";
        };
        String dbDirection = switch (kindInput) {
            case "PAYABLE", "EXPENSE" -> "PAYABLE";
            case "RECEIVABLE", "REVENUE" -> "RECEIVABLE";
            default -> "BOTH";
        };

        return new ValidatedCategory(code, name, apiKind, dbDirection);
    }

    public static ValidatedCostCenter validateCostCenterInput(String rawCode, String rawName) {
        List<ValidationErrorItem> errors = new ArrayList<>();
        String code = normalizeCatalogCode(rawCode, "code", errors);
        String name = trimToNull(rawName);
        if (name == null || name.length() < 2 || name.length() > 160) {
            errors.add(new ValidationErrorItem(
                    "name",
                    "INVALID_COST_CENTER_NAME",
                    "O nome do centro de custo deve ter entre 2 e 160 caracteres."
            ));
        }
        if (!errors.isEmpty()) {
            throw new DomainValidationException(errors);
        }
        return new ValidatedCostCenter(code, name);
    }

    public static ValidatedFinancialAccount validateFinancialAccountInput(
            String rawCode,
            String rawName,
            String rawType,
            String rawCurrencyCode
    ) {
        List<ValidationErrorItem> errors = new ArrayList<>();
        String code = normalizeCatalogCode(rawCode, "code", errors);
        String name = trimToNull(rawName);
        if (name == null || name.length() < 2 || name.length() > 160) {
            errors.add(new ValidationErrorItem(
                    "name",
                    "INVALID_ACCOUNT_NAME",
                    "O nome da conta financeira deve ter entre 2 e 160 caracteres."
            ));
        }
        String type = rawType == null ? "CHECKING" : rawType.trim().toUpperCase(Locale.ROOT);
        if (!VALID_ACCOUNT_TYPES.contains(type)) {
            errors.add(new ValidationErrorItem(
                    "type",
                    "INVALID_ACCOUNT_TYPE",
                    "O tipo da conta financeira deve ser CASH, CHECKING, SAVINGS, PAYMENT ou OTHER."
            ));
        }
        String currencyCode = rawCurrencyCode == null || rawCurrencyCode.isBlank()
                ? "BRL"
                : rawCurrencyCode.trim().toUpperCase(Locale.ROOT);
        if (!currencyCode.matches("^[A-Z]{3}$")) {
            errors.add(new ValidationErrorItem(
                    "currencyCode",
                    "INVALID_CURRENCY_CODE",
                    "O código da moeda deve seguir o padrão ISO de 3 letras (ex.: BRL)."
            ));
        }
        if (!errors.isEmpty()) {
            throw new DomainValidationException(errors);
        }
        return new ValidatedFinancialAccount(code, name, type, currencyCode);
    }

    private static String normalizeCatalogCode(String rawCode, String field, List<ValidationErrorItem> errors) {
        String clean = rawCode == null ? "" : rawCode.trim().toUpperCase(Locale.ROOT);
        if (!CODE_PATTERN.matcher(clean).matches()) {
            errors.add(new ValidationErrorItem(
                    field,
                    "INVALID_CODE",
                    "O código deve conter de 2 a 40 caracteres alfanuméricos (A-Z, 0-9, ., _, -)."
            ));
        }
        return clean;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public record ValidatedParty(
            String code,
            String legalName,
            String tradeName,
            String documentType,
            String documentNumber,
            String partyRole,
            String dbKind,
            String email
    ) {
    }

    public record ValidatedCategory(
            String code,
            String name,
            String kind,
            String dbDirection
    ) {
    }

    public record ValidatedCostCenter(String code, String name) {
    }

    public record ValidatedFinancialAccount(
            String code,
            String name,
            String type,
            String currencyCode
    ) {
    }
}
