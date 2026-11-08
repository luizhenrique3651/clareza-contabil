package br.com.mouraoclareza.shared.pagination;

import br.com.mouraoclareza.shared.exception.DomainValidationException;
import br.com.mouraoclareza.shared.exception.ValidationErrorItem;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Codec determinístico de paginação por cursor vinculado ao escopo (resource, tenantId, companyId, branchId, filtros, ordenação).
 * Rejeita cursores adulterados ou reaproveitados entre empresas/unidades/filtros diferentes com 422 INVALID_CURSOR.
 */
public final class BoundCursorCodec {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .build();

    private BoundCursorCodec() {
    }

    public static String encodeCursor(CursorScope scope, Map<String, Object> position) {
        Objects.requireNonNull(scope, "scope must not be null");
        if (position == null || position.isEmpty()) {
            throw new IllegalArgumentException("Cursor position must not be empty");
        }
        String scopeHash = computeScopeHash(scope);
        CursorEnvelope envelope = new CursorEnvelope(1, scope.resource(), scopeHash, position);
        try {
            byte[] bytes = MAPPER.writeValueAsBytes(envelope);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Erro ao codificar cursor de paginação.", e);
        }
    }

    public static Map<String, Object> decodeCursor(String rawCursor, CursorScope expectedScope) {
        Objects.requireNonNull(expectedScope, "expectedScope must not be null");
        if (rawCursor == null || rawCursor.isBlank()) {
            return null;
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(rawCursor.trim());
            CursorEnvelope envelope = MAPPER.readValue(decoded, CursorEnvelope.class);
            String expectedHash = computeScopeHash(expectedScope);
            if (envelope == null
                    || envelope.v() != 1
                    || !expectedScope.resource().equals(envelope.resource())
                    || !expectedHash.equals(envelope.scopeHash())
                    || envelope.position() == null
                    || envelope.position().isEmpty()) {
                throw invalidCursorException();
            }
            return envelope.position();
        } catch ( DomainValidationException e) {
            throw e;
        } catch (Exception e) {
            throw invalidCursorException();
        }
    }

    public static String computeScopeHash(CursorScope scope) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("resource", scope.resource());
        canonical.put("tenantId", scope.tenantId().toString());
        canonical.put("companyId", scope.companyId().toString());
        canonical.put("branchId", scope.branchId() != null ? scope.branchId().toString() : null);
        canonical.put("order", scope.order());
        canonical.put("filters", scope.filters() == null ? Map.of() : scope.filters());
        try {
            String json = MAPPER.writeValueAsString(canonical);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(json.getBytes(StandardCharsets.UTF_8)));
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Falha ao calcular hash de escopo do cursor.", e);
        }
    }

    private static DomainValidationException invalidCursorException() {
        return new DomainValidationException(
                "Cursor de paginação inválido ou incompatível com o contexto/filtros atuais.",
                List.of(new ValidationErrorItem(
                        "cursor",
                        "INVALID_CURSOR",
                        "O cursor informado é inválido ou pertence a outro contexto de consulta."
                ))
        );
    }

    public record CursorScope(
            String resource,
            UUID tenantId,
            UUID companyId,
            UUID branchId,
            String order,
            Map<String, Object> filters
    ) {
        public CursorScope {
            Objects.requireNonNull(resource, "resource must not be null");
            Objects.requireNonNull(tenantId, "tenantId must not be null");
            Objects.requireNonNull(companyId, "companyId must not be null");
            Objects.requireNonNull(order, "order must not be null");
        }
    }

    public record CursorEnvelope(
            int v,
            String resource,
            String scopeHash,
            Map<String, Object> position
    ) {
    }
}
