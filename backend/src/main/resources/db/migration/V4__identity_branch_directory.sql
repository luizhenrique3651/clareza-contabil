-- Expose the active branch directory to identities with tenant/company-wide
-- access. The same rows are also the server-side proof used before accepting
-- x-clareza-branch-id as an active navigation context.

CREATE OR REPLACE FUNCTION app.resolve_identity_access(verified_email text)
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
BEGIN
  FOR identity_record IN
    SELECT identity.tenant_id, identity.user_id
    FROM public.auth_identities identity
    WHERE identity.provider = 'sites-siwc'
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

REVOKE ALL ON FUNCTION app.resolve_identity_access(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION app.resolve_identity_access(text) TO clarity_app;

COMMENT ON FUNCTION app.resolve_identity_access(text) IS
  'Bootstrap de autorização e diretório de empresas/unidades; aceita apenas e-mail verificado pelo hosting.';
