package br.com.mouraoclareza.shared.audit;

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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Serviço de trilha de auditoria imutável (append-only) com integrity_hash SHA-256.
 * Alinhado 100% com o schema da tabela audit_logs em V1__init.sql (linhas 527-562).
 */
@Component
public class AuditTrailService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper canonicalMapper;

    public AuditTrailService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
        this.canonicalMapper = JsonMapper.builder()
                .findAndAddModules()
                .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                .build();
    }

    public UUID recordAuditLog(
            String action,
            String entityType,
            UUID entityId,
            UUID branchId,
            Object beforeData,
            Object afterData
    ) {
        AuthenticatedTenantContext context = TenantContextHolder.getRequiredContext();
        UUID auditId = UUID.randomUUID();
        UUID effectiveBranchId = branchId != null ? branchId : context.activeBranchId();
        String beforeJson = beforeData == null ? null : toJson(beforeData);
        String afterJson = afterData == null ? null : toJson(afterData);

        Map<String, Object> metadataMap = new LinkedHashMap<>();
        metadataMap.put("requestId", context.requestId());
        if (context.roleCode() != null) {
            metadataMap.put("roleCode", context.roleCode());
        }
        String metadataJson = toJson(metadataMap);

        String integrityHash = computeIntegrityHash(
                context.tenantId(),
                context.activeCompanyId(),
                effectiveBranchId,
                context.userId(),
                action,
                entityType,
                entityId != null ? entityId.toString() : null,
                beforeJson,
                afterJson,
                context.requestId()
        );

        jdbcTemplate.update(
                """
                INSERT INTO audit_logs (
                    id, tenant_id, company_id, branch_id, actor_user_id, session_id,
                    action, entity_type, entity_id, before_data, after_data,
                    metadata, integrity_hash
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?)
                """,
                auditId,
                context.tenantId(),
                context.activeCompanyId(),
                effectiveBranchId,
                context.userId(),
                null,
                action,
                entityType,
                entityId,
                beforeJson,
                afterJson,
                metadataJson,
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
