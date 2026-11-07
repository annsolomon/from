-- P16 Billing (MASTER_PLAN P16): plans, subscriptions, payments through a provider adapter, invoices with the mock GST
-- split, webhook events (replay-safe by the provider's event id), entitlement overrides, and the entitlement state
-- whose version orders billing.entitlements.changed. Runs as billing_owner after V202611080900__platform.
--
-- Query -> index (BUILD_README §4, P16)
--   GET /v1/plans, /v1/plans/{code}                  plans_code_key (UNIQUE code); 3 rows                plans_code_key
--   the account's live subscription (checkout, me)    (account_id) WHERE live                             subscriptions_one_live
--   the account's latest subscription (me)            (account_id, created_at DESC, id DESC)              subscriptions_list
--   period-end job claim                              (current_period_end) WHERE trialing/active/past_due subscriptions_period_end
--   a payment by the checkout's Idempotency-Key       UNIQUE (account_id, idem_key)                       payments_idem_key
--   a payment by its provider id                      UNIQUE (provider_payment_id)                        payments_provider_payment_id_key
--   GET /v1/invoices (newest first)                   (account_id, issued_at DESC, id DESC)               invoices_list
--   an invoice's lines (batch = ANY(:ids))            PRIMARY KEY (invoice_id, line_no)                   invoice_lines_pkey
--   the invoice of a payment (one, ever)              UNIQUE (payment_id)                                 invoices_payment_id_key
--   webhook replay guard                              PRIMARY KEY (provider, provider_event_id)           webhook_events_pkey
--   entitlements: overrides / state                   PRIMARY KEY (account_id, key) / (account_id)        *_pkey
--   stale Mode B checkouts (sweeper)                  idempotency_keys_pkey + the in-progress partial      idempotency_keys_stale

-- ---------------------------------------------------------------- the owner reaches across accounts
-- FORCE RLS applies to the table owner too, and the platform template gives idempotency_keys no owner policy. The
-- checkout sweeper (claim_stale_checkouts below) runs as billing_owner, so without this it would see nothing - the
-- A-84 lesson (reco's claim_dirty), applied before the first CI run instead of after it.
CREATE POLICY idempotency_keys_owner ON billing.idempotency_keys FOR ALL TO billing_owner USING (true) WITH CHECK (true);
-- the sweeper's claim: Mode B keys still in progress whose lease ran out
CREATE INDEX idempotency_keys_stale ON billing.idempotency_keys (locked_until)
  WHERE state = 'in_progress' AND locked_until IS NOT NULL;

-- ---------------------------------------------------------------- plans (global reference data)
-- price_paise is before GST; the charge is price + 18 % (libs/portfolio-calc Gst.on, the one definition of the rate).
-- entitlements is the plan's feature map; overrides are laid over it per account. `interval` is a Postgres type name,
-- so the column is billing_interval (the API field stays `interval`, as reco's window_code is `window`, A-80).
CREATE TABLE billing.plans (
  id               uuid        PRIMARY KEY,
  code             text        NOT NULL UNIQUE CHECK (code IN ('FREE', 'PREMIUM', 'VIP')),
  name             text        NOT NULL CHECK (length(name) BETWEEN 1 AND 60),
  price_paise      bigint      NOT NULL CHECK (price_paise >= 0),
  billing_interval text        NOT NULL CHECK (billing_interval IN ('month', 'year')),
  entitlements     jsonb       NOT NULL CHECK (jsonb_typeof(entitlements) = 'object'),
  active           boolean     NOT NULL DEFAULT true,
  sort_order       smallint    NOT NULL,
  created_at       timestamptz NOT NULL DEFAULT now(),
  updated_at       timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT plans_free_is_free CHECK ((code = 'FREE') = (price_paise = 0))
);
ALTER TABLE billing.plans ENABLE ROW LEVEL SECURITY;
ALTER TABLE billing.plans FORCE  ROW LEVEL SECURITY;
-- every caller reads every plan (an inactive plan still names what a grandfathered subscription bought); nobody but
-- the owner writes one - plans change by migration, not at runtime
CREATE POLICY plans_read ON billing.plans FOR SELECT TO billing_app, billing_readonly USING (true);
CREATE POLICY plans_owner ON billing.plans FOR ALL TO billing_owner USING (true) WITH CHECK (true);
GRANT SELECT ON billing.plans TO billing_app, billing_readonly;

INSERT INTO billing.plans (id, code, name, price_paise, billing_interval, entitlements, sort_order) VALUES
  ('0192f1e2-0000-7000-8000-00000000b001', 'FREE', 'Free', 0, 'month',
   '{"cardsMax": 3, "budgetsMax": 3, "statementUploadsPerMonth": 2, "walletOptimizer": false, "bestCardNow": false, "adFree": false, "prioritySupport": false}', 1),
  ('0192f1e2-0000-7000-8000-00000000b002', 'PREMIUM', 'Premium', 19900, 'month',
   '{"cardsMax": 10, "budgetsMax": 20, "statementUploadsPerMonth": 20, "walletOptimizer": true, "bestCardNow": true, "adFree": true, "prioritySupport": false}', 2),
  ('0192f1e2-0000-7000-8000-00000000b003', 'VIP', 'VIP', 49900, 'month',
   '{"cardsMax": 50, "budgetsMax": 100, "statementUploadsPerMonth": 100, "walletOptimizer": true, "bestCardNow": true, "adFree": true, "prioritySupport": true}', 3);

-- ---------------------------------------------------------------- subscriptions
-- A checkout first records the subscription as `incomplete` (payment pending); the payment's outcome moves it to
-- active (with its first period) or incomplete_expired. The live set includes `incomplete`, so two checkouts with
-- different keys cannot race each other into two active subscriptions (A-92): the second is refused at insert.
CREATE TABLE billing.subscriptions (
  id                   uuid        PRIMARY KEY,
  account_id           uuid        NOT NULL,
  plan_id              uuid        NOT NULL REFERENCES billing.plans (id),
  status               text        NOT NULL CHECK (status IN ('incomplete', 'incomplete_expired', 'trialing', 'active',
                                                              'past_due', 'canceled', 'expired')),
  current_period_start timestamptz,
  current_period_end   timestamptz,
  cancel_at_period_end boolean     NOT NULL DEFAULT false,
  canceled_at          timestamptz,
  ended_at             timestamptz,
  provider             text        NOT NULL CHECK (provider ~ '^[a-z][a-z0-9-]{0,31}$'),
  provider_sub_id      text        UNIQUE CHECK (provider_sub_id IS NULL OR length(provider_sub_id) BETWEEN 1 AND 120),
  -- place of supply for the invoice's GST split (a two-letter state code, validated by the app)
  billing_state        char(2)     NOT NULL,
  created_at           timestamptz NOT NULL DEFAULT now(),
  updated_at           timestamptz NOT NULL DEFAULT now(),
  version              integer     NOT NULL DEFAULT 0,
  CONSTRAINT subscriptions_period CHECK (
    status IN ('incomplete', 'incomplete_expired')
    OR (current_period_start IS NOT NULL AND current_period_end > current_period_start))
);
CREATE UNIQUE INDEX subscriptions_one_live ON billing.subscriptions (account_id)
  WHERE status IN ('incomplete', 'trialing', 'active', 'past_due');
CREATE INDEX subscriptions_list ON billing.subscriptions (account_id, created_at DESC, id DESC);
CREATE INDEX subscriptions_plan ON billing.subscriptions (plan_id);
CREATE INDEX subscriptions_period_end ON billing.subscriptions (current_period_end)
  WHERE status IN ('trialing', 'active', 'past_due');

ALTER TABLE billing.subscriptions ENABLE ROW LEVEL SECURITY;
ALTER TABLE billing.subscriptions FORCE  ROW LEVEL SECURITY;
CREATE POLICY subscriptions_tenant ON billing.subscriptions FOR ALL TO billing_app
  USING      (account_id = (SELECT billing.app_account()))
  WITH CHECK (account_id = (SELECT billing.app_account()));
CREATE POLICY subscriptions_owner ON billing.subscriptions FOR ALL TO billing_owner USING (true) WITH CHECK (true);
GRANT SELECT, INSERT, UPDATE ON billing.subscriptions TO billing_app;

-- ---------------------------------------------------------------- payments
-- One row per charge attempt. idem_key is the checkout's Idempotency-Key: Mode B's recovery (a retry, or the sweeper)
-- finds the attempt by it. status only moves forward - pending -> succeeded | failed - however the outcomes arrive
-- (the synchronous answer, a webhook, a sweeper lookup), which is what makes out-of-order webhooks harmless.
-- payment_method is the provider's opaque token (pm_...), never card data: Dexter stores no PAN (MASTER_PLAN 1).
CREATE TABLE billing.payments (
  id                  uuid        PRIMARY KEY,
  account_id          uuid        NOT NULL,
  subscription_id     uuid        NOT NULL REFERENCES billing.subscriptions (id),
  idem_key            uuid        NOT NULL,
  amount_paise        bigint      NOT NULL CHECK (amount_paise > 0),
  currency            char(3)     NOT NULL DEFAULT 'INR',
  status              text        NOT NULL CHECK (status IN ('pending', 'succeeded', 'failed')),
  provider            text        NOT NULL,
  provider_payment_id text        UNIQUE CHECK (provider_payment_id IS NULL OR length(provider_payment_id) BETWEEN 1 AND 120),
  payment_method      text        NOT NULL CHECK (payment_method ~ '^pm_[a-z0-9_]{1,60}$'),
  failure_code        text        CHECK (failure_code IS NULL OR failure_code ~ '^[a-z0-9_]{1,40}$'),
  created_at          timestamptz NOT NULL DEFAULT now(),
  updated_at          timestamptz NOT NULL DEFAULT now(),
  decided_at          timestamptz,
  version             integer     NOT NULL DEFAULT 0,
  CONSTRAINT payments_idem_key UNIQUE (account_id, idem_key),
  CONSTRAINT payments_decided CHECK ((status = 'pending') = (decided_at IS NULL)),
  CONSTRAINT payments_failure CHECK ((status = 'failed') = (failure_code IS NOT NULL))
);
CREATE INDEX payments_subscription ON billing.payments (subscription_id);

ALTER TABLE billing.payments ENABLE ROW LEVEL SECURITY;
ALTER TABLE billing.payments FORCE  ROW LEVEL SECURITY;
CREATE POLICY payments_tenant ON billing.payments FOR ALL TO billing_app
  USING      (account_id = (SELECT billing.app_account()))
  WITH CHECK (account_id = (SELECT billing.app_account()));
CREATE POLICY payments_owner ON billing.payments FOR ALL TO billing_owner USING (true) WITH CHECK (true);
GRANT SELECT, INSERT, UPDATE ON billing.payments TO billing_app;

-- ---------------------------------------------------------------- invoices
-- One invoice per succeeded payment, ever (UNIQUE payment_id): the payment moves to succeeded once, and the invoice
-- is written in that same transaction. Mock GST: 18 % on the plan price, split CGST 9 % + SGST 9 % when the place
-- of supply is the supplier's state, IGST 18 % otherwise (ADR-0022 §6; a real tax engine replaces this).
CREATE SEQUENCE billing.invoice_number_seq AS bigint START 1 NO CYCLE;
CREATE TABLE billing.invoices (
  id              uuid        PRIMARY KEY,
  account_id      uuid        NOT NULL,
  subscription_id uuid        NOT NULL REFERENCES billing.subscriptions (id),
  payment_id      uuid        NOT NULL UNIQUE REFERENCES billing.payments (id),
  number          text        NOT NULL UNIQUE CHECK (number ~ '^DX/[0-9]{4}-[0-9]{2}/[0-9]{6,}$'),
  issued_at       timestamptz NOT NULL,
  period_start    timestamptz NOT NULL,
  period_end      timestamptz NOT NULL CHECK (period_end > period_start),
  supplier_state  char(2)     NOT NULL,
  place_of_supply char(2)     NOT NULL,
  tax_regime      text        NOT NULL CHECK (tax_regime IN ('intra', 'inter')),
  subtotal_paise  bigint      NOT NULL CHECK (subtotal_paise >= 0),
  tax_paise       bigint      NOT NULL CHECK (tax_paise >= 0),
  total_paise     bigint      NOT NULL CHECK (total_paise > 0),
  currency        char(3)     NOT NULL DEFAULT 'INR',
  status          text        NOT NULL DEFAULT 'paid' CHECK (status IN ('paid')),
  created_at      timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT invoices_total CHECK (total_paise = subtotal_paise + tax_paise),
  CONSTRAINT invoices_regime CHECK ((tax_regime = 'intra') = (supplier_state = place_of_supply))
);
CREATE INDEX invoices_list ON billing.invoices (account_id, issued_at DESC, id DESC);
CREATE INDEX invoices_subscription ON billing.invoices (subscription_id);

ALTER TABLE billing.invoices ENABLE ROW LEVEL SECURITY;
ALTER TABLE billing.invoices FORCE  ROW LEVEL SECURITY;
CREATE POLICY invoices_tenant ON billing.invoices FOR ALL TO billing_app
  USING      (account_id = (SELECT billing.app_account()))
  WITH CHECK (account_id = (SELECT billing.app_account()));
CREATE POLICY invoices_owner ON billing.invoices FOR ALL TO billing_owner USING (true) WITH CHECK (true);
-- an invoice is a legal document: written once, never updated by the app
GRANT SELECT, INSERT ON billing.invoices TO billing_app;
GRANT USAGE ON SEQUENCE billing.invoice_number_seq TO billing_app;

CREATE TABLE billing.invoice_lines (
  invoice_id   uuid     NOT NULL REFERENCES billing.invoices (id) ON DELETE CASCADE,
  account_id   uuid     NOT NULL,
  line_no      smallint NOT NULL CHECK (line_no BETWEEN 1 AND 20),
  kind         text     NOT NULL CHECK (kind IN ('item', 'cgst', 'sgst', 'igst')),
  description  text     NOT NULL CHECK (length(description) BETWEEN 1 AND 120),
  rate_bps     integer  CHECK (rate_bps IS NULL OR rate_bps BETWEEN 0 AND 10000),
  amount_paise bigint   NOT NULL CHECK (amount_paise >= 0),
  PRIMARY KEY (invoice_id, line_no)
);
ALTER TABLE billing.invoice_lines ENABLE ROW LEVEL SECURITY;
ALTER TABLE billing.invoice_lines FORCE  ROW LEVEL SECURITY;
CREATE POLICY invoice_lines_tenant ON billing.invoice_lines FOR ALL TO billing_app
  USING      (account_id = (SELECT billing.app_account()))
  WITH CHECK (account_id = (SELECT billing.app_account()));
CREATE POLICY invoice_lines_owner ON billing.invoice_lines FOR ALL TO billing_owner USING (true) WITH CHECK (true);
GRANT SELECT, INSERT ON billing.invoice_lines TO billing_app;

-- ---------------------------------------------------------------- webhook events (replay guard)
-- The provider's event id is the natural idempotency key (MASTER_PLAN 5.4): a replayed delivery conflicts on the
-- primary key and changes nothing. account_id comes from the payment the event names, resolved before the row is
-- written (payment_account below), so the row is tenant-scoped like everything else.
CREATE TABLE billing.webhook_events (
  provider          text        NOT NULL,
  provider_event_id text        NOT NULL CHECK (length(provider_event_id) BETWEEN 1 AND 120),
  account_id        uuid        NOT NULL,
  event_type        text        NOT NULL CHECK (length(event_type) BETWEEN 1 AND 60),
  payment_id        uuid        NOT NULL,
  payload           jsonb       NOT NULL CHECK (jsonb_typeof(payload) = 'object'),
  outcome           text        CHECK (outcome IS NULL OR outcome IN ('applied', 'no_change')),
  received_at       timestamptz NOT NULL DEFAULT now(),
  processed_at      timestamptz,
  PRIMARY KEY (provider, provider_event_id)
);
CREATE INDEX webhook_events_account ON billing.webhook_events (account_id, received_at DESC);
CREATE INDEX webhook_events_payment ON billing.webhook_events (payment_id);

ALTER TABLE billing.webhook_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE billing.webhook_events FORCE  ROW LEVEL SECURITY;
CREATE POLICY webhook_events_tenant ON billing.webhook_events FOR ALL TO billing_app
  USING      (account_id = (SELECT billing.app_account()))
  WITH CHECK (account_id = (SELECT billing.app_account()));
CREATE POLICY webhook_events_owner ON billing.webhook_events FOR ALL TO billing_owner USING (true) WITH CHECK (true);
GRANT SELECT, INSERT, UPDATE ON billing.webhook_events TO billing_app;

-- ---------------------------------------------------------------- entitlement overrides (perks, ad holidays)
-- Laid over the plan's map until expires_at. Nothing writes them yet (P17 perks / an admin console); the read path
-- and its precedence are built and tested now (BUILD_README §7).
CREATE TABLE billing.entitlement_overrides (
  account_id uuid        NOT NULL,
  key        text        NOT NULL CHECK (key ~ '^[a-z][a-zA-Z0-9]{0,39}$'),
  value      jsonb       NOT NULL,
  reason     text        NOT NULL CHECK (length(reason) BETWEEN 1 AND 200),
  expires_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (account_id, key)
);
ALTER TABLE billing.entitlement_overrides ENABLE ROW LEVEL SECURITY;
ALTER TABLE billing.entitlement_overrides FORCE  ROW LEVEL SECURITY;
CREATE POLICY entitlement_overrides_tenant ON billing.entitlement_overrides FOR ALL TO billing_app
  USING      (account_id = (SELECT billing.app_account()))
  WITH CHECK (account_id = (SELECT billing.app_account()));
CREATE POLICY entitlement_overrides_owner ON billing.entitlement_overrides FOR ALL TO billing_owner
  USING (true) WITH CHECK (true);
GRANT SELECT ON billing.entitlement_overrides TO billing_app;

-- ---------------------------------------------------------------- entitlement state (what was last announced)
-- The tier and map billing last published, and a per-account version that only grows: identity's tier projection
-- compares it (WHERE tier_version < :v), so an event delivered late through a retry topic cannot downgrade a token.
-- A subscription's own version cannot serve: a new subscription starts again at 0.
CREATE TABLE billing.entitlement_state (
  account_id   uuid        PRIMARY KEY,
  tier         text        NOT NULL CHECK (tier IN ('FREE', 'PREMIUM', 'VIP')),
  entitlements jsonb       NOT NULL CHECK (jsonb_typeof(entitlements) = 'object'),
  version      bigint      NOT NULL CHECK (version >= 1),
  updated_at   timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE billing.entitlement_state ENABLE ROW LEVEL SECURITY;
ALTER TABLE billing.entitlement_state FORCE  ROW LEVEL SECURITY;
CREATE POLICY entitlement_state_tenant ON billing.entitlement_state FOR ALL TO billing_app
  USING      (account_id = (SELECT billing.app_account()))
  WITH CHECK (account_id = (SELECT billing.app_account()));
CREATE POLICY entitlement_state_owner ON billing.entitlement_state FOR ALL TO billing_owner USING (true) WITH CHECK (true);
GRANT SELECT, INSERT, UPDATE ON billing.entitlement_state TO billing_app;

-- ---------------------------------------------------------------- owner functions (the only cross-account reach)
-- Each does one thing, returns the minimum, pins search_path and is granted to billing_app alone (MASTER_PLAN 3.2).

-- A webhook arrives with no token: the payment it names says which account it belongs to. Pre-authentication lookup
-- in the A-30 shape. The signature has already been verified when this is called.
CREATE FUNCTION billing.payment_account(p_payment uuid) RETURNS uuid
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS
$$ SELECT account_id FROM billing.payments WHERE id = p_payment $$;
REVOKE ALL ON FUNCTION billing.payment_account(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION billing.payment_account(uuid) TO billing_app;

-- The Mode B sweeper (MASTER_PLAN 5.3): checkout keys still in progress whose lease ran out - the attempt that held
-- them died between the provider call and its last transaction. Claim and re-lease in one statement, SKIP LOCKED, so
-- every instance may sweep and a client retry meanwhile gets 409 IDEMPOTENCY_IN_PROGRESS rather than a second charge.
CREATE FUNCTION billing.claim_stale_checkouts(p_lease_ms bigint, p_limit integer)
RETURNS TABLE (account_id uuid, idem_key uuid)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS
$$
BEGIN
  IF p_lease_ms NOT BETWEEN 1000 AND 3600000 OR p_limit NOT BETWEEN 1 AND 1000 THEN
    RAISE EXCEPTION 'bad claim arguments';
  END IF;
  RETURN QUERY
  WITH stale AS (
    SELECT k.account_id, k.idem_key FROM billing.idempotency_keys k
     WHERE k.state = 'in_progress' AND k.locked_until IS NOT NULL AND k.locked_until <= now()
       AND k.request_method = 'POST' AND k.request_path = '/v1/subscriptions/checkout'
     ORDER BY k.locked_until
     LIMIT p_limit
     FOR UPDATE SKIP LOCKED
  ), leased AS (
    UPDATE billing.idempotency_keys k SET locked_until = now() + p_lease_ms * interval '1 millisecond'
      FROM stale s WHERE k.account_id = s.account_id AND k.idem_key = s.idem_key
      RETURNING k.account_id, k.idem_key
  )
  SELECT leased.account_id, leased.idem_key FROM leased;
END
$$;
REVOKE ALL ON FUNCTION billing.claim_stale_checkouts(bigint, integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION billing.claim_stale_checkouts(bigint, integer) TO billing_app;

-- The period-end job: subscriptions whose period is over (and past_due ones past their grace). The job re-locks and
-- re-checks each row in that account's own transaction, so a row claimed twice is decided once.
CREATE FUNCTION billing.claim_period_ends(p_grace_ms bigint, p_limit integer)
RETURNS TABLE (account_id uuid, subscription_id uuid)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS
$$
BEGIN
  IF p_grace_ms NOT BETWEEN 0 AND 2592000000 OR p_limit NOT BETWEEN 1 AND 1000 THEN
    RAISE EXCEPTION 'bad claim arguments';
  END IF;
  RETURN QUERY
  SELECT s.account_id, s.id FROM billing.subscriptions s
   WHERE s.status IN ('trialing', 'active', 'past_due')
     AND s.current_period_end <= now()            -- the range on subscriptions_period_end
     AND s.current_period_end <= now() - CASE WHEN s.status = 'past_due'
                                              THEN p_grace_ms * interval '1 millisecond' ELSE interval '0' END
   ORDER BY s.current_period_end
   LIMIT p_limit
   FOR UPDATE SKIP LOCKED;
END
$$;
REVOKE ALL ON FUNCTION billing.claim_period_ends(bigint, integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION billing.claim_period_ends(bigint, integer) TO billing_app;
