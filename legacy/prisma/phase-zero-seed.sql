-- Additional deterministic, fictional scenarios required by the Phase 0 gate.
-- This file intentionally contains no real person, company, credential or tax ID.

BEGIN;
SELECT set_config('app.tenant_id', '10000000-0000-4000-8000-000000000001', true);
SELECT set_config('app.user_id', '40000000-0000-4000-8000-000000000001', true);
SELECT set_config('app.tenant_wide_access', 'true', true);
SELECT set_config(
  'app.company_ids',
  '20000000-0000-4000-8000-000000000001,20000000-0000-4000-8000-000000000003',
  true
);
SELECT set_config('app.branch_ids', '', true);

INSERT INTO companies (
  id, tenant_id, legal_name, trade_name, tax_id, country_code, currency_code, timezone
)
VALUES (
  '20000000-0000-4000-8000-000000000003',
  '10000000-0000-4000-8000-000000000001',
  'Horizonte Serviços Demonstrativos Ltda.',
  'Horizonte Serviços Demo',
  '00.000.000/0002-01',
  'BR',
  'BRL',
  'America/Manaus'
)
ON CONFLICT DO NOTHING;

INSERT INTO branches (id, tenant_id, company_id, code, name, tax_id)
VALUES
  (
    '30000000-0000-4000-8000-000000000003',
    '10000000-0000-4000-8000-000000000001',
    '20000000-0000-4000-8000-000000000001',
    'UNIDADE-SUL',
    'Unidade Sul Manaus',
    '00.000.000/0003-01'
  ),
  (
    '30000000-0000-4000-8000-000000000004',
    '10000000-0000-4000-8000-000000000001',
    '20000000-0000-4000-8000-000000000003',
    'MATRIZ',
    'Matriz Serviços Manaus',
    '00.000.000/0002-01'
  ),
  (
    '30000000-0000-4000-8000-000000000005',
    '10000000-0000-4000-8000-000000000001',
    '20000000-0000-4000-8000-000000000003',
    'UNIDADE-LESTE',
    'Unidade Leste Manaus',
    '00.000.000/0004-01'
  )
ON CONFLICT DO NOTHING;

INSERT INTO roles (id, tenant_id, code, name, description, is_system)
VALUES
  (
    '50000000-0000-4000-8000-000000000003',
    '10000000-0000-4000-8000-000000000001',
    'financial_read_only',
    'Financeiro somente leitura',
    'Leitura financeira restrita ao escopo da membership.',
    true
  ),
  (
    '50000000-0000-4000-8000-000000000004',
    '10000000-0000-4000-8000-000000000001',
    'branch_financial_writer',
    'Financeiro por unidade',
    'Leitura e escrita financeira restritas a uma unidade.',
    true
  ),
  (
    '50000000-0000-4000-8000-000000000005',
    '10000000-0000-4000-8000-000000000001',
    'no_permissions',
    'Sem permissões',
    'Papel de teste sem qualquer permissão.',
    true
  )
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (tenant_id, role_id, permission_id)
SELECT
  '10000000-0000-4000-8000-000000000001',
  '50000000-0000-4000-8000-000000000003',
  permission.id
FROM permissions permission
WHERE permission.tenant_id = '10000000-0000-4000-8000-000000000001'
  AND permission.code IN ('companies.view', 'financial.view', 'payables.view', 'receivables.view')
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (tenant_id, role_id, permission_id)
SELECT
  '10000000-0000-4000-8000-000000000001',
  '50000000-0000-4000-8000-000000000004',
  permission.id
FROM permissions permission
WHERE permission.tenant_id = '10000000-0000-4000-8000-000000000001'
  AND permission.code IN (
    'companies.view',
    'financial.view',
    'financial.manage',
    'payables.view',
    'payables.manage',
    'receivables.view',
    'receivables.manage'
  )
ON CONFLICT DO NOTHING;

INSERT INTO users (
  id, tenant_id, email, display_name, password_hash, explanation_level, locale, is_active
)
VALUES
  (
    '40000000-0000-4000-8000-000000000003',
    '10000000-0000-4000-8000-000000000001',
    'carla.leitura@example.invalid',
    'Carla Leitura (Demo)',
    '$argon2id$v=19$m=65536,t=3,p=1$SEED_NAO_UTILIZAVEL$HASH_FICTICIO_NAO_UTILIZAVEL',
    'ESSENTIAL',
    'pt-BR',
    true
  ),
  (
    '40000000-0000-4000-8000-000000000004',
    '10000000-0000-4000-8000-000000000001',
    'diego.unidade@example.invalid',
    'Diego Unidade (Demo)',
    '$argon2id$v=19$m=65536,t=3,p=1$SEED_NAO_UTILIZAVEL$HASH_FICTICIO_NAO_UTILIZAVEL',
    'TECHNICAL',
    'pt-BR',
    true
  ),
  (
    '40000000-0000-4000-8000-000000000005',
    '10000000-0000-4000-8000-000000000001',
    'eva.sem-acesso@example.invalid',
    'Eva Sem Acesso (Demo)',
    '$argon2id$v=19$m=65536,t=3,p=1$SEED_NAO_UTILIZAVEL$HASH_FICTICIO_NAO_UTILIZAVEL',
    'ESSENTIAL',
    'pt-BR',
    true
  ),
  (
    '40000000-0000-4000-8000-000000000006',
    '10000000-0000-4000-8000-000000000001',
    'fabio.inativo@example.invalid',
    'Fábio Inativo (Demo)',
    '$argon2id$v=19$m=65536,t=3,p=1$SEED_NAO_UTILIZAVEL$HASH_FICTICIO_NAO_UTILIZAVEL',
    'MANAGERIAL',
    'pt-BR',
    true
  ),
  (
    '40000000-0000-4000-8000-000000000007',
    '10000000-0000-4000-8000-000000000001',
    'gabi.sem-permissao@example.invalid',
    'Gabi Sem Permissão (Demo)',
    '$argon2id$v=19$m=65536,t=3,p=1$SEED_NAO_UTILIZAVEL$HASH_FICTICIO_NAO_UTILIZAVEL',
    'ESSENTIAL',
    'pt-BR',
    true
  ),
  (
    '40000000-0000-4000-8000-000000000008',
    '10000000-0000-4000-8000-000000000001',
    'tiago.tenant@example.invalid',
    'Tiago Tenant (Demo)',
    '$argon2id$v=19$m=65536,t=3,p=1$SEED_NAO_UTILIZAVEL$HASH_FICTICIO_NAO_UTILIZAVEL',
    'TECHNICAL',
    'pt-BR',
    true
  )
ON CONFLICT DO NOTHING;

INSERT INTO auth_identities (
  id, tenant_id, user_id, provider, subject, email, is_active
)
VALUES
  (
    '41000000-0000-4000-8000-000000000003',
    '10000000-0000-4000-8000-000000000001',
    '40000000-0000-4000-8000-000000000003',
    'sites-siwc',
    'carla.leitura@example.invalid',
    'carla.leitura@example.invalid',
    true
  ),
  (
    '41000000-0000-4000-8000-000000000004',
    '10000000-0000-4000-8000-000000000001',
    '40000000-0000-4000-8000-000000000004',
    'sites-siwc',
    'diego.unidade@example.invalid',
    'diego.unidade@example.invalid',
    true
  ),
  (
    '41000000-0000-4000-8000-000000000005',
    '10000000-0000-4000-8000-000000000001',
    '40000000-0000-4000-8000-000000000005',
    'sites-siwc',
    'eva.sem-acesso@example.invalid',
    'eva.sem-acesso@example.invalid',
    true
  ),
  (
    '41000000-0000-4000-8000-000000000006',
    '10000000-0000-4000-8000-000000000001',
    '40000000-0000-4000-8000-000000000006',
    'sites-siwc',
    'fabio.inativo@example.invalid',
    'fabio.inativo@example.invalid',
    true
  ),
  (
    '41000000-0000-4000-8000-000000000007',
    '10000000-0000-4000-8000-000000000001',
    '40000000-0000-4000-8000-000000000007',
    'sites-siwc',
    'gabi.sem-permissao@example.invalid',
    'gabi.sem-permissao@example.invalid',
    true
  ),
  (
    '41000000-0000-4000-8000-000000000008',
    '10000000-0000-4000-8000-000000000001',
    '40000000-0000-4000-8000-000000000008',
    'sites-siwc',
    'tiago.tenant@example.invalid',
    'tiago.tenant@example.invalid',
    true
  )
ON CONFLICT DO NOTHING;

INSERT INTO memberships (
  id, tenant_id, user_id, role_id, scope, company_id, branch_id, is_active
)
VALUES
  (
    '70000000-0000-4000-8000-000000000003',
    '10000000-0000-4000-8000-000000000001',
    '40000000-0000-4000-8000-000000000003',
    '50000000-0000-4000-8000-000000000003',
    'COMPANY',
    '20000000-0000-4000-8000-000000000001',
    NULL,
    true
  ),
  (
    '70000000-0000-4000-8000-000000000004',
    '10000000-0000-4000-8000-000000000001',
    '40000000-0000-4000-8000-000000000004',
    '50000000-0000-4000-8000-000000000004',
    'BRANCH',
    '20000000-0000-4000-8000-000000000001',
    '30000000-0000-4000-8000-000000000001',
    true
  ),
  (
    '70000000-0000-4000-8000-000000000006',
    '10000000-0000-4000-8000-000000000001',
    '40000000-0000-4000-8000-000000000006',
    '50000000-0000-4000-8000-000000000003',
    'COMPANY',
    '20000000-0000-4000-8000-000000000001',
    NULL,
    false
  ),
  (
    '70000000-0000-4000-8000-000000000007',
    '10000000-0000-4000-8000-000000000001',
    '40000000-0000-4000-8000-000000000007',
    '50000000-0000-4000-8000-000000000005',
    'COMPANY',
    '20000000-0000-4000-8000-000000000001',
    NULL,
    true
  ),
  (
    '70000000-0000-4000-8000-000000000008',
    '10000000-0000-4000-8000-000000000001',
    '40000000-0000-4000-8000-000000000008',
    '50000000-0000-4000-8000-000000000001',
    'TENANT',
    NULL,
    NULL,
    true
  )
ON CONFLICT DO NOTHING;

INSERT INTO payables (
  id, tenant_id, company_id, branch_id, counterparty_name, description,
  document_number, issue_date, due_date, amount, outstanding_amount,
  currency_code, status, created_by_id
)
VALUES
  (
    '80000000-0000-4000-8000-000000000003',
    '10000000-0000-4000-8000-000000000001',
    '20000000-0000-4000-8000-000000000001',
    '30000000-0000-4000-8000-000000000003',
    'Fornecedor Unidade Sul (Fictício)',
    'Registro exclusivo da segunda unidade da Empresa A1',
    'A1-SUL-001',
    DATE '2026-07-21',
    DATE '2026-08-21',
    3100.00,
    3100.00,
    'BRL',
    'OPEN',
    '40000000-0000-4000-8000-000000000001'
  ),
  (
    '80000000-0000-4000-8000-000000000004',
    '10000000-0000-4000-8000-000000000001',
    '20000000-0000-4000-8000-000000000003',
    '30000000-0000-4000-8000-000000000004',
    'Fornecedor Empresa A2 (Fictício)',
    'Registro exclusivo da Empresa A2',
    'A2-MATRIZ-001',
    DATE '2026-07-22',
    DATE '2026-08-22',
    4200.00,
    4200.00,
    'BRL',
    'OPEN',
    '40000000-0000-4000-8000-000000000008'
  ),
  (
    '80000000-0000-4000-8000-000000000005',
    '10000000-0000-4000-8000-000000000001',
    '20000000-0000-4000-8000-000000000003',
    '30000000-0000-4000-8000-000000000005',
    'Fornecedor Unidade Leste (Fictício)',
    'Registro exclusivo da segunda unidade da Empresa A2',
    'A2-LESTE-001',
    DATE '2026-07-23',
    DATE '2026-08-23',
    5300.00,
    5300.00,
    'BRL',
    'OPEN',
    '40000000-0000-4000-8000-000000000008'
  ),
  (
    '80000000-0000-4000-8000-000000000006',
    '10000000-0000-4000-8000-000000000001',
    '20000000-0000-4000-8000-000000000001',
    NULL,
    'Fornecedor Corporativo (Fictício)',
    'Registro da Empresa A1 sem unidade',
    'A1-CORP-001',
    DATE '2026-07-24',
    DATE '2026-08-24',
    6400.00,
    6400.00,
    'BRL',
    'OPEN',
    '40000000-0000-4000-8000-000000000001'
  )
ON CONFLICT DO NOTHING;

INSERT INTO receivables (
  id, tenant_id, company_id, branch_id, counterparty_name, description,
  document_number, issue_date, due_date, amount, outstanding_amount,
  currency_code, status, created_by_id
)
VALUES
  (
    '81000000-0000-4000-8000-000000000003',
    '10000000-0000-4000-8000-000000000001',
    '20000000-0000-4000-8000-000000000001',
    '30000000-0000-4000-8000-000000000003',
    'Cliente Unidade Sul (Fictício)',
    'Recebível exclusivo da segunda unidade da Empresa A1',
    'A1-SUL-REC-001',
    DATE '2026-07-21',
    DATE '2026-08-21',
    7100.00,
    7100.00,
    'BRL',
    'OPEN',
    '40000000-0000-4000-8000-000000000001'
  ),
  (
    '81000000-0000-4000-8000-000000000004',
    '10000000-0000-4000-8000-000000000001',
    '20000000-0000-4000-8000-000000000003',
    '30000000-0000-4000-8000-000000000004',
    'Cliente Empresa A2 (Fictício)',
    'Recebível exclusivo da Empresa A2',
    'A2-MATRIZ-REC-001',
    DATE '2026-07-22',
    DATE '2026-08-22',
    8200.00,
    8200.00,
    'BRL',
    'OPEN',
    '40000000-0000-4000-8000-000000000008'
  )
ON CONFLICT DO NOTHING;

COMMIT;

BEGIN;
SELECT set_config('app.tenant_id', '10000000-0000-4000-8000-000000000002', true);
SELECT set_config('app.user_id', '40000000-0000-4000-8000-000000000002', true);
SELECT set_config('app.tenant_wide_access', 'true', true);
SELECT set_config('app.company_ids', '20000000-0000-4000-8000-000000000002', true);
SELECT set_config('app.branch_ids', '', true);

INSERT INTO branches (id, tenant_id, company_id, code, name, tax_id)
VALUES (
  '30000000-0000-4000-8000-000000000006',
  '10000000-0000-4000-8000-000000000002',
  '20000000-0000-4000-8000-000000000002',
  'UNIDADE-SUL',
  'Unidade Sul Recife',
  '00.000.000/0002-02'
)
ON CONFLICT DO NOTHING;

INSERT INTO payables (
  id, tenant_id, company_id, branch_id, counterparty_name, description,
  document_number, issue_date, due_date, amount, outstanding_amount,
  currency_code, status, created_by_id
)
VALUES (
  '80000000-0000-4000-8000-000000000102',
  '10000000-0000-4000-8000-000000000002',
  '20000000-0000-4000-8000-000000000002',
  '30000000-0000-4000-8000-000000000006',
  'Fornecedor Unidade Sul B (Fictício)',
  'Registro exclusivo da segunda unidade do Tenant B',
  'B-SUL-001',
  DATE '2026-07-24',
  DATE '2026-08-24',
  9900.00,
  9900.00,
  'BRL',
  'OPEN',
  '40000000-0000-4000-8000-000000000002'
)
ON CONFLICT DO NOTHING;

COMMIT;
