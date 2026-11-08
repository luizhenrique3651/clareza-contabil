-- Idempotency storage for retry-safe financial mutations.

CREATE TABLE idempotency_records (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL,
  user_id uuid NOT NULL,
  operation varchar(100) NOT NULL,
  key varchar(128) NOT NULL,
  request_hash char(64) NOT NULL,
  resource_type varchar(100) NOT NULL,
  resource_id uuid,
  response_status integer NOT NULL,
  response_body jsonb NOT NULL,
  created_at timestamptz(6) NOT NULL DEFAULT now(),
  expires_at timestamptz(6) NOT NULL,
  CONSTRAINT idempotency_records_tenant_fk FOREIGN KEY (tenant_id)
    REFERENCES tenants(id) ON DELETE RESTRICT,
  CONSTRAINT idempotency_records_user_fk FOREIGN KEY (tenant_id, user_id)
    REFERENCES users(tenant_id, id) ON DELETE RESTRICT,
  CONSTRAINT idempotency_records_scope_key
    UNIQUE (tenant_id, user_id, operation, key),
  CONSTRAINT idempotency_records_operation_not_blank CHECK (btrim(operation) <> ''),
  CONSTRAINT idempotency_records_key_not_blank CHECK (btrim(key) <> ''),
  CONSTRAINT idempotency_records_hash_format CHECK (request_hash ~ '^[0-9a-f]{64}$'),
  CONSTRAINT idempotency_records_resource_type_not_blank
    CHECK (btrim(resource_type) <> ''),
  CONSTRAINT idempotency_records_response_status
    CHECK (response_status BETWEEN 200 AND 599),
  CONSTRAINT idempotency_records_response_object
    CHECK (jsonb_typeof(response_body) = 'object'),
  CONSTRAINT idempotency_records_expiry_after_creation
    CHECK (expires_at > created_at)
);

CREATE INDEX idempotency_records_expiry_idx
  ON idempotency_records (tenant_id, expires_at);

ALTER TABLE idempotency_records ENABLE ROW LEVEL SECURITY;
ALTER TABLE idempotency_records FORCE ROW LEVEL SECURITY;
CREATE POLICY idempotency_records_isolation ON idempotency_records TO PUBLIC
  USING (
    tenant_id = app.current_tenant_id()
    AND user_id = app.current_user_id()
  )
  WITH CHECK (
    tenant_id = app.current_tenant_id()
    AND user_id = app.current_user_id()
  );

GRANT SELECT, INSERT, DELETE ON idempotency_records TO clarity_app;
REVOKE UPDATE ON idempotency_records FROM clarity_app;

COMMENT ON TABLE idempotency_records IS
  'Retry protection for mutations. Payloads must already be redacted and scoped to the authenticated actor.';
