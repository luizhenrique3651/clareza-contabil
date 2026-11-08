-- Phase 2 hardening after adversarial review:
-- - shared catalogs remain visible to branch-only memberships;
-- - catalog references are compatible with title branch and direction;
-- - financial projections are maintained by append-only events in PostgreSQL;
-- - OVERDUE is a read-time status, never a stored temporal projection.

CREATE OR REPLACE FUNCTION app.can_access_catalog(
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
      OR (
        candidate_branch_id IS NULL
        AND EXISTS (
          SELECT 1
          FROM public.branches branch
          WHERE branch.tenant_id = app.current_tenant_id()
            AND branch.company_id = candidate_company_id
            AND app.can_access_branch(branch.id)
        )
      )
    )
$function$;

DO $catalog_policies$
DECLARE
  table_name text;
BEGIN
  FOREACH table_name IN ARRAY ARRAY[
    'counterparties',
    'financial_categories',
    'cost_centers',
    'financial_accounts'
  ]
  LOOP
    EXECUTE format('DROP POLICY branch_isolation ON %I', table_name);
    EXECUTE format(
      'CREATE POLICY catalog_select ON %I FOR SELECT TO PUBLIC USING (tenant_id = app.current_tenant_id() AND app.can_access_catalog(company_id, branch_id))',
      table_name
    );
    EXECUTE format(
      'CREATE POLICY catalog_insert ON %I FOR INSERT TO PUBLIC WITH CHECK (tenant_id = app.current_tenant_id() AND app.can_access_record(company_id, branch_id))',
      table_name
    );
    EXECUTE format(
      'CREATE POLICY catalog_update ON %I FOR UPDATE TO PUBLIC USING (tenant_id = app.current_tenant_id() AND app.can_access_record(company_id, branch_id)) WITH CHECK (tenant_id = app.current_tenant_id() AND app.can_access_record(company_id, branch_id))',
      table_name
    );
    EXECUTE format(
      'CREATE POLICY catalog_delete ON %I FOR DELETE TO PUBLIC USING (tenant_id = app.current_tenant_id() AND app.can_access_record(company_id, branch_id))',
      table_name
    );
  END LOOP;
END
$catalog_policies$;

CREATE OR REPLACE FUNCTION app.assert_financial_record_catalog_scope()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
DECLARE
  catalog_branch uuid;
  counterparty_type counterparty_kind;
  category_direction financial_direction;
  expected_counterparty counterparty_kind;
  expected_direction financial_direction;
BEGIN
  IF TG_TABLE_NAME = 'payables' THEN
    expected_counterparty := 'SUPPLIER';
    expected_direction := 'PAYABLE';
  ELSIF TG_TABLE_NAME = 'receivables' THEN
    expected_counterparty := 'CUSTOMER';
    expected_direction := 'RECEIVABLE';
  ELSE
    RAISE EXCEPTION 'unsupported financial record table'
      USING ERRCODE = 'check_violation';
  END IF;

  IF NEW.counterparty_id IS NOT NULL THEN
    SELECT item.branch_id, item.kind
      INTO catalog_branch, counterparty_type
      FROM counterparties item
     WHERE item.tenant_id = NEW.tenant_id
       AND item.company_id = NEW.company_id
       AND item.id = NEW.counterparty_id
       AND item.is_active
       AND item.deleted_at IS NULL;
    IF NOT FOUND THEN
      RAISE EXCEPTION 'counterparty is not available in the financial record scope'
        USING ERRCODE = 'foreign_key_violation';
    END IF;
    IF catalog_branch IS NOT NULL
       AND catalog_branch IS DISTINCT FROM NEW.branch_id THEN
      RAISE EXCEPTION 'counterparty branch differs from financial record branch'
        USING ERRCODE = 'check_violation';
    END IF;
    IF counterparty_type NOT IN (expected_counterparty, 'BOTH') THEN
      RAISE EXCEPTION 'counterparty kind is incompatible with financial record'
        USING ERRCODE = 'check_violation';
    END IF;
  END IF;

  IF NEW.category_id IS NOT NULL THEN
    SELECT item.branch_id, item.direction
      INTO catalog_branch, category_direction
      FROM financial_categories item
     WHERE item.tenant_id = NEW.tenant_id
       AND item.company_id = NEW.company_id
       AND item.id = NEW.category_id
       AND item.is_active
       AND item.deleted_at IS NULL;
    IF NOT FOUND THEN
      RAISE EXCEPTION 'category is not available in the financial record scope'
        USING ERRCODE = 'foreign_key_violation';
    END IF;
    IF catalog_branch IS NOT NULL
       AND catalog_branch IS DISTINCT FROM NEW.branch_id THEN
      RAISE EXCEPTION 'category branch differs from financial record branch'
        USING ERRCODE = 'check_violation';
    END IF;
    IF category_direction NOT IN (expected_direction, 'BOTH') THEN
      RAISE EXCEPTION 'category direction is incompatible with financial record'
        USING ERRCODE = 'check_violation';
    END IF;
  END IF;

  IF NEW.cost_center_id IS NOT NULL THEN
    SELECT item.branch_id
      INTO catalog_branch
      FROM cost_centers item
     WHERE item.tenant_id = NEW.tenant_id
       AND item.company_id = NEW.company_id
       AND item.id = NEW.cost_center_id
       AND item.is_active
       AND item.deleted_at IS NULL;
    IF NOT FOUND THEN
      RAISE EXCEPTION 'cost center is not available in the financial record scope'
        USING ERRCODE = 'foreign_key_violation';
    END IF;
    IF catalog_branch IS NOT NULL
       AND catalog_branch IS DISTINCT FROM NEW.branch_id THEN
      RAISE EXCEPTION 'cost center branch differs from financial record branch'
        USING ERRCODE = 'check_violation';
    END IF;
  END IF;

  RETURN NEW;
END
$function$;

CREATE TRIGGER payables_catalog_scope_guard
  BEFORE INSERT OR UPDATE OF
    tenant_id, company_id, branch_id, counterparty_id, category_id, cost_center_id
  ON payables
  FOR EACH ROW EXECUTE FUNCTION app.assert_financial_record_catalog_scope();

CREATE TRIGGER receivables_catalog_scope_guard
  BEFORE INSERT OR UPDATE OF
    tenant_id, company_id, branch_id, counterparty_id, category_id, cost_center_id
  ON receivables
  FOR EACH ROW EXECUTE FUNCTION app.assert_financial_record_catalog_scope();

-- Fail the upgrade explicitly if an unreleased 0005 database received temporal
-- values that the hardened projection model cannot represent safely.
DO $block$
DECLARE
  migration_now timestamptz(6) := clock_timestamp();
BEGIN
  IF EXISTS (
       SELECT 1 FROM payments
        WHERE NOT isfinite(occurred_at) OR occurred_at > migration_now
     )
     OR EXISTS (
       SELECT 1 FROM receipts
        WHERE NOT isfinite(occurred_at) OR occurred_at > migration_now
     )
     OR EXISTS (
       SELECT 1 FROM payment_reversals
        WHERE NOT isfinite(occurred_at) OR occurred_at > migration_now
     )
     OR EXISTS (
       SELECT 1 FROM receipt_reversals
        WHERE NOT isfinite(occurred_at) OR occurred_at > migration_now
     ) THEN
    RAISE EXCEPTION 'future or non-finite financial events require correction before phase two hardening'
      USING ERRCODE = 'check_violation';
  END IF;
END
$block$;

CREATE OR REPLACE FUNCTION app.assert_payment_scope()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
DECLARE
  target_branch uuid;
  target_currency char(3);
  target_status financial_status;
  account_branch uuid;
  account_currency char(3);
BEGIN
  IF NOT isfinite(NEW.occurred_at)
     OR NEW.occurred_at > clock_timestamp() THEN
    RAISE EXCEPTION 'payment timestamp must be finite and cannot occur in the future'
      USING ERRCODE = 'check_violation';
  END IF;

  SELECT payable.branch_id, payable.currency_code, payable.status
    INTO target_branch, target_currency, target_status
    FROM payables payable
   WHERE payable.tenant_id = NEW.tenant_id
     AND payable.company_id = NEW.company_id
     AND payable.id = NEW.payable_id
     AND payable.deleted_at IS NULL;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'payable is not available in the authenticated scope'
      USING ERRCODE = 'foreign_key_violation';
  END IF;
  IF target_status = 'CANCELLED' THEN
    RAISE EXCEPTION 'cancelled payable cannot receive payment events'
      USING ERRCODE = 'check_violation';
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
  IF account_branch IS NOT NULL
     AND account_branch IS DISTINCT FROM target_branch THEN
    RAISE EXCEPTION 'financial account branch differs from payable branch'
      USING ERRCODE = 'check_violation';
  END IF;
  IF account_currency IS DISTINCT FROM target_currency THEN
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
  target_status financial_status;
  account_branch uuid;
  account_currency char(3);
BEGIN
  IF NOT isfinite(NEW.occurred_at)
     OR NEW.occurred_at > clock_timestamp() THEN
    RAISE EXCEPTION 'receipt timestamp must be finite and cannot occur in the future'
      USING ERRCODE = 'check_violation';
  END IF;

  SELECT receivable.branch_id, receivable.currency_code, receivable.status
    INTO target_branch, target_currency, target_status
    FROM receivables receivable
   WHERE receivable.tenant_id = NEW.tenant_id
     AND receivable.company_id = NEW.company_id
     AND receivable.id = NEW.receivable_id
     AND receivable.deleted_at IS NULL;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'receivable is not available in the authenticated scope'
      USING ERRCODE = 'foreign_key_violation';
  END IF;
  IF target_status = 'CANCELLED' THEN
    RAISE EXCEPTION 'cancelled receivable cannot receive receipt events'
      USING ERRCODE = 'check_violation';
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
  IF account_branch IS NOT NULL
     AND account_branch IS DISTINCT FROM target_branch THEN
    RAISE EXCEPTION 'financial account branch differs from receivable branch'
      USING ERRCODE = 'check_violation';
  END IF;
  IF account_currency IS DISTINCT FROM target_currency THEN
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
  target_occurred_at timestamptz(6);
BEGIN
  IF NOT isfinite(NEW.occurred_at)
     OR NEW.occurred_at > clock_timestamp() THEN
    RAISE EXCEPTION 'payment reversal timestamp must be finite and cannot occur in the future'
      USING ERRCODE = 'check_violation';
  END IF;

  SELECT payment.branch_id, payment.occurred_at
    INTO target_branch, target_occurred_at
    FROM payments payment
   WHERE payment.tenant_id = NEW.tenant_id
     AND payment.company_id = NEW.company_id
     AND payment.id = NEW.payment_id;
  IF NOT FOUND OR NEW.branch_id IS DISTINCT FROM target_branch THEN
    RAISE EXCEPTION 'payment reversal scope differs from payment scope'
      USING ERRCODE = 'check_violation';
  END IF;
  IF NEW.occurred_at < target_occurred_at THEN
    RAISE EXCEPTION 'payment reversal cannot occur before payment'
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
  target_occurred_at timestamptz(6);
BEGIN
  IF NOT isfinite(NEW.occurred_at)
     OR NEW.occurred_at > clock_timestamp() THEN
    RAISE EXCEPTION 'receipt reversal timestamp must be finite and cannot occur in the future'
      USING ERRCODE = 'check_violation';
  END IF;

  SELECT receipt.branch_id, receipt.occurred_at
    INTO target_branch, target_occurred_at
    FROM receipts receipt
   WHERE receipt.tenant_id = NEW.tenant_id
     AND receipt.company_id = NEW.company_id
     AND receipt.id = NEW.receipt_id;
  IF NOT FOUND OR NEW.branch_id IS DISTINCT FROM target_branch THEN
    RAISE EXCEPTION 'receipt reversal scope differs from receipt scope'
      USING ERRCODE = 'check_violation';
  END IF;
  IF NEW.occurred_at < target_occurred_at THEN
    RAISE EXCEPTION 'receipt reversal cannot occur before receipt'
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END
$function$;

-- Normalize all existing records before projection mutation is protected.
WITH payment_projection AS (
  SELECT
    payable.tenant_id,
    payable.company_id,
    payable.id,
    COALESCE(
      SUM(payment.principal_amount)
        FILTER (WHERE payment_reversal.id IS NULL),
      0
    ) AS settled_amount,
    MAX(payment.occurred_at)
      FILTER (WHERE payment_reversal.id IS NULL) AS settled_at
  FROM payables payable
  LEFT JOIN payments payment
    ON payment.tenant_id = payable.tenant_id
   AND payment.company_id = payable.company_id
   AND payment.payable_id = payable.id
  LEFT JOIN payment_reversals payment_reversal
    ON payment_reversal.tenant_id = payment.tenant_id
   AND payment_reversal.company_id = payment.company_id
   AND payment_reversal.payment_id = payment.id
  GROUP BY payable.tenant_id, payable.company_id, payable.id
)
UPDATE payables payable
   SET outstanding_amount = payable.amount - projection.settled_amount,
       status = CASE
         WHEN payable.status = 'CANCELLED' THEN 'CANCELLED'::financial_status
         WHEN projection.settled_amount = payable.amount THEN 'SETTLED'::financial_status
         WHEN projection.settled_amount > 0 THEN 'PARTIAL'::financial_status
         ELSE 'OPEN'::financial_status
       END,
       paid_at = CASE
         WHEN payable.status <> 'CANCELLED'
          AND projection.settled_amount = payable.amount
           THEN projection.settled_at
         ELSE NULL
       END
  FROM payment_projection projection
 WHERE payable.tenant_id = projection.tenant_id
   AND payable.company_id = projection.company_id
   AND payable.id = projection.id;

WITH receipt_projection AS (
  SELECT
    receivable.tenant_id,
    receivable.company_id,
    receivable.id,
    COALESCE(
      SUM(receipt.principal_amount)
        FILTER (WHERE receipt_reversal.id IS NULL),
      0
    ) AS settled_amount,
    MAX(receipt.occurred_at)
      FILTER (WHERE receipt_reversal.id IS NULL) AS settled_at
  FROM receivables receivable
  LEFT JOIN receipts receipt
    ON receipt.tenant_id = receivable.tenant_id
   AND receipt.company_id = receivable.company_id
   AND receipt.receivable_id = receivable.id
  LEFT JOIN receipt_reversals receipt_reversal
    ON receipt_reversal.tenant_id = receipt.tenant_id
   AND receipt_reversal.company_id = receipt.company_id
   AND receipt_reversal.receipt_id = receipt.id
  GROUP BY receivable.tenant_id, receivable.company_id, receivable.id
)
UPDATE receivables receivable
   SET outstanding_amount = receivable.amount - projection.settled_amount,
       status = CASE
         WHEN receivable.status = 'CANCELLED' THEN 'CANCELLED'::financial_status
         WHEN projection.settled_amount = receivable.amount THEN 'SETTLED'::financial_status
         WHEN projection.settled_amount > 0 THEN 'PARTIAL'::financial_status
         ELSE 'OPEN'::financial_status
       END,
       received_at = CASE
         WHEN receivable.status <> 'CANCELLED'
          AND projection.settled_amount = receivable.amount
           THEN projection.settled_at
         ELSE NULL
       END
  FROM receipt_projection projection
 WHERE receivable.tenant_id = projection.tenant_id
   AND receivable.company_id = projection.company_id
   AND receivable.id = projection.id;

CREATE OR REPLACE FUNCTION app.refresh_payable_projection(
  target_tenant_id uuid,
  target_company_id uuid,
  target_payable_id uuid,
  actor_user_id uuid
)
RETURNS void
LANGUAGE plpgsql
AS $function$
DECLARE
  title_amount numeric(19,4);
  title_status financial_status;
  settled_amount numeric(19,4);
  latest_settlement timestamptz(6);
BEGIN
  SELECT payable.amount, payable.status
    INTO title_amount, title_status
    FROM payables payable
   WHERE payable.tenant_id = target_tenant_id
     AND payable.company_id = target_company_id
     AND payable.id = target_payable_id
     AND payable.deleted_at IS NULL
   FOR UPDATE;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'payable is not available for projection refresh'
      USING ERRCODE = 'foreign_key_violation';
  END IF;

  SELECT
    COALESCE(
      SUM(payment.principal_amount)
        FILTER (WHERE payment_reversal.id IS NULL),
      0
    ),
    MAX(payment.occurred_at)
      FILTER (WHERE payment_reversal.id IS NULL)
    INTO settled_amount, latest_settlement
    FROM payments payment
    LEFT JOIN payment_reversals payment_reversal
      ON payment_reversal.tenant_id = payment.tenant_id
     AND payment_reversal.company_id = payment.company_id
     AND payment_reversal.payment_id = payment.id
   WHERE payment.tenant_id = target_tenant_id
     AND payment.company_id = target_company_id
     AND payment.payable_id = target_payable_id;

  IF settled_amount > title_amount THEN
    RAISE EXCEPTION 'payment events exceed payable amount'
      USING ERRCODE = 'check_violation';
  END IF;

  UPDATE payables
     SET outstanding_amount = title_amount - settled_amount,
         status = CASE
           WHEN title_status = 'CANCELLED' THEN 'CANCELLED'::financial_status
           WHEN settled_amount = title_amount THEN 'SETTLED'::financial_status
           WHEN settled_amount > 0 THEN 'PARTIAL'::financial_status
           ELSE 'OPEN'::financial_status
         END,
         paid_at = CASE
           WHEN title_status <> 'CANCELLED' AND settled_amount = title_amount
             THEN latest_settlement
           ELSE NULL
         END,
         updated_by_id = actor_user_id,
         updated_at = clock_timestamp()
   WHERE tenant_id = target_tenant_id
     AND company_id = target_company_id
     AND id = target_payable_id;
END
$function$;

CREATE OR REPLACE FUNCTION app.refresh_receivable_projection(
  target_tenant_id uuid,
  target_company_id uuid,
  target_receivable_id uuid,
  actor_user_id uuid
)
RETURNS void
LANGUAGE plpgsql
AS $function$
DECLARE
  title_amount numeric(19,4);
  title_status financial_status;
  settled_amount numeric(19,4);
  latest_settlement timestamptz(6);
BEGIN
  SELECT receivable.amount, receivable.status
    INTO title_amount, title_status
    FROM receivables receivable
   WHERE receivable.tenant_id = target_tenant_id
     AND receivable.company_id = target_company_id
     AND receivable.id = target_receivable_id
     AND receivable.deleted_at IS NULL
   FOR UPDATE;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'receivable is not available for projection refresh'
      USING ERRCODE = 'foreign_key_violation';
  END IF;

  SELECT
    COALESCE(
      SUM(receipt.principal_amount)
        FILTER (WHERE receipt_reversal.id IS NULL),
      0
    ),
    MAX(receipt.occurred_at)
      FILTER (WHERE receipt_reversal.id IS NULL)
    INTO settled_amount, latest_settlement
    FROM receipts receipt
    LEFT JOIN receipt_reversals receipt_reversal
      ON receipt_reversal.tenant_id = receipt.tenant_id
     AND receipt_reversal.company_id = receipt.company_id
     AND receipt_reversal.receipt_id = receipt.id
   WHERE receipt.tenant_id = target_tenant_id
     AND receipt.company_id = target_company_id
     AND receipt.receivable_id = target_receivable_id;

  IF settled_amount > title_amount THEN
    RAISE EXCEPTION 'receipt events exceed receivable amount'
      USING ERRCODE = 'check_violation';
  END IF;

  UPDATE receivables
     SET outstanding_amount = title_amount - settled_amount,
         status = CASE
           WHEN title_status = 'CANCELLED' THEN 'CANCELLED'::financial_status
           WHEN settled_amount = title_amount THEN 'SETTLED'::financial_status
           WHEN settled_amount > 0 THEN 'PARTIAL'::financial_status
           ELSE 'OPEN'::financial_status
         END,
         received_at = CASE
           WHEN title_status <> 'CANCELLED' AND settled_amount = title_amount
             THEN latest_settlement
           ELSE NULL
         END,
         updated_by_id = actor_user_id,
         updated_at = clock_timestamp()
   WHERE tenant_id = target_tenant_id
     AND company_id = target_company_id
     AND id = target_receivable_id;
END
$function$;

CREATE OR REPLACE FUNCTION app.refresh_payment_projection_after_event()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
DECLARE
  target_payable_id uuid;
BEGIN
  IF TG_TABLE_NAME = 'payments' THEN
    target_payable_id := NEW.payable_id;
  ELSE
    SELECT payment.payable_id
      INTO target_payable_id
      FROM payments payment
     WHERE payment.tenant_id = NEW.tenant_id
       AND payment.company_id = NEW.company_id
       AND payment.id = NEW.payment_id;
  END IF;
  PERFORM app.refresh_payable_projection(
    NEW.tenant_id,
    NEW.company_id,
    target_payable_id,
    NEW.created_by_id
  );
  RETURN NEW;
END
$function$;

CREATE OR REPLACE FUNCTION app.refresh_receipt_projection_after_event()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
DECLARE
  target_receivable_id uuid;
BEGIN
  IF TG_TABLE_NAME = 'receipts' THEN
    target_receivable_id := NEW.receivable_id;
  ELSE
    SELECT receipt.receivable_id
      INTO target_receivable_id
      FROM receipts receipt
     WHERE receipt.tenant_id = NEW.tenant_id
       AND receipt.company_id = NEW.company_id
       AND receipt.id = NEW.receipt_id;
  END IF;
  PERFORM app.refresh_receivable_projection(
    NEW.tenant_id,
    NEW.company_id,
    target_receivable_id,
    NEW.created_by_id
  );
  RETURN NEW;
END
$function$;

CREATE OR REPLACE FUNCTION app.prevent_financial_projection_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
BEGIN
  IF pg_trigger_depth() <= 1 AND (
    NEW.amount IS DISTINCT FROM OLD.amount
    OR NEW.outstanding_amount IS DISTINCT FROM OLD.outstanding_amount
    OR NEW.status IS DISTINCT FROM OLD.status
    OR (
      TG_TABLE_NAME = 'payables'
      AND to_jsonb(NEW)->'paid_at' IS DISTINCT FROM to_jsonb(OLD)->'paid_at'
    )
    OR (
      TG_TABLE_NAME = 'receivables'
      AND to_jsonb(NEW)->'received_at' IS DISTINCT FROM to_jsonb(OLD)->'received_at'
    )
  ) THEN
    RAISE EXCEPTION 'financial projections can only change through append-only events'
      USING ERRCODE = 'integrity_constraint_violation';
  END IF;
  RETURN NEW;
END
$function$;

CREATE TRIGGER payables_projection_guard
  BEFORE UPDATE OF amount, outstanding_amount, status, paid_at
  ON payables
  FOR EACH ROW EXECUTE FUNCTION app.prevent_financial_projection_mutation();

CREATE TRIGGER receivables_projection_guard
  BEFORE UPDATE OF amount, outstanding_amount, status, received_at
  ON receivables
  FOR EACH ROW EXECUTE FUNCTION app.prevent_financial_projection_mutation();

CREATE TRIGGER payments_refresh_projection
  AFTER INSERT ON payments
  FOR EACH ROW EXECUTE FUNCTION app.refresh_payment_projection_after_event();

CREATE TRIGGER payment_reversals_refresh_projection
  AFTER INSERT ON payment_reversals
  FOR EACH ROW EXECUTE FUNCTION app.refresh_payment_projection_after_event();

CREATE TRIGGER receipts_refresh_projection
  AFTER INSERT ON receipts
  FOR EACH ROW EXECUTE FUNCTION app.refresh_receipt_projection_after_event();

CREATE TRIGGER receipt_reversals_refresh_projection
  AFTER INSERT ON receipt_reversals
  FOR EACH ROW EXECUTE FUNCTION app.refresh_receipt_projection_after_event();

REVOKE DELETE ON payables, receivables FROM clarity_app;

COMMENT ON FUNCTION app.can_access_catalog(uuid, uuid) IS
  'Allows company/tenant access, an authorized branch, or shared catalogs for a company reached through an authorized branch.';
COMMENT ON FUNCTION app.prevent_financial_projection_mutation() IS
  'Rejects direct changes to title amount, balance, settlement status and settlement timestamp.';
