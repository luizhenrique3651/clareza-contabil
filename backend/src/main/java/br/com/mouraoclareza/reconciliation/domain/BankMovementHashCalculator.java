package br.com.mouraoclareza.reconciliation.domain;

import br.com.mouraoclareza.shared.domain.Money;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * Calculadora determinística de hashes SHA-256 para importação bancária (OFX/CSV)
 * e deduplicação estrita de movimentos (compatível com server/bank-imports.mjs).
 */
public final class BankMovementHashCalculator {

    private BankMovementHashCalculator() {
    }

    public static String computeSourceChecksum(String rawFileContent) {
        Objects.requireNonNull(rawFileContent, "rawFileContent must not be null");
        String normalized = rawFileContent.replace("\r\n", "\n").trim();
        return sha256Hex(normalized);
    }

    public static String computeMovementHash(
            UUID tenantId,
            UUID companyId,
            UUID bankAccountId,
            LocalDate postedDate,
            String direction,
            Money amount,
            String description,
            String externalReference
    ) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(companyId, "companyId must not be null");
        Objects.requireNonNull(bankAccountId, "bankAccountId must not be null");
        Objects.requireNonNull(postedDate, "postedDate must not be null");
        Objects.requireNonNull(direction, "direction must not be null");
        Objects.requireNonNull(amount, "amount must not be null");

        String canonicalDescription = normalizeText(description);
        String canonicalExternalRef = normalizeText(externalReference);
        String payload = String.join("|",
                tenantId.toString(),
                companyId.toString(),
                bankAccountId.toString(),
                postedDate.toString(),
                direction.trim().toUpperCase(Locale.ROOT),
                amount.toCanonicalString(),
                canonicalDescription,
                canonicalExternalRef
        );
        return sha256Hex(payload);
    }

    private static String normalizeText(String input) {
        if (input == null) {
            return "";
        }
        return input.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponível", e);
        }
    }
}
