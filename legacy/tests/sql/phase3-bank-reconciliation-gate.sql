DO $gate$
DECLARE
  target_tenant uuid := '10000000-0000-4000-8000-000000000001';
  target_company uuid := '20000000-0000-4000-8000-000000000001';
  target_branch uuid := '30000000-0000-4000-8000-000000000001';
  target_user uuid := '40000000-0000-4000-8000-000000000001';
  target_account uuid := '85000000-0000-4000-8000-000000000001';
  target_payment uuid;
  target_cash numeric(19,4);
  incomplete_import_id uuid := '91000000-0000-4000-8000-000000000099';
  import_id uuid := '91000000-0000-4000-8000-000000000001';
  transaction_id uuid := '92000000-0000-4000-8000-000000000001';
  group_id uuid := '93000000-0000-4000-8000-000000000001';
  second_group_id uuid := '93000000-0000-4000-8000-000000000002';
BEGIN
  SELECT payment.id, payment.cash_amount
    INTO target_payment, target_cash
    FROM payments payment
   WHERE payment.tenant_id = target_tenant
     AND payment.company_id = target_company
     AND payment.financial_account_id = target_account
     AND NOT EXISTS (
       SELECT 1
         FROM payment_reversals reversal
        WHERE reversal.tenant_id = payment.tenant_id
          AND reversal.company_id = payment.company_id
          AND reversal.payment_id = payment.id
     )
   ORDER BY payment.id
   LIMIT 1;

  IF target_payment IS NULL THEN
    RAISE EXCEPTION 'gate requires one unreversed payment';
  END IF;

  IF (
    SELECT count(*)
      FROM pg_trigger trigger
     WHERE trigger.tgname IN (
       'a_payments_account_lock',
       'a_receipts_account_lock',
       'a_bank_imports_account_lock',
       'a_bank_transactions_account_lock',
       'a_bank_reconciliation_groups_account_lock'
     )
       AND NOT trigger.tgisinternal
  ) <> 5 THEN
    RAISE EXCEPTION 'financial account event locks are incomplete';
  END IF;

  BEGIN
    INSERT INTO bank_imports (
      id, tenant_id, company_id, branch_id, financial_account_id, format,
      content_hash, source_name, total_rows, imported_rows, possible_duplicates,
      created_by_id
    ) VALUES (
      incomplete_import_id, target_tenant, target_company, target_branch,
      target_account, 'CSV', repeat('f', 64), 'incomplete-phase3-gate.csv',
      1, 1, 0, target_user
    );
    SET CONSTRAINTS ALL IMMEDIATE;
    RAISE EXCEPTION 'incomplete bank import was accepted';
  EXCEPTION
    WHEN check_violation THEN
      SET CONSTRAINTS ALL DEFERRED;
  END;

  INSERT INTO bank_imports (
    id, tenant_id, company_id, branch_id, financial_account_id, format,
    content_hash, source_name, total_rows, imported_rows, possible_duplicates,
    created_by_id
  ) VALUES (
    import_id, target_tenant, target_company, target_branch, target_account,
    'CSV', repeat('a', 64), 'phase3-gate.csv', 1, 1, 0, target_user
  );

  INSERT INTO bank_transactions (
    id, tenant_id, company_id, branch_id, bank_import_id,
    financial_account_id, source_line, external_id, fingerprint, booked_at,
    amount, currency_code, description
  ) VALUES (
    transaction_id, target_tenant, target_company, target_branch, import_id,
    target_account, 2, 'PHASE3-GATE-1', repeat('b', 64),
    clock_timestamp() - interval '1 day', -target_cash, 'BRL',
    'Débito sintético do gate da Fase 3'
  );

  INSERT INTO bank_reconciliation_groups (
    id, tenant_id, company_id, branch_id, financial_account_id,
    reconciled_at, note, created_by_id
  ) VALUES (
    group_id, target_tenant, target_company, target_branch, target_account,
    clock_timestamp(), 'Conciliação 1:1 do gate', target_user
  );
  INSERT INTO bank_reconciliation_items (
    tenant_id, company_id, group_id, participant_kind, bank_transaction_id
  ) VALUES (
    target_tenant, target_company, group_id, 'BANK_TRANSACTION', transaction_id
  );
  INSERT INTO bank_reconciliation_items (
    tenant_id, company_id, group_id, participant_kind, payment_id
  ) VALUES (
    target_tenant, target_company, group_id, 'PAYMENT', target_payment
  );
  SET CONSTRAINTS ALL IMMEDIATE;
  SET CONSTRAINTS ALL DEFERRED;

  BEGIN
    INSERT INTO bank_transactions (
      tenant_id, company_id, branch_id, bank_import_id, financial_account_id,
      source_line, external_id, fingerprint, booked_at, amount, currency_code,
      description
    ) VALUES (
      target_tenant, target_company, target_branch, import_id, target_account,
      3, 'PHASE3-GATE-EXTRA', repeat('c', 64),
      clock_timestamp() - interval '1 day', -1, 'BRL',
      'Linha excedente que deve ser rejeitada'
    );
    SET CONSTRAINTS ALL IMMEDIATE;
    RAISE EXCEPTION 'bank import accepted more rows than declared';
  EXCEPTION
    WHEN check_violation THEN
      SET CONSTRAINTS ALL DEFERRED;
  END;

  BEGIN
    INSERT INTO bank_reconciliation_groups (
      id, tenant_id, company_id, branch_id, financial_account_id,
      reconciled_at, created_by_id
    ) VALUES (
      second_group_id, target_tenant, target_company, target_branch,
      target_account, clock_timestamp(), target_user
    );
    INSERT INTO bank_reconciliation_items (
      tenant_id, company_id, group_id, participant_kind, bank_transaction_id
    ) VALUES (
      target_tenant, target_company, second_group_id,
      'BANK_TRANSACTION', transaction_id
    );
    RAISE EXCEPTION 'active reservation accepted twice';
  EXCEPTION
    WHEN unique_violation THEN NULL;
  END;

  BEGIN
    INSERT INTO payment_reversals (
      tenant_id, company_id, branch_id, payment_id, reason, occurred_at,
      created_by_id
    ) VALUES (
      target_tenant, target_company, target_branch, target_payment,
      'Deveria exigir desfazer conciliação', clock_timestamp(), target_user
    );
    RAISE EXCEPTION 'reconciled payment reversal was accepted';
  EXCEPTION
    WHEN check_violation THEN NULL;
  END;

  BEGIN
    UPDATE financial_accounts
       SET currency_code = 'USD'
     WHERE tenant_id = target_tenant
       AND company_id = target_company
       AND id = target_account;
    RAISE EXCEPTION 'account currency changed after events';
  EXCEPTION
    WHEN integrity_constraint_violation THEN NULL;
  END;

  BEGIN
    UPDATE bank_reconciliation_groups
       SET note = 'Mutação proibida'
     WHERE tenant_id = target_tenant
       AND company_id = target_company
       AND id = group_id;
    RAISE EXCEPTION 'append-only group accepted update';
  EXCEPTION
    WHEN integrity_constraint_violation THEN NULL;
  END;

  BEGIN
    INSERT INTO bank_reconciliation_groups (
      id, tenant_id, company_id, branch_id, financial_account_id,
      reconciled_at, created_by_id
    ) VALUES (
      '93000000-0000-4000-8000-000000000099',
      target_tenant, target_company, target_branch, target_account,
      clock_timestamp(), target_user
    );
    SET CONSTRAINTS ALL IMMEDIATE;
    RAISE EXCEPTION 'empty reconciliation group was accepted';
  EXCEPTION
    WHEN check_violation THEN
      SET CONSTRAINTS ALL DEFERRED;
  END;

  INSERT INTO bank_reconciliation_reversals (
    tenant_id, company_id, branch_id, group_id, reason, occurred_at,
    created_by_id
  ) VALUES (
    target_tenant, target_company, target_branch, group_id,
    'Desfazer conciliação sintética', clock_timestamp(), target_user
  );

  INSERT INTO bank_reconciliation_groups (
    id, tenant_id, company_id, branch_id, financial_account_id,
    reconciled_at, note, created_by_id
  ) VALUES (
    second_group_id, target_tenant, target_company, target_branch,
    target_account, clock_timestamp(), 'Reconciliação após reversão', target_user
  );
  INSERT INTO bank_reconciliation_items (
    tenant_id, company_id, group_id, participant_kind, bank_transaction_id
  ) VALUES (
    target_tenant, target_company, second_group_id,
    'BANK_TRANSACTION', transaction_id
  );
  INSERT INTO bank_reconciliation_items (
    tenant_id, company_id, group_id, participant_kind, payment_id
  ) VALUES (
    target_tenant, target_company, second_group_id, 'PAYMENT', target_payment
  );
  SET CONSTRAINTS ALL IMMEDIATE;
END
$gate$;
