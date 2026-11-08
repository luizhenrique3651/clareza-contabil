-- Phase 3: immutable bank imports, transactions and reversible reconciliation.

CREATE TYPE bank_import_format AS ENUM ('CSV', 'OFX');
CREATE TYPE reconciliation_participant_kind AS ENUM (
  'BANK_TRANSACTION',
  'PAYMENT',
  'RECEIPT'
);

CREATE TABLE bank_imports (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL,
  company_id uuid NOT NULL,
  branch_id uuid,
  financial_account_id uuid NOT NULL,
  format bank_import_format NOT NULL,
  content_hash char(64) NOT NULL,
  source_name varchar(255),
  total_rows integer NOT NULL,
  imported_rows integer NOT NULL,
  possible_duplicates integer NOT NULL DEFAULT 0,
  created_by_id uuid NOT NULL,
  created_at timestamptz(6) NOT NULL DEFAULT now(),
  CONSTRAINT bank_imports_tenant_fk
    FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE RESTRICT,
  CONSTRAINT bank_imports_company_fk
    FOREIGN KEY (tenant_id, company_id)
    REFERENCES companies(tenant_id, id) ON DELETE RESTRICT,
  CONSTRAINT bank_imports_branch_fk
    FOREIGN KEY (tenant_id, company_id, branch_id)
    REFERENCES branches(tenant_id, company_id, id) ON DELETE RESTRICT,
  CONSTRAINT bank_imports_account_fk
    FOREIGN KEY (tenant_id, company_id, financial_account_id)
    REFERENCES financial_accounts(tenant_id, company_id, id) ON DELETE RESTRICT,
  CONSTRAINT bank_imports_created_by_fk
    FOREIGN KEY (tenant_id, created_by_id)
    REFERENCES users(tenant_id, id) ON DELETE RESTRICT,
  CONSTRAINT bank_imports_tenant_company_id_key
    UNIQUE (tenant_id, company_id, id),
  CONSTRAINT bank_imports_content_key
    UNIQUE (tenant_id, company_id, financial_account_id, content_hash),
  CONSTRAINT bank_imports_hash_format
    CHECK (content_hash ~ '^[0-9a-f]{64}$'),
  CONSTRAINT bank_imports_counts
    CHECK (
      total_rows > 0
      AND imported_rows = total_rows
      AND possible_duplicates >= 0
      AND possible_duplicates <= imported_rows
    ),
  CONSTRAINT bank_imports_source_name_not_blank
    CHECK (source_name IS NULL OR btrim(source_name) <> '')
);

CREATE INDEX bank_imports_account_time_idx
  ON bank_imports (tenant_id, company_id, financial_account_id, created_at DESC);

CREATE TABLE bank_transactions (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL,
  company_id uuid NOT NULL,
  branch_id uuid,
  bank_import_id uuid NOT NULL,
  financial_account_id uuid NOT NULL,
  source_line integer NOT NULL,
  external_id varchar(160),
  fingerprint char(64) NOT NULL,
  booked_at timestamptz(6) NOT NULL,
  amount numeric(19,4) NOT NULL,
  currency_code char(3) NOT NULL,
  description varchar(500) NOT NULL,
  memo varchar(500),
  created_at timestamptz(6) NOT NULL DEFAULT now(),
  CONSTRAINT bank_transactions_tenant_fk
    FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE RESTRICT,
  CONSTRAINT bank_transactions_company_fk
    FOREIGN KEY (tenant_id, company_id)
    REFERENCES companies(tenant_id, id) ON DELETE RESTRICT,
  CONSTRAINT bank_transactions_branch_fk
    FOREIGN KEY (tenant_id, company_id, branch_id)
    REFERENCES branches(tenant_id, company_id, id) ON DELETE RESTRICT,
  CONSTRAINT bank_transactions_import_fk
    FOREIGN KEY (tenant_id, company_id, bank_import_id)
    REFERENCES bank_imports(tenant_id, company_id, id) ON DELETE RESTRICT,
  CONSTRAINT bank_transactions_account_fk
    FOREIGN KEY (tenant_id, company_id, financial_account_id)
    REFERENCES financial_accounts(tenant_id, company_id, id) ON DELETE RESTRICT,
  CONSTRAINT bank_transactions_tenant_company_id_key
    UNIQUE (tenant_id, company_id, id),
  CONSTRAINT bank_transactions_import_line_key
    UNIQUE (tenant_id, company_id, bank_import_id, source_line),
  CONSTRAINT bank_transactions_source_line_positive CHECK (source_line > 0),
  CONSTRAINT bank_transactions_amount_nonzero CHECK (amount <> 0),
  CONSTRAINT bank_transactions_currency_format
    CHECK (currency_code ~ '^[A-Z]{3}$'),
  CONSTRAINT bank_transactions_fingerprint_format
    CHECK (fingerprint ~ '^[0-9a-f]{64}$'),
  CONSTRAINT bank_transactions_description_not_blank
    CHECK (btrim(description) <> ''),
  CONSTRAINT bank_transactions_external_id_not_blank
    CHECK (external_id IS NULL OR btrim(external_id) <> ''),
  CONSTRAINT bank_transactions_memo_not_blank
    CHECK (memo IS NULL OR btrim(memo) <> ''),
  CONSTRAINT bank_transactions_booked_at_finite CHECK (isfinite(booked_at))
);

CREATE INDEX bank_transactions_account_time_idx
  ON bank_transactions (
    tenant_id,
    company_id,
    financial_account_id,
    booked_at DESC,
    id DESC
  );
CREATE INDEX bank_transactions_fingerprint_idx
  ON bank_transactions (
    tenant_id,
    company_id,
    financial_account_id,
    fingerprint
  );
CREATE UNIQUE INDEX bank_transactions_external_id_key
  ON bank_transactions (
    tenant_id,
    company_id,
    financial_account_id,
    external_id
  )
  WHERE external_id IS NOT NULL;

CREATE TABLE bank_reconciliation_groups (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL,
  company_id uuid NOT NULL,
  branch_id uuid,
  financial_account_id uuid NOT NULL,
  reconciled_at timestamptz(6) NOT NULL,
  note varchar(500),
  created_by_id uuid NOT NULL,
  created_at timestamptz(6) NOT NULL DEFAULT now(),
  CONSTRAINT bank_reconciliation_groups_tenant_fk
    FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE RESTRICT,
  CONSTRAINT bank_reconciliation_groups_company_fk
    FOREIGN KEY (tenant_id, company_id)
    REFERENCES companies(tenant_id, id) ON DELETE RESTRICT,
  CONSTRAINT bank_reconciliation_groups_branch_fk
    FOREIGN KEY (tenant_id, company_id, branch_id)
    REFERENCES branches(tenant_id, company_id, id) ON DELETE RESTRICT,
  CONSTRAINT bank_reconciliation_groups_account_fk
    FOREIGN KEY (tenant_id, company_id, financial_account_id)
    REFERENCES financial_accounts(tenant_id, company_id, id) ON DELETE RESTRICT,
  CONSTRAINT bank_reconciliation_groups_created_by_fk
    FOREIGN KEY (tenant_id, created_by_id)
    REFERENCES users(tenant_id, id) ON DELETE RESTRICT,
  CONSTRAINT bank_reconciliation_groups_tenant_company_id_key
    UNIQUE (tenant_id, company_id, id),
  CONSTRAINT bank_reconciliation_groups_time_finite CHECK (isfinite(reconciled_at)),
  CONSTRAINT bank_reconciliation_groups_note_not_blank
    CHECK (note IS NULL OR btrim(note) <> '')
);

CREATE INDEX bank_reconciliation_groups_account_time_idx
  ON bank_reconciliation_groups (
    tenant_id,
    company_id,
    financial_account_id,
    reconciled_at DESC
  );

CREATE TABLE bank_reconciliation_items (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL,
  company_id uuid NOT NULL,
  group_id uuid NOT NULL,
  participant_kind reconciliation_participant_kind NOT NULL,
  bank_transaction_id uuid,
  payment_id uuid,
  receipt_id uuid,
  created_at timestamptz(6) NOT NULL DEFAULT now(),
  CONSTRAINT bank_reconciliation_items_tenant_fk
    FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE RESTRICT,
  CONSTRAINT bank_reconciliation_items_company_fk
    FOREIGN KEY (tenant_id, company_id)
    REFERENCES companies(tenant_id, id) ON DELETE RESTRICT,
  CONSTRAINT bank_reconciliation_items_group_fk
    FOREIGN KEY (tenant_id, company_id, group_id)
    REFERENCES bank_reconciliation_groups(tenant_id, company_id, id)
    ON DELETE RESTRICT,
  CONSTRAINT bank_reconciliation_items_transaction_fk
    FOREIGN KEY (tenant_id, company_id, bank_transaction_id)
    REFERENCES bank_transactions(tenant_id, company_id, id) ON DELETE RESTRICT,
  CONSTRAINT bank_reconciliation_items_payment_fk
    FOREIGN KEY (tenant_id, company_id, payment_id)
    REFERENCES payments(tenant_id, company_id, id) ON DELETE RESTRICT,
  CONSTRAINT bank_reconciliation_items_receipt_fk
    FOREIGN KEY (tenant_id, company_id, receipt_id)
    REFERENCES receipts(tenant_id, company_id, id) ON DELETE RESTRICT,
  CONSTRAINT bank_reconciliation_items_tenant_company_id_key
    UNIQUE (tenant_id, company_id, id),
  CONSTRAINT bank_reconciliation_items_exact_participant
    CHECK (
      (
        participant_kind = 'BANK_TRANSACTION'
        AND bank_transaction_id IS NOT NULL
        AND payment_id IS NULL
        AND receipt_id IS NULL
      )
      OR (
        participant_kind = 'PAYMENT'
        AND bank_transaction_id IS NULL
        AND payment_id IS NOT NULL
        AND receipt_id IS NULL
      )
      OR (
        participant_kind = 'RECEIPT'
        AND bank_transaction_id IS NULL
        AND payment_id IS NULL
        AND receipt_id IS NOT NULL
      )
    )
);

CREATE INDEX bank_reconciliation_items_group_idx
  ON bank_reconciliation_items (tenant_id, company_id, group_id);

CREATE TABLE bank_reconciliation_reversals (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL,
  company_id uuid NOT NULL,
  branch_id uuid,
  group_id uuid NOT NULL,
  reason varchar(500) NOT NULL,
  occurred_at timestamptz(6) NOT NULL,
  created_by_id uuid NOT NULL,
  created_at timestamptz(6) NOT NULL DEFAULT now(),
  CONSTRAINT bank_reconciliation_reversals_tenant_fk
    FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE RESTRICT,
  CONSTRAINT bank_reconciliation_reversals_company_fk
    FOREIGN KEY (tenant_id, company_id)
    REFERENCES companies(tenant_id, id) ON DELETE RESTRICT,
  CONSTRAINT bank_reconciliation_reversals_branch_fk
    FOREIGN KEY (tenant_id, company_id, branch_id)
    REFERENCES branches(tenant_id, company_id, id) ON DELETE RESTRICT,
  CONSTRAINT bank_reconciliation_reversals_group_fk
    FOREIGN KEY (tenant_id, company_id, group_id)
    REFERENCES bank_reconciliation_groups(tenant_id, company_id, id)
    ON DELETE RESTRICT,
  CONSTRAINT bank_reconciliation_reversals_created_by_fk
    FOREIGN KEY (tenant_id, created_by_id)
    REFERENCES users(tenant_id, id) ON DELETE RESTRICT,
  CONSTRAINT bank_reconciliation_reversals_tenant_company_id_key
    UNIQUE (tenant_id, company_id, id),
  CONSTRAINT bank_reconciliation_reversals_group_key
    UNIQUE (tenant_id, company_id, group_id),
  CONSTRAINT bank_reconciliation_reversals_reason_not_blank
    CHECK (btrim(reason) <> ''),
  CONSTRAINT bank_reconciliation_reversals_time_finite CHECK (isfinite(occurred_at))
);

-- This is a mutable projection of active reservations, not an audit source.
-- Runtime SQL cannot access it directly; security-definer triggers maintain it.
CREATE TABLE bank_reconciliation_reservations (
  tenant_id uuid NOT NULL,
  company_id uuid NOT NULL,
  participant_kind reconciliation_participant_kind NOT NULL,
  participant_id uuid NOT NULL,
  group_id uuid NOT NULL,
  item_id uuid NOT NULL,
  created_at timestamptz(6) NOT NULL DEFAULT now(),
  CONSTRAINT bank_reconciliation_reservations_pkey
    PRIMARY KEY (tenant_id, company_id, participant_kind, participant_id),
  CONSTRAINT bank_reconciliation_reservations_group_fk
    FOREIGN KEY (tenant_id, company_id, group_id)
    REFERENCES bank_reconciliation_groups(tenant_id, company_id, id)
    ON DELETE RESTRICT,
  CONSTRAINT bank_reconciliation_reservations_item_fk
    FOREIGN KEY (tenant_id, company_id, item_id)
    REFERENCES bank_reconciliation_items(tenant_id, company_id, id)
    ON DELETE RESTRICT,
  CONSTRAINT bank_reconciliation_reservations_item_key
    UNIQUE (tenant_id, company_id, item_id)
);

CREATE INDEX bank_reconciliation_reservations_group_idx
  ON bank_reconciliation_reservations (tenant_id, company_id, group_id);

CREATE OR REPLACE FUNCTION app.assert_bank_import_complete()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $function$
DECLARE
  target_import uuid;
  expected_rows integer;
  actual_rows bigint;
BEGIN
  IF TG_TABLE_NAME = 'bank_imports' THEN
    target_import := NEW.id;
  ELSE
    target_import := NEW.bank_import_id;
  END IF;

  SELECT item.total_rows
    INTO expected_rows
    FROM public.bank_imports item
   WHERE item.tenant_id = NEW.tenant_id
     AND item.company_id = NEW.company_id
     AND item.id = target_import;

  SELECT count(*)
    INTO actual_rows
    FROM public.bank_transactions item
   WHERE item.tenant_id = NEW.tenant_id
     AND item.company_id = NEW.company_id
     AND item.bank_import_id = target_import;

  IF expected_rows IS NULL OR actual_rows <> expected_rows THEN
    RAISE EXCEPTION
      'bank import row count differs from declared total: expected %, found %',
      expected_rows,
      actual_rows
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END
$function$;

CREATE OR REPLACE FUNCTION app.lock_financial_account_for_event()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
BEGIN
  PERFORM 1
    FROM financial_accounts account
   WHERE account.tenant_id = NEW.tenant_id
     AND account.company_id = NEW.company_id
     AND account.id = NEW.financial_account_id
   FOR UPDATE;

  IF NOT FOUND THEN
    RAISE EXCEPTION 'financial account is not available for the event'
      USING ERRCODE = 'foreign_key_violation';
  END IF;
  RETURN NEW;
END
$function$;

CREATE OR REPLACE FUNCTION app.assert_bank_import_scope()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
DECLARE
  account_branch uuid;
  account_type financial_account_type;
BEGIN
  SELECT account.branch_id, account.type
    INTO account_branch, account_type
    FROM financial_accounts account
   WHERE account.tenant_id = NEW.tenant_id
     AND account.company_id = NEW.company_id
     AND account.id = NEW.financial_account_id
     AND account.is_active
     AND account.deleted_at IS NULL;

  IF NOT FOUND THEN
    RAISE EXCEPTION 'bank account is not available'
      USING ERRCODE = 'foreign_key_violation';
  END IF;
  IF account_type NOT IN ('CHECKING', 'SAVINGS', 'PAYMENT') THEN
    RAISE EXCEPTION 'financial account type does not accept bank imports'
      USING ERRCODE = 'check_violation';
  END IF;
  IF NEW.branch_id IS DISTINCT FROM account_branch THEN
    RAISE EXCEPTION 'bank import branch differs from account branch'
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END
$function$;

CREATE OR REPLACE FUNCTION app.assert_bank_transaction_scope()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
DECLARE
  import_branch uuid;
  import_account uuid;
  account_currency char(3);
BEGIN
  SELECT item.branch_id, item.financial_account_id
    INTO import_branch, import_account
    FROM bank_imports item
   WHERE item.tenant_id = NEW.tenant_id
     AND item.company_id = NEW.company_id
     AND item.id = NEW.bank_import_id;

  IF NOT FOUND
     OR NEW.branch_id IS DISTINCT FROM import_branch
     OR NEW.financial_account_id IS DISTINCT FROM import_account THEN
    RAISE EXCEPTION 'bank transaction scope differs from import scope'
      USING ERRCODE = 'check_violation';
  END IF;

  SELECT account.currency_code
    INTO account_currency
    FROM financial_accounts account
   WHERE account.tenant_id = NEW.tenant_id
     AND account.company_id = NEW.company_id
     AND account.id = NEW.financial_account_id;

  IF NOT FOUND OR NEW.currency_code <> account_currency THEN
    RAISE EXCEPTION 'bank transaction currency differs from account currency'
      USING ERRCODE = 'check_violation';
  END IF;
  IF NEW.booked_at > clock_timestamp() THEN
    RAISE EXCEPTION 'bank transaction cannot be booked in the future'
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END
$function$;

CREATE OR REPLACE FUNCTION app.assert_reconciliation_group_scope()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
DECLARE
  account_branch uuid;
BEGIN
  SELECT account.branch_id
    INTO account_branch
    FROM financial_accounts account
   WHERE account.tenant_id = NEW.tenant_id
     AND account.company_id = NEW.company_id
     AND account.id = NEW.financial_account_id
     AND account.is_active
     AND account.deleted_at IS NULL;

  IF NOT FOUND OR NEW.branch_id IS DISTINCT FROM account_branch THEN
    RAISE EXCEPTION 'reconciliation scope differs from account scope'
      USING ERRCODE = 'check_violation';
  END IF;
  IF NEW.reconciled_at > clock_timestamp() THEN
    RAISE EXCEPTION 'reconciliation cannot occur in the future'
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END
$function$;

CREATE OR REPLACE FUNCTION app.reserve_reconciliation_participant()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $function$
DECLARE
  group_account uuid;
  group_branch uuid;
  participant_id uuid;
  participant_account uuid;
  participant_branch uuid;
BEGIN
  SELECT target.financial_account_id, target.branch_id
    INTO group_account, group_branch
    FROM public.bank_reconciliation_groups target
   WHERE target.tenant_id = NEW.tenant_id
     AND target.company_id = NEW.company_id
     AND target.id = NEW.group_id
   FOR UPDATE;

  IF NOT FOUND OR EXISTS (
    SELECT 1
      FROM public.bank_reconciliation_reversals reversal
     WHERE reversal.tenant_id = NEW.tenant_id
       AND reversal.company_id = NEW.company_id
       AND reversal.group_id = NEW.group_id
  ) THEN
    RAISE EXCEPTION 'reconciliation group is unavailable'
      USING ERRCODE = 'check_violation';
  END IF;

  IF NEW.participant_kind = 'BANK_TRANSACTION' THEN
    participant_id := NEW.bank_transaction_id;
    SELECT item.financial_account_id, item.branch_id
      INTO participant_account, participant_branch
      FROM public.bank_transactions item
     WHERE item.tenant_id = NEW.tenant_id
       AND item.company_id = NEW.company_id
       AND item.id = participant_id
     FOR UPDATE;
  ELSIF NEW.participant_kind = 'PAYMENT' THEN
    participant_id := NEW.payment_id;
    SELECT item.financial_account_id, item.branch_id
      INTO participant_account, participant_branch
      FROM public.payments item
     WHERE item.tenant_id = NEW.tenant_id
       AND item.company_id = NEW.company_id
       AND item.id = participant_id
     FOR UPDATE;
    IF EXISTS (
      SELECT 1 FROM public.payment_reversals reversal
       WHERE reversal.tenant_id = NEW.tenant_id
         AND reversal.company_id = NEW.company_id
         AND reversal.payment_id = participant_id
    ) THEN
      RAISE EXCEPTION 'reversed payment cannot be reconciled'
        USING ERRCODE = 'check_violation';
    END IF;
  ELSE
    participant_id := NEW.receipt_id;
    SELECT item.financial_account_id, item.branch_id
      INTO participant_account, participant_branch
      FROM public.receipts item
     WHERE item.tenant_id = NEW.tenant_id
       AND item.company_id = NEW.company_id
       AND item.id = participant_id
     FOR UPDATE;
    IF EXISTS (
      SELECT 1 FROM public.receipt_reversals reversal
       WHERE reversal.tenant_id = NEW.tenant_id
         AND reversal.company_id = NEW.company_id
         AND reversal.receipt_id = participant_id
    ) THEN
      RAISE EXCEPTION 'reversed receipt cannot be reconciled'
        USING ERRCODE = 'check_violation';
    END IF;
  END IF;

  IF NOT FOUND
     OR participant_account IS DISTINCT FROM group_account
     OR (
       group_branch IS NOT NULL
       AND participant_branch IS DISTINCT FROM group_branch
     ) THEN
    RAISE EXCEPTION 'reconciliation participant differs from group scope'
      USING ERRCODE = 'check_violation';
  END IF;

  INSERT INTO public.bank_reconciliation_reservations (
    tenant_id,
    company_id,
    participant_kind,
    participant_id,
    group_id,
    item_id
  ) VALUES (
    NEW.tenant_id,
    NEW.company_id,
    NEW.participant_kind,
    participant_id,
    NEW.group_id,
    NEW.id
  );
  RETURN NEW;
END
$function$;

CREATE OR REPLACE FUNCTION app.assert_reconciliation_balanced()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $function$
DECLARE
  target_group uuid;
  bank_count integer;
  financial_count integer;
  bank_total numeric(19,4);
  financial_total numeric(19,4);
  group_account uuid;
BEGIN
  IF TG_TABLE_NAME = 'bank_reconciliation_groups' THEN
    target_group := NEW.id;
  ELSE
    target_group := NEW.group_id;
  END IF;

  SELECT target.financial_account_id
    INTO group_account
    FROM public.bank_reconciliation_groups target
   WHERE target.tenant_id = NEW.tenant_id
     AND target.company_id = NEW.company_id
     AND target.id = target_group;

  SELECT
    count(*) FILTER (WHERE item.participant_kind = 'BANK_TRANSACTION'),
    count(*) FILTER (WHERE item.participant_kind IN ('PAYMENT', 'RECEIPT')),
    COALESCE(sum(transaction.amount)
      FILTER (WHERE item.participant_kind = 'BANK_TRANSACTION'), 0),
    COALESCE(sum(receipt.cash_amount)
      FILTER (WHERE item.participant_kind = 'RECEIPT'), 0)
      - COALESCE(sum(payment.cash_amount)
      FILTER (WHERE item.participant_kind = 'PAYMENT'), 0)
    INTO bank_count, financial_count, bank_total, financial_total
    FROM public.bank_reconciliation_items item
    LEFT JOIN public.bank_transactions transaction
      ON item.participant_kind = 'BANK_TRANSACTION'
     AND transaction.tenant_id = item.tenant_id
     AND transaction.company_id = item.company_id
     AND transaction.id = item.bank_transaction_id
    LEFT JOIN public.payments payment
      ON item.participant_kind = 'PAYMENT'
     AND payment.tenant_id = item.tenant_id
     AND payment.company_id = item.company_id
     AND payment.id = item.payment_id
    LEFT JOIN public.receipts receipt
      ON item.participant_kind = 'RECEIPT'
     AND receipt.tenant_id = item.tenant_id
     AND receipt.company_id = item.company_id
     AND receipt.id = item.receipt_id
   WHERE item.tenant_id = NEW.tenant_id
     AND item.company_id = NEW.company_id
     AND item.group_id = target_group;

  IF bank_count < 1 OR financial_count < 1 THEN
    RAISE EXCEPTION 'reconciliation requires bank and financial participants'
      USING ERRCODE = 'check_violation';
  END IF;
  IF bank_total <> financial_total THEN
    RAISE EXCEPTION 'reconciliation totals are not balanced'
      USING ERRCODE = 'check_violation';
  END IF;
  IF EXISTS (
    SELECT 1
      FROM public.bank_reconciliation_items item
      LEFT JOIN public.bank_transactions transaction
        ON item.participant_kind = 'BANK_TRANSACTION'
       AND transaction.tenant_id = item.tenant_id
       AND transaction.company_id = item.company_id
       AND transaction.id = item.bank_transaction_id
      LEFT JOIN public.payments payment
        ON item.participant_kind = 'PAYMENT'
       AND payment.tenant_id = item.tenant_id
       AND payment.company_id = item.company_id
       AND payment.id = item.payment_id
      LEFT JOIN public.receipts receipt
        ON item.participant_kind = 'RECEIPT'
       AND receipt.tenant_id = item.tenant_id
       AND receipt.company_id = item.company_id
       AND receipt.id = item.receipt_id
     WHERE item.tenant_id = NEW.tenant_id
       AND item.company_id = NEW.company_id
       AND item.group_id = target_group
       AND COALESCE(
         transaction.financial_account_id,
         payment.financial_account_id,
         receipt.financial_account_id
       ) IS DISTINCT FROM group_account
  ) THEN
    RAISE EXCEPTION 'reconciliation contains another financial account'
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END
$function$;

CREATE OR REPLACE FUNCTION app.reverse_reconciliation_reservations()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $function$
DECLARE
  group_branch uuid;
  group_time timestamptz;
BEGIN
  SELECT target.branch_id, target.reconciled_at
    INTO group_branch, group_time
    FROM public.bank_reconciliation_groups target
   WHERE target.tenant_id = NEW.tenant_id
     AND target.company_id = NEW.company_id
     AND target.id = NEW.group_id
   FOR UPDATE;

  IF NOT FOUND OR NEW.branch_id IS DISTINCT FROM group_branch THEN
    RAISE EXCEPTION 'reconciliation reversal scope differs from group'
      USING ERRCODE = 'check_violation';
  END IF;
  IF NEW.occurred_at < group_time OR NEW.occurred_at > clock_timestamp() THEN
    RAISE EXCEPTION 'reconciliation reversal timestamp is invalid'
      USING ERRCODE = 'check_violation';
  END IF;

  DELETE FROM public.bank_reconciliation_reservations reservation
   WHERE reservation.tenant_id = NEW.tenant_id
     AND reservation.company_id = NEW.company_id
     AND reservation.group_id = NEW.group_id;
  RETURN NEW;
END
$function$;

CREATE OR REPLACE FUNCTION app.prevent_reconciled_financial_reversal()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $function$
DECLARE
  target_kind reconciliation_participant_kind;
  target_id uuid;
BEGIN
  IF TG_TABLE_NAME = 'payment_reversals' THEN
    target_kind := 'PAYMENT';
    target_id := NEW.payment_id;
    PERFORM 1
      FROM public.payments item
     WHERE item.tenant_id = NEW.tenant_id
       AND item.company_id = NEW.company_id
       AND item.id = target_id
     FOR UPDATE;
  ELSE
    target_kind := 'RECEIPT';
    target_id := NEW.receipt_id;
    PERFORM 1
      FROM public.receipts item
     WHERE item.tenant_id = NEW.tenant_id
       AND item.company_id = NEW.company_id
       AND item.id = target_id
     FOR UPDATE;
  END IF;

  IF EXISTS (
    SELECT 1
      FROM public.bank_reconciliation_reservations reservation
     WHERE reservation.tenant_id = NEW.tenant_id
       AND reservation.company_id = NEW.company_id
       AND reservation.participant_kind = target_kind
       AND reservation.participant_id = target_id
  ) THEN
    RAISE EXCEPTION 'undo bank reconciliation before reversing financial event'
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END
$function$;

CREATE OR REPLACE FUNCTION app.prevent_account_scope_change_after_event()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
BEGIN
  IF (
    NEW.branch_id IS DISTINCT FROM OLD.branch_id
    OR NEW.currency_code IS DISTINCT FROM OLD.currency_code
    OR NEW.type IS DISTINCT FROM OLD.type
  ) AND (
    EXISTS (
      SELECT 1 FROM payments item
       WHERE item.tenant_id = OLD.tenant_id
         AND item.company_id = OLD.company_id
         AND item.financial_account_id = OLD.id
    )
    OR EXISTS (
      SELECT 1 FROM receipts item
       WHERE item.tenant_id = OLD.tenant_id
         AND item.company_id = OLD.company_id
         AND item.financial_account_id = OLD.id
    )
    OR EXISTS (
      SELECT 1 FROM bank_transactions item
       WHERE item.tenant_id = OLD.tenant_id
         AND item.company_id = OLD.company_id
         AND item.financial_account_id = OLD.id
    )
  ) THEN
    RAISE EXCEPTION 'account branch, currency and type are immutable after events'
      USING ERRCODE = 'integrity_constraint_violation';
  END IF;
  RETURN NEW;
END
$function$;

CREATE TRIGGER a_payments_account_lock
  BEFORE INSERT ON payments
  FOR EACH ROW EXECUTE FUNCTION app.lock_financial_account_for_event();
CREATE TRIGGER a_receipts_account_lock
  BEFORE INSERT ON receipts
  FOR EACH ROW EXECUTE FUNCTION app.lock_financial_account_for_event();
CREATE TRIGGER a_bank_imports_account_lock
  BEFORE INSERT ON bank_imports
  FOR EACH ROW EXECUTE FUNCTION app.lock_financial_account_for_event();
CREATE TRIGGER a_bank_transactions_account_lock
  BEFORE INSERT ON bank_transactions
  FOR EACH ROW EXECUTE FUNCTION app.lock_financial_account_for_event();
CREATE TRIGGER a_bank_reconciliation_groups_account_lock
  BEFORE INSERT ON bank_reconciliation_groups
  FOR EACH ROW EXECUTE FUNCTION app.lock_financial_account_for_event();

CREATE TRIGGER bank_imports_scope_guard
  BEFORE INSERT ON bank_imports
  FOR EACH ROW EXECUTE FUNCTION app.assert_bank_import_scope();
CREATE TRIGGER bank_transactions_scope_guard
  BEFORE INSERT ON bank_transactions
  FOR EACH ROW EXECUTE FUNCTION app.assert_bank_transaction_scope();
CREATE TRIGGER bank_reconciliation_groups_scope_guard
  BEFORE INSERT ON bank_reconciliation_groups
  FOR EACH ROW EXECUTE FUNCTION app.assert_reconciliation_group_scope();
CREATE TRIGGER bank_reconciliation_items_reserve
  AFTER INSERT ON bank_reconciliation_items
  FOR EACH ROW EXECUTE FUNCTION app.reserve_reconciliation_participant();
CREATE TRIGGER bank_reconciliation_reversals_release
  BEFORE INSERT ON bank_reconciliation_reversals
  FOR EACH ROW EXECUTE FUNCTION app.reverse_reconciliation_reservations();
CREATE TRIGGER payment_reversals_reconciliation_guard
  BEFORE INSERT ON payment_reversals
  FOR EACH ROW EXECUTE FUNCTION app.prevent_reconciled_financial_reversal();
CREATE TRIGGER receipt_reversals_reconciliation_guard
  BEFORE INSERT ON receipt_reversals
  FOR EACH ROW EXECUTE FUNCTION app.prevent_reconciled_financial_reversal();
CREATE TRIGGER financial_accounts_event_scope_guard
  BEFORE UPDATE OF branch_id, currency_code, type ON financial_accounts
  FOR EACH ROW EXECUTE FUNCTION app.prevent_account_scope_change_after_event();

CREATE CONSTRAINT TRIGGER bank_reconciliation_groups_balance_guard
  AFTER INSERT ON bank_reconciliation_groups
  DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION app.assert_reconciliation_balanced();
CREATE CONSTRAINT TRIGGER bank_reconciliation_items_balance_guard
  AFTER INSERT ON bank_reconciliation_items
  DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION app.assert_reconciliation_balanced();

CREATE CONSTRAINT TRIGGER bank_imports_complete_guard
  AFTER INSERT ON bank_imports
  DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION app.assert_bank_import_complete();
CREATE CONSTRAINT TRIGGER bank_transactions_complete_guard
  AFTER INSERT ON bank_transactions
  DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION app.assert_bank_import_complete();

CREATE TRIGGER bank_imports_immutable
  BEFORE UPDATE OR DELETE ON bank_imports
  FOR EACH ROW EXECUTE FUNCTION app.prevent_financial_event_mutation();
CREATE TRIGGER bank_transactions_immutable
  BEFORE UPDATE OR DELETE ON bank_transactions
  FOR EACH ROW EXECUTE FUNCTION app.prevent_financial_event_mutation();
CREATE TRIGGER bank_reconciliation_groups_immutable
  BEFORE UPDATE OR DELETE ON bank_reconciliation_groups
  FOR EACH ROW EXECUTE FUNCTION app.prevent_financial_event_mutation();
CREATE TRIGGER bank_reconciliation_items_immutable
  BEFORE UPDATE OR DELETE ON bank_reconciliation_items
  FOR EACH ROW EXECUTE FUNCTION app.prevent_financial_event_mutation();
CREATE TRIGGER bank_reconciliation_reversals_immutable
  BEFORE UPDATE OR DELETE ON bank_reconciliation_reversals
  FOR EACH ROW EXECUTE FUNCTION app.prevent_financial_event_mutation();

DO $bank_rls$
DECLARE
  table_name text;
BEGIN
  FOREACH table_name IN ARRAY ARRAY[
    'bank_imports',
    'bank_transactions',
    'bank_reconciliation_groups',
    'bank_reconciliation_reversals'
  ]
  LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', table_name);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', table_name);
    EXECUTE format(
      'CREATE POLICY bank_scope ON %I TO PUBLIC USING (tenant_id = app.current_tenant_id() AND app.can_access_record(company_id, branch_id)) WITH CHECK (tenant_id = app.current_tenant_id() AND app.can_access_record(company_id, branch_id))',
      table_name
    );
  END LOOP;
END
$bank_rls$;

ALTER TABLE bank_reconciliation_items ENABLE ROW LEVEL SECURITY;
ALTER TABLE bank_reconciliation_items FORCE ROW LEVEL SECURITY;
CREATE POLICY reconciliation_item_scope
  ON bank_reconciliation_items
  TO PUBLIC
  USING (
    tenant_id = app.current_tenant_id()
    AND EXISTS (
      SELECT 1
        FROM bank_reconciliation_groups target
       WHERE target.tenant_id = bank_reconciliation_items.tenant_id
         AND target.company_id = bank_reconciliation_items.company_id
         AND target.id = bank_reconciliation_items.group_id
         AND app.can_access_record(target.company_id, target.branch_id)
    )
  )
  WITH CHECK (
    tenant_id = app.current_tenant_id()
    AND EXISTS (
      SELECT 1
        FROM bank_reconciliation_groups target
       WHERE target.tenant_id = bank_reconciliation_items.tenant_id
         AND target.company_id = bank_reconciliation_items.company_id
         AND target.id = bank_reconciliation_items.group_id
         AND app.can_access_record(target.company_id, target.branch_id)
    )
  );

GRANT USAGE ON TYPE bank_import_format, reconciliation_participant_kind
TO clarity_app;
GRANT SELECT, INSERT ON
  bank_imports,
  bank_transactions,
  bank_reconciliation_groups,
  bank_reconciliation_items,
  bank_reconciliation_reversals
TO clarity_app;
REVOKE UPDATE, DELETE ON
  bank_imports,
  bank_transactions,
  bank_reconciliation_groups,
  bank_reconciliation_items,
  bank_reconciliation_reversals
FROM clarity_app;
REVOKE ALL ON bank_reconciliation_reservations FROM PUBLIC, clarity_app;

COMMENT ON TABLE bank_imports IS
  'Immutable metadata for atomic CSV/OFX imports; raw file content is not stored.';
COMMENT ON TABLE bank_transactions IS
  'Immutable signed bank statement entries; positive is credit and negative is debit.';
COMMENT ON TABLE bank_reconciliation_groups IS
  'Immutable reconciliation groups supporting 1:1, 1:N and N:1.';
COMMENT ON TABLE bank_reconciliation_items IS
  'Immutable participants of a balanced reconciliation group.';
COMMENT ON TABLE bank_reconciliation_reversals IS
  'Append-only events that undo reconciliation without deleting its history.';
COMMENT ON TABLE bank_reconciliation_reservations IS
  'Internal mutable projection reserving participants for active groups.';
