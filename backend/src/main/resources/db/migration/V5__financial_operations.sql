-- Phase 2: operational finance catalogs plus append-only payment/receipt
-- events. Payable/receivable balances remain projections derived from events.

CREATE TYPE counterparty_kind AS ENUM ('SUPPLIER', 'CUSTOMER', 'BOTH');
CREATE TYPE financial_direction AS ENUM ('PAYABLE', 'RECEIVABLE', 'BOTH');
CREATE TYPE financial_account_type AS ENUM (
  'CASH',
  'CHECKING',
  'SAVINGS',
  'PAYMENT',
  'OTHER'
);

CREATE TABLE counterparties (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL,
  company_id uuid NOT NULL,
  branch_id uuid,
  code varchar(40) NOT NULL,
  name varchar(180) NOT NULL,
  tax_id varchar(24),
  kind counterparty_kind NOT NULL DEFAULT 'BOTH',
  is_active boolean NOT NULL DEFAULT true,
  created_by_id uuid NOT NULL,
  created_at timestamptz(6) NOT NULL DEFAULT now(),
  updated_at timestamptz(6) NOT NULL DEFAULT now(),
  deleted_at timestamptz(6),
  CONSTRAINT counterparties_tenant_fk FOREIGN KEY (tenant_id)
    REFERENCES tenants(id) ON DELETE RESTRICT,
  CONSTRAINT counterparties_company_fk FOREIGN KEY (tenant_id, company_id)
    REFERENCES companies(tenant_id, id) ON DELETE RESTRICT,
  CONSTRAINT counterparties_branch_fk FOREIGN KEY (tenant_id, company_id, branch_id)
    REFERENCES branches(tenant_id, company_id, id) ON DELETE RESTRICT,
  CONSTRAINT counterparties_created_by_fk FOREIGN KEY (tenant_id, created_by_id)
    REFERENCES users(tenant_id, id) ON DELETE RESTRICT,
  CONSTRAINT counterparties_tenant_company_id_key
    UNIQUE (tenant_id, company_id, id),
  CONSTRAINT counterparties_tenant_company_code_key
    UNIQUE (tenant_id, company_id, code),
  CONSTRAINT counterparties_code_not_blank CHECK (btrim(code) <> ''),
  CONSTRAINT counterparties_name_not_blank CHECK (btrim(name) <> '')
);

CREATE INDEX counterparties_name_idx
  ON counterparties (tenant_id, company_id, name);

CREATE TABLE financial_categories (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL,
  company_id uuid NOT NULL,
  branch_id uuid,
  code varchar(40) NOT NULL,
  name varchar(160) NOT NULL,
  direction financial_direction NOT NULL DEFAULT 'BOTH',
  is_active boolean NOT NULL DEFAULT true,
  created_by_id uuid NOT NULL,
  created_at timestamptz(6) NOT NULL DEFAULT now(),
  updated_at timestamptz(6) NOT NULL DEFAULT now(),
  deleted_at timestamptz(6),
  CONSTRAINT financial_categories_tenant_fk FOREIGN KEY (tenant_id)
    REFERENCES tenants(id) ON DELETE RESTRICT,
  CONSTRAINT financial_categories_company_fk FOREIGN KEY (tenant_id, company_id)
    REFERENCES companies(tenant_id, id) ON DELETE RESTRICT,
  CONSTRAINT financial_categories_branch_fk FOREIGN KEY (tenant_id, company_id, branch_id)
    REFERENCES branches(tenant_id, company_id, id) ON DELETE RESTRICT,
  CONSTRAINT financial_categories_created_by_fk FOREIGN KEY (tenant_id, created_by_id)
    REFERENCES users(tenant_id, id) ON DELETE RESTRICT,
  CONSTRAINT financial_categories_tenant_company_id_key
    UNIQUE (tenant_id, company_id, id),
  CONSTRAINT financial_categories_tenant_company_code_key
    UNIQUE (tenant_id, company_id, code),
  CONSTRAINT financial_categories_code_not_blank CHECK (btrim(code) <> ''),
  CONSTRAINT financial_categories_name_not_blank CHECK (btrim(name) <> '')
);

CREATE INDEX financial_categories_name_idx
  ON financial_categories (tenant_id, company_id, name);

CREATE TABLE cost_centers (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL,
  company_id uuid NOT NULL,
  branch_id uuid,
  code varchar(40) NOT NULL,
  name varchar(160) NOT NULL,
  is_active boolean NOT NULL DEFAULT true,
  created_by_id uuid NOT NULL,
  created_at timestamptz(6) NOT NULL DEFAULT now(),
  updated_at timestamptz(6) NOT NULL DEFAULT now(),
  deleted_at timestamptz(6),
  CONSTRAINT cost_centers_tenant_fk FOREIGN KEY (tenant_id)
    REFERENCES tenants(id) ON DELETE RESTRICT,
  CONSTRAINT cost_centers_company_fk FOREIGN KEY (tenant_id, company_id)
    REFERENCES companies(tenant_id, id) ON DELETE RESTRICT,
  CONSTRAINT cost_centers_branch_fk FOREIGN KEY (tenant_id, company_id, branch_id)
    REFERENCES branches(tenant_id, company_id, id) ON DELETE RESTRICT,
  CONSTRAINT cost_centers_created_by_fk FOREIGN KEY (tenant_id, created_by_id)
    REFERENCES users(tenant_id, id) ON DELETE RESTRICT,
  CONSTRAINT cost_centers_tenant_company_id_key
    UNIQUE (tenant_id, company_id, id),
  CONSTRAINT cost_centers_tenant_company_code_key
    UNIQUE (tenant_id, company_id, code),
  CONSTRAINT cost_centers_code_not_blank CHECK (btrim(code) <> ''),
  CONSTRAINT cost_centers_name_not_blank CHECK (btrim(name) <> '')
);

CREATE INDEX cost_centers_name_idx
  ON cost_centers (tenant_id, company_id, name);

CREATE TABLE financial_accounts (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL,
  company_id uuid NOT NULL,
  branch_id uuid,
  code varchar(40) NOT NULL,
  name varchar(160) NOT NULL,
  type financial_account_type NOT NULL,
  currency_code char(3) NOT NULL DEFAULT 'BRL',
  is_active boolean NOT NULL DEFAULT true,
  created_by_id uuid NOT NULL,
  created_at timestamptz(6) NOT NULL DEFAULT now(),
  updated_at timestamptz(6) NOT NULL DEFAULT now(),
  deleted_at timestamptz(6),
  CONSTRAINT financial_accounts_tenant_fk FOREIGN KEY (tenant_id)
    REFERENCES tenants(id) ON DELETE RESTRICT,
  CONSTRAINT financial_accounts_company_fk FOREIGN KEY (tenant_id, company_id)
    REFERENCES companies(tenant_id, id) ON DELETE RESTRICT,
  CONSTRAINT financial_accounts_branch_fk FOREIGN KEY (tenant_id, company_id, branch_id)
    REFERENCES branches(tenant_id, company_id, id) ON DELETE RESTRICT,
  CONSTRAINT financial_accounts_created_by_fk FOREIGN KEY (tenant_id, created_by_id)
    REFERENCES users(tenant_id, id) ON DELETE RESTRICT,
  CONSTRAINT financial_accounts_tenant_company_id_key
    UNIQUE (tenant_id, company_id, id),
  CONSTRAINT financial_accounts_tenant_company_code_key
    UNIQUE (tenant_id, company_id, code),
  CONSTRAINT financial_accounts_code_not_blank CHECK (btrim(code) <> ''),
  CONSTRAINT financial_accounts_name_not_blank CHECK (btrim(name) <> ''),
  CONSTRAINT financial_accounts_currency_format
    CHECK (currency_code ~ '^[A-Z]{3}$')
);

CREATE INDEX financial_accounts_name_idx
  ON financial_accounts (tenant_id, company_id, name);

ALTER TABLE payables
  ADD COLUMN counterparty_id uuid,
  ADD COLUMN category_id uuid,
  ADD COLUMN cost_center_id uuid,
  ADD CONSTRAINT payables_counterparty_fk
    FOREIGN KEY (tenant_id, company_id, counterparty_id)
    REFERENCES counterparties(tenant_id, company_id, id) ON DELETE RESTRICT,
  ADD CONSTRAINT payables_category_fk
    FOREIGN KEY (tenant_id, company_id, category_id)
    REFERENCES financial_categories(tenant_id, company_id, id) ON DELETE RESTRICT,
  ADD CONSTRAINT payables_cost_center_fk
    FOREIGN KEY (tenant_id, company_id, cost_center_id)
    REFERENCES cost_centers(tenant_id, company_id, id) ON DELETE RESTRICT;

ALTER TABLE receivables
  ADD COLUMN counterparty_id uuid,
  ADD COLUMN category_id uuid,
  ADD COLUMN cost_center_id uuid,
  ADD CONSTRAINT receivables_counterparty_fk
    FOREIGN KEY (tenant_id, company_id, counterparty_id)
    REFERENCES counterparties(tenant_id, company_id, id) ON DELETE RESTRICT,
  ADD CONSTRAINT receivables_category_fk
    FOREIGN KEY (tenant_id, company_id, category_id)
    REFERENCES financial_categories(tenant_id, company_id, id) ON DELETE RESTRICT,
  ADD CONSTRAINT receivables_cost_center_fk
    FOREIGN KEY (tenant_id, company_id, cost_center_id)
    REFERENCES cost_centers(tenant_id, company_id, id) ON DELETE RESTRICT;

CREATE INDEX payables_catalog_idx
  ON payables (tenant_id, company_id, counterparty_id, category_id, cost_center_id);
CREATE INDEX receivables_catalog_idx
  ON receivables (tenant_id, company_id, counterparty_id, category_id, cost_center_id);

CREATE TABLE payments (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL,
  company_id uuid NOT NULL,
  branch_id uuid,
  payable_id uuid NOT NULL,
  financial_account_id uuid NOT NULL,
  principal_amount numeric(19,4) NOT NULL,
  interest_amount numeric(19,4) NOT NULL DEFAULT 0,
  fine_amount numeric(19,4) NOT NULL DEFAULT 0,
  discount_amount numeric(19,4) NOT NULL DEFAULT 0,
  cash_amount numeric(19,4) NOT NULL,
  occurred_at timestamptz(6) NOT NULL,
  note varchar(500),
  created_by_id uuid NOT NULL,
  created_at timestamptz(6) NOT NULL DEFAULT now(),
  CONSTRAINT payments_tenant_fk FOREIGN KEY (tenant_id)
    REFERENCES tenants(id) ON DELETE RESTRICT,
  CONSTRAINT payments_company_fk FOREIGN KEY (tenant_id, company_id)
    REFERENCES companies(tenant_id, id) ON DELETE RESTRICT,
  CONSTRAINT payments_branch_fk FOREIGN KEY (tenant_id, company_id, branch_id)
    REFERENCES branches(tenant_id, company_id, id) ON DELETE RESTRICT,
  CONSTRAINT payments_payable_fk FOREIGN KEY (tenant_id, company_id, payable_id)
    REFERENCES payables(tenant_id, company_id, id) ON DELETE RESTRICT,
  CONSTRAINT payments_financial_account_fk
    FOREIGN KEY (tenant_id, company_id, financial_account_id)
    REFERENCES financial_accounts(tenant_id, company_id, id) ON DELETE RESTRICT,
  CONSTRAINT payments_created_by_fk FOREIGN KEY (tenant_id, created_by_id)
    REFERENCES users(tenant_id, id) ON DELETE RESTRICT,
  CONSTRAINT payments_tenant_company_id_key
    UNIQUE (tenant_id, company_id, id),
  CONSTRAINT payments_principal_positive CHECK (principal_amount > 0),
  CONSTRAINT payments_adjustments_nonnegative CHECK (
    interest_amount >= 0 AND fine_amount >= 0 AND discount_amount >= 0
  ),
  CONSTRAINT payments_cash_nonnegative CHECK (cash_amount >= 0),
  CONSTRAINT payments_cash_consistency CHECK (
    cash_amount = principal_amount + interest_amount + fine_amount - discount_amount
  ),
  CONSTRAINT payments_discount_limit CHECK (
    discount_amount <= principal_amount + interest_amount + fine_amount
  )
);

CREATE INDEX payments_payable_time_idx
  ON payments (tenant_id, company_id, payable_id, occurred_at);

CREATE TABLE payment_reversals (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL,
  company_id uuid NOT NULL,
  branch_id uuid,
  payment_id uuid NOT NULL,
  reason varchar(500) NOT NULL,
  occurred_at timestamptz(6) NOT NULL,
  created_by_id uuid NOT NULL,
  created_at timestamptz(6) NOT NULL DEFAULT now(),
  CONSTRAINT payment_reversals_tenant_fk FOREIGN KEY (tenant_id)
    REFERENCES tenants(id) ON DELETE RESTRICT,
  CONSTRAINT payment_reversals_company_fk FOREIGN KEY (tenant_id, company_id)
    REFERENCES companies(tenant_id, id) ON DELETE RESTRICT,
  CONSTRAINT payment_reversals_branch_fk
    FOREIGN KEY (tenant_id, company_id, branch_id)
    REFERENCES branches(tenant_id, company_id, id) ON DELETE RESTRICT,
  CONSTRAINT payment_reversals_payment_fk
    FOREIGN KEY (tenant_id, company_id, payment_id)
    REFERENCES payments(tenant_id, company_id, id) ON DELETE RESTRICT,
  CONSTRAINT payment_reversals_created_by_fk FOREIGN KEY (tenant_id, created_by_id)
    REFERENCES users(tenant_id, id) ON DELETE RESTRICT,
  CONSTRAINT payment_reversals_tenant_company_id_key
    UNIQUE (tenant_id, company_id, id),
  CONSTRAINT payment_reversals_payment_key
    UNIQUE (tenant_id, company_id, payment_id),
  CONSTRAINT payment_reversals_reason_not_blank CHECK (btrim(reason) <> '')
);

CREATE INDEX payment_reversals_time_idx
  ON payment_reversals (tenant_id, company_id, occurred_at);

CREATE TABLE receipts (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL,
  company_id uuid NOT NULL,
  branch_id uuid,
  receivable_id uuid NOT NULL,
  financial_account_id uuid NOT NULL,
  principal_amount numeric(19,4) NOT NULL,
  interest_amount numeric(19,4) NOT NULL DEFAULT 0,
  fine_amount numeric(19,4) NOT NULL DEFAULT 0,
  discount_amount numeric(19,4) NOT NULL DEFAULT 0,
  cash_amount numeric(19,4) NOT NULL,
  occurred_at timestamptz(6) NOT NULL,
  note varchar(500),
  created_by_id uuid NOT NULL,
  created_at timestamptz(6) NOT NULL DEFAULT now(),
  CONSTRAINT receipts_tenant_fk FOREIGN KEY (tenant_id)
    REFERENCES tenants(id) ON DELETE RESTRICT,
  CONSTRAINT receipts_company_fk FOREIGN KEY (tenant_id, company_id)
    REFERENCES companies(tenant_id, id) ON DELETE RESTRICT,
  CONSTRAINT receipts_branch_fk FOREIGN KEY (tenant_id, company_id, branch_id)
    REFERENCES branches(tenant_id, company_id, id) ON DELETE RESTRICT,
  CONSTRAINT receipts_receivable_fk
    FOREIGN KEY (tenant_id, company_id, receivable_id)
    REFERENCES receivables(tenant_id, company_id, id) ON DELETE RESTRICT,
  CONSTRAINT receipts_financial_account_fk
    FOREIGN KEY (tenant_id, company_id, financial_account_id)
    REFERENCES financial_accounts(tenant_id, company_id, id) ON DELETE RESTRICT,
  CONSTRAINT receipts_created_by_fk FOREIGN KEY (tenant_id, created_by_id)
    REFERENCES users(tenant_id, id) ON DELETE RESTRICT,
  CONSTRAINT receipts_tenant_company_id_key
    UNIQUE (tenant_id, company_id, id),
  CONSTRAINT receipts_principal_positive CHECK (principal_amount > 0),
  CONSTRAINT receipts_adjustments_nonnegative CHECK (
    interest_amount >= 0 AND fine_amount >= 0 AND discount_amount >= 0
  ),
  CONSTRAINT receipts_cash_nonnegative CHECK (cash_amount >= 0),
  CONSTRAINT receipts_cash_consistency CHECK (
    cash_amount = principal_amount + interest_amount + fine_amount - discount_amount
  ),
  CONSTRAINT receipts_discount_limit CHECK (
    discount_amount <= principal_amount + interest_amount + fine_amount
  )
);

CREATE INDEX receipts_receivable_time_idx
  ON receipts (tenant_id, company_id, receivable_id, occurred_at);

CREATE TABLE receipt_reversals (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL,
  company_id uuid NOT NULL,
  branch_id uuid,
  receipt_id uuid NOT NULL,
  reason varchar(500) NOT NULL,
  occurred_at timestamptz(6) NOT NULL,
  created_by_id uuid NOT NULL,
  created_at timestamptz(6) NOT NULL DEFAULT now(),
  CONSTRAINT receipt_reversals_tenant_fk FOREIGN KEY (tenant_id)
    REFERENCES tenants(id) ON DELETE RESTRICT,
  CONSTRAINT receipt_reversals_company_fk FOREIGN KEY (tenant_id, company_id)
    REFERENCES companies(tenant_id, id) ON DELETE RESTRICT,
  CONSTRAINT receipt_reversals_branch_fk
    FOREIGN KEY (tenant_id, company_id, branch_id)
    REFERENCES branches(tenant_id, company_id, id) ON DELETE RESTRICT,
  CONSTRAINT receipt_reversals_receipt_fk
    FOREIGN KEY (tenant_id, company_id, receipt_id)
    REFERENCES receipts(tenant_id, company_id, id) ON DELETE RESTRICT,
  CONSTRAINT receipt_reversals_created_by_fk FOREIGN KEY (tenant_id, created_by_id)
    REFERENCES users(tenant_id, id) ON DELETE RESTRICT,
  CONSTRAINT receipt_reversals_tenant_company_id_key
    UNIQUE (tenant_id, company_id, id),
  CONSTRAINT receipt_reversals_receipt_key
    UNIQUE (tenant_id, company_id, receipt_id),
  CONSTRAINT receipt_reversals_reason_not_blank CHECK (btrim(reason) <> '')
);

CREATE INDEX receipt_reversals_time_idx
  ON receipt_reversals (tenant_id, company_id, occurred_at);

CREATE TRIGGER counterparties_set_updated_at BEFORE UPDATE ON counterparties
  FOR EACH ROW EXECUTE FUNCTION app.set_updated_at();
CREATE TRIGGER financial_categories_set_updated_at BEFORE UPDATE ON financial_categories
  FOR EACH ROW EXECUTE FUNCTION app.set_updated_at();
CREATE TRIGGER cost_centers_set_updated_at BEFORE UPDATE ON cost_centers
  FOR EACH ROW EXECUTE FUNCTION app.set_updated_at();
CREATE TRIGGER financial_accounts_set_updated_at BEFORE UPDATE ON financial_accounts
  FOR EACH ROW EXECUTE FUNCTION app.set_updated_at();

CREATE OR REPLACE FUNCTION app.assert_payment_scope()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
DECLARE
  target_branch uuid;
  target_currency char(3);
  account_branch uuid;
  account_currency char(3);
BEGIN
  SELECT payable.branch_id, payable.currency_code
    INTO target_branch, target_currency
    FROM payables payable
   WHERE payable.tenant_id = NEW.tenant_id
     AND payable.company_id = NEW.company_id
     AND payable.id = NEW.payable_id
     AND payable.deleted_at IS NULL;

  IF NOT FOUND THEN
    RAISE EXCEPTION 'payable is not available in the authenticated scope'
      USING ERRCODE = 'foreign_key_violation';
  END IF;

  SELECT account.branch_id, account.currency_code
    INTO account_branch, account_currency
    FROM financial_accounts account
   WHERE account.tenant_id = NEW.tenant_id
     AND account.company_id = NEW.company_id
     AND account.id = NEW.financial_account_id
     AND account.is_active
     AND account.deleted_at IS NULL;

  IF NOT FOUND THEN
    RAISE EXCEPTION 'financial account is not available in the authenticated scope'
      USING ERRCODE = 'foreign_key_violation';
  END IF;

  IF NEW.branch_id IS DISTINCT FROM target_branch THEN
    RAISE EXCEPTION 'payment branch differs from payable branch'
      USING ERRCODE = 'check_violation';
  END IF;
  IF account_branch IS NOT NULL AND account_branch IS DISTINCT FROM target_branch THEN
    RAISE EXCEPTION 'financial account branch differs from payable branch'
      USING ERRCODE = 'check_violation';
  END IF;
  IF account_currency <> target_currency THEN
    RAISE EXCEPTION 'financial account currency differs from payable currency'
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END
$function$;

CREATE OR REPLACE FUNCTION app.assert_receipt_scope()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
DECLARE
  target_branch uuid;
  target_currency char(3);
  account_branch uuid;
  account_currency char(3);
BEGIN
  SELECT receivable.branch_id, receivable.currency_code
    INTO target_branch, target_currency
    FROM receivables receivable
   WHERE receivable.tenant_id = NEW.tenant_id
     AND receivable.company_id = NEW.company_id
     AND receivable.id = NEW.receivable_id
     AND receivable.deleted_at IS NULL;

  IF NOT FOUND THEN
    RAISE EXCEPTION 'receivable is not available in the authenticated scope'
      USING ERRCODE = 'foreign_key_violation';
  END IF;

  SELECT account.branch_id, account.currency_code
    INTO account_branch, account_currency
    FROM financial_accounts account
   WHERE account.tenant_id = NEW.tenant_id
     AND account.company_id = NEW.company_id
     AND account.id = NEW.financial_account_id
     AND account.is_active
     AND account.deleted_at IS NULL;

  IF NOT FOUND THEN
    RAISE EXCEPTION 'financial account is not available in the authenticated scope'
      USING ERRCODE = 'foreign_key_violation';
  END IF;

  IF NEW.branch_id IS DISTINCT FROM target_branch THEN
    RAISE EXCEPTION 'receipt branch differs from receivable branch'
      USING ERRCODE = 'check_violation';
  END IF;
  IF account_branch IS NOT NULL AND account_branch IS DISTINCT FROM target_branch THEN
    RAISE EXCEPTION 'financial account branch differs from receivable branch'
      USING ERRCODE = 'check_violation';
  END IF;
  IF account_currency <> target_currency THEN
    RAISE EXCEPTION 'financial account currency differs from receivable currency'
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END
$function$;

CREATE OR REPLACE FUNCTION app.assert_payment_reversal_scope()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
DECLARE
  target_branch uuid;
BEGIN
  SELECT payment.branch_id
    INTO target_branch
    FROM payments payment
   WHERE payment.tenant_id = NEW.tenant_id
     AND payment.company_id = NEW.company_id
     AND payment.id = NEW.payment_id;
  IF NOT FOUND OR NEW.branch_id IS DISTINCT FROM target_branch THEN
    RAISE EXCEPTION 'payment reversal scope differs from payment scope'
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END
$function$;

CREATE OR REPLACE FUNCTION app.assert_receipt_reversal_scope()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
DECLARE
  target_branch uuid;
BEGIN
  SELECT receipt.branch_id
    INTO target_branch
    FROM receipts receipt
   WHERE receipt.tenant_id = NEW.tenant_id
     AND receipt.company_id = NEW.company_id
     AND receipt.id = NEW.receipt_id;
  IF NOT FOUND OR NEW.branch_id IS DISTINCT FROM target_branch THEN
    RAISE EXCEPTION 'receipt reversal scope differs from receipt scope'
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END
$function$;

CREATE TRIGGER payments_scope_guard BEFORE INSERT ON payments
  FOR EACH ROW EXECUTE FUNCTION app.assert_payment_scope();
CREATE TRIGGER receipts_scope_guard BEFORE INSERT ON receipts
  FOR EACH ROW EXECUTE FUNCTION app.assert_receipt_scope();
CREATE TRIGGER payment_reversals_scope_guard BEFORE INSERT ON payment_reversals
  FOR EACH ROW EXECUTE FUNCTION app.assert_payment_reversal_scope();
CREATE TRIGGER receipt_reversals_scope_guard BEFORE INSERT ON receipt_reversals
  FOR EACH ROW EXECUTE FUNCTION app.assert_receipt_reversal_scope();

CREATE OR REPLACE FUNCTION app.prevent_financial_event_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
BEGIN
  RAISE EXCEPTION 'financial events are append-only'
    USING ERRCODE = 'integrity_constraint_violation';
END
$function$;

CREATE TRIGGER payments_immutable BEFORE UPDATE OR DELETE ON payments
  FOR EACH ROW EXECUTE FUNCTION app.prevent_financial_event_mutation();
CREATE TRIGGER payment_reversals_immutable BEFORE UPDATE OR DELETE ON payment_reversals
  FOR EACH ROW EXECUTE FUNCTION app.prevent_financial_event_mutation();
CREATE TRIGGER receipts_immutable BEFORE UPDATE OR DELETE ON receipts
  FOR EACH ROW EXECUTE FUNCTION app.prevent_financial_event_mutation();
CREATE TRIGGER receipt_reversals_immutable BEFORE UPDATE OR DELETE ON receipt_reversals
  FOR EACH ROW EXECUTE FUNCTION app.prevent_financial_event_mutation();

DO $financial_rls$
DECLARE
  table_name text;
BEGIN
  FOREACH table_name IN ARRAY ARRAY[
    'counterparties',
    'financial_categories',
    'cost_centers',
    'financial_accounts',
    'payments',
    'payment_reversals',
    'receipts',
    'receipt_reversals'
  ]
  LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', table_name);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', table_name);
    EXECUTE format(
      'CREATE POLICY branch_isolation ON %I TO PUBLIC USING (tenant_id = app.current_tenant_id() AND app.can_access_record(company_id, branch_id)) WITH CHECK (tenant_id = app.current_tenant_id() AND app.can_access_record(company_id, branch_id))',
      table_name
    );
  END LOOP;
END
$financial_rls$;

GRANT USAGE ON TYPE
  counterparty_kind,
  financial_direction,
  financial_account_type
TO clarity_app;

REVOKE UPDATE, DELETE ON
  payments,
  payment_reversals,
  receipts,
  receipt_reversals
FROM clarity_app;

COMMENT ON TABLE payments IS
  'Append-only payable settlement events. outstanding_amount/status are derived projections.';
COMMENT ON TABLE receipts IS
  'Append-only receivable settlement events. outstanding_amount/status are derived projections.';
COMMENT ON TABLE payment_reversals IS
  'Append-only full reversals of payment events.';
COMMENT ON TABLE receipt_reversals IS
  'Append-only full reversals of receipt events.';
