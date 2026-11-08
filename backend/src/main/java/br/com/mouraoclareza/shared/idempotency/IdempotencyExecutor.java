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

/**
 * Executor de idempotência transacional com pg_advisory_xact_lock(hashtextextended(?, 0)).
 * Preserva o comportamento exato de runIdempotentOperation em server/tenant-context.mjs.
 */
@Component
public class IdempotencyExecutor {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper canonicalMapper;

    public IdempotencyExecutor(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
        this.canonicalMapper = objectMapper.copy()
                .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    public static String requireValidIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.trim().length() < 8 || idempotencyKey.trim().length() > 128) {
            throw new DomainValidationException(List.of(
                    new ValidationErrorItem(
                            "Idempotency-Key",
                            "INVALID_IDEMPOTENCY_KEY",
                            "O cabeçalho Idempotency-Key deve conter entre 8 e 128 caracteres."
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

        List<StoredIdempotencyRecord> existing = jdbcTemplate.query(
                """
                SELECT request_hash, response_status, response_body::text
                  FROM idempotency_records
                 WHERE tenant_id = ?
                   AND actor_user_id = ?
                   AND operation = ?
                   AND idempotency_key = ?
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

        jdbcTemplate.update(
                """
                INSERT INTO idempotency_records (
                    id, tenant_id, company_id, actor_user_id, operation,
                    idempotency_key, request_hash, response_status, response_body
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                """,
                UUID.randomUUID(),
                context.tenantId(),
                context.activeCompanyId(),
                context.userId(),
                operation,
                idempotencyKey,
                requestHash,
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

    public record ResponseEnvelope<T>(int status, T body) {
    }

    public record IdempotentResult<T>(int status, T body, boolean replayed) {
    }

    private record StoredIdempotencyRecord(String requestHash, int responseStatus, String responseBodyJson) {
    }
}
