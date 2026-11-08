package br.com.mouraoclareza.iam.security;

import br.com.mouraoclareza.iam.application.IdentityAccessDirectoryService;
import br.com.mouraoclareza.iam.application.IdentityAccessDirectoryService.ContextSelection;
import br.com.mouraoclareza.iam.domain.AccessRow;
import br.com.mouraoclareza.iam.domain.IdentitySummary;
import br.com.mouraoclareza.shared.domain.DomainIds;
import br.com.mouraoclareza.shared.exception.ClarezaException;
import br.com.mouraoclareza.shared.security.AuthenticatedTenantContext;
import br.com.mouraoclareza.shared.security.TenantContextHolder;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Filtro de autenticação e resolução de contexto multi-tenant:
 * 1. Resolve a identidade autenticada via app.resolve_session_token(token_hash) (SECURITY DEFINER)
 *    ou cabeçalhos de identidade verificada quando habilitado em perfil de teste;
 * 2. Valida X-Clareza-Tenant-Id, X-Clareza-Company-Id e X-Clareza-Branch-Id contra os vínculos ativos;
 * 3. Armazena IdentitySummary no request attribute para eliminar duplo round-trip em GET /api/v1/me (FIX-06);
 * 4. Popula SecurityContextHolder e TenantContextHolder e garante limpeza no bloco finally.
 */
@Component
public class ClarezaAuthenticationFilter extends OncePerRequestFilter {

    public static final String SESSION_COOKIE_NAME = "clareza_session";
    public static final String TEST_EMAIL_HEADER = "X-Clareza-Verified-User-Email";
    public static final String OAI_EMAIL_HEADER = "oai-authenticated-user-email";
    public static final String IDENTITY_SUMMARY_REQUEST_ATTR = "clareza.identitySummary";

    private final IdentityAccessDirectoryService directoryService;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final boolean allowHeaderIdentityFallback;

    public ClarezaAuthenticationFilter(
            IdentityAccessDirectoryService directoryService,
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            @Value("${clareza.security.allow-test-email-header:false}") boolean allowTestEmailHeader,
            @Value("${clareza.security.allow-header-identity-fallback:false}") boolean allowHeaderFallback
    ) {
        this.directoryService = directoryService;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.allowHeaderIdentityFallback = allowTestEmailHeader || allowHeaderFallback;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/actuator/health") || path.startsWith("/actuator/info");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        try {
            String verifiedEmail = resolveVerifiedEmail(request);
            if (verifiedEmail != null) {
                UUID tenantId = parseOptionalUuid(request.getHeader("X-Clareza-Tenant-Id"), "X-Clareza-Tenant-Id");
                UUID companyId = parseOptionalUuid(request.getHeader("X-Clareza-Company-Id"), "X-Clareza-Company-Id");
                UUID branchId = parseOptionalUuid(request.getHeader("X-Clareza-Branch-Id"), "X-Clareza-Branch-Id");

                List<AccessRow> rows = directoryService.fetchAccessRowsByEmail(verifiedEmail);
                IdentitySummary summary = directoryService.summarizeIdentityAccess(
                        rows,
                        new ContextSelection(tenantId, companyId, branchId)
                );
                request.setAttribute(IDENTITY_SUMMARY_REQUEST_ATTR, summary);

                boolean isMeEndpoint = "/api/v1/me".equals(request.getRequestURI());
                String requestId = resolveRequestId(request);
                AuthenticatedTenantContext tenantContext = directoryService.toAuthenticatedContext(
                        summary,
                        requestId,
                        !isMeEndpoint
                );

                List<SimpleGrantedAuthority> authorities = tenantContext.permissions().stream()
                        .map(SimpleGrantedAuthority::new)
                        .toList();
                UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                        verifiedEmail,
                        null,
                        authorities
                );
                SecurityContextHolder.getContext().setAuthentication(auth);

                TenantContextHolder.runWithContext(tenantContext, () -> {
                    try {
                        filterChain.doFilter(request, response);
                    } catch (IOException | ServletException e) {
                        throw new RuntimeException(e);
                    }
                });
                return;
            }

            filterChain.doFilter(request, response);
        } catch (RuntimeException ex) {
            if (ex.getCause() instanceof IOException ioEx) {
                throw ioEx;
            }
            if (ex.getCause() instanceof ServletException servletEx) {
                throw servletEx;
            }
            if (ex instanceof ClarezaException clarezaEx) {
                writeProblemResponse(request, response, clarezaEx);
                return;
            }
            throw ex;
        } finally {
            TenantContextHolder.clear();
            SecurityContextHolder.clearContext();
        }
    }

    private String resolveVerifiedEmail(HttpServletRequest request) {
        String sessionToken = extractSessionToken(request);
        if (sessionToken != null && !sessionToken.isBlank()) {
            String tokenHash = sha256Hex(sessionToken.trim());
            List<String> emails = jdbcTemplate.query(
                    "SELECT email FROM app.resolve_session_token(?)",
                    (rs, rowNum) -> rs.getString("email"),
                    tokenHash
            );
            if (!emails.isEmpty()) {
                return emails.getFirst();
            }
        }

        if (allowHeaderIdentityFallback) {
            String testEmail = request.getHeader(TEST_EMAIL_HEADER);
            if (testEmail == null || testEmail.isBlank()) {
                testEmail = request.getHeader(OAI_EMAIL_HEADER);
            }
            if (testEmail != null && !testEmail.isBlank()) {
                return IdentityAccessDirectoryService.normalizeEmail(testEmail);
            }
        }

        return null;
    }

    private String extractSessionToken(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return authHeader.substring(7).trim();
        }
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (SESSION_COOKIE_NAME.equals(cookie.getName()) && cookie.getValue() != null) {
                    return cookie.getValue();
                }
            }
        }
        return null;
    }

    private UUID parseOptionalUuid(String raw, String headerName) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return DomainIds.parseUuid(raw, headerName, "INVALID_CONTEXT_SELECTION");
    }

    private String resolveRequestId(HttpServletRequest request) {
        String header = request.getHeader("X-Request-Id");
        return (header != null && !header.isBlank()) ? header.trim() : UUID.randomUUID().toString();
    }

    private void writeProblemResponse(
            HttpServletRequest request,
            HttpServletResponse response,
            ClarezaException ex
    ) throws IOException {
        String slug = ex.getCode().toLowerCase(Locale.ROOT).replace('_', '-');
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "https://clareza.example/problems/" + slug);
        body.put("title", ex.getCode());
        body.put("status", ex.getStatus());
        body.put("detail", ex.getMessage());
        body.put("instance", request.getRequestURI());
        body.put("code", ex.getCode());
        body.put("requestId", resolveRequestId(request));

        response.setStatus(ex.getStatus());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(), body);
    }

    public static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponível", e);
        }
    }
}
