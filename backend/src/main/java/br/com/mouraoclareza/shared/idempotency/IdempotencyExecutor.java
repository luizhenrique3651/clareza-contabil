package br.com.mouraoclareza.shared.idempotency;

import br.com.mouraoclareza.shared.exception.DomainValidationException;
import br.com.mouraoclareza.shared.exception.IdempotencyConflictException;
import br.com.mouraoclareza.shared.exception.ValidationErrorItem;
import br.com.mouraoclareza.shared.security.AuthenticatedTenantContext;
import br.com.mouraoclareza.shared.security.TenantContextHolder;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Executor de idempotência transacional com pg_advisory_xact_lock(hashtextextended(?, 0)).
 * Alinhado 100% com o schema de idempotency_records em V3__financial_api_foundation.sql.
 */
@Component
public class IdempotencyExecutor {

    public static final Pattern IDEMPOTENCY_KEY_PATTERN = Pattern.compile("^[A-Za-z0-9._:-]{8,128}$");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper canonicalMapper;

    public IdempotencyExecutor(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
        this.canonicalMapper = JsonMapper.builder()
                .findAndAddModules()
                .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                .build();
    }

    public static String requireValidIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || !IDEMPOTENCY_KEY_PATTERN.matcher(idempotencyKey.trim()).matches()) {
            throw new DomainValidationException(List.of(
                    new ValidationErrorItem(
                            "Idempotency-Key",
                            "INVALID_IDEMPOTENCY_KEY",
                            "O cabeçalho Idempotency-Key deve obedecer ao padrão ^[A-Za-z0-9._:-]{8,128}$."
                    )
            ));
        }
        return idempotencyKey.trim();
    }

    public <T> IdempotentResult<T> execute(
            String operation,
            String rawIdempotencyKey,
            Object requestPayload,
            Class<T> responseType,
            Supplier<ResponseEnvelope<T>> action
    ) {
        String idempotencyKey = requireValidIdempotencyKey(rawIdempotencyKey);
        AuthenticatedTenantContext context = TenantContextHolder.getRequiredContext();
        String requestHash = computeSha256Json(requestPayload);
        String lockScope = context.tenantId() + ":" + context.userId() + ":" + operation + ":" + idempotencyKey;

        jdbcTemplate.queryForObject(
                "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))::text",
                String.class,
                lockScope
        );

        // Remove registros expirados para a mesma tupla (tenant_id, user_id, operation, key)
        jdbcTemplate.update(
                """
                DELETE FROM idempotency_records
                 WHERE tenant_id = ?
                   AND user_id = ?
                   AND operation = ?
                   AND key = ?
                   AND expires_at <= CURRENT_TIMESTAMP
                """,
                context.tenantId(),
                context.userId(),
                operation,
                idempotencyKey
        );

        List<StoredIdempotencyRecord> existing = jdbcTemplate.query(
                """
                SELECT request_hash, response_status, response_body::text
                  FROM idempotency_records
                 WHERE tenant_id = ?
                   AND user_id = ?
                   AND operation = ?
                   AND key = ?
                   AND expires_at > CURRENT_TIMESTAMP
                """,
                (rs, rowNum) -> new StoredIdempotencyRecord(
                        rs.getString("request_hash"),
                        rs.getInt("response_status"),
                        rs.getString("response_body")
                ),
                context.tenantId(),
                context.userId(),
                operation,
                idempotencyKey
        );

        if (!existing.isEmpty()) {
            StoredIdempotencyRecord record = existing.getFirst();
            if (!record.requestHash().equals(requestHash)) {
                throw new IdempotencyConflictException(
                        "A chave de idempotência já foi utilizada com um payload diferente para esta operação."
                );
            }
            try {
                T replayedBody = canonicalMapper.readValue(record.responseBodyJson(), responseType);
                return new IdempotentResult<>(record.responseStatus(), replayedBody, true);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Falha ao desserializar resposta idempotente armazenada.", e);
            }
        }

        ResponseEnvelope<T> executed = action.get();
        String responseJson = toJson(executed.body());
        String resourceType = (executed.resourceType() != null && !executed.resourceType().isBlank())
                ? executed.resourceType()
                : operation;

        jdbcTemplate.update(
                """
                INSERT INTO idempotency_records (
                    id, tenant_id, user_id, operation, key,
                    request_hash, resource_type, resource_id,
                    response_status, response_body, expires_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, CURRENT_TIMESTAMP + INTERVAL '24 hours')
                """,
                UUID.randomUUID(),
                context.tenantId(),
                context.userId(),
                operation,
                idempotencyKey,
                requestHash,
                resourceType,
                executed.resourceId(),
                executed.status(),
                responseJson
        );

        return new IdempotentResult<>(executed.status(), executed.body(), false);
    }

    public String computeSha256Json(Object payload) {
        String json = toJson(payload);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(json.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Algoritmo SHA-256 indisponível.", e);
        }
    }

    private String toJson(Object value) {
        try {
            return canonicalMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Falha ao serializar objeto para JSON canônico.", e);
        }
    }

    public record ResponseEnvelope<T>(
            int status,
            String resourceType,
            UUID resourceId,
            T body
    ) {
        public ResponseEnvelope(int status, T body) {
            this(status, "domain_resource", null, body);
        }
    }

    public record IdempotentResult<T>(int status, T body, boolean replayed) {
    }

    private record StoredIdempotencyRecord(String requestHash, int responseStatus, String responseBodyJson) {
    }
}
