package br.com.mouraoclareza.shared.audit;

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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Serviço de trilha de auditoria imutável (append-only) com integrity_hash SHA-256.
 * A tabela audit_logs possui triggers PostgreSQL que bloqueiam UPDATE e DELETE.
 */
@Component
public class AuditTrailService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper canonicalMapper;

    public AuditTrailService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
        this.canonicalMapper = objectMapper.copy()
                .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    public UUID recordAuditLog(
            String action,
            String entityType,
            String entityId,
            UUID branchId,
            Object beforeState,
            Object afterState
    ) {
        AuthenticatedTenantContext context = TenantContextHolder.getRequiredContext();
        UUID auditId = UUID.randomUUID();
        String beforeJson = beforeState == null ? null : toJson(beforeState);
        String afterJson = afterState == null ? null : toJson(afterState);
        String integrityHash = computeIntegrityHash(
                context.tenantId(),
                context.activeCompanyId(),
                branchId != null ? branchId : context.activeBranchId(),
                context.userId(),
                action,
                entityType,
                entityId,
                beforeJson,
                afterJson,
                context.requestId()
        );

        jdbcTemplate.update(
                """
                INSERT INTO audit_logs (
                    id, tenant_id, company_id, branch_id, actor_user_id, actor_role,
                    action, entity_type, entity_id, before_state, after_state,
                    request_id, integrity_hash
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?)
                """,
                auditId,
                context.tenantId(),
                context.activeCompanyId(),
                branchId != null ? branchId : context.activeBranchId(),
                context.userId(),
                context.roleCode(),
                action,
                entityType,
                entityId,
                beforeJson,
                afterJson,
                context.requestId(),
                integrityHash
        );

        return auditId;
    }

    public String computeIntegrityHash(
            UUID tenantId,
            UUID companyId,
            UUID branchId,
            UUID actorUserId,
            String action,
            String entityType,
            String entityId,
            String beforeJson,
            String afterJson,
            String requestId
    ) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("tenantId", tenantId != null ? tenantId.toString() : null);
        envelope.put("companyId", companyId != null ? companyId.toString() : null);
        envelope.put("branchId", branchId != null ? branchId.toString() : null);
        envelope.put("actorUserId", actorUserId != null ? actorUserId.toString() : null);
        envelope.put("action", action);
        envelope.put("entityType", entityType);
        envelope.put("entityId", entityId);
        envelope.put("beforeJson", beforeJson);
        envelope.put("afterJson", afterJson);
        envelope.put("requestId", requestId);

        String serialized = toJson(envelope);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(serialized.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponível", e);
        }
    }

    private String toJson(Object value) {
        try {
            return canonicalMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Falha ao serializar estado de auditoria.", e);
        }
    }
}
