-- Fictional, deterministic data for local development only.
-- Password values below are intentionally unusable Argon2id-shaped placeholders;
-- no plaintext credential or real personal/company data is included.

BEGIN;
SELECT set_config('app.tenant_id', '10000000-0000-4000-8000-000000000001', true);
SELECT set_config('app.user_id', '40000000-0000-4000-8000-000000000001', true);
SELECT set_config('app.tenant_wide_access', 'true', true);
SELECT set_config('app.company_ids', '20000000-0000-4000-8000-000000000001', true);

INSERT INTO tenants (id, name, slug, settings)
VALUES (
  '10000000-0000-4000-8000-000000000001',
  'Grupo Horizonte (Fictício)',
  'grupo-horizonte-demo',
  '{"environment":"demo","dataClassification":"fictional"}'::jsonb
)
ON CONFLICT DO NOTHING;

INSERT INTO companies (
  id, tenant_id, legal_name, trade_name, tax_id, country_code, currency_code, timezone
)
VALUES (
  '20000000-0000-4000-8000-000000000001',
  '10000000-0000-4000-8000-000000000001',
  'Horizonte Comércio Demonstrativo Ltda.',
  'Horizonte Demo',
  '00.000.000/0001-01',
  'BR',
  'BRL',
  'America/Manaus'
)
ON CONFLICT DO NOTHING;

INSERT INTO branches (id, tenant_id, company_id, code, name, tax_id)
VALUES (
  '30000000-0000-4000-8000-000000000001',
  '10000000-0000-4000-8000-000000000001',
  '20000000-0000-4000-8000-000000000001',
  'MATRIZ',
  'Matriz Manaus',
  '00.000.000/0001-01'
)
ON CONFLICT DO NOTHING;

INSERT INTO users (
  id, tenant_id, email, display_name, password_hash, explanation_level, locale, is_active
)
VALUES (
  '40000000-0000-4000-8000-000000000001',
  '10000000-0000-4000-8000-000000000001',
  'ana.horizonte@example.invalid',
  'Ana Souza (Demo)',
  '$argon2id$v=19$m=65536,t=3,p=1$SEED_NAO_UTILIZAVEL$HASH_FICTICIO_NAO_UTILIZAVEL',
  'MANAGERIAL',
  'pt-BR',
  true
)
ON CONFLICT DO NOTHING;

INSERT INTO auth_identities (
  id, tenant_id, user_id, provider, subject, email, is_active
)
VALUES (
  '41000000-0000-4000-8000-000000000001',
  '10000000-0000-4000-8000-000000000001',
  '40000000-0000-4000-8000-000000000001',
  'sites-siwc',
  'ana.horizonte@example.invalid',
  'ana.horizonte@example.invalid',
  true
)
ON CONFLICT DO NOTHING;

INSERT INTO roles (id, tenant_id, code, name, description, is_system)
VALUES (
  '50000000-0000-4000-8000-000000000001',
  '10000000-0000-4000-8000-000000000001',
  'company_admin',
  'Administrador da empresa',
  'Administração da empresa demo, sem acesso a outros tenants.',
  true
)
ON CONFLICT DO NOTHING;

INSERT INTO permissions (id, tenant_id, code, description)
VALUES
  ('60000000-0000-4000-8000-000000000001', '10000000-0000-4000-8000-000000000001', 'companies.view', 'Visualizar empresas autorizadas'),
  ('60000000-0000-4000-8000-000000000002', '10000000-0000-4000-8000-000000000001', 'financial.view', 'Visualizar visão financeira'),
  ('60000000-0000-4000-8000-000000000003', '10000000-0000-4000-8000-000000000001', 'payables.view', 'Visualizar contas a pagar'),
  ('60000000-0000-4000-8000-000000000004', '10000000-0000-4000-8000-000000000001', 'payables.manage', 'Gerenciar contas a pagar'),
  ('60000000-0000-4000-8000-000000000005', '10000000-0000-4000-8000-000000000001', 'receivables.view', 'Visualizar contas a receber'),
  ('60000000-0000-4000-8000-000000000006', '10000000-0000-4000-8000-000000000001', 'receivables.manage', 'Gerenciar contas a receber'),
  ('60000000-0000-4000-8000-000000000007', '10000000-0000-4000-8000-000000000001', 'accounting.view', 'Visualizar contabilidade'),
  ('60000000-0000-4000-8000-000000000008', '10000000-0000-4000-8000-000000000001', 'audit.view', 'Visualizar auditoria'),
  ('60000000-0000-4000-8000-000000000009', '10000000-0000-4000-8000-000000000001', 'users.manage', 'Gerenciar usuários'),
  ('60000000-0000-4000-8000-000000000010', '10000000-0000-4000-8000-000000000001', 'permissions.manage', 'Gerenciar perfis e permissões'),
  ('60000000-0000-4000-8000-000000000011', '10000000-0000-4000-8000-000000000001', 'risks.manage', 'Gerenciar riscos e planos de ação'),
  ('60000000-0000-4000-8000-000000000012', '10000000-0000-4000-8000-000000000001', 'financial.manage', 'Gerenciar cadastros e eventos financeiros')
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (tenant_id, role_id, permission_id)
SELECT
  '10000000-0000-4000-8000-000000000001',
  '50000000-0000-4000-8000-000000000001',
  permission.id
FROM permissions permission
WHERE permission.tenant_id = '10000000-0000-4000-8000-000000000001'
ON CONFLICT DO NOTHING;

INSERT INTO memberships (
  id, tenant_id, user_id, role_id, scope, company_id, branch_id, is_active
)
VALUES (
  '70000000-0000-4000-8000-000000000001',
  '10000000-0000-4000-8000-000000000001',
  '40000000-0000-4000-8000-000000000001',
  '50000000-0000-4000-8000-000000000001',
  'COMPANY',
  '20000000-0000-4000-8000-000000000001',
  NULL,
  true
)
ON CONFLICT DO NOTHING;

INSERT INTO counterparties (
  id, tenant_id, company_id, branch_id, code, name, tax_id, kind, created_by_id
)
VALUES
  (
    '82000000-0000-4000-8000-000000000001',
    '10000000-0000-4000-8000-000000000001',
    '20000000-0000-4000-8000-000000000001',
    '30000000-0000-4000-8000-000000000001',
    'FORN-DEMO',
    'Fornecedor Horizonte (Fictício)',
    '00.000.000/0101-01',
    'SUPPLIER',
    '40000000-0000-4000-8000-000000000001'
  ),
  (
    '82000000-0000-4000-8000-000000000002',
    '10000000-0000-4000-8000-000000000001',
    '20000000-0000-4000-8000-000000000001',
    '30000000-0000-4000-8000-000000000001',
    'CLI-DEMO',
    'Cliente Horizonte (Fictício)',
    '00.000.000/0102-01',
    'CUSTOMER',
    '40000000-0000-4000-8000-000000000001'
  ),
  (
    '82000000-0000-4000-8000-000000000003',
    '10000000-0000-4000-8000-000000000001',
    '20000000-0000-4000-8000-000000000001',
    NULL,
    'PARC-CORP',
    'Parceiro corporativo compartilhado (Fictício)',
    NULL,
    'BOTH',
    '40000000-0000-4000-8000-000000000001'
  )
ON CONFLICT DO NOTHING;

INSERT INTO financial_categories (
  id, tenant_id, company_id, branch_id, code, name, direction, created_by_id
)
VALUES
  (
    '83000000-0000-4000-8000-000000000001',
    '10000000-0000-4000-8000-000000000001',
    '20000000-0000-4000-8000-000000000001',
    '30000000-0000-4000-8000-000000000001',
    'DESP-OPER',
    'Despesa operacional (Demo)',
    'PAYABLE',
    '40000000-0000-4000-8000-000000000001'
  ),
  (
    '83000000-0000-4000-8000-000000000002',
    '10000000-0000-4000-8000-000000000001',
    '20000000-0000-4000-8000-000000000001',
    '30000000-0000-4000-8000-000000000001',
    'REC-OPER',
    'Receita operacional (Demo)',
    'RECEIVABLE',
    '40000000-0000-4000-8000-000000000001'
  ),
  (
    '83000000-0000-4000-8000-000000000003',
    '10000000-0000-4000-8000-000000000001',
    '20000000-0000-4000-8000-000000000001',
    NULL,
    'FLUXO-CORP',
    'Fluxo corporativo compartilhado (Demo)',
    'BOTH',
    '40000000-0000-4000-8000-000000000001'
  )
ON CONFLICT DO NOTHING;

INSERT INTO cost_centers (
  id, tenant_id, company_id, branch_id, code, name, created_by_id
)
VALUES
  (
    '84000000-0000-4000-8000-000000000001',
    '10000000-0000-4000-8000-000000000001',
    '20000000-0000-4000-8000-000000000001',
    '30000000-0000-4000-8000-000000000001',
    'OPER-DEMO',
    'Operação (Demo)',
    '40000000-0000-4000-8000-000000000001'
  ),
  (
    '84000000-0000-4000-8000-000000000002',
    '10000000-0000-4000-8000-000000000001',
    '20000000-0000-4000-8000-000000000001',
    NULL,
    'CORP-DEMO',
    'Corporativo compartilhado (Demo)',
    '40000000-0000-4000-8000-000000000001'
  )
ON CONFLICT DO NOTHING;

INSERT INTO financial_accounts (
  id, tenant_id, company_id, branch_id, code, name, type, currency_code, created_by_id
)
VALUES
  (
    '85000000-0000-4000-8000-000000000001',
    '10000000-0000-4000-8000-000000000001',
    '20000000-0000-4000-8000-000000000001',
    '30000000-0000-4000-8000-000000000001',
    'BANCO-DEMO',
    'Conta bancária demonstrativa',
    'CHECKING',
    'BRL',
    '40000000-0000-4000-8000-000000000001'
  ),
  (
    '85000000-0000-4000-8000-000000000002',
    '10000000-0000-4000-8000-000000000001',
    '20000000-0000-4000-8000-000000000001',
    NULL,
    'CAIXA-CORP',
    'Caixa corporativo compartilhado (Demo)',
    'CASH',
    'BRL',
    '40000000-0000-4000-8000-000000000001'
  )
ON CONFLICT DO NOTHING;

INSERT INTO payables (
  id, tenant_id, company_id, branch_id, counterparty_name, description,
  document_number, issue_date, due_date, amount, outstanding_amount,
  currency_code, status, created_by_id
)
VALUES
  (
    '80000000-0000-4000-8000-000000000001',
    '10000000-0000-4000-8000-000000000001',
    '20000000-0000-4000-8000-000000000001',
    '30000000-0000-4000-8000-000000000001',
    'Logística Aurora (Fictícia)',
    'Frete de mercadorias — demonstração',
    'NF-DEMO-1042',
    DATE '2026-07-01',
    DATE '2026-07-15',
    18450.00,
    18450.00,
    'BRL',
    'OVERDUE',
    '40000000-0000-4000-8000-000000000001'
  ),
  (
    '80000000-0000-4000-8000-000000000002',
    '10000000-0000-4000-8000-000000000001',
    '20000000-0000-4000-8000-000000000001',
    '30000000-0000-4000-8000-000000000001',
    'Energia Modelo S.A. (Fictícia)',
    'Energia elétrica — demonstração',
    'FAT-DEMO-0726',
    DATE '2026-07-05',
    DATE '2026-07-25',
    9270.00,
    9270.00,
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
VALUES (
  '81000000-0000-4000-8000-000000000001',
  '10000000-0000-4000-8000-000000000001',
  '20000000-0000-4000-8000-000000000001',
  '30000000-0000-4000-8000-000000000001',
  'Cliente Estrela (Fictício)',
  'Venda empresarial — demonstração',
  'VENDA-DEMO-2208',
  DATE '2026-07-10',
  DATE '2026-08-10',
  125000.00,
  125000.00,
  'BRL',
  'OPEN',
  '40000000-0000-4000-8000-000000000001'
)
ON CONFLICT DO NOTHING;

INSERT INTO journal_entries (
  id, tenant_id, company_id, branch_id, entry_number, entry_date, description,
  source_type, source_id, status, posted_at, created_by_id
)
VALUES (
  '90000000-0000-4000-8000-000000000001',
  '10000000-0000-4000-8000-000000000001',
  '20000000-0000-4000-8000-000000000001',
  '30000000-0000-4000-8000-000000000001',
  'LC-2026-000001',
  DATE '2026-07-10',
  'Reconhecimento de venda fictícia',
  'RECEIVABLE',
  '81000000-0000-4000-8000-000000000001',
  'DRAFT',
  NULL,
  '40000000-0000-4000-8000-000000000001'
)
ON CONFLICT DO NOTHING;

INSERT INTO journal_entry_lines (
  id, tenant_id, company_id, journal_entry_id, line_number, account_code,
  account_name, description, debit, credit
)
SELECT
  seed.id::uuid,
  seed.tenant_id::uuid,
  seed.company_id::uuid,
  seed.journal_entry_id::uuid,
  seed.line_number::integer,
  seed.account_code,
  seed.account_name,
  seed.description,
  seed.debit::numeric,
  seed.credit::numeric
FROM (VALUES
  (
    '91000000-0000-4000-8000-000000000001',
    '10000000-0000-4000-8000-000000000001',
    '20000000-0000-4000-8000-000000000001',
    '90000000-0000-4000-8000-000000000001',
    1,
    '1.1.02.001',
    'Clientes a receber',
    'Venda fictícia',
    125000.00,
    0
  ),
  (
    '91000000-0000-4000-8000-000000000002',
    '10000000-0000-4000-8000-000000000001',
    '20000000-0000-4000-8000-000000000001',
    '90000000-0000-4000-8000-000000000001',
    2,
    '3.1.01.001',
    'Receita bruta de vendas',
    'Venda fictícia',
    0,
    125000.00
  )
) AS seed (
  id, tenant_id, company_id, journal_entry_id, line_number, account_code,
  account_name, description, debit, credit
)
WHERE EXISTS (
  SELECT 1
  FROM journal_entries entry
  WHERE entry.tenant_id = seed.tenant_id::uuid
    AND entry.company_id = seed.company_id::uuid
    AND entry.id = seed.journal_entry_id::uuid
    AND entry.status = 'DRAFT'
)
ON CONFLICT DO NOTHING;

UPDATE journal_entries
   SET status = 'POSTED', posted_at = TIMESTAMPTZ '2026-07-10 14:00:00-04'
 WHERE tenant_id = '10000000-0000-4000-8000-000000000001'
   AND id = '90000000-0000-4000-8000-000000000001'
   AND status = 'DRAFT';

INSERT INTO glossary_terms (
  id, tenant_id, slug, name, acronym, category, simple_definition,
  managerial_definition, technical_definition, practical_example,
  numeric_example, formula, importance, where_appears, interpretation,
  common_errors, related_risks, related_term_slugs, "references", last_reviewed_at
)
VALUES
  (
    '92000000-0000-4000-8000-000000000001',
    '10000000-0000-4000-8000-000000000001',
    'ativo-circulante',
    'Ativo Circulante',
    NULL,
    'ACCOUNTING',
    'Recursos que a empresa possui ou espera transformar em dinheiro no curto prazo.',
    'Mostra parte dos recursos disponíveis para sustentar a operação e os compromissos de curto prazo.',
    'Grupo do ativo realizável no ciclo operacional ou, em regra, até o término do exercício seguinte.',
    'Caixa, bancos, aplicações, clientes a receber e estoques.',
    'Caixa de R$ 40 mil + clientes de R$ 60 mil = R$ 100 mil de ativo circulante, antes de outros itens.',
    NULL,
    'Ajuda a compreender liquidez e capital de giro.',
    'Balanço Patrimonial e indicadores de liquidez.',
    'Deve ser analisado junto da qualidade e do prazo de realização de cada saldo.',
    'Tratar todo ativo circulante como dinheiro imediatamente disponível.',
    'Inadimplência, estoque obsoleto e baixa liquidez.',
    ARRAY['passivo-circulante', 'liquidez-corrente', 'capital-de-giro'],
    '[{"title":"CPC 26 — Apresentação das Demonstrações Contábeis","kind":"standard"}]'::jsonb,
    DATE '2026-07-01'
  ),
  (
    '92000000-0000-4000-8000-000000000002',
    '10000000-0000-4000-8000-000000000001',
    'liquidez-corrente',
    'Liquidez Corrente',
    'LC',
    'INDICATORS',
    'Relação entre ativos e obrigações de curto prazo registrados pela empresa.',
    'Ajuda a observar a folga financeira de curto prazo, sem funcionar como nota automática de saúde.',
    'Quociente entre Ativo Circulante e Passivo Circulante.',
    'Compara o que tende a virar caixa com o que vence no curto prazo.',
    'Ativo circulante de R$ 143 mil / passivo circulante de R$ 100 mil = 1,43.',
    'Ativo Circulante ÷ Passivo Circulante',
    'Apoia a análise da capacidade de cumprir compromissos de curto prazo.',
    'Dashboard, Balanço Patrimonial e análises financeiras.',
    'Exige contexto de histórico, setor, sazonalidade e qualidade dos ativos.',
    'Classificar isoladamente um índice acima de 1 como bom ou abaixo de 1 como ruim.',
    'Concentração de vencimentos, inadimplência e descasamento de prazos.',
    ARRAY['ativo-circulante', 'passivo-circulante'],
    '[]'::jsonb,
    DATE '2026-07-01'
  )
ON CONFLICT DO NOTHING;

INSERT INTO pending_items (
  id, tenant_id, company_id, branch_id, type, category, title, description,
  responsible_user_id, priority, due_date, status, source_type, source_id,
  impact, suggested_action
)
VALUES (
  '93000000-0000-4000-8000-000000000001',
  '10000000-0000-4000-8000-000000000001',
  '20000000-0000-4000-8000-000000000001',
  '30000000-0000-4000-8000-000000000001',
  'OVERDUE_PAYABLE',
  'FINANCIAL',
  'Conta a pagar vencida há 5 dias',
  'Pendência fictícia vinculada à conta da Logística Aurora.',
  '40000000-0000-4000-8000-000000000001',
  'IMPORTANT',
  DATE '2026-07-15',
  'OPEN',
  'PAYABLE',
  '80000000-0000-4000-8000-000000000001',
  'Possível incidência de encargos e impacto no relacionamento com o fornecedor.',
  'Confirmar documento, negociar o prazo e registrar a decisão.'
)
ON CONFLICT DO NOTHING;

INSERT INTO risks (
  id, tenant_id, company_id, branch_id, title, area, problem, evidence, cause,
  probability, impact, financial_impact, operational_impact, criticality,
  owner_user_id, due_date, status
)
VALUES (
  '94000000-0000-4000-8000-000000000001',
  '10000000-0000-4000-8000-000000000001',
  '20000000-0000-4000-8000-000000000001',
  '30000000-0000-4000-8000-000000000001',
  'Concentração de recebimentos em um cliente',
  'Financeiro',
  'Parcela relevante das contas a receber está concentrada em um único cliente fictício.',
  'Título demo de R$ 125.000 representa a maior exposição individual do período.',
  'Carteira comercial pouco diversificada no conjunto fictício.',
  'MEDIUM',
  'HIGH',
  125000.00,
  'Atrasos podem pressionar o caixa projetado.',
  'IMPORTANT',
  '40000000-0000-4000-8000-000000000001',
  DATE '2026-08-15',
  'TREATMENT_PLANNED'
)
ON CONFLICT DO NOTHING;

INSERT INTO action_plans (
  id, tenant_id, company_id, branch_id, risk_id, title, description,
  owner_user_id, priority, status, start_date, due_date, progress_percent,
  success_criteria
)
VALUES (
  '95000000-0000-4000-8000-000000000001',
  '10000000-0000-4000-8000-000000000001',
  '20000000-0000-4000-8000-000000000001',
  '30000000-0000-4000-8000-000000000001',
  '94000000-0000-4000-8000-000000000001',
  'Revisar política de crédito e concentração',
  'Definir limites fictícios por cliente e acompanhar a exposição semanalmente.',
  '40000000-0000-4000-8000-000000000001',
  'IMPORTANT',
  'IN_PROGRESS',
  DATE '2026-07-20',
  DATE '2026-08-15',
  25,
  'Política aprovada e painel de concentração revisado pela gestão.'
)
ON CONFLICT DO NOTHING;

INSERT INTO audit_logs (
  id, tenant_id, company_id, branch_id, actor_user_id, session_id, action,
  entity_type, entity_id, after_data, metadata, redacted_fields, ip_hash,
  user_agent, integrity_hash, occurred_at
)
VALUES (
  '96000000-0000-4000-8000-000000000001',
  '10000000-0000-4000-8000-000000000001',
  '20000000-0000-4000-8000-000000000001',
  '30000000-0000-4000-8000-000000000001',
  '40000000-0000-4000-8000-000000000001',
  '97000000-0000-4000-8000-000000000001',
  'payable.created',
  'Payable',
  '80000000-0000-4000-8000-000000000001',
  '{"status":"OVERDUE","amount":"18450.00","counterparty":"[REDACTED_DEMO]"}'::jsonb,
  '{"source":"seed","fictional":true}'::jsonb,
  ARRAY['counterparty'],
  encode(digest('192.0.2.1', 'sha256'), 'hex'),
  'seed/1.0',
  encode(digest('demo-audit-event-a', 'sha256'), 'hex'),
  TIMESTAMPTZ '2026-07-20 09:00:00-04'
)
ON CONFLICT DO NOTHING;

COMMIT;

BEGIN;
SELECT set_config('app.tenant_id', '10000000-0000-4000-8000-000000000002', true);
SELECT set_config('app.user_id', '40000000-0000-4000-8000-000000000002', true);
SELECT set_config('app.tenant_wide_access', 'true', true);
SELECT set_config('app.company_ids', '20000000-0000-4000-8000-000000000002', true);

INSERT INTO tenants (id, name, slug, settings)
VALUES (
  '10000000-0000-4000-8000-000000000002',
  'Grupo Atlântico (Fictício)',
  'grupo-atlantico-demo',
  '{"environment":"demo","dataClassification":"fictional"}'::jsonb
)
ON CONFLICT DO NOTHING;

INSERT INTO companies (
  id, tenant_id, legal_name, trade_name, tax_id, country_code, currency_code, timezone
)
VALUES (
  '20000000-0000-4000-8000-000000000002',
  '10000000-0000-4000-8000-000000000002',
  'Atlântico Serviços Demonstrativos S.A.',
  'Atlântico Demo',
  '00.000.000/0001-02',
  'BR',
  'BRL',
  'America/Recife'
)
ON CONFLICT DO NOTHING;

INSERT INTO branches (id, tenant_id, company_id, code, name, tax_id)
VALUES (
  '30000000-0000-4000-8000-000000000002',
  '10000000-0000-4000-8000-000000000002',
  '20000000-0000-4000-8000-000000000002',
  'MATRIZ',
  'Matriz Recife',
  '00.000.000/0001-02'
)
ON CONFLICT DO NOTHING;

INSERT INTO users (
  id, tenant_id, email, display_name, password_hash, explanation_level, locale, is_active
)
VALUES (
  '40000000-0000-4000-8000-000000000002',
  '10000000-0000-4000-8000-000000000002',
  'bruno.atlantico@example.invalid',
  'Bruno Lima (Demo)',
  '$argon2id$v=19$m=65536,t=3,p=1$SEED_NAO_UTILIZAVEL$HASH_FICTICIO_NAO_UTILIZAVEL',
  'TECHNICAL',
  'pt-BR',
  true
)
ON CONFLICT DO NOTHING;

INSERT INTO auth_identities (
  id, tenant_id, user_id, provider, subject, email, is_active
)
VALUES (
  '41000000-0000-4000-8000-000000000002',
  '10000000-0000-4000-8000-000000000002',
  '40000000-0000-4000-8000-000000000002',
  'sites-siwc',
  'bruno.atlantico@example.invalid',
  'bruno.atlantico@example.invalid',
  true
)
ON CONFLICT DO NOTHING;

INSERT INTO roles (id, tenant_id, code, name, description, is_system)
VALUES (
  '50000000-0000-4000-8000-000000000002',
  '10000000-0000-4000-8000-000000000002',
  'company_admin',
  'Administrador da empresa',
  'Administrador restrito ao tenant Atlântico demo.',
  true
)
ON CONFLICT DO NOTHING;

INSERT INTO permissions (id, tenant_id, code, description)
VALUES
  ('61000000-0000-4000-8000-000000000001', '10000000-0000-4000-8000-000000000002', 'companies.view', 'Visualizar empresas autorizadas'),
  ('61000000-0000-4000-8000-000000000002', '10000000-0000-4000-8000-000000000002', 'financial.view', 'Visualizar visão financeira'),
  ('61000000-0000-4000-8000-000000000003', '10000000-0000-4000-8000-000000000002', 'payables.view', 'Visualizar contas a pagar'),
  ('61000000-0000-4000-8000-000000000004', '10000000-0000-4000-8000-000000000002', 'payables.manage', 'Gerenciar contas a pagar'),
  ('61000000-0000-4000-8000-000000000005', '10000000-0000-4000-8000-000000000002', 'receivables.view', 'Visualizar contas a receber'),
  ('61000000-0000-4000-8000-000000000006', '10000000-0000-4000-8000-000000000002', 'accounting.view', 'Visualizar contabilidade'),
  ('61000000-0000-4000-8000-000000000007', '10000000-0000-4000-8000-000000000002', 'audit.view', 'Visualizar auditoria'),
  ('61000000-0000-4000-8000-000000000008', '10000000-0000-4000-8000-000000000002', 'financial.manage', 'Gerenciar cadastros e eventos financeiros')
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (tenant_id, role_id, permission_id)
SELECT
  '10000000-0000-4000-8000-000000000002',
  '50000000-0000-4000-8000-000000000002',
  permission.id
FROM permissions permission
WHERE permission.tenant_id = '10000000-0000-4000-8000-000000000002'
ON CONFLICT DO NOTHING;

INSERT INTO memberships (
  id, tenant_id, user_id, role_id, scope, company_id, branch_id, is_active
)
VALUES (
  '70000000-0000-4000-8000-000000000002',
  '10000000-0000-4000-8000-000000000002',
  '40000000-0000-4000-8000-000000000002',
  '50000000-0000-4000-8000-000000000002',
  'COMPANY',
  '20000000-0000-4000-8000-000000000002',
  NULL,
  true
)
ON CONFLICT DO NOTHING;

INSERT INTO counterparties (
  id, tenant_id, company_id, branch_id, code, name, tax_id, kind, created_by_id
)
VALUES
  (
    '82000000-0000-4000-8000-000000000101',
    '10000000-0000-4000-8000-000000000002',
    '20000000-0000-4000-8000-000000000002',
    '30000000-0000-4000-8000-000000000002',
    'FORN-B-DEMO',
    'Fornecedor Atlântico (Fictício)',
    '00.000.000/0201-01',
    'SUPPLIER',
    '40000000-0000-4000-8000-000000000002'
  ),
  (
    '82000000-0000-4000-8000-000000000102',
    '10000000-0000-4000-8000-000000000002',
    '20000000-0000-4000-8000-000000000002',
    '30000000-0000-4000-8000-000000000002',
    'CLI-B-DEMO',
    'Cliente Atlântico (Fictício)',
    '00.000.000/0202-01',
    'CUSTOMER',
    '40000000-0000-4000-8000-000000000002'
  )
ON CONFLICT DO NOTHING;

INSERT INTO financial_categories (
  id, tenant_id, company_id, branch_id, code, name, direction, created_by_id
)
VALUES
  (
    '83000000-0000-4000-8000-000000000101',
    '10000000-0000-4000-8000-000000000002',
    '20000000-0000-4000-8000-000000000002',
    '30000000-0000-4000-8000-000000000002',
    'DESP-B-DEMO',
    'Despesa Atlântico (Demo)',
    'PAYABLE',
    '40000000-0000-4000-8000-000000000002'
  ),
  (
    '83000000-0000-4000-8000-000000000102',
    '10000000-0000-4000-8000-000000000002',
    '20000000-0000-4000-8000-000000000002',
    '30000000-0000-4000-8000-000000000002',
    'REC-B-DEMO',
    'Receita Atlântico (Demo)',
    'RECEIVABLE',
    '40000000-0000-4000-8000-000000000002'
  )
ON CONFLICT DO NOTHING;

INSERT INTO cost_centers (
  id, tenant_id, company_id, branch_id, code, name, created_by_id
)
VALUES (
  '84000000-0000-4000-8000-000000000101',
  '10000000-0000-4000-8000-000000000002',
  '20000000-0000-4000-8000-000000000002',
  '30000000-0000-4000-8000-000000000002',
  'OPER-B-DEMO',
  'Operação Atlântico (Demo)',
  '40000000-0000-4000-8000-000000000002'
)
ON CONFLICT DO NOTHING;

INSERT INTO financial_accounts (
  id, tenant_id, company_id, branch_id, code, name, type, currency_code, created_by_id
)
VALUES (
  '85000000-0000-4000-8000-000000000101',
  '10000000-0000-4000-8000-000000000002',
  '20000000-0000-4000-8000-000000000002',
  '30000000-0000-4000-8000-000000000002',
  'BANCO-B-DEMO',
  'Conta Atlântico demonstrativa',
  'CHECKING',
  'BRL',
  '40000000-0000-4000-8000-000000000002'
)
ON CONFLICT DO NOTHING;

INSERT INTO payables (
  id, tenant_id, company_id, branch_id, counterparty_name, description,
  document_number, issue_date, due_date, amount, outstanding_amount,
  currency_code, status, created_by_id
)
VALUES (
  '80000000-0000-4000-8000-000000000101',
  '10000000-0000-4000-8000-000000000002',
  '20000000-0000-4000-8000-000000000002',
  '30000000-0000-4000-8000-000000000002',
  'Fornecedor Oceano (Fictício)',
  'Dado confidencial fictício exclusivo da Empresa B',
  'B-DEMO-0001',
  DATE '2026-07-12',
  DATE '2026-07-30',
  77777.00,
  77777.00,
  'BRL',
  'OPEN',
  '40000000-0000-4000-8000-000000000002'
)
ON CONFLICT DO NOTHING;

INSERT INTO receivables (
  id, tenant_id, company_id, branch_id, counterparty_name, description,
  document_number, issue_date, due_date, amount, outstanding_amount,
  currency_code, status, created_by_id
)
VALUES (
  '81000000-0000-4000-8000-000000000101',
  '10000000-0000-4000-8000-000000000002',
  '20000000-0000-4000-8000-000000000002',
  '30000000-0000-4000-8000-000000000002',
  'Cliente Maré (Fictício)',
  'Receita fictícia exclusiva da Empresa B',
  'B-REC-0001',
  DATE '2026-07-13',
  DATE '2026-08-13',
  93000.00,
  93000.00,
  'BRL',
  'OPEN',
  '40000000-0000-4000-8000-000000000002'
)
ON CONFLICT DO NOTHING;

INSERT INTO journal_entries (
  id, tenant_id, company_id, branch_id, entry_number, entry_date, description,
  source_type, source_id, status, posted_at, created_by_id
)
VALUES (
  '90000000-0000-4000-8000-000000000101',
  '10000000-0000-4000-8000-000000000002',
  '20000000-0000-4000-8000-000000000002',
  '30000000-0000-4000-8000-000000000002',
  'LC-2026-B-0001',
  DATE '2026-07-13',
  'Reconhecimento de receita fictícia da Empresa B',
  'RECEIVABLE',
  '81000000-0000-4000-8000-000000000101',
  'DRAFT',
  NULL,
  '40000000-0000-4000-8000-000000000002'
)
ON CONFLICT DO NOTHING;

INSERT INTO journal_entry_lines (
  id, tenant_id, company_id, journal_entry_id, line_number, account_code,
  account_name, debit, credit
)
SELECT
  seed.id::uuid,
  seed.tenant_id::uuid,
  seed.company_id::uuid,
  seed.journal_entry_id::uuid,
  seed.line_number::integer,
  seed.account_code,
  seed.account_name,
  seed.debit::numeric,
  seed.credit::numeric
FROM (VALUES
  (
    '91000000-0000-4000-8000-000000000101',
    '10000000-0000-4000-8000-000000000002',
    '20000000-0000-4000-8000-000000000002',
    '90000000-0000-4000-8000-000000000101',
    1,
    '1.1.02.001',
    'Clientes a receber',
    93000.00,
    0
  ),
  (
    '91000000-0000-4000-8000-000000000102',
    '10000000-0000-4000-8000-000000000002',
    '20000000-0000-4000-8000-000000000002',
    '90000000-0000-4000-8000-000000000101',
    2,
    '3.1.02.001',
    'Receita de serviços',
    0,
    93000.00
  )
) AS seed (
  id, tenant_id, company_id, journal_entry_id, line_number, account_code,
  account_name, debit, credit
)
WHERE EXISTS (
  SELECT 1
  FROM journal_entries entry
  WHERE entry.tenant_id = seed.tenant_id::uuid
    AND entry.company_id = seed.company_id::uuid
    AND entry.id = seed.journal_entry_id::uuid
    AND entry.status = 'DRAFT'
)
ON CONFLICT DO NOTHING;

UPDATE journal_entries
   SET status = 'POSTED', posted_at = TIMESTAMPTZ '2026-07-13 11:00:00-03'
 WHERE tenant_id = '10000000-0000-4000-8000-000000000002'
   AND id = '90000000-0000-4000-8000-000000000101'
   AND status = 'DRAFT';

INSERT INTO glossary_terms (
  id, tenant_id, slug, name, category, simple_definition,
  managerial_definition, technical_definition, related_term_slugs,
  "references", last_reviewed_at
)
VALUES (
  '92000000-0000-4000-8000-000000000101',
  '10000000-0000-4000-8000-000000000002',
  'capital-de-giro',
  'Capital de Giro',
  'FINANCIAL',
  'Recursos usados para manter as atividades do dia a dia enquanto pagamentos e recebimentos acontecem em datas diferentes.',
  'Ajuda a visualizar a necessidade de financiamento da operação e o descasamento de prazos.',
  'A análise pode considerar o capital circulante líquido e a necessidade de capital de giro, conforme o objetivo.',
  ARRAY['ativo-circulante', 'passivo-circulante'],
  '[]'::jsonb,
  DATE '2026-07-01'
)
ON CONFLICT DO NOTHING;

INSERT INTO pending_items (
  id, tenant_id, company_id, branch_id, type, category, title,
  responsible_user_id, priority, due_date, status, impact, suggested_action
)
VALUES (
  '93000000-0000-4000-8000-000000000101',
  '10000000-0000-4000-8000-000000000002',
  '20000000-0000-4000-8000-000000000002',
  '30000000-0000-4000-8000-000000000002',
  'BANK_RECONCILIATION',
  'ACCOUNTING',
  'Conciliação bancária fictícia pendente',
  '40000000-0000-4000-8000-000000000002',
  'ATTENTION',
  DATE '2026-07-22',
  'OPEN',
  'Saldos gerenciais podem permanecer provisórios.',
  'Conferir extrato fictício e registrar diferenças identificadas.'
)
ON CONFLICT DO NOTHING;

INSERT INTO risks (
  id, tenant_id, company_id, branch_id, title, area, problem, probability,
  impact, financial_impact, criticality, owner_user_id, due_date, status
)
VALUES (
  '94000000-0000-4000-8000-000000000101',
  '10000000-0000-4000-8000-000000000002',
  '20000000-0000-4000-8000-000000000002',
  '30000000-0000-4000-8000-000000000002',
  'Conciliação fora do prazo',
  'Contabilidade',
  'A conciliação bancária fictícia ainda não foi concluída.',
  'MEDIUM',
  'MEDIUM',
  0,
  'ATTENTION',
  '40000000-0000-4000-8000-000000000002',
  DATE '2026-07-24',
  'IDENTIFIED'
)
ON CONFLICT DO NOTHING;

INSERT INTO action_plans (
  id, tenant_id, company_id, branch_id, risk_id, title, description,
  owner_user_id, priority, status, start_date, due_date, progress_percent,
  success_criteria
)
VALUES (
  '95000000-0000-4000-8000-000000000101',
  '10000000-0000-4000-8000-000000000002',
  '20000000-0000-4000-8000-000000000002',
  '30000000-0000-4000-8000-000000000002',
  '94000000-0000-4000-8000-000000000101',
  'Concluir conciliação fictícia',
  'Revisar movimentos fictícios, documentar diferenças e concluir a conciliação.',
  '40000000-0000-4000-8000-000000000002',
  'ATTENTION',
  'PLANNED',
  DATE '2026-07-20',
  DATE '2026-07-24',
  0,
  'Conciliação concluída com diferenças explicadas e aprovadas.'
)
ON CONFLICT DO NOTHING;

INSERT INTO audit_logs (
  id, tenant_id, company_id, branch_id, actor_user_id, session_id, action,
  entity_type, entity_id, after_data, metadata, redacted_fields,
  integrity_hash, occurred_at
)
VALUES (
  '96000000-0000-4000-8000-000000000101',
  '10000000-0000-4000-8000-000000000002',
  '20000000-0000-4000-8000-000000000002',
  '30000000-0000-4000-8000-000000000002',
  '40000000-0000-4000-8000-000000000002',
  '97000000-0000-4000-8000-000000000002',
  'receivable.created',
  'Receivable',
  '81000000-0000-4000-8000-000000000101',
  '{"status":"OPEN","amount":"93000.00","counterparty":"[REDACTED_DEMO]"}'::jsonb,
  '{"source":"seed","fictional":true}'::jsonb,
  ARRAY['counterparty'],
  encode(digest('demo-audit-event-b', 'sha256'), 'hex'),
  TIMESTAMPTZ '2026-07-20 10:00:00-03'
)
ON CONFLICT DO NOTHING;

COMMIT;
