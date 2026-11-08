-- Phase 3.5 / EPIC-03 (CLZ-007): Persisted user sessions and multi-provider identity access resolution.
-- Preserves 100% backward compatibility with single-argument app.resolve_identity_access(text)
-- while adding support for OIDC/JWT providers and revocable server-side sessions.

CREATE TABLE user_sessions (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL,
  user_id uuid NOT NULL,
  authentication_source varchar(40) NOT NULL DEFAULT 'jwt-session',
  token_hash char(64) NOT NULL,
  ip_hash varchar(128),
  user_agent varchar(500),
  expires_at timestamptz(6) NOT NULL,
  revoked_at timestamptz(6),
  created_at timestamptz(6) NOT NULL DEFAULT now(),
  CONSTRAINT user_sessions_tenant_fk FOREIGN KEY (tenant_id)
    REFERENCES tenants(id) ON DELETE RESTRICT,
  CONSTRAINT user_sessions_user_fk FOREIGN KEY (tenant_id, user_id)
    REFERENCES users(tenant_id, id) ON DELETE CASCADE,
  CONSTRAINT user_sessions_tenant_id_key UNIQUE (tenant_id, id),
  CONSTRAINT user_sessions_token_hash_key UNIQUE (token_hash),
  CONSTRAINT user_sessions_token_hash_format CHECK (token_hash ~ '^[0-9a-f]{64}$'),
  CONSTRAINT user_sessions_source_not_blank CHECK (btrim(authentication_source) <> ''),
  CONSTRAINT user_sessions_expiry_check CHECK (expires_at > created_at)
);

CREATE INDEX user_sessions_user_active_idx
  ON user_sessions (tenant_id, user_id, expires_at DESC)
  WHERE revoked_at IS NULL;

ALTER TABLE user_sessions ENABLE ROW LEVEL SECURITY;
ALTER TABLE user_sessions FORCE ROW LEVEL SECURITY;

CREATE POLICY user_sessions_isolation ON user_sessions TO PUBLIC
  USING (
    tenant_id = app.current_tenant_id()
    AND user_id = app.current_user_id()
  )
  WITH CHECK (
    tenant_id = app.current_tenant_id()
    AND user_id = app.current_user_id()
  );

GRANT SELECT, INSERT, UPDATE (revoked_at) ON user_sessions TO clarity_app;
REVOKE DELETE ON user_sessions FROM clarity_app;

CREATE OR REPLACE FUNCTION app.resolve_session_token(raw_token_hash text)
RETURNS TABLE (
  session_id uuid,
  tenant_id uuid,
  user_id uuid,
  email text,
  authentication_source text
)
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, public, app
AS $function$
  SELECT
    s.id AS session_id,
    s.tenant_id,
    s.user_id,
    u.email::text,
    s.authentication_source::text
  FROM public.user_sessions s
  JOIN public.users u
    ON u.tenant_id = s.tenant_id
   AND u.id = s.user_id
  JOIN public.tenants t
    ON t.id = s.tenant_id
  WHERE s.token_hash = lower(btrim(raw_token_hash))
    AND s.revoked_at IS NULL
    AND s.expires_at > CURRENT_TIMESTAMP
    AND u.is_active = true
    AND u.deleted_at IS NULL
    AND t.status = 'ACTIVE'
    AND t.deleted_at IS NULL
  LIMIT 1;
$function$;

REVOKE ALL ON FUNCTION app.resolve_session_token(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION app.resolve_session_token(text) TO clarity_app;

CREATE OR REPLACE FUNCTION app.resolve_identity_access(
  verified_email text,
  auth_provider text
)
RETURNS TABLE (
  tenant_id uuid,
  tenant_name text,
  user_id uuid,
  display_name text,
  email text,
  scope membership_scope,
  company_id uuid,
  company_name text,
  branch_id uuid,
  branch_name text,
  permissions text[]
)
LANGUAGE plpgsql
VOLATILE
SECURITY DEFINER
SET search_path = pg_catalog, public, app
AS $function$
DECLARE
  identity_record record;
  normalized_provider text := COALESCE(NULLIF(btrim(auth_provider), ''), 'sites-siwc');
BEGIN
  FOR identity_record IN
    SELECT identity.tenant_id, identity.user_id
    FROM public.auth_identities identity
    WHERE identity.provider = normalized_provider
      AND identity.email = lower(btrim(verified_email))
      AND identity.is_active
  LOOP
    PERFORM set_config('app.tenant_id', identity_record.tenant_id::text, true);
    PERFORM set_config('app.user_id', identity_record.user_id::text, true);
    PERFORM set_config('app.company_ids', '', true);
    PERFORM set_config('app.branch_ids', '', true);
    PERFORM set_config('app.tenant_wide_access', 'true', true);

    RETURN QUERY
    WITH active_memberships AS (
      SELECT
        membership.tenant_id,
        membership.user_id,
        membership.role_id,
        membership.scope,
        membership.company_id,
        membership.branch_id
      FROM public.memberships membership
      JOIN public.users app_user
        ON app_user.tenant_id = membership.tenant_id
       AND app_user.id = membership.user_id
      JOIN public.tenants tenant
        ON tenant.id = membership.tenant_id
      WHERE membership.tenant_id = identity_record.tenant_id
        AND membership.user_id = identity_record.user_id
        AND app_user.is_active
        AND app_user.deleted_at IS NULL
        AND tenant.status = 'ACTIVE'
        AND tenant.deleted_at IS NULL
        AND membership.is_active
        AND (membership.starts_at IS NULL OR membership.starts_at <= CURRENT_TIMESTAMP)
        AND (membership.ends_at IS NULL OR membership.ends_at > CURRENT_TIMESTAMP)
    ),
    expanded_contexts AS (
      SELECT
        membership.tenant_id,
        membership.user_id,
        membership.role_id,
        membership.scope,
        company.id AS company_id,
        branch.id AS branch_id
      FROM active_memberships membership
      JOIN public.companies company
        ON company.tenant_id = membership.tenant_id
       AND company.status = 'ACTIVE'
       AND company.deleted_at IS NULL
      LEFT JOIN public.branches branch
        ON branch.tenant_id = company.tenant_id
       AND branch.company_id = company.id
       AND branch.status = 'ACTIVE'
       AND branch.deleted_at IS NULL
      WHERE membership.scope = 'TENANT'

      UNION ALL

      SELECT
        membership.tenant_id,
        membership.user_id,
        membership.role_id,
        membership.scope,
        company.id,
        branch.id
      FROM active_memberships membership
      JOIN public.companies company
        ON company.tenant_id = membership.tenant_id
       AND company.id = membership.company_id
       AND company.status = 'ACTIVE'
       AND company.deleted_at IS NULL
      LEFT JOIN public.branches branch
        ON branch.tenant_id = company.tenant_id
       AND branch.company_id = company.id
       AND branch.status = 'ACTIVE'
       AND branch.deleted_at IS NULL
      WHERE membership.scope = 'COMPANY'

      UNION ALL

      SELECT
        membership.tenant_id,
        membership.user_id,
        membership.role_id,
        membership.scope,
        company.id,
        branch.id
      FROM active_memberships membership
      JOIN public.companies company
        ON company.tenant_id = membership.tenant_id
       AND company.id = membership.company_id
       AND company.status = 'ACTIVE'
       AND company.deleted_at IS NULL
      JOIN public.branches branch
        ON branch.tenant_id = membership.tenant_id
       AND branch.company_id = membership.company_id
       AND branch.id = membership.branch_id
       AND branch.status = 'ACTIVE'
       AND branch.deleted_at IS NULL
      WHERE membership.scope = 'BRANCH'
    )
    SELECT
      context.tenant_id,
      tenant.name::text,
      context.user_id,
      app_user.display_name::text,
      app_user.email::text,
      context.scope,
      context.company_id,
      COALESCE(company.trade_name, company.legal_name)::text,
      context.branch_id,
      branch.name::text,
      COALESCE(
        array_agg(DISTINCT permission.code::text)
          FILTER (WHERE permission.code IS NOT NULL),
        ARRAY[]::text[]
      )
    FROM expanded_contexts context
    JOIN public.tenants tenant
      ON tenant.id = context.tenant_id
    JOIN public.users app_user
      ON app_user.tenant_id = context.tenant_id
     AND app_user.id = context.user_id
    JOIN public.companies company
      ON company.tenant_id = context.tenant_id
     AND company.id = context.company_id
    LEFT JOIN public.branches branch
      ON branch.tenant_id = context.tenant_id
     AND branch.company_id = context.company_id
     AND branch.id = context.branch_id
    LEFT JOIN public.role_permissions role_permission
      ON role_permission.tenant_id = context.tenant_id
     AND role_permission.role_id = context.role_id
    LEFT JOIN public.permissions permission
      ON permission.tenant_id = role_permission.tenant_id
     AND permission.id = role_permission.permission_id
    GROUP BY
      context.tenant_id,
      tenant.name,
      context.user_id,
      app_user.display_name,
      app_user.email,
      context.scope,
      context.company_id,
      company.trade_name,
      company.legal_name,
      context.branch_id,
      branch.name;
  END LOOP;

  PERFORM set_config('app.tenant_id', '', true);
  PERFORM set_config('app.user_id', '', true);
  PERFORM set_config('app.company_ids', '', true);
  PERFORM set_config('app.branch_ids', '', true);
  PERFORM set_config('app.tenant_wide_access', 'false', true);
END
$function$;

REVOKE ALL ON FUNCTION app.resolve_identity_access(text, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION app.resolve_identity_access(text, text) TO clarity_app;

COMMENT ON TABLE user_sessions IS
  'Server-side revocable sessions scoped to tenant and user via RLS.';
