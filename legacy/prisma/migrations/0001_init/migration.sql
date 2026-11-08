-- Clarity MVP: PostgreSQL foundation with tenant/company isolation.
-- Run migrations with DIRECT_DATABASE_URL (table owner). Runtime connections must
-- SET ROLE clarity_app and set app.tenant_id/app.company_ids inside a transaction.

CREATE EXTENSION IF NOT EXISTS pgcrypto;

DO $role$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'clarity_app') THEN
    CREATE ROLE clarity_app
      NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS;
  END IF;
END
$role$;

CREATE SCHEMA IF NOT EXISTS app;

CREATE TYPE tenant_status AS ENUM ('ACTIVE', 'SUSPENDED', 'ARCHIVED');
CREATE TYPE company_status AS ENUM ('ACTIVE', 'INACTIVE', 'ARCHIVED');
CREATE TYPE membership_scope AS ENUM ('TENANT', 'COMPANY', 'BRANCH');
CREATE TYPE financial_status AS ENUM ('OPEN', 'PARTIAL', 'SETTLED', 'OVERDUE', 'CANCELLED');
CREATE TYPE journal_entry_status AS ENUM ('DRAFT', 'POSTED', 'REVERSED');
CREATE TYPE glossary_category AS ENUM (
  'ACCOUNTING', 'FINANCIAL', 'FISCAL', 'TAX', 'LABOR', 'PAYROLL',
  'CONTROLLERSHIP', 'AUDIT', 'MANAGEMENT', 'INDICATORS'
);
CREATE TYPE priority AS ENUM ('INFORMATIONAL', 'ATTENTION', 'IMPORTANT', 'CRITICAL');
CREATE TYPE work_status AS ENUM ('OPEN', 'IN_PROGRESS', 'BLOCKED', 'RESOLVED', 'CANCELLED');
CREATE TYPE risk_level AS ENUM ('VERY_LOW', 'LOW', 'MEDIUM', 'HIGH', 'VERY_HIGH');
CREATE TYPE risk_status AS ENUM (
  'IDENTIFIED', 'UNDER_ANALYSIS', 'TREATMENT_PLANNED', 'IN_TREATMENT',
  'MONITORING', 'MITIGATED', 'ACCEPTED', 'CLOSED'
);
CREATE TYPE action_plan_status AS ENUM ('PLANNED', 'IN_PROGRESS', 'BLOCKED', 'COMPLETED', 'CANCELLED');

CREATE TABLE tenants (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  name varchar(160) NOT NULL,
  slug varchar(80) NOT NULL,
  status tenant_status NOT NULL DEFAULT 'ACTIVE',
  settings jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at timestamptz(6) NOT NULL DEFAULT now(),
  updated_at timestamptz(6) NOT NULL DEFAULT now(),
  deleted_at timestamptz(6),
  CONSTRAINT tenants_slug_key UNIQUE (slug),
  CONSTRAINT tenants_name_not_blank CHECK (btrim(name) <> ''),
  CONSTRAINT tenants_slug_format CHECK (slug ~ '^[a-z0-9]+(?:-[a-z0-9]+)*$'),
  CONSTRAINT tenants_settings_object CHECK (jsonb_typeof(settings) = 'object'),
  CONSTRAINT tenants_deleted_archived CHECK (deleted_at IS NULL OR status = 'ARCHIVED')
);

CREATE TABLE companies (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL,
  legal_name varchar(180) NOT NULL,
  trade_name varchar(180),
  tax_id varchar(24) NOT NULL,
  country_code char(2) NOT NULL DEFAULT 'BR',
  currency_code char(3) NOT NULL DEFAULT 'BRL',
  timezone varchar(80) NOT NULL DEFAULT 'America/Sao_Paulo',
  status company_status NOT NULL DEFAULT 'ACTIVE',
  settings jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at timestamptz(6) NOT NULL DEFAULT now(),
  updated_at timestamptz(6) NOT NULL DEFAULT now(),
  deleted_at timestamptz(6),
  CONSTRAINT companies_tenant_id_fk FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE RESTRICT,
  CONSTRAINT companies_tenant_id_id_key UNIQUE (tenant_id, id),
  CONSTRAINT companies_tenant_tax_id_key UNIQUE (tenant_id, tax_id),
  CONSTRAINT companies_legal_name_not_blank CHECK (btrim(legal_name) <> ''),
  CONSTRAINT companies_tax_id_not_blank CHECK (btrim(tax_id) <> ''),
  CONSTRAINT companies_country_code_format CHECK (country_code ~ '^[A-Z]{2}$'),
  CONSTRAINT companies_currency_code_format CHECK (currency_code ~ '^[A-Z]{3}$'),
  CONSTRAINT companies_settings_object CHECK (jsonb_typeof(settings) = 'object'),
  CONSTRAINT companies_deleted_archived CHECK (deleted_at IS NULL OR status = 'ARCHIVED')
);

CREATE INDEX companies_tenant_status_idx ON companies (tenant_id, status);

CREATE TABLE branches (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL,
  company_id uuid NOT NULL,
  code varchar(40) NOT NULL,
  name varchar(160) NOT NULL,
  tax_id varchar(24),
  status company_status NOT NULL DEFAULT 'ACTIVE',
  created_at timestamptz(6) NOT NULL DEFAULT now(),
  updated_at timestamptz(6) NOT NULL DEFAULT now(),
  deleted_at timestamptz(6),
  CONSTRAINT branches_tenant_id_fk FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE RESTRICT,
  CONSTRAINT branches_company_fk FOREIGN KEY (tenant_id, company_id)
    REFERENCES companies(tenant_id, id) ON DELETE RESTRICT,
  CONSTRAINT branches_tenant_id_id_key UNIQUE (tenant_id, id),
  CONSTRAINT branches_tenant_company_id_id_key UNIQUE (tenant_id, company_id, id),
  CONSTRAINT branches_tenant_company_code_key UNIQUE (tenant_id, company_id, code),
  CONSTRAINT branches_code_not_blank CHECK (btrim(code) <> ''),
  CONSTRAINT branches_name_not_blank CHECK (btrim(name) <> ''),
  CONSTRAINT branches_deleted_archived CHECK (deleted_at IS NULL OR status = 'ARCHIVED')
);

CREATE INDEX branches_tenant_company_status_idx ON branches (tenant_id, company_id, status);

CREATE TABLE users (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL,
  email varchar(254) NOT NULL,
  display_name varchar(160) NOT NULL,
  password_hash varchar(255) NOT NULL,
  explanation_level varchar(20) NOT NULL DEFAULT 'ESSENTIAL',
  locale varchar(16) NOT NULL DEFAULT 'pt-BR',
  is_active boolean NOT NULL DEFAULT true,
  last_login_at timestamptz(6),
  created_at timestamptz(6) NOT NULL DEFAULT now(),
  updated_at timestamptz(6) NOT NULL DEFAULT now(),
  deleted_at timestamptz(6),
  CONSTRAINT users_tenant_id_fk FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE RESTRICT,
  CONSTRAINT users_tenant_id_id_key UNIQUE (tenant_id, id),
  CONSTRAINT users_tenant_email_key UNIQUE (tenant_id, email),
  CONSTRAINT users_email_normalized CHECK (email = lower(email) AND email LIKE '%_@_%._%'),
  CONSTRAINT users_display_name_not_blank CHECK (btrim(display_name) <> ''),
  CONSTRAINT users_password_hash_not_blank CHECK (length(password_hash) >= 20),
  CONSTRAINT users_explanation_level_check CHECK (explanation_level IN ('ESSENTIAL', 'MANAGERIAL', 'TECHNICAL')),
  CONSTRAINT users_deleted_inactive CHECK (deleted_at IS NULL OR is_active = false)
);

CREATE INDEX users_tenant_active_idx ON users (tenant_id, is_active);

CREATE TABLE roles (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL,
  code varchar(80) NOT NULL,
  name varchar(120) NOT NULL,
  description varchar(500),
  is_system boolean NOT NULL DEFAULT false,
  created_at timestamptz(6) NOT NULL DEFAULT now(),
  updated_at timestamptz(6) NOT NULL DEFAULT now(),
  CONSTRAINT roles_tenant_id_fk FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE RESTRICT,
  CONSTRAINT roles_tenant_id_id_key UNIQUE (tenant_id, id),
  CONSTRAINT roles_tenant_code_key UNIQUE (tenant_id, code),
  CONSTRAINT roles_code_format CHECK (code ~ '^[a-z][a-z0-9_.-]*$'),
  CONSTRAINT roles_name_not_blank CHECK (btrim(name) <> '')
);

CREATE TABLE permissions (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL,
  code varchar(100) NOT NULL,
  description varchar(500),
  created_at timestamptz(6) NOT NULL DEFAULT now(),
  CONSTRAINT permissions_tenant_id_fk FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE RESTRICT,
  CONSTRAINT permissions_tenant_id_id_key UNIQUE (tenant_id, id),
  CONSTRAINT permissions_tenant_code_key UNIQUE (tenant_id, code),
  CONSTRAINT permissions_code_format CHECK (code ~ '^[a-z][a-z0-9_]*\.[a-z][a-z0-9_]*$')
);

CREATE TABLE role_permissions (
  tenant_id uuid NOT NULL,
  role_id uuid NOT NULL,
  permission_id uuid NOT NULL,
  created_at timestamptz(6) NOT NULL DEFAULT now(),
  CONSTRAINT role_permissions_pkey PRIMARY KEY (tenant_id, role_id, permission_id),
  CONSTRAINT role_permissions_tenant_id_fk FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE RESTRICT,
  CONSTRAINT role_permissions_role_fk FOREIGN KEY (tenant_id, role_id)
    REFERENCES roles(tenant_id, id) ON DELETE CASCADE,
  CONSTRAINT role_permissions_permission_fk FOREIGN KEY (tenant_id, permission_id)
    REFERENCES permissions(tenant_id, id) ON DELETE CASCADE
);

CREATE INDEX role_permissions_permission_idx ON role_permissions (tenant_id, permission_id);

CREATE TABLE memberships (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL,
  user_id uuid NOT NULL,
  role_id uuid NOT NULL,
  scope membership_scope NOT NULL,
  company_id uuid,
  branch_id uuid,
  is_active boolean NOT NULL DEFAULT true,
  starts_at timestamptz(6),
  ends_at timestamptz(6),
  created_at timestamptz(6) NOT NULL DEFAULT now(),
  updated_at timestamptz(6) NOT NULL DEFAULT now(),
  CONSTRAINT memberships_tenant_id_fk FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE RESTRICT,
  CONSTRAINT memberships_user_fk FOREIGN KEY (tenant_id, user_id)
    REFERENCES users(tenant_id, id) ON DELETE CASCADE,
  CONSTRAINT memberships_role_fk FOREIGN KEY (tenant_id, role_id)
    REFERENCES roles(tenant_id, id) ON DELETE RESTRICT,
  CONSTRAINT memberships_company_fk FOREIGN KEY (tenant_id, company_id)
    REFERENCES companies(tenant_id, id) ON DELETE CASCADE,
  CONSTRAINT memberships_branch_fk FOREIGN KEY (tenant_id, company_id, branch_id)
    REFERENCES branches(tenant_id, company_id, id) ON DELETE CASCADE,
  CONSTRAINT memberships_tenant_id_id_key UNIQUE (tenant_id, id),
  CONSTRAINT memberships_scope_shape CHECK (
    (scope = 'TENANT' AND company_id IS NULL AND branch_id IS NULL) OR
    (scope = 'COMPANY' AND company_id IS NOT NULL AND branch_id IS NULL) OR
    (scope = 'BRANCH' AND company_id IS NOT NULL AND branch_id IS NOT NULL)
  ),
  CONSTRAINT memberships_period_check CHECK (ends_at IS NULL OR starts_at IS NULL OR ends_at > starts_at)
);

CREATE INDEX memberships_user_active_idx ON memberships (tenant_id, user_id, is_active);
CREATE INDEX memberships_scope_idx ON memberships (tenant_id, company_id, branch_id);
CREATE UNIQUE INDEX memberships_tenant_scope_uq
  ON memberships (tenant_id, user_id, role_id) WHERE scope = 'TENANT';
CREATE UNIQUE INDEX memberships_company_scope_uq
  ON memberships (tenant_id, user_id, role_id, company_id) WHERE scope = 'COMPANY';
CREATE UNIQUE INDEX memberships_branch_scope_uq
  ON memberships (tenant_id, user_id, role_id, company_id, branch_id) WHERE scope = 'BRANCH';

CREATE TABLE payables (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL,
  company_id uuid NOT NULL,
  branch_id uuid,
  counterparty_name varchar(180) NOT NULL,
  description varchar(500) NOT NULL,
  document_number varchar(80),
  issue_date date,
  due_date date NOT NULL,
  amount numeric(19,4) NOT NULL,
  outstanding_amount numeric(19,4) NOT NULL,
  currency_code char(3) NOT NULL DEFAULT 'BRL',
  status financial_status NOT NULL DEFAULT 'OPEN',
  paid_at timestamptz(6),
  created_by_id uuid NOT NULL,
  updated_by_id uuid,
  created_at timestamptz(6) NOT NULL DEFAULT now(),
  updated_at timestamptz(6) NOT NULL DEFAULT now(),
  deleted_at timestamptz(6),
  CONSTRAINT payables_tenant_id_fk FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE RESTRICT,
  CONSTRAINT payables_company_fk FOREIGN KEY (tenant_id, company_id)
    REFERENCES companies(tenant_id, id) ON DELETE RESTRICT,
  CONSTRAINT payables_branch_fk FOREIGN KEY (tenant_id, company_id, branch_id)
    REFERENCES branches(tenant_id, company_id, id) ON DELETE RESTRICT,
  CONSTRAINT payables_created_by_fk FOREIGN KEY (tenant_id, created_by_id)
    REFERENCES users(tenant_id, id) ON DELETE RESTRICT,
  CONSTRAINT payables_updated_by_fk FOREIGN KEY (tenant_id, updated_by_id)
    REFERENCES users(tenant_id, id) ON DELETE RESTRICT,
  CONSTRAINT payables_tenant_company_id_key UNIQUE (tenant_id, company_id, id),
  CONSTRAINT payables_amount_positive CHECK (amount > 0),
  CONSTRAINT payables_outstanding_range CHECK (outstanding_amount >= 0 AND outstanding_amount <= amount),
  CONSTRAINT payables_settlement_consistency CHECK (
    (status = 'SETTLED' AND outstanding_amount = 0 AND paid_at IS NOT NULL) OR
    (status <> 'SETTLED' AND (paid_at IS NULL OR outstanding_amount = 0))
  ),
  CONSTRAINT payables_currency_code_format CHECK (currency_code ~ '^[A-Z]{3}$'),
  CONSTRAINT payables_counterparty_not_blank CHECK (btrim(counterparty_name) <> ''),
  CONSTRAINT payables_description_not_blank CHECK (btrim(description) <> '')
);

CREATE INDEX payables_status_due_idx ON payables (tenant_id, company_id, status, due_date);
CREATE INDEX payables_counterparty_idx ON payables (tenant_id, company_id, counterparty_name);
CREATE INDEX payables_active_due_idx ON payables (tenant_id, company_id, due_date)
  WHERE deleted_at IS NULL AND status IN ('OPEN', 'PARTIAL', 'OVERDUE');

CREATE TABLE receivables (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL,
  company_id uuid NOT NULL,
  branch_id uuid,
  counterparty_name varchar(180) NOT NULL,
  description varchar(500) NOT NULL,
  document_number varchar(80),
  issue_date date,
  due_date date NOT NULL,
  amount numeric(19,4) NOT NULL,
  outstanding_amount numeric(19,4) NOT NULL,
  currency_code char(3) NOT NULL DEFAULT 'BRL',
  status financial_status NOT NULL DEFAULT 'OPEN',
  received_at timestamptz(6),
  created_by_id uuid NOT NULL,
  updated_by_id uuid,
  created_at timestamptz(6) NOT NULL DEFAULT now(),
  updated_at timestamptz(6) NOT NULL DEFAULT now(),
  deleted_at timestamptz(6),
  CONSTRAINT receivables_tenant_id_fk FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE RESTRICT,
  CONSTRAINT receivables_company_fk FOREIGN KEY (tenant_id, company_id)
    REFERENCES companies(tenant_id, id) ON DELETE RESTRICT,
  CONSTRAINT receivables_branch_fk FOREIGN KEY (tenant_id, company_id, branch_id)
    REFERENCES branches(tenant_id, company_id, id) ON DELETE RESTRICT,
  CONSTRAINT receivables_created_by_fk FOREIGN KEY (tenant_id, created_by_id)
    REFERENCES users(tenant_id, id) ON DELETE RESTRICT,
  CONSTRAINT receivables_updated_by_fk FOREIGN KEY (tenant_id, updated_by_id)
    REFERENCES users(tenant_id, id) ON DELETE RESTRICT,
  CONSTRAINT receivables_tenant_company_id_key UNIQUE (tenant_id, company_id, id),
  CONSTRAINT receivables_amount_positive CHECK (amount > 0),
  CONSTRAINT receivables_outstanding_range CHECK (outstanding_amount >= 0 AND outstanding_amount <= amount),
  CONSTRAINT receivables_settlement_consistency CHECK (
    (status = 'SETTLED' AND outstanding_amount = 0 AND received_at IS NOT NULL) OR
    (status <> 'SETTLED' AND (received_at IS NULL OR outstanding_amount = 0))
  ),
  CONSTRAINT receivables_currency_code_format CHECK (currency_code ~ '^[A-Z]{3}$'),
  CONSTRAINT receivables_counterparty_not_blank CHECK (btrim(counterparty_name) <> ''),
  CONSTRAINT receivables_description_not_blank CHECK (btrim(description) <> '')
);

CREATE INDEX receivables_status_due_idx ON receivables (tenant_id, company_id, status, due_date);
CREATE INDEX receivables_counterparty_idx ON receivables (tenant_id, company_id, counterparty_name);
CREATE INDEX receivables_active_due_idx ON receivables (tenant_id, company_id, due_date)
  WHERE deleted_at IS NULL AND status IN ('OPEN', 'PARTIAL', 'OVERDUE');

CREATE TABLE journal_entries (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL,
  company_id uuid NOT NULL,
  branch_id uuid,
  entry_number varchar(60) NOT NULL,
  entry_date date NOT NULL,
  description varchar(500) NOT NULL,
  source_type varchar(80),
  source_id uuid,
  status journal_entry_status NOT NULL DEFAULT 'DRAFT',
  posted_at timestamptz(6),
  reverses_entry_id uuid,
  created_by_id uuid NOT NULL,
  updated_by_id uuid,
  created_at timestamptz(6) NOT NULL DEFAULT now(),
  updated_at timestamptz(6) NOT NULL DEFAULT now(),
  CONSTRAINT journal_entries_tenant_id_fk FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE RESTRICT,
  CONSTRAINT journal_entries_company_fk FOREIGN KEY (tenant_id, company_id)
    REFERENCES companies(tenant_id, id) ON DELETE RESTRICT,
  CONSTRAINT journal_entries_branch_fk FOREIGN KEY (tenant_id, company_id, branch_id)
    REFERENCES branches(tenant_id, company_id, id) ON DELETE RESTRICT,
  CONSTRAINT journal_entries_created_by_fk FOREIGN KEY (tenant_id, created_by_id)
    REFERENCES users(tenant_id, id) ON DELETE RESTRICT,
  CONSTRAINT journal_entries_updated_by_fk FOREIGN KEY (tenant_id, updated_by_id)
    REFERENCES users(tenant_id, id) ON DELETE RESTRICT,
  CONSTRAINT journal_entries_tenant_company_id_key UNIQUE (tenant_id, company_id, id),
  CONSTRAINT journal_entries_number_key UNIQUE (tenant_id, company_id, entry_number),
  CONSTRAINT journal_entries_reversal_fk FOREIGN KEY (tenant_id, company_id, reverses_entry_id)
    REFERENCES journal_entries(tenant_id, company_id, id) ON DELETE RESTRICT,
  CONSTRAINT journal_entries_description_not_blank CHECK (btrim(description) <> ''),
  CONSTRAINT journal_entries_posted_at_consistency CHECK (
    (status = 'DRAFT' AND posted_at IS NULL) OR (status IN ('POSTED', 'REVERSED') AND posted_at IS NOT NULL)
  ),
  CONSTRAINT journal_entries_not_self_reversing CHECK (reverses_entry_id IS NULL OR reverses_entry_id <> id)
);

CREATE INDEX journal_entries_date_status_idx ON journal_entries (tenant_id, company_id, entry_date, status);
CREATE INDEX journal_entries_source_idx ON journal_entries (tenant_id, company_id, source_type, source_id);

CREATE TABLE journal_entry_lines (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL,
  company_id uuid NOT NULL,
  journal_entry_id uuid NOT NULL,
  line_number integer NOT NULL,
  account_code varchar(60) NOT NULL,
  account_name varchar(180) NOT NULL,
  description varchar(500),
  debit numeric(19,4) NOT NULL DEFAULT 0,
  credit numeric(19,4) NOT NULL DEFAULT 0,
  cost_center_code varchar(60),
  created_at timestamptz(6) NOT NULL DEFAULT now(),
  CONSTRAINT journal_entry_lines_tenant_id_fk FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE RESTRICT,
  CONSTRAINT journal_entry_lines_company_fk FOREIGN KEY (tenant_id, company_id)
    REFERENCES companies(tenant_id, id) ON DELETE RESTRICT,
  CONSTRAINT journal_entry_lines_entry_fk FOREIGN KEY (tenant_id, company_id, journal_entry_id)
    REFERENCES journal_entries(tenant_id, company_id, id) ON DELETE CASCADE,
  CONSTRAINT journal_entry_lines_number_key UNIQUE (tenant_id, company_id, journal_entry_id, line_number),
  CONSTRAINT journal_entry_lines_line_number_positive CHECK (line_number > 0),
  CONSTRAINT journal_entry_lines_one_side_only CHECK (
    (debit > 0 AND credit = 0) OR (credit > 0 AND debit = 0)
  ),
  CONSTRAINT journal_entry_lines_account_code_not_blank CHECK (btrim(account_code) <> ''),
  CONSTRAINT journal_entry_lines_account_name_not_blank CHECK (btrim(account_name) <> '')
);

CREATE INDEX journal_entry_lines_account_idx ON journal_entry_lines (tenant_id, company_id, account_code);

CREATE TABLE glossary_terms (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL,
  slug varchar(120) NOT NULL,
  name varchar(160) NOT NULL,
  acronym varchar(32),
  category glossary_category NOT NULL,
  simple_definition text NOT NULL,
  managerial_definition text,
  technical_definition text,
  practical_example text,
  numeric_example text,
  formula text,
  importance text,
  where_appears text,
  interpretation text,
  common_errors text,
  related_risks text,
  related_term_slugs text[] NOT NULL DEFAULT ARRAY[]::text[],
  "references" jsonb NOT NULL DEFAULT '[]'::jsonb,
  is_published boolean NOT NULL DEFAULT true,
  last_reviewed_at date,
  created_at timestamptz(6) NOT NULL DEFAULT now(),
  updated_at timestamptz(6) NOT NULL DEFAULT now(),
  CONSTRAINT glossary_terms_tenant_id_fk FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE RESTRICT,
  CONSTRAINT glossary_terms_tenant_id_id_key UNIQUE (tenant_id, id),
  CONSTRAINT glossary_terms_tenant_slug_key UNIQUE (tenant_id, slug),
  CONSTRAINT glossary_terms_slug_format CHECK (slug ~ '^[a-z0-9]+(?:-[a-z0-9]+)*$'),
  CONSTRAINT glossary_terms_name_not_blank CHECK (btrim(name) <> ''),
  CONSTRAINT glossary_terms_definition_not_blank CHECK (btrim(simple_definition) <> ''),
  CONSTRAINT glossary_terms_references_array CHECK (jsonb_typeof("references") = 'array')
);

CREATE INDEX glossary_terms_category_idx ON glossary_terms (tenant_id, category, is_published);

CREATE TABLE pending_items (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL,
  company_id uuid NOT NULL,
  branch_id uuid,
  type varchar(80) NOT NULL,
  category varchar(80) NOT NULL,
  title varchar(180) NOT NULL,
  description text,
  responsible_user_id uuid,
  priority priority NOT NULL DEFAULT 'ATTENTION',
  due_date date,
  status work_status NOT NULL DEFAULT 'OPEN',
  source_type varchar(80),
  source_id uuid,
  impact text,
  suggested_action text,
  resolved_at timestamptz(6),
  created_at timestamptz(6) NOT NULL DEFAULT now(),
  updated_at timestamptz(6) NOT NULL DEFAULT now(),
  CONSTRAINT pending_items_tenant_id_fk FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE RESTRICT,
  CONSTRAINT pending_items_company_fk FOREIGN KEY (tenant_id, company_id)
    REFERENCES companies(tenant_id, id) ON DELETE RESTRICT,
  CONSTRAINT pending_items_branch_fk FOREIGN KEY (tenant_id, company_id, branch_id)
    REFERENCES branches(tenant_id, company_id, id) ON DELETE RESTRICT,
  CONSTRAINT pending_items_responsible_fk FOREIGN KEY (tenant_id, responsible_user_id)
    REFERENCES users(tenant_id, id) ON DELETE RESTRICT,
  CONSTRAINT pending_items_tenant_company_id_key UNIQUE (tenant_id, company_id, id),
  CONSTRAINT pending_items_title_not_blank CHECK (btrim(title) <> ''),
  CONSTRAINT pending_items_resolution_consistency CHECK (
    (status = 'RESOLVED' AND resolved_at IS NOT NULL) OR (status <> 'RESOLVED' AND resolved_at IS NULL)
  )
);

CREATE INDEX pending_items_queue_idx ON pending_items (tenant_id, company_id, status, priority, due_date);
CREATE INDEX pending_items_responsible_idx ON pending_items (tenant_id, responsible_user_id, status);

CREATE TABLE risks (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL,
  company_id uuid NOT NULL,
  branch_id uuid,
  title varchar(180) NOT NULL,
  area varchar(100) NOT NULL,
  problem text NOT NULL,
  evidence text,
  cause text,
  probability risk_level NOT NULL,
  impact risk_level NOT NULL,
  financial_impact numeric(19,4),
  tax_impact text,
  labor_impact text,
  operational_impact text,
  criticality priority NOT NULL,
  owner_user_id uuid,
  due_date date,
  status risk_status NOT NULL DEFAULT 'IDENTIFIED',
  created_at timestamptz(6) NOT NULL DEFAULT now(),
  updated_at timestamptz(6) NOT NULL DEFAULT now(),
  closed_at timestamptz(6),
  CONSTRAINT risks_tenant_id_fk FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE RESTRICT,
  CONSTRAINT risks_company_fk FOREIGN KEY (tenant_id, company_id)
    REFERENCES companies(tenant_id, id) ON DELETE RESTRICT,
  CONSTRAINT risks_branch_fk FOREIGN KEY (tenant_id, company_id, branch_id)
    REFERENCES branches(tenant_id, company_id, id) ON DELETE RESTRICT,
  CONSTRAINT risks_owner_fk FOREIGN KEY (tenant_id, owner_user_id)
    REFERENCES users(tenant_id, id) ON DELETE RESTRICT,
  CONSTRAINT risks_tenant_company_id_key UNIQUE (tenant_id, company_id, id),
  CONSTRAINT risks_title_not_blank CHECK (btrim(title) <> ''),
  CONSTRAINT risks_problem_not_blank CHECK (btrim(problem) <> ''),
  CONSTRAINT risks_financial_impact_nonnegative CHECK (financial_impact IS NULL OR financial_impact >= 0),
  CONSTRAINT risks_closed_at_consistency CHECK (
    (status = 'CLOSED' AND closed_at IS NOT NULL) OR (status <> 'CLOSED' AND closed_at IS NULL)
  )
);

CREATE INDEX risks_status_criticality_idx ON risks (tenant_id, company_id, status, criticality);
CREATE INDEX risks_owner_idx ON risks (tenant_id, owner_user_id, status);

CREATE TABLE action_plans (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL,
  company_id uuid NOT NULL,
  branch_id uuid,
  risk_id uuid,
  title varchar(180) NOT NULL,
  description text NOT NULL,
  owner_user_id uuid,
  priority priority NOT NULL DEFAULT 'IMPORTANT',
  status action_plan_status NOT NULL DEFAULT 'PLANNED',
  start_date date,
  due_date date NOT NULL,
  completed_at timestamptz(6),
  progress_percent integer NOT NULL DEFAULT 0,
  success_criteria text,
  created_at timestamptz(6) NOT NULL DEFAULT now(),
  updated_at timestamptz(6) NOT NULL DEFAULT now(),
  CONSTRAINT action_plans_tenant_id_fk FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE RESTRICT,
  CONSTRAINT action_plans_company_fk FOREIGN KEY (tenant_id, company_id)
    REFERENCES companies(tenant_id, id) ON DELETE RESTRICT,
  CONSTRAINT action_plans_branch_fk FOREIGN KEY (tenant_id, company_id, branch_id)
    REFERENCES branches(tenant_id, company_id, id) ON DELETE RESTRICT,
  CONSTRAINT action_plans_risk_fk FOREIGN KEY (tenant_id, company_id, risk_id)
    REFERENCES risks(tenant_id, company_id, id) ON DELETE RESTRICT,
  CONSTRAINT action_plans_owner_fk FOREIGN KEY (tenant_id, owner_user_id)
    REFERENCES users(tenant_id, id) ON DELETE RESTRICT,
  CONSTRAINT action_plans_tenant_company_id_key UNIQUE (tenant_id, company_id, id),
  CONSTRAINT action_plans_title_not_blank CHECK (btrim(title) <> ''),
  CONSTRAINT action_plans_description_not_blank CHECK (btrim(description) <> ''),
  CONSTRAINT action_plans_progress_range CHECK (progress_percent BETWEEN 0 AND 100),
  CONSTRAINT action_plans_dates_check CHECK (start_date IS NULL OR due_date >= start_date),
  CONSTRAINT action_plans_completion_consistency CHECK (
    (status = 'COMPLETED' AND completed_at IS NOT NULL AND progress_percent = 100) OR
    (status <> 'COMPLETED' AND completed_at IS NULL)
  )
);

CREATE INDEX action_plans_status_due_idx ON action_plans (tenant_id, company_id, status, due_date);
CREATE INDEX action_plans_owner_idx ON action_plans (tenant_id, owner_user_id, status);

CREATE TABLE audit_logs (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL,
  company_id uuid,
  branch_id uuid,
  actor_user_id uuid,
  session_id uuid,
  action varchar(100) NOT NULL,
  entity_type varchar(100) NOT NULL,
  entity_id uuid,
  before_data jsonb,
  after_data jsonb,
  metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
  redacted_fields text[] NOT NULL DEFAULT ARRAY[]::text[],
  ip_hash varchar(128),
  user_agent varchar(500),
  integrity_hash varchar(128),
  occurred_at timestamptz(6) NOT NULL DEFAULT now(),
  CONSTRAINT audit_logs_tenant_id_fk FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE RESTRICT,
  CONSTRAINT audit_logs_company_fk FOREIGN KEY (tenant_id, company_id)
    REFERENCES companies(tenant_id, id) ON DELETE RESTRICT,
  CONSTRAINT audit_logs_branch_fk FOREIGN KEY (tenant_id, company_id, branch_id)
    REFERENCES branches(tenant_id, company_id, id) ON DELETE RESTRICT,
  CONSTRAINT audit_logs_actor_fk FOREIGN KEY (tenant_id, actor_user_id)
    REFERENCES users(tenant_id, id) ON DELETE RESTRICT,
  CONSTRAINT audit_logs_tenant_id_id_key UNIQUE (tenant_id, id),
  CONSTRAINT audit_logs_company_branch_shape CHECK (branch_id IS NULL OR company_id IS NOT NULL),
  CONSTRAINT audit_logs_action_not_blank CHECK (btrim(action) <> ''),
  CONSTRAINT audit_logs_entity_type_not_blank CHECK (btrim(entity_type) <> ''),
  CONSTRAINT audit_logs_metadata_object CHECK (jsonb_typeof(metadata) = 'object')
);

CREATE INDEX audit_logs_company_time_idx ON audit_logs (tenant_id, company_id, occurred_at);
CREATE INDEX audit_logs_actor_time_idx ON audit_logs (tenant_id, actor_user_id, occurred_at);
CREATE INDEX audit_logs_entity_time_idx ON audit_logs (tenant_id, entity_type, entity_id, occurred_at);

-- Database context helpers. Missing context always means no rows, never all rows.
CREATE OR REPLACE FUNCTION app.current_tenant_id()
RETURNS uuid
LANGUAGE sql
STABLE
PARALLEL SAFE
AS $function$
  SELECT CASE
    WHEN NULLIF(current_setting('app.user_id', true), '') IS NULL THEN NULL
    ELSE NULLIF(current_setting('app.tenant_id', true), '')::uuid
  END
$function$;

CREATE OR REPLACE FUNCTION app.current_user_id()
RETURNS uuid
LANGUAGE sql
STABLE
PARALLEL SAFE
AS $function$
  SELECT NULLIF(current_setting('app.user_id', true), '')::uuid
$function$;

CREATE OR REPLACE FUNCTION app.has_tenant_wide_access()
RETURNS boolean
LANGUAGE sql
STABLE
PARALLEL SAFE
AS $function$
  SELECT COALESCE(NULLIF(current_setting('app.tenant_wide_access', true), '')::boolean, false)
$function$;

CREATE OR REPLACE FUNCTION app.can_access_company(candidate_company_id uuid)
RETURNS boolean
LANGUAGE sql
STABLE
PARALLEL SAFE
AS $function$
  SELECT candidate_company_id IS NOT NULL AND (
    app.has_tenant_wide_access() OR
    candidate_company_id = ANY (
      COALESCE(
        string_to_array(NULLIF(current_setting('app.company_ids', true), ''), ','),
        ARRAY[]::text[]
      )::uuid[]
    )
  )
$function$;

CREATE OR REPLACE FUNCTION app.set_updated_at()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
BEGIN
  NEW.updated_at := clock_timestamp();
  RETURN NEW;
END
$function$;

CREATE TRIGGER tenants_set_updated_at BEFORE UPDATE ON tenants
  FOR EACH ROW EXECUTE FUNCTION app.set_updated_at();
CREATE TRIGGER companies_set_updated_at BEFORE UPDATE ON companies
  FOR EACH ROW EXECUTE FUNCTION app.set_updated_at();
CREATE TRIGGER branches_set_updated_at BEFORE UPDATE ON branches
  FOR EACH ROW EXECUTE FUNCTION app.set_updated_at();
CREATE TRIGGER users_set_updated_at BEFORE UPDATE ON users
  FOR EACH ROW EXECUTE FUNCTION app.set_updated_at();
CREATE TRIGGER roles_set_updated_at BEFORE UPDATE ON roles
  FOR EACH ROW EXECUTE FUNCTION app.set_updated_at();
CREATE TRIGGER memberships_set_updated_at BEFORE UPDATE ON memberships
  FOR EACH ROW EXECUTE FUNCTION app.set_updated_at();
CREATE TRIGGER payables_set_updated_at BEFORE UPDATE ON payables
  FOR EACH ROW EXECUTE FUNCTION app.set_updated_at();
CREATE TRIGGER receivables_set_updated_at BEFORE UPDATE ON receivables
  FOR EACH ROW EXECUTE FUNCTION app.set_updated_at();
CREATE TRIGGER journal_entries_set_updated_at BEFORE UPDATE ON journal_entries
  FOR EACH ROW EXECUTE FUNCTION app.set_updated_at();
CREATE TRIGGER glossary_terms_set_updated_at BEFORE UPDATE ON glossary_terms
  FOR EACH ROW EXECUTE FUNCTION app.set_updated_at();
CREATE TRIGGER pending_items_set_updated_at BEFORE UPDATE ON pending_items
  FOR EACH ROW EXECUTE FUNCTION app.set_updated_at();
CREATE TRIGGER risks_set_updated_at BEFORE UPDATE ON risks
  FOR EACH ROW EXECUTE FUNCTION app.set_updated_at();
CREATE TRIGGER action_plans_set_updated_at BEFORE UPDATE ON action_plans
  FOR EACH ROW EXECUTE FUNCTION app.set_updated_at();

-- Posted journal entries are immutable; correction is performed with a reversal.
CREATE OR REPLACE FUNCTION app.guard_journal_line_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
DECLARE
  old_entry_status journal_entry_status;
  new_entry_status journal_entry_status;
BEGIN
  IF TG_OP = 'UPDATE' AND (
    NEW.tenant_id IS DISTINCT FROM OLD.tenant_id OR
    NEW.company_id IS DISTINCT FROM OLD.company_id OR
    NEW.journal_entry_id IS DISTINCT FROM OLD.journal_entry_id
  ) THEN
    RAISE EXCEPTION 'reparenting a journal entry line is forbidden'
      USING ERRCODE = 'integrity_constraint_violation';
  END IF;

  IF TG_OP IN ('UPDATE', 'DELETE') THEN
    SELECT status INTO old_entry_status
      FROM journal_entries
     WHERE tenant_id = OLD.tenant_id
       AND company_id = OLD.company_id
       AND id = OLD.journal_entry_id;
  END IF;

  IF TG_OP IN ('INSERT', 'UPDATE') THEN
    SELECT status INTO new_entry_status
      FROM journal_entries
     WHERE tenant_id = NEW.tenant_id
       AND company_id = NEW.company_id
       AND id = NEW.journal_entry_id;
  END IF;

  IF
    (TG_OP IN ('UPDATE', 'DELETE') AND old_entry_status IS DISTINCT FROM 'DRAFT'::journal_entry_status) OR
    (TG_OP IN ('INSERT', 'UPDATE') AND new_entry_status IS DISTINCT FROM 'DRAFT'::journal_entry_status)
  THEN
    RAISE EXCEPTION 'lines of a posted/reversed journal entry are immutable'
      USING ERRCODE = 'integrity_constraint_violation';
  END IF;
  RETURN COALESCE(NEW, OLD);
END
$function$;

CREATE TRIGGER journal_entry_lines_guard
  BEFORE INSERT OR UPDATE OR DELETE ON journal_entry_lines
  FOR EACH ROW EXECUTE FUNCTION app.guard_journal_line_mutation();

CREATE OR REPLACE FUNCTION app.guard_journal_entry_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
BEGIN
  IF TG_OP = 'DELETE' THEN
    IF OLD.status IS DISTINCT FROM 'DRAFT'::journal_entry_status THEN
      RAISE EXCEPTION 'posted/reversed journal entry headers are immutable'
        USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN OLD;
  END IF;

  IF
    NEW.id IS DISTINCT FROM OLD.id OR
    NEW.tenant_id IS DISTINCT FROM OLD.tenant_id OR
    NEW.company_id IS DISTINCT FROM OLD.company_id OR
    NEW.created_by_id IS DISTINCT FROM OLD.created_by_id OR
    NEW.created_at IS DISTINCT FROM OLD.created_at
  THEN
    RAISE EXCEPTION 'journal entry identity and ownership are immutable'
      USING ERRCODE = 'integrity_constraint_violation';
  END IF;

  IF OLD.status = 'DRAFT'::journal_entry_status THEN
    IF NEW.status NOT IN ('DRAFT'::journal_entry_status, 'POSTED'::journal_entry_status) THEN
      RAISE EXCEPTION 'invalid journal entry transition from DRAFT to %', NEW.status
        USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
  END IF;

  IF OLD.status = 'REVERSED'::journal_entry_status THEN
    RAISE EXCEPTION 'reversed journal entry headers are immutable'
      USING ERRCODE = 'integrity_constraint_violation';
  END IF;

  -- A posted entry may only be marked REVERSED. Every business/header field
  -- remains unchanged and a separate posted reversing entry must already exist.
  IF
    NEW.status IS DISTINCT FROM 'REVERSED'::journal_entry_status OR
    NEW.branch_id IS DISTINCT FROM OLD.branch_id OR
    NEW.entry_number IS DISTINCT FROM OLD.entry_number OR
    NEW.entry_date IS DISTINCT FROM OLD.entry_date OR
    NEW.description IS DISTINCT FROM OLD.description OR
    NEW.source_type IS DISTINCT FROM OLD.source_type OR
    NEW.source_id IS DISTINCT FROM OLD.source_id OR
    NEW.posted_at IS DISTINCT FROM OLD.posted_at OR
    NEW.reverses_entry_id IS DISTINCT FROM OLD.reverses_entry_id
  THEN
    RAISE EXCEPTION 'posted journal entry headers are immutable; use a reversal entry'
      USING ERRCODE = 'integrity_constraint_violation';
  END IF;

  IF NOT EXISTS (
    SELECT 1
      FROM journal_entries reversal
     WHERE reversal.tenant_id = OLD.tenant_id
       AND reversal.company_id = OLD.company_id
       AND reversal.reverses_entry_id = OLD.id
       AND reversal.status = 'POSTED'::journal_entry_status
  ) THEN
    RAISE EXCEPTION 'a posted reversal entry is required before marking entry % as REVERSED', OLD.id
      USING ERRCODE = 'integrity_constraint_violation';
  END IF;

  RETURN NEW;
END
$function$;

CREATE TRIGGER journal_entries_immutability_guard
  BEFORE UPDATE OR DELETE ON journal_entries
  FOR EACH ROW EXECUTE FUNCTION app.guard_journal_entry_mutation();

CREATE OR REPLACE FUNCTION app.assert_journal_entry_balanced()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
DECLARE
  target_tenant uuid := COALESCE(NEW.tenant_id, OLD.tenant_id);
  target_company uuid := COALESCE(NEW.company_id, OLD.company_id);
  target_entry uuid := COALESCE(NEW.id, OLD.id);
  total_debit numeric(19,4);
  total_credit numeric(19,4);
  line_count integer;
BEGIN
  IF TG_TABLE_NAME = 'journal_entry_lines' THEN
    target_entry := COALESCE(NEW.journal_entry_id, OLD.journal_entry_id);
  END IF;

  IF EXISTS (
    SELECT 1 FROM journal_entries
     WHERE tenant_id = target_tenant
       AND company_id = target_company
       AND id = target_entry
       AND status IN ('POSTED', 'REVERSED')
  ) THEN
    SELECT COALESCE(sum(debit), 0), COALESCE(sum(credit), 0), count(*)
      INTO total_debit, total_credit, line_count
      FROM journal_entry_lines
     WHERE tenant_id = target_tenant
       AND company_id = target_company
       AND journal_entry_id = target_entry;

    IF line_count < 2 OR total_debit <> total_credit THEN
      RAISE EXCEPTION 'journal entry % is not balanced (debit %, credit %, lines %)',
        target_entry, total_debit, total_credit, line_count
        USING ERRCODE = 'integrity_constraint_violation';
    END IF;
  END IF;
  RETURN NULL;
END
$function$;

CREATE CONSTRAINT TRIGGER journal_entries_balance_check
  AFTER INSERT OR UPDATE ON journal_entries
  DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION app.assert_journal_entry_balanced();

CREATE CONSTRAINT TRIGGER journal_entry_lines_balance_check
  AFTER INSERT OR UPDATE OR DELETE ON journal_entry_lines
  DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION app.assert_journal_entry_balanced();

-- Audit events are append-only, including for the table owner.
CREATE OR REPLACE FUNCTION app.prevent_audit_log_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
BEGIN
  RAISE EXCEPTION 'audit logs are append-only'
    USING ERRCODE = 'integrity_constraint_violation';
END
$function$;

CREATE TRIGGER audit_logs_immutable
  BEFORE UPDATE OR DELETE ON audit_logs
  FOR EACH ROW EXECUTE FUNCTION app.prevent_audit_log_mutation();

-- Row-level security. The owner is used only for migrations/seeding. FORCE RLS
-- ensures the policies also apply to ordinary table-owner sessions; superusers
-- still bypass RLS and therefore must never be used by the running application.
ALTER TABLE tenants ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenants FORCE ROW LEVEL SECURITY;
CREATE POLICY tenants_isolation ON tenants TO PUBLIC
  USING (id = app.current_tenant_id())
  WITH CHECK (id = app.current_tenant_id());

DO $rls_tenant$
DECLARE
  table_name text;
BEGIN
  FOREACH table_name IN ARRAY ARRAY[
    'roles', 'permissions', 'role_permissions', 'glossary_terms'
  ]
  LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', table_name);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', table_name);
    EXECUTE format(
      'CREATE POLICY tenant_isolation ON %I TO PUBLIC USING (tenant_id = app.current_tenant_id()) WITH CHECK (tenant_id = app.current_tenant_id())',
      table_name
    );
  END LOOP;
END
$rls_tenant$;

DO $rls_company$
DECLARE
  table_name text;
BEGIN
  FOREACH table_name IN ARRAY ARRAY[
    'branches', 'payables', 'receivables', 'journal_entries',
    'journal_entry_lines', 'pending_items', 'risks', 'action_plans'
  ]
  LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', table_name);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', table_name);
    EXECUTE format(
      'CREATE POLICY company_isolation ON %I TO PUBLIC USING (tenant_id = app.current_tenant_id() AND app.can_access_company(company_id)) WITH CHECK (tenant_id = app.current_tenant_id() AND app.can_access_company(company_id))',
      table_name
    );
  END LOOP;
END
$rls_company$;

ALTER TABLE companies ENABLE ROW LEVEL SECURITY;
ALTER TABLE companies FORCE ROW LEVEL SECURITY;
CREATE POLICY companies_isolation ON companies TO PUBLIC
  USING (tenant_id = app.current_tenant_id() AND app.can_access_company(id))
  WITH CHECK (tenant_id = app.current_tenant_id() AND app.can_access_company(id));

ALTER TABLE memberships ENABLE ROW LEVEL SECURITY;
ALTER TABLE memberships FORCE ROW LEVEL SECURITY;
CREATE POLICY memberships_isolation ON memberships TO PUBLIC
  USING (
    tenant_id = app.current_tenant_id() AND (
      user_id = app.current_user_id() OR
      app.has_tenant_wide_access() OR
      app.can_access_company(company_id)
    )
  )
  WITH CHECK (
    tenant_id = app.current_tenant_id() AND (
      app.has_tenant_wide_access() OR app.can_access_company(company_id)
    )
  );

ALTER TABLE users ENABLE ROW LEVEL SECURITY;
ALTER TABLE users FORCE ROW LEVEL SECURITY;
CREATE POLICY users_isolation ON users TO PUBLIC
  USING (
    tenant_id = app.current_tenant_id() AND (
      id = app.current_user_id() OR
      app.has_tenant_wide_access() OR
      EXISTS (
        SELECT 1
          FROM memberships membership
         WHERE membership.tenant_id = users.tenant_id
           AND membership.user_id = users.id
           AND membership.is_active
           AND app.can_access_company(membership.company_id)
      )
    )
  )
  WITH CHECK (
    tenant_id = app.current_tenant_id() AND (
      id = app.current_user_id() OR app.has_tenant_wide_access()
    )
  );

ALTER TABLE audit_logs ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit_logs FORCE ROW LEVEL SECURITY;
CREATE POLICY audit_logs_select ON audit_logs FOR SELECT TO PUBLIC
  USING (
    tenant_id = app.current_tenant_id() AND (
      app.has_tenant_wide_access() OR app.can_access_company(company_id)
    )
  );
CREATE POLICY audit_logs_insert ON audit_logs FOR INSERT TO PUBLIC
  WITH CHECK (
    tenant_id = app.current_tenant_id() AND
    (app.has_tenant_wide_access() OR app.can_access_company(company_id)) AND
    (actor_user_id IS NULL OR actor_user_id = app.current_user_id())
  );

GRANT USAGE ON SCHEMA public, app TO clarity_app;
GRANT USAGE ON TYPE
  tenant_status,
  company_status,
  membership_scope,
  financial_status,
  journal_entry_status,
  glossary_category,
  priority,
  work_status,
  risk_level,
  risk_status,
  action_plan_status
TO clarity_app;
GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA app TO clarity_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO clarity_app;
REVOKE UPDATE, DELETE ON audit_logs FROM clarity_app;

ALTER DEFAULT PRIVILEGES IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO clarity_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
  GRANT USAGE ON TYPES TO clarity_app;

COMMENT ON ROLE clarity_app IS
  'Runtime role without BYPASSRLS. Login roles should be granted this role and SET ROLE before queries.';
COMMENT ON SCHEMA app IS
  'Trusted per-transaction authentication context and database integrity helpers.';
COMMENT ON TABLE audit_logs IS
  'Append-only business audit trail; secrets and full sensitive documents must not be stored here.';
