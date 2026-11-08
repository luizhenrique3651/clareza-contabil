package br.com.mouraoclareza.shared.domain;

import br.com.mouraoclareza.shared.exception.DomainValidationException;
import br.com.mouraoclareza.shared.exception.ValidationErrorItem;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Immutable Value Object representing exact decimal monetary values with scale 4 (NUMERIC(19,4)).
 * Binary floating-point types (float, double) are strictly forbidden.
 */
public final class Money implements Comparable<Money> {

    public static final int SCALE = 4;
    public static final BigDecimal MAX_VALUE = new BigDecimal("999999999999999.9999");
    public static final BigDecimal MIN_VALUE = new BigDecimal("-999999999999999.9999");
    public static final Money ZERO = new Money(BigDecimal.ZERO.setScale(SCALE, RoundingMode.UNNECESSARY));

    private static final Pattern CANONICAL_DECIMAL_PATTERN =
            Pattern.compile("^([+-]?)(\\d{1,15})(?:[.,](\\d{1,4}))?$");

    private final BigDecimal amount;

    private Money(BigDecimal amount) {
        this.amount = Objects.requireNonNull(amount, "amount must not be null")
                .setScale(SCALE, RoundingMode.UNNECESSARY);
        if (this.amount.compareTo(MAX_VALUE) > 0 || this.amount.compareTo(MIN_VALUE) < 0) {
            throw new DomainValidationException(
                    "O valor excede o limite monetário suportado.",
                    List.of(new ValidationErrorItem(
                            "amount",
                            "MONEY_OVERFLOW",
                            "Use no máximo 15 dígitos inteiros e 4 casas decimais."
                    ))
            );
        }
    }

    @JsonCreator
    public static Money of(String rawValue) {
        return parse(rawValue, "amount", true);
    }

    public static Money parse(String rawValue, String fieldName) {
        return parse(rawValue, fieldName, false);
    }

    public static Money parse(String rawValue, String fieldName, boolean allowCommaSeparator) {
        if (rawValue == null || rawValue.isBlank()) {
            throw invalidDecimal(fieldName);
        }
        String trimmed = rawValue.trim();
        if (!allowCommaSeparator && trimmed.contains(",")) {
            throw invalidDecimal(fieldName);
        }
        var matcher = CANONICAL_DECIMAL_PATTERN.matcher(trimmed);
        if (!matcher.matches()) {
            throw invalidDecimal(fieldName);
        }

        String sign = "-".equals(matcher.group(1)) ? "-" : "";
        String integerPart = matcher.group(2).replaceFirst("^0+(?=\\d)", "");
        String fractionPart = matcher.group(3) == null ? "0000" : padRightFourDigits(matcher.group(3));
        BigDecimal normalized = new BigDecimal(sign + integerPart + "." + fractionPart);
        if (normalized.compareTo(BigDecimal.ZERO) == 0) {
            return ZERO;
        }
        return new Money(normalized);
    }

    public static Money parsePositive(String rawValue, String fieldName) {
        return parse(rawValue, fieldName, false).requirePositive(fieldName);
    }

    public static Money parseNonZeroSigned(String rawValue, String fieldName) {
        Money parsed = parse(rawValue, fieldName, true);
        if (parsed.isZero()) {
            throw new DomainValidationException(
                    "O arquivo bancário é inválido.",
                    List.of(new ValidationErrorItem(
                            fieldName,
                            "ZERO_AMOUNT",
                            "A movimentação bancária não pode ter valor zero."
                    ))
            );
        }
        return parsed;
    }

    public static Money ofBigDecimal(BigDecimal value) {
        if (value == null) {
            throw invalidDecimal("amount");
        }
        try {
            return new Money(value.setScale(SCALE, RoundingMode.UNNECESSARY));
        } catch (ArithmeticException ex) {
            throw invalidDecimal("amount");
        }
    }

    public Money requirePositive(String fieldName) {
        if (!isPositive()) {
            throw new DomainValidationException(
                    "O valor deve ser maior que zero.",
                    List.of(new ValidationErrorItem(
                            fieldName,
                            "AMOUNT_NOT_POSITIVE",
                            "Informe um valor maior que zero."
                    ))
            );
        }
        return this;
    }

    public Money requireNonNegative(String fieldName) {
        if (isNegative()) {
            throw new DomainValidationException(
                    "O valor não pode ser negativo.",
                    List.of(new ValidationErrorItem(
                            fieldName,
                            "NEGATIVE_AMOUNT_NOT_ALLOWED",
                            "Informe um valor maior ou igual a zero."
                    ))
            );
        }
        return this;
    }

    public Money add(Money other) {
        Objects.requireNonNull(other, "other must not be null");
        return new Money(this.amount.add(other.amount));
    }

    public Money subtract(Money other) {
        Objects.requireNonNull(other, "other must not be null");
        return new Money(this.amount.subtract(other.amount));
    }

    public boolean isPositive() {
        return this.amount.compareTo(BigDecimal.ZERO) > 0;
    }

    public boolean isNegative() {
        return this.amount.compareTo(BigDecimal.ZERO) < 0;
    }

    public boolean isZero() {
        return this.amount.compareTo(BigDecimal.ZERO) == 0;
    }

    public BigDecimal toBigDecimal() {
        return amount;
    }

    @JsonValue
    public String toCanonicalString() {
        return amount.toPlainString();
    }

    private static String padRightFourDigits(String fraction) {
        StringBuilder sb = new StringBuilder(fraction);
        while (sb.length() < SCALE) {
            sb.append('0');
        }
        return sb.toString();
    }

    private static DomainValidationException invalidDecimal(String fieldName) {
        return new DomainValidationException(
                "Formato monetário inválido.",
                List.of(new ValidationErrorItem(
                        fieldName,
                        "INVALID_DECIMAL",
                        "Use um decimal com até 15 inteiros e 4 casas, sem separador de milhar."
                ))
        );
    }

    @Override
    public int compareTo(Money other) {
        return this.amount.compareTo(other.amount);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Money money)) return false;
        return this.amount.compareTo(money.amount) == 0;
    }

    @Override
    public int hashCode() {
        return amount.stripTrailingZeros().hashCode();
    }

    @Override
    public String toString() {
        return toCanonicalString();
    }
}
