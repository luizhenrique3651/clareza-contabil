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
 * (Participantes, Categorias Financeiras e Centros de Custo).
 * Paridade integral com legacy/server/financial-catalogs.mjs.
 */
public final class FinancialCatalogDomainPolicy {

    public static final Set<String> VALID_PARTY_ROLES = Set.of("CUSTOMER", "SUPPLIER", "BOTH", "OTHER");
    public static final Set<String> VALID_CATEGORY_KINDS = Set.of("REVENUE", "EXPENSE");
    private static final Pattern CODE_PATTERN = Pattern.compile("^[A-Z0-9._-]{2,32}$");

    private FinancialCatalogDomainPolicy() {
    }

    public static ValidatedParty validatePartyInput(
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

        if (!errors.isEmpty()) {
            throw new DomainValidationException(errors);
        }

        return new ValidatedParty(
                cleanLegalName,
                cleanTradeName != null ? cleanTradeName : cleanLegalName,
                documentType,
                digits,
                normalizedRole,
                cleanEmail
        );
    }

    public static ValidatedCategory validateCategoryInput(String rawCode, String rawName, String rawKind) {
        List<ValidationErrorItem> errors = new ArrayList<>();
        String code = normalizeCatalogCode(rawCode, "code", errors);
        String name = trimToNull(rawName);
        if (name == null || name.length() < 2 || name.length() > 140) {
            errors.add(new ValidationErrorItem(
                    "name",
                    "INVALID_CATEGORY_NAME",
                    "O nome da categoria deve ter entre 2 e 140 caracteres."
            ));
        }
        String kind = rawKind == null ? "" : rawKind.trim().toUpperCase(Locale.ROOT);
        if (!VALID_CATEGORY_KINDS.contains(kind)) {
            errors.add(new ValidationErrorItem(
                    "kind",
                    "INVALID_CATEGORY_KIND",
                    "A natureza da categoria deve ser REVENUE ou EXPENSE."
            ));
        }

        if (!errors.isEmpty()) {
            throw new DomainValidationException(errors);
        }
        return new ValidatedCategory(code, name, kind);
    }

    public static ValidatedCostCenter validateCostCenterInput(String rawCode, String rawName) {
        List<ValidationErrorItem> errors = new ArrayList<>();
        String code = normalizeCatalogCode(rawCode, "code", errors);
        String name = trimToNull(rawName);
        if (name == null || name.length() < 2 || name.length() > 140) {
            errors.add(new ValidationErrorItem(
                    "name",
                    "INVALID_COST_CENTER_NAME",
                    "O nome do centro de custo deve ter entre 2 e 140 caracteres."
            ));
        }
        if (!errors.isEmpty()) {
            throw new DomainValidationException(errors);
        }
        return new ValidatedCostCenter(code, name);
    }

    private static String normalizeCatalogCode(String rawCode, String field, List<ValidationErrorItem> errors) {
        String clean = rawCode == null ? "" : rawCode.trim().toUpperCase(Locale.ROOT);
        if (!CODE_PATTERN.matcher(clean).matches()) {
            errors.add(new ValidationErrorItem(
                    field,
                    "INVALID_CODE",
                    "O código deve conter de 2 a 32 caracteres alfanuméricos (A-Z, 0-9, ., _, -)."
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
            String legalName,
            String tradeName,
            String documentType,
            String documentNumber,
            String partyRole,
            String email
    ) {
    }

    public record ValidatedCategory(String code, String name, String kind) {
    }

    public record ValidatedCostCenter(String code, String name) {
    }
}
