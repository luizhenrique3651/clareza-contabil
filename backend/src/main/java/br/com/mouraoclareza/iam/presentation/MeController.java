package br.com.mouraoclareza.iam.presentation;

import br.com.mouraoclareza.iam.application.IdentityAccessDirectoryService;
import br.com.mouraoclareza.iam.application.IdentityAccessDirectoryService.ContextSelection;
import br.com.mouraoclareza.iam.domain.AccessRow;
import br.com.mouraoclareza.iam.domain.IdentitySummary;
import br.com.mouraoclareza.iam.security.ClarezaAuthenticationFilter;
import br.com.mouraoclareza.shared.domain.DomainIds;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Endpoint de descoberta de identidade e escopo operacional autorizado (GET /api/v1/me).
 * Reutiliza o IdentitySummary já resolvido pelo ClarezaAuthenticationFilter quando presente,
 * eliminando o duplo round-trip ao PostgreSQL (FIX-03 / FIX-06).
 */
@RestController
@RequestMapping("/api/v1/me")
public class MeController {

    private final IdentityAccessDirectoryService directoryService;

    public MeController(IdentityAccessDirectoryService directoryService) {
        this.directoryService = directoryService;
    }

    @GetMapping
    public ResponseEntity<IdentitySummary> getMe(
            Authentication authentication,
            @RequestHeader(value = "X-Clareza-Tenant-Id", required = false) String rawTenantId,
            @RequestHeader(value = "X-Clareza-Company-Id", required = false) String rawCompanyId,
            @RequestHeader(value = "X-Clareza-Branch-Id", required = false) String rawBranchId,
            HttpServletRequest request
    ) {
        Object preResolved = request.getAttribute(ClarezaAuthenticationFilter.IDENTITY_SUMMARY_REQUEST_ATTR);
        if (preResolved instanceof IdentitySummary summary) {
            return ResponseEntity.ok(summary);
        }

        String email = authentication != null ? authentication.getName() : null;
        UUID tenantId = parseOptionalHeaderUuid(rawTenantId, "X-Clareza-Tenant-Id");
        UUID companyId = parseOptionalHeaderUuid(rawCompanyId, "X-Clareza-Company-Id");
        UUID branchId = parseOptionalHeaderUuid(rawBranchId, "X-Clareza-Branch-Id");

        List<AccessRow> rows = directoryService.fetchAccessRowsByEmail(email);
        IdentitySummary summary = directoryService.summarizeIdentityAccess(
                rows,
                new ContextSelection(tenantId, companyId, branchId)
        );
        return ResponseEntity.ok(summary);
    }

    private UUID parseOptionalHeaderUuid(String raw, String headerName) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return DomainIds.parseUuid(raw, headerName, "INVALID_CONTEXT_SELECTION");
    }
}
