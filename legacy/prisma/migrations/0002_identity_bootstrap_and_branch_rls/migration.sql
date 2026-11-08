-- Authenticated identity bootstrap and branch-aware RLS.
-- The hosting layer authenticates the email. This migration exposes only the
-- minimum access directory needed to derive tenant/company/branch context.

CREATE OR REPLACE FUNCTION app.current_branch_ids()
RETURNS uuid[]
LANGUAGE sql
STABLE
PARALLEL SAFE
AS $function$
  SELECT COALESCE(
    string_to_array(NULLIF(current_setting('app.branch_ids', true), ''), ',')::uuid[],
    ARRAY[]::uuid[]
  )
$function$;

CREATE OR REPLACE FUNCTION app.can_access_branch(candidate_branch_id uuid)
RETURNS boolean
LANGUAGE sql
STABLE
PARALLEL SAFE
AS $function$
  SELECT candidate_branch_id IS NOT NULL
    AND candidate_branch_id = ANY(app.current_branch_ids())
$function$;

CREATE OR REPLACE FUNCTION app.can_access_record(
  candidate_company_id uuid,
  candidate_branch_id uuid
)
RETURNS boolean
LANGUAGE sql
STABLE
PARALLEL SAFE
AS $function$
  SELECT
    candidate_company_id IS NOT NULL
    AND (
      app.can_access_company(candidate_company_id)
      OR app.can_access_branch(candidate_branch_id)
    )
$function$;

DROP POLICY company_isolation ON branches;
CREATE POLICY branch_isolation ON branches TO PUBLIC
  USING (
    tenant_id = app.current_tenant_id()
    AND app.can_access_record(company_id, id)
  )
  WITH CHECK (
    tenant_id = app.current_tenant_id()
    AND app.can_access_record(company_id, id)
  );

DO $replace_branch_policies$
DECLARE
  table_name text;
BEGIN
  FOREACH table_name IN ARRAY ARRAY[
    'payables', 'receivables', 'journal_entries',
    'pending_items', 'risks', 'action_plans'
  ]
  LOOP
    EXECUTE format('DROP POLICY company_isolation ON %I', table_name);
    EXECUTE format(
      'CREATE POLICY branch_isolation ON %I TO PUBLIC USING (tenant_id = app.current_tenant_id() AND app.can_access_record(company_id, branch_id)) WITH CHECK (tenant_id = app.current_tenant_id() AND app.can_access_record(company_id, branch_id))',
      table_name
    );
  END LOOP;
END
$replace_branch_policies$;

DROP POLICY company_isolation ON journal_entry_lines;
CREATE POLICY journal_entry_lines_isolation ON journal_entry_lines TO PUBLIC
  USING (
    tenant_id = app.current_tenant_id()
    AND EXISTS (
      SELECT 1
      FROM journal_entries entry
      WHERE entry.tenant_id = journal_entry_lines.tenant_id
        AND entry.company_id = journal_entry_lines.company_id
        AND entry.id = journal_entry_lines.journal_entry_id
        AND app.can_access_record(entry.company_id, entry.branch_id)
    )
  )
  WITH CHECK (
    tenant_id = app.current_tenant_id()
    AND EXISTS (
      SELECT 1
      FROM journal_entries entry
      WHERE entry.tenant_id = journal_entry_lines.tenant_id
        AND entry.company_id = journal_entry_lines.company_id
        AND entry.id = journal_entry_lines.journal_entry_id
        AND app.can_access_record(entry.company_id, entry.branch_id)
    )
  );

DROP POLICY companies_isolation ON companies;
CREATE POLICY companies_isolation ON companies TO PUBLIC
  USING (
    tenant_id = app.current_tenant_id()
    AND (
      app.can_access_company(id)
      OR EXISTS (
        SELECT 1
        FROM branches branch
        WHERE branch.tenant_id = companies.tenant_id
          AND branch.company_id = companies.id
          AND app.can_access_branch(branch.id)
      )
    )
  )
  WITH CHECK (
    tenant_id = app.current_tenant_id()
    AND app.can_access_company(id)
  );

DROP POLICY audit_logs_select ON audit_logs;
DROP POLICY audit_logs_insert ON audit_logs;
CREATE POLICY audit_logs_select ON audit_logs FOR SELECT TO PUBLIC
  USING (
    tenant_id = app.current_tenant_id()
    AND (
      company_id IS NULL
      OR app.can_access_record(company_id, branch_id)
    )
  );
CREATE POLICY audit_logs_insert ON audit_logs FOR INSERT TO PUBLIC
  WITH CHECK (
    tenant_id = app.current_tenant_id()
    AND (
      company_id IS NULL
      OR app.can_access_record(company_id, branch_id)
    )
    AND (actor_user_id IS NULL OR actor_user_id = app.current_user_id())
  );

CREATE TABLE auth_identities (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL,
  user_id uuid NOT NULL,
  provider varchar(40) NOT NULL,
  subject varchar(254) NOT NULL,
  email varchar(254) NOT NULL,
  is_active boolean NOT NULL DEFAULT true,
  last_seen_at timestamptz(6),
  created_at timestamptz(6) NOT NULL DEFAULT now(),
  updated_at timestamptz(6) NOT NULL DEFAULT now(),
  CONSTRAINT auth_identities_tenant_fk FOREIGN KEY (tenant_id)
    REFERENCES tenants(id) ON DELETE RESTRICT,
  CONSTRAINT auth_identities_user_fk FOREIGN KEY (tenant_id, user_id)
    REFERENCES users(tenant_id, id) ON DELETE CASCADE,
  CONSTRAINT auth_identities_provider_subject_tenant_key
    UNIQUE (provider, subject, tenant_id),
  CONSTRAINT auth_identities_user_provider_key
    UNIQUE (tenant_id, user_id, provider),
  CONSTRAINT auth_identities_provider_not_blank CHECK (btrim(provider) <> ''),
  CONSTRAINT auth_identities_subject_not_blank CHECK (btrim(subject) <> ''),
  CONSTRAINT auth_identities_email_normalized
    CHECK (email = lower(email) AND email LIKE '%_@_%._%')
);

CREATE INDEX auth_identities_email_active_idx
  ON auth_identities (provider, email, is_active);

CREATE TRIGGER auth_identities_set_updated_at BEFORE UPDATE ON auth_identities
  FOR EACH ROW EXECUTE FUNCTION app.set_updated_at();

-- Default privileges from the first migration grant new tables to clarity_app.
-- The identity directory is deliberately reachable only through the narrow
-- security-definer function below.
REVOKE ALL ON auth_identities FROM PUBLIC, clarity_app;

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
        NULL::uuid AS branch_id
      FROM active_memberships membership
      JOIN public.companies company
        ON company.tenant_id = membership.tenant_id
       AND company.status = 'ACTIVE'
       AND company.deleted_at IS NULL
      WHERE membership.scope = 'TENANT'

      UNION ALL

      SELECT
        membership.tenant_id,
        membership.user_id,
        membership.role_id,
        membership.scope,
        company.id,
        NULL::uuid
      FROM active_memberships membership
      JOIN public.companies company
        ON company.tenant_id = membership.tenant_id
       AND company.id = membership.company_id
       AND company.status = 'ACTIVE'
       AND company.deleted_at IS NULL
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
  'Bootstrap mínimo de autorização. O chamador só pode fornecer e-mail verificado pelo hosting.';
